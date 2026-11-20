package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.infra.repository.AssetRepository;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuDeviceListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;
import com.innogrid.aims.monitoring.repository.MonitoringTargetRepository;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostCategoryMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostCategoryMetricsQueryService.CpuDevice;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostCategoryMetricsQueryService.CpuInventory;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 노드 CPU 카테고리 패널 테스트 — 스코프 접근 검증(404), exporter 미등록 노드의 미수집 처리,
 * KPI 전달, CPU 목록 행 구성(CPU ID·기본 클럭·수집 상태·마지막 수집)과 정렬·페이징, interval 구간·step, 빈 시리즈.
 *
 * 대역: node-01(assets.id 11, node_exporter 10.0.0.1:9100) — ADMIN 스코프
 *       node-02(assets.id 12, exporter 없음) — ADMIN 스코프
 *       assets.id 19 — 스코프 밖
 */
class ResourceExplorerNodeCpuServiceTest {

    ResourceScopeService scopeService = mock(ResourceScopeService.class);
    AssetRepository assetRepository = mock(AssetRepository.class);
    HostCategoryMetricsQueryService metrics = mock(HostCategoryMetricsQueryService.class);

    ResourceExplorerNodeCpuService service = new ResourceExplorerNodeCpuService(
            new NodeAccessService(scopeService, assetRepository, mock(MonitoringTargetRepository.class)), metrics);

    static final MetricSelector NODE_SEL = MetricSelector.instance("10.0.0.1:9100");
    static final Instant SAMPLE_AT = Instant.ofEpochSecond(1_700_000_000L);

    @BeforeEach
    void setUp() {
        Asset node01 = new Asset("mdc-01", Asset.AssetType.SERVER, "node-01");
        ReflectionTestUtils.setField(node01, "id", 11L);
        Asset node02 = new Asset("mdc-01", Asset.AssetType.SERVER, "node-02");
        ReflectionTestUtils.setField(node02, "id", 12L);

        ScopedHost withExporter = new ScopedHost("ADMIN", 11L, "node-01", null, "mdc-01", ResourceStatus.RUNNING,
                "10.0.0.1:9100", List.of("10.0.0.1"));
        ScopedHost withoutExporter = new ScopedHost("ADMIN", 12L, "node-02", null, "mdc-01", ResourceStatus.PENDING,
                null, List.of());
        when(scopeService.currentUserScope()).thenReturn(
                new Scope(Set.of("ADMIN"), List.of(withExporter, withoutExporter), List.of(), List.of()));
        when(assetRepository.findById(11L)).thenReturn(Optional.of(node01));
        when(assetRepository.findById(12L)).thenReturn(Optional.of(node02));
    }

    // ── 접근 검증 ───────────────────────────────────────────────

    @Test
    void 스코프_밖_노드나_숫자가_아닌_ID는_404이고_Thanos를_조회하지_않는다() {
        assertThatThrownBy(() -> service.getCpuSummary("19"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getCpuDevices("abc", 1, 10, "cpuId,asc"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getCpuMetricTrend("19", CpuMetric.CPU_USED, NodeMetricInterval.LAST_1H))
                .isInstanceOf(BusinessException.class);

        verify(metrics, never()).cpuSummary(any());
        verify(metrics, never()).cpuInventory(any());
        verify(metrics, never()).cpuTrend(any(CpuMetric.class), any(), any(), any(), any());
    }

    @Test
    void exporter가_없는_노드는_Thanos를_조회하지_않고_미수집으로_내린다() {
        assertThat(service.getCpuSummary("12")).isEqualTo(new CpuSummaryResponse(null, null, null, null));
        assertThat(service.getCpuDevices("12", 1, 10, "cpuId,asc").totalElements()).isZero();

        CpuMetricTrendResponse trend = service.getCpuMetricTrend("12", CpuMetric.CPU_USED, NodeMetricInterval.LAST_1H);
        assertThat(trend.series()).isEmpty();
        assertThat(trend.step()).isEqualTo("5m");

        verify(metrics, never()).cpuSummary(any());
        verify(metrics, never()).cpuInventory(any());
        verify(metrics, never()).cpuTrend(any(CpuMetric.class), any(), any(), any(), any());
    }

    // ── KPI ─────────────────────────────────────────────────────

    @Test
    void CPU_KPI는_instance_선택자로_조회한_값을_그대로_돌려준다() {
        CpuSummaryResponse kpi = new CpuSummaryResponse(32.5, 1.2, 5.6, null);
        when(metrics.cpuSummary(NODE_SEL)).thenReturn(kpi);

        assertThat(service.getCpuSummary("11")).isSameAs(kpi);
    }

    // ── CPU 목록 ────────────────────────────────────────────────

    @Test
    void CPU_목록은_소켓별_행에_CPU_ID_기본_클럭_수집_상태_마지막_수집을_채운다() {
        when(metrics.cpuInventory(NODE_SEL)).thenReturn(new CpuInventory(List.of(
                new CpuDevice(0, "GenuineIntel", "Intel(R) Xeon(R) Gold 6338 CPU @ 2.00GHz", 32, 64),
                new CpuDevice(1, "AuthenticAMD", "AMD EPYC 7763 64-Core Processor", 64, 128)), SAMPLE_AT));

        PageResponse<CpuDeviceListItemResponse> page = service.getCpuDevices("11", 1, 10, "cpuId,asc");

        assertThat(page.totalElements()).isEqualTo(2);
        CpuDeviceListItemResponse intel = page.items().get(0);
        assertThat(intel.cpuId()).isEqualTo("cpu-0");
        assertThat(intel.socket()).isZero();
        assertThat(intel.vendor()).isEqualTo("GenuineIntel");
        assertThat(intel.coreCount()).isEqualTo(32);
        assertThat(intel.threadCount()).isEqualTo(64);
        assertThat(intel.baseClockGhz()).isEqualTo(2.0);
        assertThat(intel.collectStatus()).isEqualTo(MonitoringStatus.COLLECTING);
        assertThat(intel.lastCollectedAt()).isEqualTo(SAMPLE_AT.atZone(ZoneId.systemDefault()).toOffsetDateTime());

        CpuDeviceListItemResponse amd = page.items().get(1);
        assertThat(amd.cpuId()).isEqualTo("cpu-1");
        assertThat(amd.baseClockGhz()).isNull();                       // model_name 에 클럭 표기 없음
    }

    @Test
    void cpu_info가_없는_노드는_논리_CPU_수_한_행이_벤더_모델_클럭_null로_내려간다() {
        when(metrics.cpuInventory(NODE_SEL)).thenReturn(new CpuInventory(List.of(new CpuDevice(0, null, null, 8, 8)), SAMPLE_AT));

        List<CpuDeviceListItemResponse> rows = service.getCpuDevices("11", 1, 10, "cpuId,asc").items();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).vendor()).isNull();
        assertThat(rows.get(0).model()).isNull();
        assertThat(rows.get(0).baseClockGhz()).isNull();
        assertThat(rows.get(0).threadCount()).isEqualTo(8);
    }

