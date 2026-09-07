package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.RoleNamespace;
import com.innogrid.aims.infra.repository.RoleNamespaceRepository;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.cpu.VmCpuListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.cpu.VmCpuMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.cpu.VmCpuMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.memory.VmMemoryMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.memory.VmMemoryMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.network.VmNetworkMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.network.VmNetworkMetricTrendResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostCategoryMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostCategoryMetricsQueryService.CpuDevice;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbInfra;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbNode;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VM 카테고리 패널(CPU/Memory/Network/GPU) 테스트 — 공통 스코프(404), tb_* 선택자 전달, interval 구간·step,
 * CPU KPI·목록 정렬·페이징, 빈 시리즈, GPU 501.
 *
 * 대역: ns openstack / infra openstack-infra-01 / node vm-1, ADMIN → openstack 매핑
 */
class ResourceExplorerVmCategoryServiceTest {

    AuthService authService = mock(AuthService.class);
    RoleNamespaceRepository roleNamespaceRepository = mock(RoleNamespaceRepository.class);
    TumblebugTreeBuilder tumblebugTreeBuilder = mock(TumblebugTreeBuilder.class);
    HostCategoryMetricsQueryService metrics = mock(HostCategoryMetricsQueryService.class);
    TumblebugPanelSupport support = new TumblebugPanelSupport(authService, roleNamespaceRepository, tumblebugTreeBuilder);

    ResourceExplorerVmCpuService cpu = new ResourceExplorerVmCpuService(support, metrics);
    ResourceExplorerVmMemoryService memory = new ResourceExplorerVmMemoryService(support, metrics);
    ResourceExplorerVmNetworkService network = new ResourceExplorerVmNetworkService(support, metrics);
    ResourceExplorerVmGpuService gpu = new ResourceExplorerVmGpuService(support);

    static final String NS = "openstack";
    static final String INFRA = "openstack-infra-01";
    static final String NODE = "vm-1";
    static final MetricSelector SEL = MetricSelector.tumblebugNode(NS, INFRA, NODE);

    @BeforeEach
    void setUp() {
        when(authService.currentUserRoleIds()).thenReturn(new LinkedHashSet<>(List.of("ADMIN")));
        Role admin = new Role("ADMIN", "관리자");
        when(roleNamespaceRepository.findByRole_NameIn(anyCollection())).thenAnswer(inv -> {
            Collection<String> roles = inv.getArgument(0);
            return roles.contains("ADMIN") ? List.of(new RoleNamespace(admin, NS)) : List.of();
        });
        when(tumblebugTreeBuilder.snapshot(anyCollection())).thenReturn(new TbSnapshot(Instant.now(),
                Map.of(NS, NS),
                Map.of(NS, List.of(new TbInfra(INFRA, INFRA, "Running:1 (R:1/1)", List.of(
                        new TbNode(NODE, NODE, "Running", "g1", "openstack", null, "172.16.0.1"))))),
                Set.of(NS), Set.of(), false));
    }

    // ── 스코프 ──────────────────────────────────────────────────

