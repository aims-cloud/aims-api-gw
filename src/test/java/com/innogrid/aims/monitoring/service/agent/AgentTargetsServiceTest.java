package com.innogrid.aims.monitoring.service.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.AgentTargetsStatusResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.AgentTargetsStatusResponse.TargetsStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceRequest;
import com.innogrid.aims.monitoring.entity.AgentNode;
import com.innogrid.aims.monitoring.entity.AgentTransport;
import com.innogrid.aims.monitoring.entity.CollectOwner;
import com.innogrid.aims.monitoring.entity.CollectService;
import com.innogrid.aims.monitoring.entity.MonitoringTarget;
import com.innogrid.aims.monitoring.entity.MonitoringTarget.ExporterType;
import com.innogrid.aims.monitoring.repository.AgentNodeRepository;
import com.innogrid.aims.monitoring.repository.CollectServiceRepository;
import com.innogrid.aims.monitoring.repository.MonitoringTargetRepository;
import com.innogrid.aims.monitoring.service.agent.CollectTargetsBuilder.Targets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 목록 생성(정적 제외·정렬·해시), 로컬 전송(원자적 교체·해시 비교), 동기화 상태 전이, 상태 응답을 검증한다. */
class AgentTargetsServiceTest {

    CollectTargetsBuilder builder = new CollectTargetsBuilder(new AgentProperties());
    AgentNodeRepository nodes = mock(AgentNodeRepository.class);
    CollectServiceRepository services = mock(CollectServiceRepository.class);
    MonitoringTargetRepository targets = mock(MonitoringTargetRepository.class);
    LocalFileTargetsPublisher local = new LocalFileTargetsPublisher();
    AgentTargetsService service = new AgentTargetsService(nodes, services, targets, builder, List.of(local));
    Asset asset = assetWithId(1L);
    CollectOwner vm = CollectOwner.vm("ns-1", "infra-1", "vm-1");

    static Asset assetWithId(long id) {
        Asset row = new Asset("mdc-01", Asset.AssetType.GPU_SERVER, "node-" + id);
        ReflectionTestUtils.setField(row, "id", id);
        return row;
    }

    static AgentNode withId(AgentNode node, long id) {
        ReflectionTestUtils.setField(node, "id", id);
        return node;
    }

    CollectService registered(long id, String name, boolean enabled, String... paths) {
        CollectService row = new CollectService(asset, "user@example.com");
        return fill(row, id, name, enabled, paths);
    }

    CollectService registeredOnVm(long id, String name, String... paths) {
        return fill(new CollectService(vm, "user@example.com"), id, name, true, paths);
    }

    static CollectService fill(CollectService row, long id, String name, boolean enabled, String... paths) {
        row.update(new CollectServiceRequest(name, null, new CollectServiceRequest.LogCollection(enabled, false, List.of(paths)), null));
        ReflectionTestUtils.setField(row, "id", id);
        return row;
    }

    // ── 목록 생성 ───────────────────────────────────────────────

    @Test void builderSkipsStaticAndDisabledPathsSortsByPathAndHashesDeterministically() {
        List<CollectService> rows = List.of(
                registered(12, "nginx", true, "/var/log/nginx/error.log", "/var/log/nginx/access.log"),
                registered(13, "syslog", true, "/var/log/syslog"),                 // 정적 경로 → 제외
                registered(14, "aims", true, "/var/log/aims/app.log"),             // 정적 glob → 제외
                registered(15, "off", false, "/opt/off/app.log"),                  // 비활성 → 제외
                registered(16, "dup", true, "/var/log/nginx/access.log"));         // 같은 경로 → 먼저 온 것만
        Targets targets = builder.build(rows);
        assertThat(targets.count()).isEqualTo(2);
        assertThat(targets.json()).contains("\"__path__\" : \"/var/log/nginx/access.log\"")
                .contains("\"job\" : \"collect/nginx\"").contains("\"service_id\" : \"12\"")
                .doesNotContain("/var/log/syslog").doesNotContain("/opt/off");
        assertThat(targets.json().indexOf("access.log")).isLessThan(targets.json().indexOf("error.log"));
        assertThat(builder.build(List.of(rows.get(0))).hash()).isEqualTo(targets.hash());   // 순서·중복과 무관
        assertThat(builder.build(List.of()).json().trim()).isEqualTo("[ ]");
    }

