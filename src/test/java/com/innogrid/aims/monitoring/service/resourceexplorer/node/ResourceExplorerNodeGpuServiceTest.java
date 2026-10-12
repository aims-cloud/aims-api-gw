package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.infra.repository.AssetRepository;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.ClocksEventReason;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuClocksEventsResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuClocksEventsResponse.GpuClocksEvents;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuCollectState;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuCollectStatusResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuDeviceCollectStatusResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuDeviceInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuDeviceListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuDeviceSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuGroupSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuHealthLevel;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuHealthSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuHealthSummaryResponse.HealthCard;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuPcieReplayResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuPcieReplayResponse.GpuCounter;
import com.innogrid.aims.monitoring.entity.MonitoringTarget;
import com.innogrid.aims.monitoring.entity.MonitoringTarget.ExporterType;
import com.innogrid.aims.monitoring.repository.MonitoringTargetRepository;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostGpuMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostGpuMetricsQueryService.GpuHealth;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostGpuMetricsQueryService.GpuSample;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostGpuMetricsQueryService.GpuSeries;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 노드 GPU 카테고리 패널 1단계 테스트 — 스코프 접근 검증(404), dcgm_exporter 미등록 노드의 미수집 처리,
 * 그룹 KPI 집계(수집 중 GPU 기준·VRAM 짝 맞춤), 목록 행·정렬(인덱스 숫자순·null 마지막)·400, 개별 KPI·정보·404,
 * 수집 상태(HEALTHY/FAILED·체크리스트), 차트(시리즈 이름·UUID 전달·기간 400),
 * 2단계 상태 요약(카드·배너 판정, 미수집 카드 null), PCIe Replay 상세(카운터·24시간 추이), Clock 이벤트 상세(비트마스크 해석·안내).
 *
 * 대역: node-01(assets.id 11, dcgm_exporter 10.0.0.1:9400) — ADMIN 스코프
 *       node-02(assets.id 12, node_exporter 만) — ADMIN 스코프
 *       assets.id 19 — 스코프 밖
 *       GPU 0(GPU-a) 수집 중·지표 7종, 20/80GB, 250.5W, 55°C
 *       GPU 2(GPU-b) 30분 전 끊김, 1시간 평균만 남음
 *       GPU 10(GPU-c) 수집 중·지표 2종, VRAM 사용만 보고, 100W, 61°C
 *       상태 지표: GPU-a ECC 0·PCIe 누적 12(1h 0, 24h 3)·Clock GPU_IDLE / GPU-b 없음
 *                 GPU-c ECC 2·PCIe 누적 5(1h 1.2, 24h 1.4)·Clock HW_THERMAL+SW_POWER_CAP(0x44)
 */
class ResourceExplorerNodeGpuServiceTest {

    ResourceScopeService scopeService = mock(ResourceScopeService.class);
    AssetRepository assetRepository = mock(AssetRepository.class);
    MonitoringTargetRepository targetRepository = mock(MonitoringTargetRepository.class);
    HostGpuMetricsQueryService metrics = mock(HostGpuMetricsQueryService.class);

    ResourceExplorerNodeGpuService service = new ResourceExplorerNodeGpuService(
            new NodeAccessService(scopeService, assetRepository), targetRepository, metrics);

    static final MetricSelector DCGM_SEL = MetricSelector.instance("10.0.0.1:9400");
    static final Instant NOW = Instant.ofEpochSecond(1_700_000_000L);
    static final List<String> ALL = HostGpuMetricsQueryService.EXPECTED_METRICS;

    static final GpuSample A = new GpuSample("GPU-a", "0", "NVIDIA A100-SXM4-80GB", "00000000:17:00.0", "550.54.15",
            NOW.minusSeconds(10), Set.copyOf(ALL), 42.04, 30.0, 20480.0, 61440.0, 20.0, 55.0, 250.5, null);
    static final GpuSample B = new GpuSample("GPU-b", "2", "NVIDIA A100-SXM4-80GB", null, null,
            NOW.minusSeconds(1800), Set.of(), null, 5.0, null, null, null, null, null, null);
    static final GpuSample C = new GpuSample("GPU-c", "10", "NVIDIA A100-SXM4-80GB", null, null,
            NOW.minusSeconds(5), Set.of("DCGM_FI_DEV_GPU_UTIL", "DCGM_FI_DEV_FB_USED"), 3.0, 2.0, 1024.0, null, null, 61.0, 100.0, null);

