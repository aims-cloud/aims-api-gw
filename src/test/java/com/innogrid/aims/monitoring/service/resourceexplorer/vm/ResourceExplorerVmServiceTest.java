package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.cloudbarista.tumblebug.TumblebugClient;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.RoleNamespace;
import com.innogrid.aims.infra.repository.RoleNamespaceRepository;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeDetailMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeMetricTrendResponse.Series;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.VmInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.VmSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostDetailMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostDetailMetricsQueryService.HostSummaryMetrics;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbInfra;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbNode;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VM 상세 패널 테스트 — Namespace 스코프·Node 존재 검증(404), tb_* 라벨 선택자, 카드 null 규칙,
 * interval 기반 시계열, Tumblebug Node 상세 파싱. Tumblebug·Thanos 는 대역.
 *
 * 대역: ns openstack / infra openstack-infra-01 / node vm-1 (Running, g1)
 *   ADMIN → openstack 매핑, USER → 매핑 없음
 */
class ResourceExplorerVmServiceTest {

    AuthService authService = mock(AuthService.class);
    RoleNamespaceRepository roleNamespaceRepository = mock(RoleNamespaceRepository.class);
    TumblebugTreeBuilder tumblebugTreeBuilder = mock(TumblebugTreeBuilder.class);
    TumblebugClient tumblebugClient = mock(TumblebugClient.class);
    HostDetailMetricsQueryService detailMetrics = mock(HostDetailMetricsQueryService.class);
    ResourceExplorerVmService service = new ResourceExplorerVmService(
            new TumblebugPanelSupport(authService, roleNamespaceRepository, tumblebugTreeBuilder), tumblebugClient, detailMetrics);

    static final String NS = "openstack";
    static final String INFRA = "openstack-infra-01";
    static final String NODE = "vm-1";
    static final MetricSelector SEL = MetricSelector.tumblebugNode(NS, INFRA, NODE);

    @BeforeEach
    void setUp() {
        when(authService.currentUserRoleIds()).thenReturn(new LinkedHashSet<>(List.of("ADMIN", "USER")));
        Role admin = new Role("ADMIN", "관리자");
        when(roleNamespaceRepository.findByRole_NameIn(anyCollection())).thenAnswer(inv -> {
            Collection<String> roles = inv.getArgument(0);
            return roles.contains("ADMIN") ? List.of(new RoleNamespace(admin, NS)) : List.of();
        });
        when(tumblebugTreeBuilder.snapshot(anyCollection())).thenReturn(new TbSnapshot(Instant.now(),
                Map.of(NS, "openstack"),
                Map.of(NS, List.of(new TbInfra(INFRA, INFRA, "Running:1 (R:1/1)", List.of(
                        new TbNode(NODE, NODE, "Running", "g1", "openstack", "101.79.72.62", "172.16.0.1"))))),
                false));
        when(detailMetrics.summary(any(), any())).thenReturn(HostSummaryMetrics.EMPTY);
    }

    // ── 접근 검증 ───────────────────────────────────────────────

    @Test
    void 역할에_매핑되지_않은_Namespace는_404() {
        when(authService.currentUserRoleIds()).thenReturn(new LinkedHashSet<>(List.of("USER")));

        assertThatThrownBy(() -> service.getVmSummary(null, NS, INFRA, NODE))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(tumblebugTreeBuilder, never()).snapshot(anyCollection());
        verify(detailMetrics, never()).summary(any(), any());
    }

