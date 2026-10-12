package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.ClocksEventReason;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuMetric;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostGpuMetricsQueryService.GpuHealth;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostGpuMetricsQueryService.GpuSample;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostGpuMetricsQueryService.GpuSeries;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 노드 GPU 패널 Thanos 조회 — PromQL(MIG 제외·UUID 필터), 24시간 존재 판정과 마지막 수집 시각, 지금 수집 지표,
 * 현재값·1시간 평균 병합, 끊긴 GPU, 인덱스 정렬, 시계열 반올림, 장애 처리.
 *
 * 대역: dcgm_exporter 10.0.0.1:9400 — GPU 0(UUID GPU-a, 수집 중, 지표 7종 전부), GPU 10(GPU-c, 수집 중, 지표 2종),
 *       GPU 2(GPU-b, 30분 전 끊김)
 */
class HostGpuMetricsQueryServiceTest {

    private static final MetricSelector SEL = MetricSelector.instance("10.0.0.1:9400");
    private static final String PHYS = "{GPU_I_ID=\"\",instance=\"10.0.0.1:9400\"}";
    private static final long NOW = 1_700_000_000L;

    VictoriaMetricsClient client = mock(VictoriaMetricsClient.class);
    HostGpuMetricsQueryService service = new HostGpuMetricsQueryService(client);

    @Test
    void PromQL은_MIG_시계열을_빼고_instance로_좁히며_장치_1장은_UUID로_좁힌다() {
        assertThat(HostGpuMetricsQueryService.currentQuery("DCGM_FI_DEV_GPU_UTIL", SEL))
                .isEqualTo("max by (UUID) (DCGM_FI_DEV_GPU_UTIL" + PHYS + ")");
        assertThat(HostGpuMetricsQueryService.averageQuery("DCGM_FI_DEV_GPU_UTIL", SEL))
                .isEqualTo("max by (UUID) (avg_over_time(DCGM_FI_DEV_GPU_UTIL" + PHYS + "[1h]))");
        // timestamp() 는 지표 이름을 지우므로 지표별 서브쿼리를 or 로 잇는다 (한 선택자에 여러 지표면 라벨 조합이 겹쳐 거부됨)
        assertThat(HostGpuMetricsQueryService.presenceQuery(SEL))
                .startsWith("max by (UUID, gpu, modelName, pci_bus_id, DCGM_FI_DRIVER_VERSION) ("
                        + "max_over_time(timestamp(DCGM_FI_DEV_GPU_UTIL" + PHYS + ")[1d:1m]) or "
                        + "max_over_time(timestamp(DCGM_FI_DEV_MEM_COPY_UTIL" + PHYS + ")[1d:1m]) or ")
                .endsWith("max_over_time(timestamp(DCGM_FI_DEV_SM_CLOCK" + PHYS + ")[1d:1m]))")
                .doesNotContain("__name__");
        assertThat(HostGpuMetricsQueryService.collectedQuery(SEL)).startsWith("count by (UUID, __name__) ({__name__=~");

        assertThat(HostGpuMetricsQueryService.trendQuery(GpuMetric.GPU_UTILIZATION, SEL, null))
                .isEqualTo("max by (UUID, gpu) (DCGM_FI_DEV_GPU_UTIL" + PHYS + ")");
        assertThat(HostGpuMetricsQueryService.trendQuery(GpuMetric.VRAM_USED, SEL, "GPU-a"))
                .isEqualTo("max by (UUID, gpu) (DCGM_FI_DEV_FB_USED{GPU_I_ID=\"\",UUID=\"GPU-a\",instance=\"10.0.0.1:9400\"}) / 1024");
        assertThat(HostGpuMetricsQueryService.trendQuery(GpuMetric.VRAM_UTILIZATION, SEL, null))
                .isEqualTo("max by (UUID, gpu) (DCGM_FI_DEV_FB_USED" + PHYS + ") / (max by (UUID, gpu) (DCGM_FI_DEV_FB_USED" + PHYS
                        + ") + max by (UUID, gpu) (DCGM_FI_DEV_FB_FREE" + PHYS + ")) * 100");
        assertThat(HostGpuMetricsQueryService.trendQuery(GpuMetric.FAN_SPEED, SEL, null))
                .isEqualTo("max by (UUID, gpu) (DCGM_FI_DEV_FAN_SPEED" + PHYS + ")");
    }