    @Test void pullTargetsExcludePackageStaticPathsInsteadOfK8sCpOnes() {
        List<CollectService> rows = List.of(
                registered(21, "aims", true, "/var/log/aims/app.log"),            // k8s-cp 정적 glob — LOCAL 만 제외
                registered(22, "secure", true, "/var/log/secure"),                // 패키지 정적(RHEL) — PULL 만 제외
                registered(23, "nginx", true, "/var/log/nginx/access.log"));
        Targets local = builder.build(rows, AgentTransport.LOCAL);
        Targets pull = builder.build(rows, AgentTransport.PULL);
        assertThat(local.json()).doesNotContain("/var/log/aims/app.log").contains("/var/log/secure");
        assertThat(pull.json()).contains("/var/log/aims/app.log").doesNotContain("/var/log/secure");
        assertThat(local.count()).isEqualTo(2);
        assertThat(pull.count()).isEqualTo(2);
        assertThat(builder.build(rows).hash()).isEqualTo(local.hash());   // 기본은 LOCAL
    }

    @Test void ownerIsEitherAssetOrCompleteTumblebugKey() {
        assertThat(CollectOwner.asset(3L).isVm()).isFalse();
        assertThat(vm.isVm()).isTrue();
        assertThat(vm.label()).isEqualTo("tb:ns-1/infra-1/vm-1");
        assertThatThrownBy(() -> new CollectOwner(3L, "ns", "infra", "node")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CollectOwner(null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CollectOwner.vm("ns", " ", "node")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new AgentNode(asset, AgentTransport.LOCAL, "/x").owner()).isEqualTo(CollectOwner.asset(1L));
        AgentNode vmNode = AgentNode.forVm(vm);
        assertThat(vmNode.owner()).isEqualTo(vm);
        assertThat(vmNode.getTransport()).isEqualTo(AgentTransport.PULL);
        assertThat(vmNode.getAssetId()).isNull();
        assertThatThrownBy(() -> AgentNode.forVm(CollectOwner.asset(1L))).isInstanceOf(IllegalArgumentException.class);
    }

    // ── 로컬 전송 ───────────────────────────────────────────────

    @Test void localPublisherWritesAtomicallyAndReportsCurrentHash(@TempDir Path dir) throws IOException {
        AgentNode node = new AgentNode(asset, AgentTransport.LOCAL, dir.resolve("nested/log-targets.json").toString());
        assertThat(local.currentHash(node)).isNull();
        local.publish(node, "[ ]\n");
        assertThat(Files.readString(dir.resolve("nested/log-targets.json"))).isEqualTo("[ ]\n");
        assertThat(Files.exists(dir.resolve("nested/log-targets.json.tmp"))).isFalse();
        assertThat(local.currentHash(node)).isEqualTo(CollectTargetsBuilder.sha256("[ ]\n"));
    }

    // ── 동기화 ──────────────────────────────────────────────────

    @Test void syncWritesOnlyWhenFileDiffersAndMarksApplied(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("log-targets.json");
        AgentNode node = withId(new AgentNode(asset, AgentTransport.LOCAL, file.toString()), 10L);
        when(nodes.findById(10L)).thenReturn(Optional.of(node));
        when(services.findByAsset_Id(1L)).thenReturn(List.of(registered(12, "nginx", true, "/var/log/nginx/access.log")));

        service.sync(10L);
        assertThat(Files.readString(file)).contains("/var/log/nginx/access.log");
        assertThat(node.getTargetsHashApplied()).isEqualTo(node.getTargetsHashDesired());
        assertThat(node.getTargetsAppliedAt()).isNotNull();
        assertThat(node.isTargetsPending()).isFalse();

        OffsetDateTime first = node.getTargetsAppliedAt();
        long modified = Files.getLastModifiedTime(file).toMillis();
        service.sync(10L);                                                    // 변경 없음 → 파일 그대로
        assertThat(Files.getLastModifiedTime(file).toMillis()).isEqualTo(modified);
        assertThat(node.getTargetsAppliedAt()).isAfterOrEqualTo(first);

        Files.delete(file);                                                   // 파일이 지워지면 다음 동기화가 복구
        when(nodes.findByAsset_Id(1L)).thenReturn(Optional.of(node));
        service.syncOwner(CollectOwner.asset(1L));                            // 커밋 후 트리거 경로(소유자 단위)
        assertThat(Files.exists(file)).isTrue();
    }

    @Test void syncRecordsFailureInsteadOfThrowingAndSkipsNodesWithoutTransport(@TempDir Path dir) throws IOException {
        Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "not a directory");                          // 부모가 파일이라 디렉터리 생성 실패
        AgentNode node = new AgentNode(asset, AgentTransport.LOCAL, blocked.resolve("log-targets.json").toString());
        when(nodes.findById(10L)).thenReturn(Optional.of(node));
        when(services.findByAsset_Id(1L)).thenReturn(List.of(registered(12, "nginx", true, "/var/log/nginx/access.log")));

        assertThatCode(() -> service.sync(10L)).doesNotThrowAnyException();
        assertThat(node.getTargetsError()).isNotBlank();
        assertThat(node.isTargetsPending()).isTrue();

        AgentNode ssh = new AgentNode(assetWithId(2L), AgentTransport.SSH, "/etc/alloy/targets.json");
        when(nodes.findById(20L)).thenReturn(Optional.of(ssh));
        when(services.findByAsset_Id(2L)).thenReturn(List.of());
        service.sync(20L);
        assertThat(ssh.getTargetsError()).contains("SSH");

        when(nodes.findById(30L)).thenReturn(Optional.of(new AgentNode(assetWithId(3L), AgentTransport.NONE, null)));
        when(nodes.findById(40L)).thenReturn(Optional.empty());
        service.sync(30L);
        service.sync(40L);
        service.syncOwner(CollectOwner.asset(4L));                            // 에이전트 행 없는 소유자
        verify(services, never()).findByAsset_Id(3L);
        verify(services, never()).findByAsset_Id(4L);
    }