    @BeforeEach
    void setUp() {
        Asset node01 = new Asset("mdc-01", Asset.AssetType.SERVER, "node-01");
        ReflectionTestUtils.setField(node01, "id", 11L);
        Asset node02 = new Asset("mdc-01", Asset.AssetType.SERVER, "node-02");
        ReflectionTestUtils.setField(node02, "id", 12L);

        ScopedHost gpuHost = new ScopedHost("ADMIN", 11L, "node-01", null, "mdc-01", ResourceStatus.RUNNING,
                "10.0.0.1:9100", List.of("10.0.0.1"));
        ScopedHost cpuHost = new ScopedHost("ADMIN", 12L, "node-02", null, "mdc-01", ResourceStatus.RUNNING,
                "10.0.0.2:9100", List.of("10.0.0.2"));
        when(scopeService.currentUserScope()).thenReturn(
                new Scope(Set.of("ADMIN"), List.of(gpuHost, cpuHost), List.of(), List.of()));
        when(assetRepository.findById(11L)).thenReturn(Optional.of(node01));
        when(assetRepository.findById(12L)).thenReturn(Optional.of(node02));

        when(targetRepository.findByAsset_IdIn(List.of(11L))).thenReturn(List.of(
                new MonitoringTarget("mdc-01", "node-01", "10.0.0.1", 9100, ExporterType.NODE_EXPORTER),
                new MonitoringTarget("mdc-01", "node-01", "10.0.0.1", 9400, ExporterType.DCGM_EXPORTER)));
        when(targetRepository.findByAsset_IdIn(List.of(12L))).thenReturn(List.of(
                new MonitoringTarget("mdc-01", "node-02", "10.0.0.2", 9100, ExporterType.NODE_EXPORTER)));

        when(metrics.devices(DCGM_SEL)).thenReturn(List.of(A, B, C));
        when(metrics.health(DCGM_SEL)).thenReturn(Map.of(
                "GPU-a", new GpuHealth(0.0, 12.0, 0.0, 3.0, 0x1L),
                "GPU-c", new GpuHealth(2.0, 5.0, 1.2, 1.4, 0x44L)));
    }

    // ── 접근 검증·미수집 ────────────────────────────────────────