    @Test
    void 장치는_24시간_존재로_정하고_현재값_평균_지금_수집_지표를_UUID로_병합해_인덱스_숫자순이다() {
        stubDevices();

        List<GpuSample> gpus = service.devices(SEL);

        assertThat(gpus).extracting(GpuSample::index).containsExactly("0", "2", "10");

        GpuSample a = gpus.get(0);
        assertThat(a.uuid()).isEqualTo("GPU-a");
        assertThat(a.model()).isEqualTo("NVIDIA A100-SXM4-80GB");
        assertThat(a.pciBusId()).isEqualTo("00000000:17:00.0");
        assertThat(a.driverVersion()).isEqualTo("550.54.15");
        assertThat(a.lastCollectedAt()).isEqualTo(Instant.ofEpochSecond(NOW - 10));   // 라벨이 바뀐 이전 행보다 최근 것
        assertThat(a.collectedMetrics()).containsExactlyInAnyOrderElementsOf(HostGpuMetricsQueryService.EXPECTED_METRICS);
        assertThat(a.collecting()).isTrue();
        assertThat(a.utilization()).isEqualTo(42.0);
        assertThat(a.utilizationAvg()).isEqualTo(30.0);
        assertThat(a.fbUsedMb()).isEqualTo(20480.0);
        assertThat(a.fbTotalMb()).isEqualTo(81920.0);
        assertThat(a.vramPercent()).isEqualTo(25.0);
        assertThat(a.vramPercentAvg()).isEqualTo(20.0);
        assertThat(a.temperatureC()).isEqualTo(55.0);
        assertThat(a.powerWatts()).isEqualTo(250.5);
        assertThat(a.fanSpeedPercent()).isNull();                                       // 기본 수집 항목 아님
        assertThat(a.displayName()).isEqualTo("GPU 0");

        GpuSample b = gpus.get(1);                                                      // 끊긴 GPU — 현재값 없음, 평균은 남음
        assertThat(b.collecting()).isFalse();
        assertThat(b.utilization()).isNull();
        assertThat(b.fbTotalMb()).isNull();
        assertThat(b.vramPercent()).isNull();
        assertThat(b.utilizationAvg()).isEqualTo(5.0);
        assertThat(b.lastCollectedAt()).isEqualTo(Instant.ofEpochSecond(NOW - 1800));

        assertThat(gpus.get(2).collectedMetrics()).containsExactlyInAnyOrder("DCGM_FI_DEV_GPU_UTIL", "DCGM_FI_DEV_FB_USED");
    }

    @Test
    void 최근_24시간_안에_수집된_GPU가_없으면_값_쿼리를_보내지_않는다() {
        when(client.queryResults(anyString())).thenReturn(List.of());

        assertThat(service.devices(SEL)).isEmpty();
        verify(client).queryResults(HostGpuMetricsQueryService.presenceQuery(SEL));
        verify(client, times(1)).queryResults(anyString());
    }

    @Test
    void 시계열은_GPU별로_인덱스순이고_값은_소수1자리이며_빈_시리즈는_뺀다() {
        Instant from = Instant.ofEpochSecond(NOW - 3600);
        Instant to = Instant.ofEpochSecond(NOW);
        when(client.queryRangeResults(anyString(), any(), any(), anyString())).thenReturn(List.of(
                series("GPU-c", "10", List.of(List.of(NOW - 300, "1.26"))),
                series("GPU-a", "0", List.of(List.of(NOW - 300, "12.34"), List.of(NOW, "NaN"))),
                series("GPU-b", "2", List.of(List.of(NOW, "NaN")))));

        List<GpuSeries> out = service.trend(GpuMetric.GPU_UTILIZATION, SEL, null, from, to, "60s");

        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        verify(client).queryRangeResults(q.capture(), eq(from), eq(to), eq("60s"));
        assertThat(q.getValue()).isEqualTo(HostGpuMetricsQueryService.trendQuery(GpuMetric.GPU_UTILIZATION, SEL, null));
        assertThat(out).extracting(GpuSeries::index).containsExactly("0", "10");
        assertThat(out.get(0).points()).containsExactly(new DataPoint(NOW - 300, 12.3));
        assertThat(out.get(1).points()).containsExactly(new DataPoint(NOW - 300, 1.3));
    }

