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
    Asset asset = new Asset("mdc-01", Asset.AssetType.GPU_SERVER, "k8s-cp");

    CollectService registered(long id, String name, boolean enabled, String... paths) {
        CollectService row = new CollectService(asset, "user@example.com");
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
        AgentNode node = new AgentNode(asset, AgentTransport.LOCAL, file.toString());
        when(nodes.findById(1L)).thenReturn(Optional.of(node));
        when(services.findByAsset_Id(1L)).thenReturn(List.of(registered(12, "nginx", true, "/var/log/nginx/access.log")));

        service.sync(1L);
        assertThat(Files.readString(file)).contains("/var/log/nginx/access.log");
        assertThat(node.getTargetsHashApplied()).isEqualTo(node.getTargetsHashDesired());
        assertThat(node.getTargetsAppliedAt()).isNotNull();
        assertThat(node.isTargetsPending()).isFalse();

        OffsetDateTime first = node.getTargetsAppliedAt();
        long modified = Files.getLastModifiedTime(file).toMillis();
        service.sync(1L);                                                     // 변경 없음 → 파일 그대로
        assertThat(Files.getLastModifiedTime(file).toMillis()).isEqualTo(modified);
        assertThat(node.getTargetsAppliedAt()).isAfterOrEqualTo(first);

        Files.delete(file);                                                   // 파일이 지워지면 다음 동기화가 복구
        service.sync(1L);
        assertThat(Files.exists(file)).isTrue();
    }

    @Test void syncRecordsFailureInsteadOfThrowingAndSkipsNodesWithoutTransport(@TempDir Path dir) throws IOException {
        Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "not a directory");                          // 부모가 파일이라 디렉터리 생성 실패
        AgentNode node = new AgentNode(asset, AgentTransport.LOCAL, blocked.resolve("log-targets.json").toString());
        when(nodes.findById(1L)).thenReturn(Optional.of(node));
        when(services.findByAsset_Id(1L)).thenReturn(List.of(registered(12, "nginx", true, "/var/log/nginx/access.log")));

        assertThatCode(() -> service.sync(1L)).doesNotThrowAnyException();
        assertThat(node.getTargetsError()).isNotBlank();
        assertThat(node.isTargetsPending()).isTrue();

        AgentNode ssh = new AgentNode(asset, AgentTransport.SSH, "/etc/alloy/targets.json");
        when(nodes.findById(2L)).thenReturn(Optional.of(ssh));
        when(services.findByAsset_Id(2L)).thenReturn(List.of());
        service.sync(2L);
        assertThat(ssh.getTargetsError()).contains("SSH");

        when(nodes.findById(3L)).thenReturn(Optional.of(new AgentNode(asset, AgentTransport.NONE, null)));
        when(nodes.findById(4L)).thenReturn(Optional.empty());
        service.sync(3L);
        service.sync(4L);
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

        AgentNode a = new AgentNode(asset, AgentTransport.LOCAL, "/tmp/a.json");
        ReflectionTestUtils.setField(a, "assetId", 7L);
        when(nodes.findByTransportNot(AgentTransport.NONE)).thenReturn(List.of(a));
        assertThat(service.syncCandidates()).containsExactly(7L);
    }

    // ── PULL 전송 (노드 Alloy 가 가져감) ─────────────────────────

    @Test void pullNodeOnlyRecordsDesiredHashOnSyncAndAppliesWhenServed() {
        ReflectionTestUtils.setField(asset, "id", 5L);
        AgentNode node = new AgentNode(asset, AgentTransport.NONE, null);
        node.configurePull("tester");
        assertThat(node.getTransport()).isEqualTo(AgentTransport.PULL);
        assertThat(node.getTargetsPath()).isNull();
        when(nodes.findById(5L)).thenReturn(Optional.of(node));
        when(services.findByAsset_Id(5L)).thenReturn(List.of(registered(12, "nginx", true, "/var/log/nginx/access.log")));

        service.sync(5L);                                                     // 밀어 넣을 곳이 없으니 원하는 해시만
        assertThat(node.getTargetsHashDesired()).isNotNull();
        assertThat(node.getTargetsAppliedAt()).isNull();
        assertThat(node.getTargetsError()).isNull();
        AgentTargetsStatusResponse pending = service.status(5L);
        assertThat(pending.targetsStatus()).isEqualTo(TargetsStatus.PENDING);
        assertThat(pending.message()).contains("가져가지 않았습니다");

        MonitoringTarget exporter = new MonitoringTarget("mdc-01", "k8s-cp", "10.0.0.21", 9100, ExporterType.NODE_EXPORTER);
        ReflectionTestUtils.setField(exporter, "asset", asset);
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

    // ── 상태 응답 ───────────────────────────────────────────────

    @Test void statusReflectsAgentRowAndApplyState() {
        when(services.findByAsset_Id(anyLong())).thenReturn(List.of(registered(12, "nginx", true, "/var/log/nginx/access.log")));
        when(nodes.findById(1L)).thenReturn(Optional.empty());
        AgentTargetsStatusResponse none = service.status(1L);
        assertThat(none.targetsStatus()).isEqualTo(TargetsStatus.NO_AGENT);
        assertThat(none.transport()).isEqualTo(AgentTransport.NONE);
        assertThat(none.targetCount()).isEqualTo(1);

        AgentNode node = new AgentNode(asset, AgentTransport.LOCAL, "/tmp/x.json");
        when(nodes.findById(2L)).thenReturn(Optional.of(node));
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
