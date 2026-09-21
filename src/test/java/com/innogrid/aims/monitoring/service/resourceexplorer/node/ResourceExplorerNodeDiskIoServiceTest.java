package com.innogrid.aims.monitoring.service.resourceexplorer.node;

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
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoDeviceListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostDiskIoMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostDiskIoMetricsQueryService.DeviceIo;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 노드 Disk I/O 카테고리 패널 테스트 — 스코프 접근 검증(404), exporter 미등록 노드의 미수집 처리,
 * KPI 합산(지연 IOPS 가중·사용률 평균·값 없음), 장치 목록 정렬·페이징·400, 전체/장치 시계열.
 *
 * 대역: node-01(assets.id 11, node_exporter 10.0.0.1:9100) — ADMIN 스코프
 *       node-02(assets.id 12, exporter 없음) — ADMIN 스코프
 *       assets.id 19 — 스코프 밖
 */
class ResourceExplorerNodeDiskIoServiceTest {

    ResourceScopeService scopeService = mock(ResourceScopeService.class);
    AssetRepository assetRepository = mock(AssetRepository.class);
    HostDiskIoMetricsQueryService metrics = mock(HostDiskIoMetricsQueryService.class);

    ResourceExplorerNodeDiskIoService service = new ResourceExplorerNodeDiskIoService(
            new NodeAccessService(scopeService, assetRepository), metrics);

    static final MetricSelector NODE_SEL = MetricSelector.instance("10.0.0.1:9100");

    static final DeviceIo SDA = new DeviceIo("sda", 1_000_000.0, 500_000.0, 100.0, 50.0, 0.4, 1.0, 20.0);
    static final DeviceIo NVME = new DeviceIo("nvme0n1", 3_000_000.0, 1_000_000.0, 300.0, 150.0, 0.2, 0.5, 60.0);
    static final DeviceIo IDLE = new DeviceIo("sdb", 0.0, 0.0, 0.0, 0.0, null, null, 0.0);   // 구간에 I/O 없음

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

