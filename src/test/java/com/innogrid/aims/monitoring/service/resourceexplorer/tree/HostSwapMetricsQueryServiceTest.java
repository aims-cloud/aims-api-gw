package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.swap.SwapMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.swap.SwapSummaryResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Swap 패널 Thanos 조회 — PromQL, KPI 계산(스왑 없음·미수집), 시계열 반올림·rate 창, 장애 처리. */
class HostSwapMetricsQueryServiceTest {

    private static final MetricSelector SEL = MetricSelector.instance("10.0.0.1:9100");
    private static final String INSTANCE = "{instance=\"10.0.0.1:9100\"}";
    private static final double GB = 1024.0 * 1024 * 1024;

    VictoriaMetricsClient client = mock(VictoriaMetricsClient.class);
    HostSwapMetricsQueryService service = new HostSwapMetricsQueryService(client);

    @Test
    void PromQL은_meminfo_용량과_vmstat_rate를_쓰고_Used와_Free가_Total을_이룬다() {
        assertThat(HostSwapMetricsQueryService.trendQuery(SwapMetric.SWAP_USED, SEL, "5m"))
                .isEqualTo("(node_memory_SwapTotal_bytes" + INSTANCE + " - node_memory_SwapFree_bytes" + INSTANCE + ") / 1073741824");
        assertThat(HostSwapMetricsQueryService.trendQuery(SwapMetric.SWAP_USED_PERCENT, SEL, "5m"))
                .isEqualTo("(1 - node_memory_SwapFree_bytes" + INSTANCE + " / node_memory_SwapTotal_bytes" + INSTANCE + ") * 100");
        assertThat(HostSwapMetricsQueryService.trendQuery(SwapMetric.SWAP_TOTAL, SEL, "5m"))
                .isEqualTo("node_memory_SwapTotal_bytes" + INSTANCE + " / 1073741824");
        assertThat(HostSwapMetricsQueryService.trendQuery(SwapMetric.SWAP_IN, SEL, "1h"))
                .isEqualTo("rate(node_vmstat_pswpin" + INSTANCE + "[1h])");
        assertThat(HostSwapMetricsQueryService.trendQuery(SwapMetric.SWAP_OUT, SEL, "5m"))
                .isEqualTo("rate(node_vmstat_pswpout" + INSTANCE + "[5m])");
    }

    @Test
    void KPI는_용량을_GB로_사용률은_Free_Total로_계산하고_인아웃은_소수2자리다() {
        when(client.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.startsWith("node_memory_SwapTotal_bytes")) return vector(String.valueOf(8 * GB));
            if (q.startsWith("node_memory_SwapFree_bytes")) return vector(String.valueOf(6 * GB));
            if (q.startsWith("rate(node_vmstat_pswpin")) return vector("12.3456");
            if (q.startsWith("rate(node_vmstat_pswpout")) return vector("0");
            return List.of();
        });

        SwapSummaryResponse summary = service.summary(SEL);

        assertThat(summary.totalGb()).isEqualTo(8.0);
        assertThat(summary.usedGb()).isEqualTo(2.0);
        assertThat(summary.freeGb()).isEqualTo(6.0);
        assertThat(summary.usedPercent()).isEqualTo(25.0);
        assertThat(summary.swapInPerSec()).isEqualTo(12.35);
        assertThat(summary.swapOutPerSec()).isEqualTo(0.0);
    }

    @Test
    void 스왑이_없는_호스트는_용량_0에_사용률_null이고_지표_부재는_null이다() {
        when(client.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.startsWith("node_memory_Swap")) return vector("0");
            return List.of();                                       // pswpin/pswpout 부재
        });

        SwapSummaryResponse summary = service.summary(SEL);

        assertThat(summary.totalGb()).isEqualTo(0.0);
        assertThat(summary.usedGb()).isEqualTo(0.0);
        assertThat(summary.freeGb()).isEqualTo(0.0);
        assertThat(summary.usedPercent()).isNull();
        assertThat(summary.swapInPerSec()).isNull();
    }

    @Test
    void 시계열은_rate_창을_step에_맞추고_용량은_소수1자리_인아웃은_소수2자리로_반올림한다() {
        Instant from = Instant.ofEpochSecond(1_700_000_000L);
        Instant to = Instant.ofEpochSecond(1_700_003_600L);
        when(client.queryRangeResults(anyString(), any(), any(), anyString()))
                .thenReturn(List.of(range(List.of(List.of(1_700_000_000L, "1.2345"), List.of(1_700_000_030L, "NaN"),
                        List.of(1_700_000_060L, "2.5")))));

        List<DataPoint> in = service.trend(SwapMetric.SWAP_IN, SEL, from, to, "30s");
        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        verify(client).queryRangeResults(q.capture(), eq(from), eq(to), eq("30s"));
        assertThat(q.getValue()).isEqualTo("rate(node_vmstat_pswpin" + INSTANCE + "[5m])");     // 30s 는 rate 창 하한 5m
        assertThat(in).containsExactly(new DataPoint(1_700_000_000L, 1.23), new DataPoint(1_700_000_060L, 2.5));

        List<DataPoint> used = service.trend(SwapMetric.SWAP_USED, SEL, from, to, "5m");
        assertThat(used).containsExactly(new DataPoint(1_700_000_000L, 1.2), new DataPoint(1_700_000_060L, 2.5));
    }

    @Test
    void Thanos_장애는_KPI_전부_null_시계열_빈_목록이다() {
        when(client.queryResults(anyString())).thenThrow(new RuntimeException("connection refused"));
        when(client.queryRangeResults(anyString(), any(), any(), anyString())).thenThrow(new RuntimeException("timeout"));

        assertThat(service.summary(SEL)).isEqualTo(new SwapSummaryResponse(null, null, null, null, null, null));
        assertThat(service.trend(SwapMetric.SWAP_TOTAL, SEL, Instant.EPOCH, Instant.EPOCH, "5m")).isEmpty();
    }

    // ── 대역 ────────────────────────────────────────────────────

    static List<Map<String, Object>> vector(String value) {
        return List.of(Map.of("metric", Map.of(), "value", List.of(1_700_000_000L, value)));
    }

    static Map<String, Object> range(List<List<Object>> values) {
        return Map.of("metric", Map.of(), "values", values);
    }
}