    @Test void reconcileJobSyncsEveryCandidateThroughTheServiceAndSurvivesOneFailure() {
        AgentTargetsService proxy = mock(AgentTargetsService.class);
        when(proxy.syncCandidates()).thenReturn(List.of(1L, 2L, 3L));
        doThrow(new IllegalStateException("boom")).when(proxy).sync(2L);
        new AgentTargetsReconcileJob(proxy).reconcile();
        verify(proxy).sync(1L);
        verify(proxy).sync(2L);
        verify(proxy).sync(3L);

        AgentNode a = withId(new AgentNode(asset, AgentTransport.LOCAL, "/tmp/a.json"), 7L);
        AgentNode v = withId(AgentNode.forVm(vm), 8L);
        when(nodes.findByTransportNot(AgentTransport.NONE)).thenReturn(List.of(a, v));
        assertThat(service.syncCandidates()).containsExactly(7L, 8L);          // 에이전트 행 id(물리·VM 공통)
    }

    // ── PULL 전송 (노드·VM Alloy 가 가져감) ─────────────────────

    @Test void pullNodeOnlyRecordsDesiredHashOnSyncAndAppliesWhenServed() {
        Asset pulled = assetWithId(5L);
        AgentNode node = withId(new AgentNode(pulled, AgentTransport.NONE, null), 50L);
        node.configurePull("tester");
        assertThat(node.getTransport()).isEqualTo(AgentTransport.PULL);
        assertThat(node.getTargetsPath()).isNull();
        when(nodes.findById(50L)).thenReturn(Optional.of(node));
        when(nodes.findByAsset_Id(5L)).thenReturn(Optional.of(node));
        when(services.findByAsset_Id(5L)).thenReturn(List.of(registered(12, "nginx", true, "/var/log/nginx/access.log")));

        service.sync(50L);                                                    // 밀어 넣을 곳이 없으니 원하는 해시만
        assertThat(node.getTargetsHashDesired()).isNotNull();
        assertThat(node.getTargetsAppliedAt()).isNull();
        assertThat(node.getTargetsError()).isNull();
        AgentTargetsStatusResponse pending = service.status(5L);
        assertThat(pending.targetsStatus()).isEqualTo(TargetsStatus.PENDING);
        assertThat(pending.message()).contains("가져가지 않았습니다").contains("NODE_INSTANCE");

        MonitoringTarget exporter = new MonitoringTarget("mdc-01", "node-5", "10.0.0.21", 9100, ExporterType.NODE_EXPORTER);
        ReflectionTestUtils.setField(exporter, "asset", pulled);
        when(targets.findFirstByAddressAndPortAndExporterType("10.0.0.21", 9100, ExporterType.NODE_EXPORTER))
                .thenReturn(Optional.of(exporter));
        Optional<String> served = service.serve("10.0.0.21:9100");            // Alloy 가 instance 로 가져감 → 적용 기록
        assertThat(served).isPresent();
        assertThat(served.get()).contains("/var/log/nginx/access.log").contains("\"job\" : \"collect/nginx\"");
        assertThat(node.getTargetsHashApplied()).isEqualTo(node.getTargetsHashDesired());
        assertThat(service.status(5L).targetsStatus()).isEqualTo(TargetsStatus.APPLIED);

        assertThat(service.serve("10.0.0.99:9100")).isEmpty();                 // 등록되지 않은 instance
        assertThat(service.serve("10.0.0.21")).isEmpty();                      // 포트 없음
        assertThat(service.serve("10.0.0.21:abc")).isEmpty();                  // 포트 형식 오류
        assertThat(service.serve(null)).isEmpty();
        assertThat(service.serve(" ")).isEmpty();
        node.configureTransport(AgentTransport.LOCAL, "/tmp/x.json", "tester");
        assertThat(service.serve("10.0.0.21:9100")).isEmpty();                 // PULL 이 아닌 노드는 거부
    }