        when(metrics.devices(NODE_SEL)).thenReturn(List.of(NVME, SDA, IDLE));
    }

    // ── 접근 검증·미수집 ────────────────────────────────────────

    @Test
    void 스코프_밖_노드는_404이고_Thanos를_조회하지_않는다() {
        assertThatThrownBy(() -> service.getDiskIoSummary("19"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getDiskIoDevices("19", 1, 10, "device,asc")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getDiskIoMetricTrend("19", DiskIoMetric.DISK_READ_BYTES, null, NodeMetricInterval.LAST_1H))
                .isInstanceOf(BusinessException.class);

        verify(metrics, never()).devices(any());
        verify(metrics, never()).trend(any(), any(), any(), any(), any(), any());
    }

    @Test
    void exporter가_없는_노드는_Thanos를_조회하지_않고_미수집으로_내린다() {
        DiskIoSummaryResponse summary = service.getDiskIoSummary("12");
        assertThat(summary.deviceCount()).isZero();
        assertThat(summary.readBytesPerSec()).isNull();
        assertThat(summary.utilizationPercent()).isNull();
        assertThat(service.getDiskIoDevices("12", 1, 10, "device,asc").totalElements()).isZero();
        assertThat(service.getDiskIoMetricTrend("12", DiskIoMetric.DISK_UTILIZATION, null, NodeMetricInterval.LAST_1H).series()).isEmpty();

        verify(metrics, never()).devices(any());
        verify(metrics, never()).trend(any(), any(), any(), any(), any(), any());
    }

    // ── KPI ─────────────────────────────────────────────────────

    @Test
    void KPI는_바이트_IOPS를_합산하고_지연은_IOPS_가중_평균_사용률은_장치_평균이다() {
        DiskIoSummaryResponse summary = service.getDiskIoSummary("11");

        assertThat(summary.deviceCount()).isEqualTo(3);
        assertThat(summary.readBytesPerSec()).isEqualTo(4_000_000.0);
        assertThat(summary.writeBytesPerSec()).isEqualTo(1_500_000.0);
        assertThat(summary.readIops()).isEqualTo(400.0);
        assertThat(summary.writeIops()).isEqualTo(200.0);
        assertThat(summary.readLatencyMs()).isEqualTo(0.25);           // (0.4×100 + 0.2×300) / 400, sdb 는 I/O 없어 제외
        assertThat(summary.writeLatencyMs()).isEqualTo(0.63);          // (1.0×50 + 0.5×150) / 200 = 0.625 → 0.63
        assertThat(summary.utilizationPercent()).isEqualTo(26.67);     // (20 + 60 + 0) / 3
    }

    @Test
    void KPI는_장치가_없으면_장치_수_0에_값_전부_null이고_지연은_IO가_전혀_없으면_null이다() {
        when(metrics.devices(NODE_SEL)).thenReturn(List.of());
        DiskIoSummaryResponse empty = service.getDiskIoSummary("11");
        assertThat(empty.deviceCount()).isZero();
        assertThat(empty.readBytesPerSec()).isNull();

        when(metrics.devices(NODE_SEL)).thenReturn(List.of(IDLE));
        DiskIoSummaryResponse idle = service.getDiskIoSummary("11");
        assertThat(idle.deviceCount()).isEqualTo(1);
        assertThat(idle.readBytesPerSec()).isEqualTo(0.0);
        assertThat(idle.readLatencyMs()).isNull();
        assertThat(idle.utilizationPercent()).isEqualTo(0.0);
    }

    // ── 장치 목록 ───────────────────────────────────────────────

    @Test
    void 장치_목록은_행을_소수2자리로_채우고_정렬_페이징하며_미지원_필드는_400이다() {
        PageResponse<DiskIoDeviceListItemResponse> page = service.getDiskIoDevices("11", 1, 10, "device,asc");

        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.items()).extracting(DiskIoDeviceListItemResponse::device).containsExactly("nvme0n1", "sda", "sdb");
        DiskIoDeviceListItemResponse sda = page.items().get(1);
        assertThat(sda.readBytesPerSec()).isEqualTo(1_000_000.0);
        assertThat(sda.readLatencyMs()).isEqualTo(0.4);
        assertThat(sda.utilizationPercent()).isEqualTo(20.0);
        assertThat(page.items().get(2).readLatencyMs()).isNull();

        assertThat(service.getDiskIoDevices("11", 1, 10, "utilizationPercent,desc").items())
                .extracting(DiskIoDeviceListItemResponse::device).containsExactly("nvme0n1", "sda", "sdb");
        assertThat(service.getDiskIoDevices("11", 1, 10, "readLatencyMs,asc").items())
                .extracting(DiskIoDeviceListItemResponse::device).containsExactly("nvme0n1", "sda", "sdb");   // 값 없음은 마지막
        assertThat(service.getDiskIoDevices("11", 1, 10, "readLatencyMs,desc").items())
                .extracting(DiskIoDeviceListItemResponse::device).containsExactly("sda", "nvme0n1", "sdb");

        PageResponse<DiskIoDeviceListItemResponse> second = service.getDiskIoDevices("11", 2, 2, "device,asc");
        assertThat(second.items()).extracting(DiskIoDeviceListItemResponse::device).containsExactly("sdb");
        assertThat(second.totalPages()).isEqualTo(2);

        assertThatThrownBy(() -> service.getDiskIoDevices("11", 1, 10, "queueDepth,asc"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── 시계열 ──────────────────────────────────────────────────

    @Test
    void 시계열은_전체_합산이면_disk_io_시리즈_장치_지정이면_장치명_시리즈로_interval_구간을_조회한다() {
        when(metrics.trend(eq(DiskIoMetric.DISK_READ_BYTES), eq(NODE_SEL), isNull(), any(), any(), eq("15m")))
                .thenReturn(List.of(new DataPoint(1L, 4_000_000.0)));
        when(metrics.trend(eq(DiskIoMetric.DISK_READ_LATENCY), eq(NODE_SEL), eq("sda"), any(), any(), eq("5m")))
                .thenReturn(List.of(new DataPoint(1L, 0.4)));

        DiskIoMetricTrendResponse total = service.getDiskIoMetricTrend("11", DiskIoMetric.DISK_READ_BYTES, "  ", NodeMetricInterval.LAST_6H);
        assertThat(total.device()).isNull();                             // 공백은 전체 합산
        assertThat(total.unit()).isEqualTo("B/s");
        assertThat(total.step()).isEqualTo("15m");
        assertThat(total.to() - total.from()).isEqualTo(6 * 3600);
        assertThat(total.series()).hasSize(1);
        assertThat(total.series().get(0).name()).isEqualTo("disk_io");

        DiskIoMetricTrendResponse device = service.getDiskIoMetricTrend("11", DiskIoMetric.DISK_READ_LATENCY, "sda", NodeMetricInterval.LAST_1H);
        assertThat(device.device()).isEqualTo("sda");
        assertThat(device.unit()).isEqualTo("ms");
        assertThat(device.series().get(0).name()).isEqualTo("sda");
        assertThat(device.series().get(0).dataPoints()).containsExactly(new DataPoint(1L, 0.4));

        when(metrics.trend(eq(DiskIoMetric.DISK_UTILIZATION), eq(NODE_SEL), isNull(), any(), any(), eq("1d"))).thenReturn(List.of());
        assertThat(service.getDiskIoMetricTrend("11", DiskIoMetric.DISK_UTILIZATION, null, NodeMetricInterval.LAST_30D).series()).isEmpty();
    }
}