    @Test
    void Thanos_장애는_장치_빈_목록_시계열_빈_목록이다() {
        when(client.queryResults(anyString())).thenThrow(new RuntimeException("connection refused"));
        when(client.queryRangeResults(anyString(), any(), any(), anyString())).thenThrow(new RuntimeException("timeout"));

        assertThat(service.devices(SEL)).isEmpty();
        assertThat(service.trend(GpuMetric.GPU_POWER, SEL, "GPU-a", Instant.EPOCH, Instant.EPOCH.plusSeconds(60), "15s")).isEmpty();
    }

    @Test
    void 상태_지표_PromQL은_누적_카운터를_increase로_보고_Clock_이벤트는_옛_이름도_읽는다() {
        assertThat(HostGpuMetricsQueryService.increaseQuery("DCGM_FI_DEV_PCIE_REPLAY_COUNTER", SEL, "1h"))
                .isEqualTo("max by (UUID) (increase(DCGM_FI_DEV_PCIE_REPLAY_COUNTER" + PHYS + "[1h]))");
        assertThat(HostGpuMetricsQueryService.clocksQuery(SEL))
                .isEqualTo("max by (UUID) (DCGM_FI_DEV_CLOCKS_EVENT_REASONS" + PHYS + ") or "
                        + "max by (UUID) (DCGM_FI_DEV_CLOCK_THROTTLE_REASONS" + PHYS + ")");
        assertThat(HostGpuMetricsQueryService.pcieReplayTrendQuery(SEL, "GPU-a"))
                .isEqualTo("max by (UUID, gpu) (increase(DCGM_FI_DEV_PCIE_REPLAY_COUNTER"
                        + "{GPU_I_ID=\"\",UUID=\"GPU-a\",instance=\"10.0.0.1:9400\"}[1h]))");
    }