    @Test
    void 스코프_밖_Namespace나_없는_Node는_404이고_Thanos를_조회하지_않는다() {
        assertThatThrownBy(() -> cpu.getCpuSummary(null, "other-ns", INFRA, NODE))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> memory.getMemoryMetricTrend(null, NS, INFRA, "vm-999",
                VmMemoryMetric.MEMORY_USED, NodeMetricInterval.LAST_1H))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> network.getNetworkMetricTrend("GUEST", NS, INFRA, NODE,
                VmNetworkMetric.NETWORK_RECV, NodeMetricInterval.LAST_1H))
                .isInstanceOf(BusinessException.class);

        verify(metrics, never()).cpuSummary(any());
        verify(metrics, never()).memoryTrend(any(), any(), any(), any(), any());
        verify(metrics, never()).networkTrend(any(), any(), any(), any(), any());
    }

    // ── CPU ─────────────────────────────────────────────────────

    @Test
    void CPU_KPI는_tb_선택자로_조회한_값을_그대로_돌려준다() {
        CpuSummaryResponse kpi = new CpuSummaryResponse(32.5, 1.2, 5.6, null);
        when(metrics.cpuSummary(SEL)).thenReturn(kpi);

        assertThat(cpu.getCpuSummary("ADMIN", NS, INFRA, NODE)).isSameAs(kpi);
    }

    @Test
    void CPU_목록은_수집_상태_COLLECTING으로_정렬_페이징한다() {
        when(metrics.cpuDevices(SEL)).thenReturn(List.of(
                new CpuDevice(1, "GenuineIntel", "Xeon", 4, 8),
                new CpuDevice(0, "GenuineIntel", "Xeon", 2, 4)));

        PageResponse<VmCpuListItemResponse> page = cpu.getCpuDevices(null, NS, INFRA, NODE, 1, 10, "socket,asc");
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.items()).extracting(VmCpuListItemResponse::socket).containsExactly(0, 1);
        assertThat(page.items().get(0).collectStatus()).isEqualTo("COLLECTING");
        assertThat(page.items().get(0).threadCount()).isEqualTo(4);

        assertThat(cpu.getCpuDevices(null, NS, INFRA, NODE, 1, 1, "coreCount,desc").items())
                .extracting(VmCpuListItemResponse::socket).containsExactly(1);

        assertThatThrownBy(() -> cpu.getCpuDevices(null, NS, INFRA, NODE, 1, 10, "cpuId,asc"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void CPU_시계열은_interval_구간과_기본_step으로_조회하고_미수집이면_빈_시리즈다() {
        when(metrics.cpuTrend(eq(VmCpuMetric.CPU_IO_WAIT), eq(SEL), any(), any(), eq("15m")))
                .thenReturn(List.of(new DataPoint(1L, 1.5)));

        VmCpuMetricTrendResponse res = cpu.getCpuMetricTrend(null, NS, INFRA, NODE,
                VmCpuMetric.CPU_IO_WAIT, NodeMetricInterval.LAST_6H);

        assertThat(res.metric()).isEqualTo(VmCpuMetric.CPU_IO_WAIT);
        assertThat(res.unit()).isEqualTo("%");
        assertThat(res.step()).isEqualTo("15m");
        assertThat(res.to() - res.from()).isEqualTo(6 * 3600L);
        assertThat(res.series()).hasSize(1);
        assertThat(res.series().get(0).name()).isEqualTo("cpu");
        assertThat(res.series().get(0).dataPoints()).containsExactly(new DataPoint(1L, 1.5));

        when(metrics.cpuTrend(any(), any(), any(), any(), any())).thenReturn(List.of());
        assertThat(cpu.getCpuMetricTrend(null, NS, INFRA, NODE, VmCpuMetric.CPU_USED, NodeMetricInterval.LAST_10M)
                .series()).isEmpty();
    }

    // ── Memory / Network ────────────────────────────────────────

    @Test
    void 메모리_시계열은_GB_단위_지표를_interval로_조회한다() {
        when(metrics.memoryTrend(eq(VmMemoryMetric.MEMORY_CACHED), eq(SEL), any(), any(), eq("1h")))
                .thenReturn(List.of(new DataPoint(1L, 0.8)));

        VmMemoryMetricTrendResponse res = memory.getMemoryMetricTrend(null, NS, INFRA, NODE,
                VmMemoryMetric.MEMORY_CACHED, NodeMetricInterval.LAST_24H);

        assertThat(res.unit()).isEqualTo("GB");
        assertThat(res.step()).isEqualTo("1h");
        assertThat(res.to() - res.from()).isEqualTo(24 * 3600L);
        assertThat(res.series().get(0).name()).isEqualTo("memory");
        assertThat(res.series().get(0).dataPoints()).containsExactly(new DataPoint(1L, 0.8));
    }

    @Test
    void 네트워크_시계열은_원시_단위로_interval을_조회한다() {
        when(metrics.networkTrend(eq(VmNetworkMetric.NETWORK_PACKETS_SENT), eq(SEL), any(), any(), eq("5m")))
                .thenReturn(List.of(new DataPoint(1L, 120.0)));

        VmNetworkMetricTrendResponse res = network.getNetworkMetricTrend(null, NS, INFRA, NODE,
                VmNetworkMetric.NETWORK_PACKETS_SENT, NodeMetricInterval.LAST_1H);

        assertThat(res.unit()).isEqualTo("pps");
        assertThat(res.step()).isEqualTo("5m");
        assertThat(res.series().get(0).name()).isEqualTo("network");
        assertThat(res.series().get(0).dataPoints()).containsExactly(new DataPoint(1L, 120.0));
    }

    // ── GPU ─────────────────────────────────────────────────────

    @Test
    void GPU는_스코프_검증_뒤_501이다() {
        assertThatThrownBy(() -> gpu.getGroupSummary(null, NS, INFRA, NODE))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_IMPLEMENTED));
        assertThatThrownBy(() -> gpu.getGroupSummary(null, "other-ns", INFRA, NODE))
                .isInstanceOf(BusinessException.class);
    }
}
