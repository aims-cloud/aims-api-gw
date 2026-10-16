package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.monitoring.ThanosResults;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.swap.SwapMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.swap.SwapSummaryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 자원 1개의 Swap 카테고리 패널 지표를 Thanos 에서 읽는다 — KPI 현재값과 시계열 전용.
 *
 * node_exporter meminfo({@code node_memory_SwapTotal_bytes, SwapFree_bytes})와 vmstat({@code node_vmstat_pswpin, pswpout}) 지표.
 * Used = Total - Free, 사용률 = (1 - Free/Total) × 100 — 스왑이 없는 호스트(Total 0)는 사용률이 NaN 이라 null/빈 시리즈가 된다.
 * 자원은 {@link MetricSelector} 로 가리키고, Thanos 장애나 시계열 부재는 null/빈 목록(미수집)으로 돌려준다.
 */
@Component
public class HostSwapMetricsQueryService {

    private static final Logger log = LoggerFactory.getLogger(HostSwapMetricsQueryService.class);
    private static final double GB = 1024.0 * 1024 * 1024;
    private static final String RATE_WINDOW = "5m";

    private final VictoriaMetricsClient client;

    public HostSwapMetricsQueryService(VictoriaMetricsClient client) {
        this.client = client;
    }

    // ── PromQL ──────────────────────────────────────────────────

    static String totalBytes(MetricSelector s) {
        return "node_memory_SwapTotal_bytes" + s.braces();
    }

    static String freeBytes(MetricSelector s) {
        return "node_memory_SwapFree_bytes" + s.braces();
    }

    static String swapInPerSec(MetricSelector s, String window) {
        return "rate(node_vmstat_pswpin" + s.braces() + "[" + window + "])";
    }

    static String swapOutPerSec(MetricSelector s, String window) {
        return "rate(node_vmstat_pswpout" + s.braces() + "[" + window + "])";
    }

    static String trendQuery(SwapMetric metric, MetricSelector s, String window) {
        return switch (metric) {
            case SWAP_USED -> "(" + totalBytes(s) + " - " + freeBytes(s) + ") / " + (long) GB;
            case SWAP_USED_PERCENT -> "(1 - " + freeBytes(s) + " / " + totalBytes(s) + ") * 100";
            case SWAP_FREE -> freeBytes(s) + " / " + (long) GB;
            case SWAP_TOTAL -> totalBytes(s) + " / " + (long) GB;
            case SWAP_IN -> swapInPerSec(s, window);
            case SWAP_OUT -> swapOutPerSec(s, window);
        };
    }

    // ── 조회 ────────────────────────────────────────────────────

    /** KPI 카드 현재값 — 용량 GB(소수 1자리), 스왑 인·아웃 pages/s(소수 2자리). Total 0 이면 사용률 null. 장애·부재는 전부 null */
    public SwapSummaryResponse summary(MetricSelector s) {
        try {
            Double total = scalar(totalBytes(s));
            Double free = scalar(freeBytes(s));
            Double in = scalar(swapInPerSec(s, RATE_WINDOW));
            Double out = scalar(swapOutPerSec(s, RATE_WINDOW));

            Double used = total == null || free == null ? null : Math.max(0.0, total - free);
            Double usedPercent = total == null || free == null || total <= 0 ? null : (1 - free / total) * 100;
            return new SwapSummaryResponse(gb(total), gb(used), gb(free),
                    HostDetailMetricsQueryService.round1(usedPercent), round2(in), round2(out));
        } catch (RuntimeException e) {
            log.warn("Thanos Swap KPI 조회 실패 — 카드를 미수집으로 표시합니다: {}", e.getMessage());
            return new SwapSummaryResponse(null, null, null, null, null, null);
        }
    }

    /** 지표별 시계열 — 미수집이면 빈 목록. 용량·비율은 소수 1자리, 인·아웃은 소수 2자리 */
    public List<DataPoint> trend(SwapMetric metric, MetricSelector s, Instant from, Instant to, String step) {
        String query = trendQuery(metric, s, HostDetailMetricsQueryService.rateWindow(step));
        boolean fine = metric == SwapMetric.SWAP_IN || metric == SwapMetric.SWAP_OUT;
        try {
            List<Map<String, Object>> results = client.queryRangeResults(query, from, to, step);
            if (results.isEmpty()) {
                return List.of();
            }
            List<DataPoint> points = new ArrayList<>();
            for (ThanosResults.Sample sample : ThanosResults.values(results.get(0))) {
                Double value = sample.value();
                points.add(new DataPoint(sample.epochSecond(),
                        fine ? round2(value) : HostDetailMetricsQueryService.round1(value)));
            }
            return points;
        } catch (RuntimeException e) {
            log.warn("Thanos Swap 차트 조회 실패 — 빈 시계열을 돌려줍니다: {}", e.getMessage());
            return List.of();
        }
    }

    // ── 내부 ────────────────────────────────────────────────────

    private Double scalar(String query) {
        return ThanosResults.firstValue(client.queryResults(query));
    }

    private static Double gb(Double bytes) {
        return bytes == null ? null : HostDetailMetricsQueryService.round1(bytes / GB);
    }

    private static Double round2(Double value) {
        return value == null ? null : Math.round(value * 100) / 100.0;
    }
}