    @Test
    void CPU_목록은_컬럼_정렬과_페이징을_지원하고_미지원_필드는_400이다() {
        when(metrics.cpuInventory(NODE_SEL)).thenReturn(new CpuInventory(List.of(
                new CpuDevice(0, "GenuineIntel", "Xeon @ 2.00GHz", 2, 4),
                new CpuDevice(1, "GenuineIntel", "Xeon @ 3.10GHz", 4, 8),
                new CpuDevice(2, null, null, 1, 1)), SAMPLE_AT));

        assertThat(service.getCpuDevices("11", 1, 10, "coreCount,desc").items())
                .extracting(CpuDeviceListItemResponse::socket).containsExactly(1, 0, 2);
        assertThat(service.getCpuDevices("11", 1, 10, "baseClockGhz,asc").items())
                .extracting(CpuDeviceListItemResponse::socket).containsExactly(0, 1, 2);   // 값 없음은 마지막
        assertThat(service.getCpuDevices("11", 1, 10, "model,desc").items())
                .extracting(CpuDeviceListItemResponse::socket).containsExactly(2, 1, 0);

        PageResponse<CpuDeviceListItemResponse> second = service.getCpuDevices("11", 2, 2, "socket,asc");
        assertThat(second.items()).extracting(CpuDeviceListItemResponse::socket).containsExactly(2);
        assertThat(second.totalPages()).isEqualTo(2);

        assertThatThrownBy(() -> service.getCpuDevices("11", 1, 10, "clock,asc"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void 기본_클럭은_model_name의_GHz_표기에서만_읽는다() {
        assertThat(ResourceExplorerNodeCpuService.baseClockGhz("Intel(R) Xeon(R) Gold 6338 CPU @ 2.00GHz")).isEqualTo(2.0);
        assertThat(ResourceExplorerNodeCpuService.baseClockGhz("Intel(R) Core(TM) i9-13900K @ 3GHz")).isEqualTo(3.0);
        assertThat(ResourceExplorerNodeCpuService.baseClockGhz("AMD EPYC 7763 64-Core Processor")).isNull();
        assertThat(ResourceExplorerNodeCpuService.baseClockGhz(null)).isNull();
    }

    // ── 시계열 ──────────────────────────────────────────────────

    @Test
    void CPU_시계열은_interval_구간과_기본_step으로_조회하고_미수집이면_빈_시리즈다() {
        when(metrics.cpuTrend(eq(CpuMetric.CPU_IO_WAIT), eq(NODE_SEL), any(), any(), eq("15m")))
                .thenReturn(List.of(new DataPoint(1L, 1.5)));

        CpuMetricTrendResponse trend = service.getCpuMetricTrend("11", CpuMetric.CPU_IO_WAIT, NodeMetricInterval.LAST_6H);

        assertThat(trend.metric()).isEqualTo(CpuMetric.CPU_IO_WAIT);
        assertThat(trend.unit()).isEqualTo("%");
        assertThat(trend.step()).isEqualTo("15m");
        assertThat(trend.to() - trend.from()).isEqualTo(6 * 3600);
        assertThat(trend.series()).hasSize(1);
        assertThat(trend.series().get(0).name()).isEqualTo("cpu");
        assertThat(trend.series().get(0).dataPoints()).containsExactly(new DataPoint(1L, 1.5));

        when(metrics.cpuTrend(eq(CpuMetric.CPU_STEAL), eq(NODE_SEL), any(), any(), eq("1d"))).thenReturn(List.of());
        assertThat(service.getCpuMetricTrend("11", CpuMetric.CPU_STEAL, NodeMetricInterval.LAST_30D).series()).isEmpty();
    }
}
