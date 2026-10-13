package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuDeviceSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu.VmGpuAllocationResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu.VmGpuGroupSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu.VmGpuInstanceInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu.VmGpuInstanceListItemResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostGpuMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostGpuMetricsQueryService.GpuSample;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostGpuMetricsQueryService.GpuSeries;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbInfra;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbNode;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VM GPU 패널 테스트 — 스코프(404), tb_* 선택자로 DCGM 표본 조회, 그룹 KPI(최대·평균), 인스턴스 목록(행·MIG 판별·정렬·400),
 * 개별 KPI·할당 정보(VM 이름·null 칸)·기본 정보, 404, interval 시계열(구간·step·시리즈 이름).
 *
 * 대역: ns aws / infra aims-gpu-monitoring / node aims-aws-gpu-1(이름 gpu-vm), ADMIN → aws 매핑
 *       GPU 0(GPU-a) 수집 중, util 42.04·1h 평균 30, VRAM 20/80GB(1h 평균 20%), 55°C, 250.5W
 *       GPU 1(GPU-b) 30분 전 끊김, 1h 평균 5 만 남음
 *       GPU 10(GPU-c) 수집 중 MIG, util 10, VRAM 1/2GB
 */
class ResourceExplorerVmGpuServiceTest {

    AuthService authService = mock(AuthService.class);
    RoleNamespaceRepository roleNamespaceRepository = mock(RoleNamespaceRepository.class);
    TumblebugTreeBuilder tumblebugTreeBuilder = mock(TumblebugTreeBuilder.class);
    HostGpuMetricsQueryService metrics = mock(HostGpuMetricsQueryService.class);
    TumblebugPanelSupport support = new TumblebugPanelSupport(authService, roleNamespaceRepository, tumblebugTreeBuilder);

    ResourceExplorerVmGpuService service = new ResourceExplorerVmGpuService(support, metrics);

    static final String NS = "aws";
    static final String INFRA = "aims-gpu-monitoring";
    static final String NODE = "aims-aws-gpu-1";
    static final MetricSelector SEL = MetricSelector.tumblebugNode(NS, INFRA, NODE);
    static final Instant NOW = Instant.ofEpochSecond(1_700_000_000L);
    static final Set<String> ALL = Set.copyOf(HostGpuMetricsQueryService.EXPECTED_METRICS);

