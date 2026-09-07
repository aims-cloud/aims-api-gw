package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * node_exporter 사용률을 Thanos 에서 기간 평균으로 읽어 자원별로 돌려준다 (목록·Top-N 용).
 *
 * 표준 PromQL(MDC 수집 SW 인터페이스 분석, 김승한 2026-08-10)을 기간 평균 형태로 쓴다.
 * <ul>
 *   <li>CPU%  = 100 - avg by(그룹)(rate(node_cpu_seconds_total{mode="idle"}[w])) * 100</li>
 *   <li>MEM%  = (1 - avg_over_time(MemAvailable[w]) / avg_over_time(MemTotal[w])) * 100</li>
 *   <li>FS    = sum by(그룹) avg_over_time(node_filesystem_{avail,size}_bytes{fstype!~"tmpfs|overlay"}[w]) → 사용률·Free·Total</li>
 * </ul>
 * 자원마다 질의하지 않고 4개 쿼리로 전체를 받아 라벨로 조인한다. 조인 키는 두 종류다.
 * <ul>
 *   <li>물리 호스트: {@code instance}(address:port) — {@link #utilizationByInstance}</li>
 *   <li>Tumblebug VM: {@code tb_ns/tb_infra/tb_node} → {@code ns/infra/node} — {@link #utilizationByTumblebugNode}</li>
 * </ul>
 * Thanos 장애 시 빈 결과(전부 미수집)를 돌려준다.
 */
// TODO:: LAST_30D/LAST_90D 는 avg_over_time(...[30d|90d]) 을 전체 instance 에 대해 실행해 호스트 수·보존 기간에 따라 느려질 수 있음
//        — Thanos 다운샘플링·recording rule 적용 여부가 확정되면 장기 구간은 recording rule 또는 range 평균으로 교체
@Component
public class HostMetricsQueryService {

    private static final Logger log = LoggerFactory.getLogger(HostMetricsQueryService.class);
    private static final double GB = 1024.0 * 1024 * 1024;
    private static final String FS_FILTER = "fstype!~\"tmpfs|overlay\"";

    /** 물리 호스트 그룹 — instance 라벨, 필터 없음 */
    static final Grouping BY_INSTANCE = new Grouping("instance", "", row -> label(row, "instance"));
    /** Tumblebug VM 그룹 — tb_* 라벨이 붙은 시계열만, 키는 ns/infra/node */
    static final Grouping BY_TB_NODE = new Grouping("tb_ns, tb_infra, tb_node", "tb_node!=\"\"", row -> {
        String ns = label(row, "tb_ns");
        String infra = label(row, "tb_infra");
        String node = label(row, "tb_node");
        return ns == null || infra == null || node == null ? null : tumblebugKey(ns, infra, node);
    });

    /** 조인 방식 — by 절 라벨, 시계열 필터, 결과 행 → 키 */
    record Grouping(String byLabels, String filter, Function<Map<String, Object>, String> key) {
        String selector(String extra) {
            String all = filter.isBlank() ? extra : extra.isBlank() ? filter : extra + "," + filter;
            return all.isBlank() ? "" : "{" + all + "}";
        }
    }

    /** 자원 1개의 기간 평균 사용률. null 은 미수집("-") */
    public record HostUtilization(Double cpuUtilization,
                                  Double memoryUtilization,
                                  Double fileSystemUtilization,
                                  Double fileSystemFreeGb,
                                  Double fileSystemTotalGb) {
        public static final HostUtilization EMPTY = new HostUtilization(null, null, null, null, null);
    }

    private final VictoriaMetricsClient client;

    public HostMetricsQueryService(VictoriaMetricsClient client) {
        this.client = client;
    }

    /** Tumblebug VM 조인 키 — {@link #utilizationByTumblebugNode} 결과의 키 형식 */
    public static String tumblebugKey(String nsId, String infraId, String nodeId) {
        return nsId + "/" + infraId + "/" + nodeId;
    }

    // ── PromQL ──────────────────────────────────────────────────

    static String cpuQuery(Grouping g, String window) {
        return "100 - (avg by (" + g.byLabels() + ") (rate(node_cpu_seconds_total" + g.selector("mode=\"idle\"")
                + "[" + window + "])) * 100)";
    }

    static String memoryQuery(Grouping g, String window) {
        return "(1 - avg_over_time(node_memory_MemAvailable_bytes" + g.selector("") + "[" + window + "])"
                + " / avg_over_time(node_memory_MemTotal_bytes" + g.selector("") + "[" + window + "])) * 100";
    }

    static String fileSystemAvailQuery(Grouping g, String window) {
        return "sum by (" + g.byLabels() + ") (avg_over_time(node_filesystem_avail_bytes" + g.selector(FS_FILTER)
                + "[" + window + "]))";
    }

    static String fileSystemSizeQuery(Grouping g, String window) {
        return "sum by (" + g.byLabels() + ") (avg_over_time(node_filesystem_size_bytes" + g.selector(FS_FILTER)
                + "[" + window + "]))";
    }

    /** 물리 호스트용 호환 빌더 */
    static String cpuQuery(String window) { return cpuQuery(BY_INSTANCE, window); }
    static String memoryQuery(String window) { return memoryQuery(BY_INSTANCE, window); }
    static String fileSystemAvailQuery(String window) { return fileSystemAvailQuery(BY_INSTANCE, window); }
    static String fileSystemSizeQuery(String window) { return fileSystemSizeQuery(BY_INSTANCE, window); }

    // ── 조회 ────────────────────────────────────────────────────

    /** interval 기간 평균 사용률을 물리 호스트 instance(address:port) 별로 조회한다. 조회 실패 시 빈 맵. */
    public Map<String, HostUtilization> utilizationByInstance(NodeMetricInterval interval) {
        return utilization(BY_INSTANCE, interval, "호스트");
    }

    /** interval 기간 평균 사용률을 Tumblebug VM({@code ns/infra/node}) 별로 조회한다. 조회 실패 시 빈 맵. */
    public Map<String, HostUtilization> utilizationByTumblebugNode(NodeMetricInterval interval) {
        return utilization(BY_TB_NODE, interval, "Tumblebug VM");
    }

    private Map<String, HostUtilization> utilization(Grouping g, NodeMetricInterval interval, String what) {
        String window = interval.promWindow();
        try {
            Map<String, Double> cpu = byKey(client.queryResults(cpuQuery(g, window)), g);
            Map<String, Double> memory = byKey(client.queryResults(memoryQuery(g, window)), g);
            Map<String, Double> fsAvail = byKey(client.queryResults(fileSystemAvailQuery(g, window)), g);
            Map<String, Double> fsSize = byKey(client.queryResults(fileSystemSizeQuery(g, window)), g);

            Map<String, HostUtilization> result = new HashMap<>();
            for (String key : union(cpu, memory, fsAvail, fsSize)) {
                Double avail = fsAvail.get(key);
                Double size = fsSize.get(key);
                Double fsUtil = avail == null || size == null ? null
                        : size <= 0 ? 0.0 : round1((1 - avail / size) * 100);
                result.put(key, new HostUtilization(
                        round1(cpu.get(key)),
                        round1(memory.get(key)),
                        fsUtil,
                        avail == null ? null : round1(avail / GB),
                        size == null ? null : round1(size / GB)));
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("Thanos {} 사용률 조회 실패 — 사용률을 미수집으로 표시합니다: {}", what, e.getMessage());
            return Map.of();
        }
    }

    // ── 내부 ────────────────────────────────────────────────────

    @SafeVarargs
    private static Set<String> union(Map<String, Double>... maps) {
        Set<String> keys = new HashSet<>();
        for (Map<String, Double> m : maps) {
            keys.addAll(m.keySet());
        }
        return keys;
    }

    private static Map<String, Double> byKey(List<Map<String, Object>> rows, Grouping g) {
        Map<String, Double> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String key = g.key().apply(row);
            Object value = row.get("value");
            if (key == null || !(value instanceof List<?> pair) || pair.size() < 2 || pair.get(1) == null) {
                continue;
            }
            try {
                double v = Double.parseDouble(pair.get(1).toString());
                if (!Double.isNaN(v) && !Double.isInfinite(v)) {
                    result.put(key, v);
                }
            } catch (NumberFormatException ignored) {
                // "NaN"/"+Inf" 등 수치가 아닌 샘플은 미수집으로 취급
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static String label(Map<String, Object> row, String name) {
        Object metric = row.get("metric");
        if (!(metric instanceof Map<?, ?> labels)) {
            return null;
        }
        Object v = ((Map<String, Object>) labels).get(name);
        return v == null ? null : v.toString();
    }

    private static Double round1(Double value) {
        return value == null ? null : Math.round(value * 10) / 10.0;
    }
}