    @Test
    void 상태_지표는_UUID별로_묶고_없는_값은_null이며_비트마스크는_정수다() {
        when(client.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.equals(HostGpuMetricsQueryService.currentQuery("DCGM_FI_DEV_ECC_DBE_VOL_TOTAL", SEL))) {
                return List.of(byUuid("GPU-a", "2"));
            }
            if (q.equals(HostGpuMetricsQueryService.currentQuery("DCGM_FI_DEV_PCIE_REPLAY_COUNTER", SEL))) {
                return List.of(byUuid("GPU-a", "12"), byUuid("GPU-b", "4"));
            }
            if (q.equals(HostGpuMetricsQueryService.increaseQuery("DCGM_FI_DEV_PCIE_REPLAY_COUNTER", SEL, "1h"))) {
                return List.of(byUuid("GPU-a", "0.98"));
            }
            if (q.equals(HostGpuMetricsQueryService.clocksQuery(SEL))) {
                return List.of(byUuid("GPU-a", "68"));
            }
            return List.of();
        });

        Map<String, GpuHealth> health = service.health(SEL);

        assertThat(health.get("GPU-a")).isEqualTo(new GpuHealth(2.0, 12.0, 0.98, null, 68L));
        assertThat(health.get("GPU-b")).isEqualTo(new GpuHealth(null, 4.0, null, null, null));
        assertThat(ClocksEventReason.fromMask(health.get("GPU-a").clocksMask()))
                .containsExactly(ClocksEventReason.HW_THERMAL, ClocksEventReason.SW_POWER_CAP);   // 0x40 + 0x4
    }

    @Test
    void PCIe_추이는_1시간_간격이고_상태_지표_장애는_빈_맵이다() {
        Instant from = Instant.ofEpochSecond(NOW - 86_400);
        Instant to = Instant.ofEpochSecond(NOW);
        when(client.queryRangeResults(anyString(), any(), any(), anyString()))
                .thenReturn(List.of(series("GPU-a", "0", List.of(List.of(NOW, "2.04")))));

        assertThat(service.pcieReplayTrend(SEL, null, from, to)).hasSize(1);
        verify(client).queryRangeResults(HostGpuMetricsQueryService.pcieReplayTrendQuery(SEL, null), from, to, "1h");

        when(client.queryResults(anyString())).thenThrow(new RuntimeException("connection refused"));
        assertThat(service.health(SEL)).isEmpty();
    }

    @Test
    void Clock_이벤트_비트마스크는_선언_순서로_풀고_모르는_비트는_무시한다() {
        assertThat(ClocksEventReason.fromMask(0L)).isEmpty();
        assertThat(ClocksEventReason.fromMask(0x1FFL)).containsExactly(ClocksEventReason.values());
        assertThat(ClocksEventReason.fromMask(0x200L | 0x1L)).containsExactly(ClocksEventReason.GPU_IDLE);
    }

    @Test
    void 인덱스_비교는_숫자를_숫자순으로_앞에_두고_그_외는_문자열순_null은_마지막이다() {
        List<String> sorted = new ArrayList<>(Arrays.asList("10", null, "GPU-z", "2", "GPU-a", "0"));
        sorted.sort(HostGpuMetricsQueryService::compareIndex);
        assertThat(sorted).containsExactly("0", "2", "10", "GPU-a", "GPU-z", null);
    }

    // ── 대역 ────────────────────────────────────────────────────

    private void stubDevices() {
        when(client.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.equals(HostGpuMetricsQueryService.presenceQuery(SEL))) {
                return List.of(
                        row(Map.of("UUID", "GPU-c", "gpu", "10", "modelName", "NVIDIA A100-SXM4-80GB"), NOW - 5),
                        row(Map.of("UUID", "GPU-a", "gpu", "0", "modelName", "NVIDIA A100-SXM4-80GB",
                                "pci_bus_id", "00000000:17:00.0", "DCGM_FI_DRIVER_VERSION", "535.0"), NOW - 7200),
                        row(Map.of("UUID", "GPU-a", "gpu", "0", "modelName", "NVIDIA A100-SXM4-80GB",
                                "pci_bus_id", "00000000:17:00.0", "DCGM_FI_DRIVER_VERSION", "550.54.15"), NOW - 10),
                        row(Map.of("UUID", "GPU-b", "gpu", "2", "modelName", "NVIDIA A100-SXM4-80GB"), NOW - 1800));
            }
            if (q.equals(HostGpuMetricsQueryService.collectedQuery(SEL))) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (String name : HostGpuMetricsQueryService.EXPECTED_METRICS) {
                    rows.add(row(Map.of("UUID", "GPU-a", "__name__", name), 1));
                }
                rows.add(row(Map.of("UUID", "GPU-c", "__name__", "DCGM_FI_DEV_GPU_UTIL"), 1));
                rows.add(row(Map.of("UUID", "GPU-c", "__name__", "DCGM_FI_DEV_FB_USED"), 1));
                return rows;
            }
            if (q.equals(HostGpuMetricsQueryService.currentQuery("DCGM_FI_DEV_GPU_UTIL", SEL))) {
                return List.of(byUuid("GPU-a", "42"), byUuid("GPU-c", "3"));
            }
            if (q.equals(HostGpuMetricsQueryService.averageQuery("DCGM_FI_DEV_GPU_UTIL", SEL))) {
                return List.of(byUuid("GPU-a", "30"), byUuid("GPU-b", "5"));
            }
            if (q.equals(HostGpuMetricsQueryService.currentQuery("DCGM_FI_DEV_FB_USED", SEL))) {
                return List.of(byUuid("GPU-a", "20480"), byUuid("GPU-c", "1024"), byUuid("GPU-x", "1"));  // GPU-x 는 존재 판정 밖 → 무시
            }
            if (q.equals(HostGpuMetricsQueryService.currentQuery("DCGM_FI_DEV_FB_FREE", SEL))) {
                return List.of(byUuid("GPU-a", "61440"));
            }
            if (q.equals(HostGpuMetricsQueryService.vramPercentAverageQuery(SEL))) {
                return List.of(byUuid("GPU-a", "20"));
            }
            if (q.equals(HostGpuMetricsQueryService.currentQuery("DCGM_FI_DEV_GPU_TEMP", SEL))) {
                return List.of(byUuid("GPU-a", "55"));
            }
            if (q.equals(HostGpuMetricsQueryService.currentQuery("DCGM_FI_DEV_POWER_USAGE", SEL))) {
                return List.of(byUuid("GPU-a", "250.5"));
            }
            return List.of();
        });
    }

    static Map<String, Object> row(Map<String, String> labels, long value) {
        return Map.of("metric", new HashMap<>(labels), "value", List.of(NOW, String.valueOf(value)));
    }

    static Map<String, Object> byUuid(String uuid, String value) {
        return Map.of("metric", Map.of("UUID", uuid), "value", List.of(NOW, value));
    }

    static Map<String, Object> series(String uuid, String gpu, List<List<Object>> values) {
        return Map.of("metric", Map.of("UUID", uuid, "gpu", gpu), "values", values);
    }
}