    @Test
    void 스코프_밖_노드는_404이고_Thanos를_조회하지_않는다() {
        assertThatThrownBy(() -> service.getGroupSummary("19"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(metrics, never()).devices(any());
    }

    @Test
    void dcgm_exporter가_없는_노드는_조회_없이_미수집이고_개별_GPU는_404다() {
        assertThat(service.getGroupSummary("12"))
                .isEqualTo(new GpuGroupSummaryResponse(0, null, null, null, null, null, null));
        assertThat(service.getDevices("12", 1, 10, "gpuName,asc").items()).isEmpty();
        assertThat(service.getCollectStatus("12").gpus()).isEmpty();
        assertThat(service.getGroupMetricTrend("12", GpuMetric.GPU_UTILIZATION, NOW.minusSeconds(60), NOW, "15s").series())
                .isEmpty();
        assertThatThrownBy(() -> service.getDeviceSummary("12", "GPU-a"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(metrics, never()).devices(any());
        verify(metrics, never()).trend(any(), any(), any(), any(), any(), anyString());
    }

    // ── 그룹 ────────────────────────────────────────────────────

    @Test
    void 그룹_KPI는_24시간_GPU_수와_수집_중_GPU의_합계_최댓값이고_VRAM은_사용_여유가_둘_다_있는_GPU만_센다() {
        GpuGroupSummaryResponse summary = service.getGroupSummary("11");

        assertThat(summary.totalGpuCount()).isEqualTo(3);
        assertThat(summary.vramUsedGb()).isEqualTo(20.0);           // GPU-c 1GB 는 여유 미보고라 제외
        assertThat(summary.vramTotalGb()).isEqualTo(80.0);
        assertThat(summary.vramUtilizationPercent()).isEqualTo(25.0);
        assertThat(summary.totalPowerWatts()).isEqualTo(350.5);
        assertThat(summary.peakPowerWatts()).isEqualTo(250.5);
        assertThat(summary.maxTemperatureCelsius()).isEqualTo(61.0);
    }

    @Test
    void 목록은_기본_인덱스_숫자순이고_행은_GB_소수1자리_끊긴_GPU는_현재값_null_알람수_null이다() {
        PageResponse<GpuDeviceListItemResponse> page = service.getDevices("11", 1, 10, "gpuName,asc");

        assertThat(page.items()).extracting(GpuDeviceListItemResponse::gpuName).containsExactly("GPU 0", "GPU 2", "GPU 10");
        assertThat(page.items().get(0)).isEqualTo(new GpuDeviceListItemResponse("GPU-a", "GPU 0", "NVIDIA A100-SXM4-80GB",
                42.0, 30.0, 25.0, 20.0, 20.0, 80.0, 55.0, 250.5, null));
        GpuDeviceListItemResponse stale = page.items().get(1);
        assertThat(stale.gpuUtilCurrentPercent()).isNull();
        assertThat(stale.gpuUtilAvgPercent()).isEqualTo(5.0);
        assertThat(stale.vramTotalGb()).isNull();
    }

    @Test
    void 목록_정렬은_값_없음을_방향과_무관하게_마지막에_두고_페이징한다() {
        assertThat(service.getDevices("11", 1, 10, "powerWatts,desc").items())
                .extracting(GpuDeviceListItemResponse::gpuId).containsExactly("GPU-a", "GPU-c", "GPU-b");
        assertThat(service.getDevices("11", 1, 10, "powerWatts,asc").items())
                .extracting(GpuDeviceListItemResponse::gpuId).containsExactly("GPU-c", "GPU-a", "GPU-b");
        assertThat(service.getDevices("11", 1, 10, "gpuName,desc").items())
                .extracting(GpuDeviceListItemResponse::gpuName).containsExactly("GPU 10", "GPU 2", "GPU 0");

        PageResponse<GpuDeviceListItemResponse> second = service.getDevices("11", 2, 2, "gpuName,asc");
        assertThat(second.items()).extracting(GpuDeviceListItemResponse::gpuName).containsExactly("GPU 10");
        assertThat(second.totalElements()).isEqualTo(3);
        assertThat(second.totalPages()).isEqualTo(2);
    }

    @Test
    void 지원하지_않는_정렬_필드는_조회_전에_400이다() {
        assertThatThrownBy(() -> service.getDevices("11", 1, 10, "fanSpeed,asc"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(metrics, never()).devices(any());
    }

    @Test
    void 그룹_차트는_GPU별_시리즈를_GPU_인덱스_이름으로_내리고_기간이_거꾸로면_400이다() {
        Instant from = NOW.minusSeconds(3600);
        when(metrics.trend(eq(GpuMetric.VRAM_USED), eq(DCGM_SEL), isNull(), eq(from), eq(NOW), eq("60s"))).thenReturn(List.of(
                new GpuSeries("GPU-a", "0", List.of(new DataPoint(NOW.getEpochSecond(), 20.0))),
                new GpuSeries("GPU-x", null, List.of(new DataPoint(NOW.getEpochSecond(), 1.0)))));

        GpuMetricTrendResponse trend = service.getGroupMetricTrend("11", GpuMetric.VRAM_USED, from, NOW, "60s");

        assertThat(trend.unit()).isEqualTo("GB");
        assertThat(trend.from()).isEqualTo(from.getEpochSecond());
        assertThat(trend.series()).extracting(GpuMetricTrendResponse.Series::name).containsExactly("GPU 0", "GPU GPU-x");

        assertThatThrownBy(() -> service.getGroupMetricTrend("11", GpuMetric.VRAM_USED, NOW, NOW, "60s"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── 개별 GPU ────────────────────────────────────────────────

    @Test
    void 개별_KPI는_현재값이고_팬처럼_수집되지_않는_값은_null이며_24시간_밖_UUID는_404다() {
        assertThat(service.getDeviceSummary("11", "GPU-a"))
                .isEqualTo(new GpuDeviceSummaryResponse(42.0, 25.0, 20.0, 80.0, 250.5, 55.0, null));
        assertThat(service.getDeviceSummary("11", "GPU-b"))
                .isEqualTo(new GpuDeviceSummaryResponse(null, null, null, null, null, null, null));
        assertThatThrownBy(() -> service.getDeviceSummary("11", "GPU-zzz"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 정보_표는_라벨값과_수집_상태를_담고_기본_수집_항목에_없는_버전은_null이다() {
        GpuDeviceInfoResponse info = service.getDeviceInfo("11", "GPU-a");

        assertThat(info).isEqualTo(new GpuDeviceInfoResponse("NVIDIA A100-SXM4-80GB", "GPU-a", "00000000:17:00.0",
                null, "550.54.15", null, null, null, "COLLECTING",
                NOW.minusSeconds(10).atZone(ZoneId.systemDefault()).toOffsetDateTime()));
        assertThat(service.getDeviceInfo("11", "GPU-b").collectStatus()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void 개별_차트는_UUID를_넘겨_해당_GPU만_조회한다() {
        Instant from = NOW.minusSeconds(600);
        when(metrics.trend(GpuMetric.GPU_TEMPERATURE, DCGM_SEL, "GPU-a", from, NOW, "15s")).thenReturn(List.of(
                new GpuSeries("GPU-a", "0", List.of(new DataPoint(NOW.getEpochSecond(), 55.0)))));

        GpuMetricTrendResponse trend = service.getDeviceMetricTrend("11", "GPU-a", GpuMetric.GPU_TEMPERATURE, from, NOW, "15s");

        assertThat(trend.unit()).isEqualTo("°C");
        assertThat(trend.series()).hasSize(1);
        assertThat(trend.series().get(0).dataPoints()).containsExactly(new DataPoint(NOW.getEpochSecond(), 55.0));
    }

    // ── 수집 상태 ───────────────────────────────────────────────

    @Test
    void 그룹_수집_상태는_기대_지표가_전부면_HEALTHY_아니면_FAILED이고_최근_수집은_최댓값이다() {
        GpuCollectStatusResponse status = service.getCollectStatus("11");

        assertThat(status.targetGpuCount()).isEqualTo(3);
        assertThat(status.healthyGpuCount()).isEqualTo(1);
        assertThat(status.failedGpuCount()).isEqualTo(2);
        assertThat(status.collectIntervalSeconds()).isEqualTo(15);
        assertThat(status.lastCollectedAt()).isEqualTo(NOW.minusSeconds(5).atZone(ZoneId.systemDefault()).toOffsetDateTime());
        assertThat(status.gpus()).extracting(GpuCollectStatusResponse.GpuCollectStatus::state)
                .containsExactly(GpuCollectState.HEALTHY, GpuCollectState.FAILED, GpuCollectState.FAILED);
        assertThat(status.gpus()).extracting(GpuCollectStatusResponse.GpuCollectStatus::collectedMetricCount)
                .containsExactly(7, 0, 2);
        assertThat(status.gpus().get(1).gpuName()).isEqualTo("GPU 2");
        assertThat(status.gpus().get(1).totalMetricCount()).isEqualTo(7);
    }

    @Test
    void 개별_수집_상태는_기대_지표_순서대로_체크리스트를_만든다() {
        GpuDeviceCollectStatusResponse status = service.getDeviceCollectStatus("11", "GPU-c");

        assertThat(status.gpuName()).isEqualTo("GPU 10");
        assertThat(status.state()).isEqualTo(GpuCollectState.FAILED);
        assertThat(status.collectedMetricCount()).isEqualTo(2);
        assertThat(status.totalMetricCount()).isEqualTo(7);
        assertThat(status.metrics()).extracting(GpuDeviceCollectStatusResponse.MetricCollectStatus::metricName)
                .containsExactlyElementsOf(ALL);
        assertThat(status.metrics()).filteredOn(GpuDeviceCollectStatusResponse.MetricCollectStatus::collected)
                .extracting(GpuDeviceCollectStatusResponse.MetricCollectStatus::metricName)
                .containsExactly("DCGM_FI_DEV_GPU_UTIL", "DCGM_FI_DEV_FB_USED");
    }

    // ── 상태 요약 (2단계) ───────────────────────────────────────

    @Test
    void 그룹_상태_요약은_GPU별_단계의_최악을_쓰고_카드는_수집되는_GPU만_센다() {
        GpuHealthSummaryResponse health = service.getGroupHealth("11");

        assertThat(health.anomalyLevel()).isEqualTo(GpuHealthLevel.CRITICAL);
        assertThat(health.anomalyGpuCount()).isEqualTo(2);                     // GPU-b(수집 끊김)·GPU-c
        assertThat(health.anomalyMessage())
                .isEqualTo("GPU 2장에서 이상 징후 감지 — 수집 끊김 2장, ECC DBE 오류 1장, PCIe Replay 증가 1장, Clock 제한 1장");
        assertThat(health.collectStatus()).isEqualTo(new HealthCard(GpuHealthLevel.WARNING, null));
        assertThat(health.eccDbeErrors()).isEqualTo(new HealthCard(GpuHealthLevel.CRITICAL, 2));
        assertThat(health.pcieReplayCounter()).isEqualTo(new HealthCard(GpuHealthLevel.WARNING, 1));   // 1.2 → 1건
        assertThat(health.clocksEventReasons()).isEqualTo(new HealthCard(GpuHealthLevel.CRITICAL, 1)); // GPU_IDLE 은 정상
    }

    @Test
    void 개별_상태_요약은_그_GPU_1장_기준이고_정상이면_배너_문구가_없다() {
        GpuHealthSummaryResponse a = service.getDeviceHealth("11", "GPU-a");
        assertThat(a).isEqualTo(new GpuHealthSummaryResponse(GpuHealthLevel.NORMAL, 0, null,
                new HealthCard(GpuHealthLevel.NORMAL, null), new HealthCard(GpuHealthLevel.NORMAL, 0),
                new HealthCard(GpuHealthLevel.NORMAL, 0), new HealthCard(GpuHealthLevel.NORMAL, 0)));

        GpuHealthSummaryResponse c = service.getDeviceHealth("11", "GPU-c");
        assertThat(c.anomalyLevel()).isEqualTo(GpuHealthLevel.CRITICAL);
        assertThat(c.anomalyGpuCount()).isEqualTo(1);
        assertThat(c.anomalyMessage()).isEqualTo("이상 징후 감지 — 수집 끊김, ECC DBE 오류, PCIe Replay 증가, Clock 제한");
        assertThat(c.collectStatus()).isEqualTo(new HealthCard(GpuHealthLevel.WARNING, null));    // 7종 중 2종
    }

    @Test
    void 상태_지표가_없는_GPU는_수집_카드만_판정하고_나머지_카드는_미수집_null이다() {
        GpuHealthSummaryResponse b = service.getDeviceHealth("11", "GPU-b");

        assertThat(b.anomalyLevel()).isEqualTo(GpuHealthLevel.CRITICAL);
        assertThat(b.anomalyMessage()).isEqualTo("이상 징후 감지 — 수집 끊김");
        assertThat(b.collectStatus()).isEqualTo(new HealthCard(GpuHealthLevel.CRITICAL, null));
        assertThat(b.eccDbeErrors()).isEqualTo(new HealthCard(null, null));
        assertThat(b.pcieReplayCounter()).isEqualTo(new HealthCard(null, null));
        assertThat(b.clocksEventReasons()).isEqualTo(new HealthCard(null, null));
    }

    @Test
    void dcgm_exporter가_없는_노드의_상태_요약은_정상_0장에_카드가_전부_미수집이다() {
        HealthCard none = new HealthCard(null, null);
        assertThat(service.getGroupHealth("12"))
                .isEqualTo(new GpuHealthSummaryResponse(GpuHealthLevel.NORMAL, 0, null, none, none, none, none));
        assertThat(service.getPcieReplay("12", null)).isEqualTo(new GpuPcieReplayResponse(List.of(), List.of()));
        assertThat(service.getClocksEvents("12", null).gpus()).isEmpty();
        verify(metrics, never()).health(any());
        verify(metrics, never()).pcieReplayTrend(any(), any(), any(), any());
    }

    // ── PCIe Replay·Clock 이벤트 상세 (2단계) ───────────────────

    @Test
    void PCIe_Replay는_GPU별_누적_1h_24h를_건수로_내리고_최근_24시간_추이를_GPU_이름으로_붙인다() {
        when(metrics.pcieReplayTrend(eq(DCGM_SEL), isNull(), any(), any())).thenReturn(List.of(
                new GpuSeries("GPU-c", "10", List.of(new DataPoint(NOW.getEpochSecond(), 1.2)))));

        GpuPcieReplayResponse pcie = service.getPcieReplay("11", null);

        assertThat(pcie.counters()).containsExactly(
                new GpuCounter("GPU-a", "GPU 0", 12L, 0L, 3L),
                new GpuCounter("GPU-b", "GPU 2", null, null, null),
                new GpuCounter("GPU-c", "GPU 10", 5L, 1L, 1L));
        assertThat(pcie.trend24h()).hasSize(1);
        assertThat(pcie.trend24h().get(0).gpuName()).isEqualTo("GPU 10");

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(metrics).pcieReplayTrend(eq(DCGM_SEL), isNull(), from.capture(), to.capture());
        assertThat(Duration.between(from.getValue(), to.getValue())).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void PCIe_Replay에_gpuId를_주면_그_GPU만이고_없는_GPU는_404다() {
        when(metrics.pcieReplayTrend(eq(DCGM_SEL), eq("GPU-c"), any(), any())).thenReturn(List.of());

        assertThat(service.getPcieReplay("11", "GPU-c").counters())
                .containsExactly(new GpuCounter("GPU-c", "GPU 10", 5L, 1L, 1L));
        verify(metrics).pcieReplayTrend(eq(DCGM_SEL), eq("GPU-c"), any(), any());
        assertThatThrownBy(() -> service.getPcieReplay("11", "GPU-zzz"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void Clock_이벤트는_비트마스크를_사유로_풀고_주의_위험_사유만_안내하며_미수집은_null이다() {
        GpuClocksEventsResponse clocks = service.getClocksEvents("11", null);

        assertThat(clocks.gpus()).extracting(GpuClocksEvents::gpuName).containsExactly("GPU 0", "GPU 2", "GPU 10");

        GpuClocksEvents a = clocks.gpus().get(0);
        assertThat(a.level()).isEqualTo(GpuHealthLevel.NORMAL);
        assertThat(a.activeReasons()).containsExactly(ClocksEventReason.GPU_IDLE);
        assertThat(a.guide()).isEqualTo("정상 범위의 Clock 이벤트만 있습니다.");

        GpuClocksEvents b = clocks.gpus().get(1);
        assertThat(b.level()).isNull();
        assertThat(b.activeReasons()).isNull();
        assertThat(b.guide()).contains("수집되지 않습니다");

        GpuClocksEvents c = clocks.gpus().get(2);
        assertThat(c.level()).isEqualTo(GpuHealthLevel.CRITICAL);
        assertThat(c.activeReasons()).containsExactly(ClocksEventReason.HW_THERMAL, ClocksEventReason.SW_POWER_CAP);
        assertThat(c.guide()).isEqualTo(ClocksEventReason.HW_THERMAL.guide() + "\n" + ClocksEventReason.SW_POWER_CAP.guide());
    }

    @Test
    void 활성_사유가_없으면_정상에_빈_목록이다() {
        when(metrics.health(DCGM_SEL)).thenReturn(Map.of("GPU-a", new GpuHealth(0.0, 0.0, 0.0, 0.0, 0L)));

        GpuClocksEvents a = service.getClocksEvents("11", "GPU-a").gpus().get(0);

        assertThat(a.level()).isEqualTo(GpuHealthLevel.NORMAL);
        assertThat(a.activeReasons()).isEmpty();
        assertThat(a.guide()).isEqualTo("활성 Clock 이벤트가 없습니다.");
    }
}