    @Test
    void 스냅샷에_없는_Node는_404() {
        assertThatThrownBy(() -> service.getVmInfo(null, NS, INFRA, "vm-999"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getVmInfo(null, NS, "other-infra", NODE))
                .isInstanceOf(BusinessException.class);
        verify(tumblebugClient, never()).getNode(any(), any(), any());
    }

    @Test
    void 스냅샷은_요청_Namespace만_조회한다() {
        service.getVmSummary(null, NS, INFRA, NODE);

        verify(tumblebugTreeBuilder).snapshot(argThat(c -> c.size() == 1 && c.contains(NS)));
    }

    // ── 주요 지표 카드 ──────────────────────────────────────────

    @Test
    void 카드는_tb_라벨_선택자로_조회하고_미수집_카드는_null이다() {
        VmSummaryResponse res = service.getVmSummary(null, NS, INFRA, NODE);

        verify(detailMetrics).summary(eq(SEL), eq(SEL));
        assertThat(res.lastUpdatedAt()).isNotNull();
        assertThat(res.cpu()).isNull();
        assertThat(res.memory()).isNull();
        assertThat(res.fileSystem()).isNull();
        assertThat(res.networkTraffic()).isNull();
        assertThat(res.gpu()).isNull();
    }

    @Test
    void 카드값은_사용률과_Free_Total로_구성된다() {
        when(detailMetrics.summary(any(), any())).thenReturn(new HostSummaryMetrics(
                45.2, 2,
                3.0, 8.0,
                20.0, 50.0,
                1024.0, 2048.0,
                70.0, 1,
                6.0, 16.0,
                null));

        VmSummaryResponse res = service.getVmSummary(null, NS, INFRA, NODE);

        assertThat(res.cpu().utilizationPercent()).isEqualTo(45.2);
        assertThat(res.cpu().coreCount()).isEqualTo(2);
        assertThat(res.memory().utilizationPercent()).isEqualTo(37.5);
        assertThat(res.memory().freeGb()).isEqualTo(5.0);
        assertThat(res.memory().totalGb()).isEqualTo(8.0);
        assertThat(res.fileSystem().utilizationPercent()).isEqualTo(40.0);
        assertThat(res.networkTraffic().receiveBytesPerSec()).isEqualTo(1024.0);
        assertThat(res.networkTraffic().transmitBytesPerSec()).isEqualTo(2048.0);
        assertThat(res.gpu().utilizationPercent()).isEqualTo(70.0);
        assertThat(res.gpu().vramFreeGb()).isEqualTo(10.0);
        assertThat(res.gpu().vramTotalGb()).isEqualTo(16.0);
    }

    @Test
    void coreCount와_VRAM이_없으면_0으로_채운다() {
        when(detailMetrics.summary(any(), any())).thenReturn(new HostSummaryMetrics(
                10.0, null, null, null, null, null, null, null, 30.0, null, null, null, null));

        VmSummaryResponse res = service.getVmSummary(null, NS, INFRA, NODE);

        assertThat(res.cpu().coreCount()).isZero();
        assertThat(res.gpu().vramFreeGb()).isZero();
        assertThat(res.gpu().vramTotalGb()).isZero();
    }

    // ── 시계열 ─────────────────────────────────────────────────

    @Test
    void 시계열은_interval_구간과_기본_step으로_조회한다() {
        when(detailMetrics.trend(eq(NodeDetailMetric.CPU), eq(SEL), eq(SEL), any(), any(), eq("5m")))
                .thenReturn(List.of(new Series("value", List.of(new DataPoint(1L, 12.5)))));

        List<NodeMetricTrendResponse> res = service.getVmMetricTrend(null, NS, INFRA, NODE,
                NodeDetailMetric.CPU, NodeMetricInterval.LAST_1H);

        assertThat(res).hasSize(1);
        NodeMetricTrendResponse cpu = res.get(0);
        assertThat(cpu.metric()).isEqualTo(NodeDetailMetric.CPU);
        assertThat(cpu.unit()).isEqualTo("%");
        assertThat(cpu.step()).isEqualTo("5m");
        assertThat(cpu.to() - cpu.from()).isEqualTo(3600L);
        assertThat(cpu.series()).hasSize(1);
        assertThat(cpu.series().get(0).dataPoints().get(0).value()).isEqualTo(12.5);
    }

    @Test
    void metric_미지정이면_6종_전부_반환한다() {
        when(detailMetrics.trend(any(), any(), any(), any(), any(), any())).thenReturn(List.of());

        List<NodeMetricTrendResponse> res = service.getVmMetricTrend(null, NS, INFRA, NODE,
                null, NodeMetricInterval.LAST_10M);

        assertThat(res).extracting(NodeMetricTrendResponse::metric)
                .containsExactly(NodeDetailMetric.values());
        assertThat(res).allSatisfy(r -> assertThat(r.step()).isEqualTo("30s"));
    }

    // ── VM 정보 ─────────────────────────────────────────────────

    @Test
    void VM_정보는_Tumblebug_Node_상세에서_구성한다() {
        when(tumblebugClient.getNode(NS, INFRA, NODE)).thenReturn(Map.ofEntries(
                Map.entry("id", NODE),
                Map.entry("name", "openstack-nodegroup-01-1"),
                Map.entry("status", "Running"),
                Map.entry("nodeGroupId", "openstack-nodegroup-01"),
                Map.entry("connectionName", "openstack-regionone"),
                Map.entry("connectionConfig", Map.of("providerName", "openstack")),
                Map.entry("region", Map.of("region", "RegionOne", "zone", "nova")),
                Map.entry("cspResourceId", "5f1c0d6e-1234"),
                Map.entry("publicIP", "101.79.72.62"),
                Map.entry("privateIP", "172.16.0.1"),
                Map.entry("spec", Map.of("vCPU", 2, "memoryGiB", 4.0, "cspSpecName", "m1.small")),
                Map.entry("image", Map.of("osType", "Ubuntu 24.04")),
                Map.entry("rootDiskSize", "20"),
                Map.entry("rootDiskType", "default"),
                Map.entry("createdTime", "2026-09-05T02:11:00Z")));

        VmInfoResponse info = service.getVmInfo(null, NS, INFRA, NODE);

        assertThat(info.vmId()).isEqualTo("TB:openstack/openstack-infra-01/vm-1");
        assertThat(info.name()).isEqualTo("openstack-nodegroup-01-1");
        assertThat(info.nsId()).isEqualTo(NS);
        assertThat(info.infraId()).isEqualTo(INFRA);
        assertThat(info.nodeGroupId()).isEqualTo("openstack-nodegroup-01");
        assertThat(info.tumblebugStatus()).isEqualTo("Running");
        assertThat(info.provider()).isEqualTo("openstack");
        assertThat(info.connectionName()).isEqualTo("openstack-regionone");
        assertThat(info.region()).isEqualTo("RegionOne");
        assertThat(info.zone()).isEqualTo("nova");
        assertThat(info.cspResourceId()).isEqualTo("5f1c0d6e-1234");
        assertThat(info.ipAddresses()).containsExactly("101.79.72.62", "172.16.0.1");
        assertThat(info.specName()).isEqualTo("m1.small");
        assertThat(info.vCpu()).isEqualTo(2);
        assertThat(info.memoryGiB()).isEqualTo(4.0);
        assertThat(info.rootDiskGb()).isEqualTo(20);
        assertThat(info.rootDiskType()).isEqualTo("default");
        assertThat(info.osType()).isEqualTo("Ubuntu 24.04");
        assertThat(info.createdTime()).isEqualTo("2026-09-05T02:11:00Z");
    }

    @Test
    void VM_정보는_빈_값을_null과_빈_IP목록으로_내린다() {
        when(tumblebugClient.getNode(NS, INFRA, NODE)).thenReturn(Map.of(
                "id", NODE, "name", "", "publicIP", "", "status", "Creating"));

        VmInfoResponse info = service.getVmInfo(null, NS, INFRA, NODE);

        assertThat(info.name()).isEqualTo(NODE);
        assertThat(info.nodeGroupId()).isNull();
        assertThat(info.provider()).isNull();
        assertThat(info.ipAddresses()).isEmpty();
        assertThat(info.vCpu()).isNull();
        assertThat(info.memoryGiB()).isNull();
    }

    @Test
    void Tumblebug_상세_조회_실패는_503이다() {
        when(tumblebugClient.getNode(NS, INFRA, NODE)).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service.getVmInfo(null, NS, INFRA, NODE))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