    @Test void vmAgentPullsByTumblebugKeyWithPackageStaticPathsExcluded() {
        AgentNode node = AgentNode.forVm(vm);
        when(nodes.findByTbNsAndTbInfraAndTbNode("ns-1", "infra-1", "vm-1")).thenReturn(Optional.of(node));
        when(services.findByTbNsAndTbInfraAndTbNode("ns-1", "infra-1", "vm-1")).thenReturn(List.of(
                registeredOnVm(31, "nginx", "/var/log/nginx/access.log"),
                registeredOnVm(32, "messages", "/var/log/messages")));          // 패키지 정적 경로 → 목록 제외

        service.syncOwner(vm);                                                // 커밋 후 트리거 — 원하는 해시만
        AgentTargetsStatusResponse pending = service.status(vm);
        assertThat(pending.transport()).isEqualTo(AgentTransport.PULL);
        assertThat(pending.targetsStatus()).isEqualTo(TargetsStatus.PENDING);
        assertThat(pending.message()).contains("VM").contains("설치 상태");
        assertThat(pending.targetCount()).isEqualTo(1);

        Optional<String> served = service.serveVm("ns-1", "infra-1", "vm-1");
        assertThat(served).isPresent();
        assertThat(served.get()).contains("/var/log/nginx/access.log").doesNotContain("/var/log/messages");
        assertThat(service.status(vm).targetsStatus()).isEqualTo(TargetsStatus.APPLIED);
        verify(services, never()).findByAsset_Id(anyLong());

        assertThat(service.serveVm("ns-1", "infra-1", "vm-x")).isEmpty();      // 에이전트 행 없는 VM
        assertThat(service.serveVm("ns-1", null, "vm-1")).isEmpty();
        assertThat(service.serveVm(" ", "infra-1", "vm-1")).isEmpty();
        when(nodes.findByTbNsAndTbInfraAndTbNode("ns-1", "infra-1", "vm-x")).thenReturn(Optional.empty());
        assertThat(service.status(CollectOwner.vm("ns-1", "infra-1", "vm-x")).targetsStatus()).isEqualTo(TargetsStatus.NO_AGENT);
    }

    // ── 상태 응답 ───────────────────────────────────────────────

    @Test void statusReflectsAgentRowAndApplyState() {
        when(services.findByAsset_Id(anyLong())).thenReturn(List.of(registered(12, "nginx", true, "/var/log/nginx/access.log")));
        when(nodes.findByAsset_Id(1L)).thenReturn(Optional.empty());
        AgentTargetsStatusResponse none = service.status(1L);
        assertThat(none.targetsStatus()).isEqualTo(TargetsStatus.NO_AGENT);
        assertThat(none.transport()).isEqualTo(AgentTransport.NONE);
        assertThat(none.targetCount()).isEqualTo(1);

        AgentNode node = new AgentNode(assetWithId(2L), AgentTransport.LOCAL, "/tmp/x.json");
        when(nodes.findByAsset_Id(2L)).thenReturn(Optional.of(node));
        assertThat(service.status(2L).targetsStatus()).isEqualTo(TargetsStatus.PENDING);      // 아직 한 번도 반영 안 됨
        node.markDesired("h1");
        node.markApplied("h1", OffsetDateTime.now());
        assertThat(service.status(2L).targetsStatus()).isEqualTo(TargetsStatus.APPLIED);
        node.markDesired("h2");
        assertThat(service.status(2L).targetsStatus()).isEqualTo(TargetsStatus.PENDING);
        node.markApplyFailed("permission denied");
        AgentTargetsStatusResponse failed = service.status(2L);
        assertThat(failed.targetsStatus()).isEqualTo(TargetsStatus.FAILED);
        assertThat(failed.message()).isEqualTo("permission denied");
    }
}