    static final GpuSample A = new GpuSample("GPU-a", "0", "Tesla T4", "00000000:00:1E.0", "595.91.07",
            NOW.minusSeconds(10), ALL, 42.04, 30.0, 20480.0, 61440.0, 20.0, 55.0, 250.5, null);
    static final GpuSample B = new GpuSample("GPU-b", "1", "Tesla T4", null, null,
            NOW.minusSeconds(1800), Set.of(), null, 5.0, null, null, null, null, null, null);
    static final GpuSample C = new GpuSample("GPU-c", "10", "Tesla T4", null, null,
            NOW.minusSeconds(5), ALL, 10.0, 8.0, 1024.0, 1024.0, 40.0, 50.0, 70.0, null);

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
                        new TbNode(NODE, "gpu-vm", "Running", "aims-aws-gpu", "aws", null, "54.180.90.83"))))),
                Set.of(NS), Set.of(), false));

        when(metrics.devices(SEL)).thenReturn(List.of(A, B, C));
        when(metrics.migUuids(SEL)).thenReturn(Set.of("GPU-c"));
    }

    // ── 스코프 ──────────────────────────────────────────────────

    @Test
    void 스코프_밖_Namespace나_없는_VM은_404이고_Thanos를_조회하지_않는다() {
        assertThatThrownBy(() -> service.getGroupSummary(null, "other-ns", INFRA, NODE))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThatThrownBy(() -> service.getInstanceSummary(null, NS, INFRA, "vm-999", "GPU-a"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(metrics, never()).devices(any());
    }

    // ── 그룹 ────────────────────────────────────────────────────

    @Test
    void 그룹_KPI는_24시간_GPU_수와_수집_중_GPU의_최대_평균이고_메모리_사용률은_VRAM_기준이며_알람수는_null이다() {
        assertThat(service.getGroupSummary("ADMIN", NS, INFRA, NODE))
                .isEqualTo(new VmGpuGroupSummaryResponse(3, 42.0, 26.0, 37.5, 50.0, null));
    }

    @Test
    void 인스턴스_목록은_인덱스_숫자순이고_MIG만_타입을_채우며_끊긴_GPU는_현재값_null이다() {
        PageResponse<VmGpuInstanceListItemResponse> page = service.getInstances(null, NS, INFRA, NODE, 1, 10, "instanceName,asc");

        assertThat(page.items()).extracting(VmGpuInstanceListItemResponse::instanceName).containsExactly("GPU 0", "GPU 1", "GPU 10");
        assertThat(page.items().get(0)).isEqualTo(new VmGpuInstanceListItemResponse("GPU-a", "GPU 0", null,
                42.0, 30.0, 25.0, 20.0, 20.0, 80.0, "COLLECTING", null));
        VmGpuInstanceListItemResponse stale = page.items().get(1);
        assertThat(stale.gpuUtilCurrentPercent()).isNull();
        assertThat(stale.gpuUtilAvgPercent()).isEqualTo(5.0);
        assertThat(stale.collectStatus()).isEqualTo("UNAVAILABLE");
        assertThat(page.items().get(2).allocationType()).isEqualTo("MIG");
    }

    @Test
    void 인스턴스_정렬은_값_없음을_마지막에_두고_지원하지_않는_필드나_방향은_조회_전에_400이다() {
        assertThat(service.getInstances(null, NS, INFRA, NODE, 1, 10, "gpuUtilCurrentPercent,desc").items())
                .extracting(VmGpuInstanceListItemResponse::instanceId).containsExactly("GPU-a", "GPU-c", "GPU-b");

        assertThatThrownBy(() -> service.getInstances(null, NS, INFRA, NODE, 1, 10, "fan,asc"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> service.getInstances(null, NS, INFRA, NODE, 1, 10, "instanceName,up"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 그룹_차트는_interval_구간과_기본_step으로_GPU별_시리즈를_GPU_이름으로_내린다() {
        when(metrics.trend(eq(GpuMetric.GPU_UTILIZATION), eq(SEL), isNull(), any(), any(), eq("5m"))).thenReturn(List.of(
                new GpuSeries("GPU-a", "0", List.of(new DataPoint(NOW.getEpochSecond(), 42.0)))));

        GpuMetricTrendResponse trend = service.getGroupMetricTrend(null, NS, INFRA, NODE,
                GpuMetric.GPU_UTILIZATION, NodeMetricInterval.LAST_1H);

        assertThat(trend.step()).isEqualTo("5m");
        assertThat(trend.to() - trend.from()).isEqualTo(Duration.ofHours(1).toSeconds());
        assertThat(trend.series()).extracting(GpuMetricTrendResponse.Series::name).containsExactly("GPU 0");
    }

    // ── 개별 인스턴스 ───────────────────────────────────────────

    @Test
    void 개별_KPI는_노드_GPU와_같은_카드이고_24시간_밖_UUID는_404다() {
        assertThat(service.getInstanceSummary(null, NS, INFRA, NODE, "GPU-a"))
                .isEqualTo(new GpuDeviceSummaryResponse(42.0, 25.0, 20.0, 80.0, 250.5, 55.0, null));
        assertThatThrownBy(() -> service.getInstanceSummary(null, NS, INFRA, NODE, "GPU-zzz"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 할당_정보는_VM_이름과_VRAM을_채우고_물리_GPU_라이선스는_null이며_MIG만_타입을_채운다() {
        assertThat(service.getInstanceAllocation(null, NS, INFRA, NODE, "GPU-a"))
                .isEqualTo(new VmGpuAllocationResponse("GPU 0", NODE, "gpu-vm", null, null, 80.0, 20.0, 25.0, null));
        assertThat(service.getInstanceAllocation(null, NS, INFRA, NODE, "GPU-c").allocationType()).isEqualTo("MIG");
    }

    @Test
    void 기본_정보는_드라이버_버전_라벨과_수집_상태를_담고_CUDA는_null이다() {
        assertThat(service.getInstanceInfo(null, NS, INFRA, NODE, "GPU-a"))
                .isEqualTo(new VmGpuInstanceInfoResponse("Tesla T4", "GPU-a", null, "595.91.07", "COLLECTING",
                        NOW.minusSeconds(10).atZone(ZoneId.systemDefault()).toOffsetDateTime()));
        assertThat(service.getInstanceInfo(null, NS, INFRA, NODE, "GPU-b").collectStatus()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void 개별_차트는_UUID를_넘겨_그_인스턴스만_조회한다() {
        when(metrics.trend(eq(GpuMetric.VRAM_USED), eq(SEL), eq("GPU-a"), any(), any(), eq("30s"))).thenReturn(List.of(
                new GpuSeries("GPU-a", "0", List.of(new DataPoint(NOW.getEpochSecond(), 20.0)))));

        GpuMetricTrendResponse trend = service.getInstanceMetricTrend(null, NS, INFRA, NODE, "GPU-a",
                GpuMetric.VRAM_USED, NodeMetricInterval.LAST_10M);

        assertThat(trend.unit()).isEqualTo("GB");
        assertThat(trend.series()).hasSize(1);
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(metrics).trend(eq(GpuMetric.VRAM_USED), eq(SEL), eq("GPU-a"), from.capture(), to.capture(), eq("30s"));
        assertThat(Duration.between(from.getValue(), to.getValue())).isEqualTo(Duration.ofMinutes(10));
    }
}
