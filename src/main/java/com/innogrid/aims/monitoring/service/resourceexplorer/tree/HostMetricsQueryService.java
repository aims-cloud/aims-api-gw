package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 물리 호스트(node_exporter) 사용률을 Thanos 에서 기간 평균으로 읽어 instance 별로 돌려준다.
 *
 * 표준 PromQL(MDC 수집 SW 인터페이스 분석, 김승한 2026-08-10)을 기간 평균 형태로 쓴다.
 * <ul>
 *   <li>CPU%  = 100 - avg by(instance)(rate(node_cpu_seconds_total{mode="idle"}[w])) * 100</li>
 *   <li>MEM%  = (1 - avg_over_time(MemAvailable[w]) / avg_over_time(MemTotal[w])) * 100</li>
 *   <li>FS    = sum by(instance) avg_over_time(node_filesystem_{avail,size}_bytes{fstype!~"tmpfs|overlay"}[w]) → 사용률·Free·Total</li>
 * </ul>
 * 호스트마다 질의하지 않고 4개 쿼리로 전체 instance 를 받아 조인한다. Thanos 장애 시 빈 결과(전부 미수집)를 돌려준다.
 */
// TODO:: LAST_30D/LAST_90D 는 avg_over_time(...[30d|90d]) 을 전체 instance 에 대해 실행해 호스트 수·보존 기간에 따라 느려질 수 있음
//        — Thanos 다운샘플링·recording rule 적용 여부가 확정되면 장기 구간은 recording rule 또는 range 평균으로 교체
@Component
public class HostMetricsQueryService {

    private static final Logger log = LoggerFactory.getLogger(HostMetricsQueryService.class);
    private static final double GB = 1024.0 * 1024 * 1024;
    private static final String FS_SELECTOR = "{fstype!~\"tmpfs|overlay\"}";

    /** 호스트 1대의 기간 평균 사용률. null 은 미수집("-") */
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

    static String cpuQuery(String window) {
        return "100 - (avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[" + window + "])) * 100)";
    }

    static String memoryQuery(String window) {
        return "(1 - avg_over_time(node_memory_MemAvailable_bytes[" + window + "])"
                + " / avg_over_time(node_memory_MemTotal_bytes[" + window + "])) * 100";
    }

    static String fileSystemAvailQuery(String window) {
        return "sum by (instance) (avg_over_time(node_filesystem_avail_bytes" + FS_SELECTOR + "[" + window + "]))";
    }

    static String fileSystemSizeQuery(String window) {
        return "sum by (instance) (avg_over_time(node_filesystem_size_bytes" + FS_SELECTOR + "[" + window + "]))";
    }

    /** interval 기간 평균 사용률을 instance(address:port) 별로 조회한다. 조회 실패 시 빈 맵. */
    public Map<String, HostUtilization> utilizationByInstance(NodeMetricInterval interval) {
        String window = interval.promWindow();
        try {
            Map<String, Double> cpu = byInstance(client.queryResults(cpuQuery(window)));
            Map<String, Double> memory = byInstance(client.queryResults(memoryQuery(window)));
            Map<String, Double> fsAvail = byInstance(client.queryResults(fileSystemAvailQuery(window)));
            Map<String, Double> fsSize = byInstance(client.queryResults(fileSystemSizeQuery(window)));

            Map<String, HostUtilization> result = new HashMap<>();
            for (String instance : union(cpu, memory, fsAvail, fsSize)) {
                Double avail = fsAvail.get(instance);
                Double size = fsSize.get(instance);
                Double fsUtil = avail == null || size == null ? null
                        : size <= 0 ? 0.0 : round1((1 - avail / size) * 100);
                result.put(instance, new HostUtilization(
                        round1(cpu.get(instance)),
                        round1(memory.get(instance)),
                        fsUtil,
                        avail == null ? null : round1(avail / GB),
                        size == null ? null : round1(size / GB)));
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("Thanos 호스트 사용률 조회 실패 — 사용률을 미수집으로 표시합니다: {}", e.getMessage());
            return Map.of();
        }
    }

    @SafeVarargs
    private static java.util.Set<String> union(Map<String, Double>... maps) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Map<String, Double> m : maps) {
            keys.addAll(m.keySet());
        }
        return keys;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Double> byInstance(List<Map<String, Object>> rows) {
        Map<String, Double> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object metric = row.get("metric");
            Object value = row.get("value");
            if (!(metric instanceof Map<?, ?> labels) || !(value instanceof List<?> pair) || pair.size() < 2) {
                continue;
            }
            Object instance = ((Map<String, Object>) labels).get("instance");
            if (instance == null || pair.get(1) == null) {
                continue;
            }
            try {
                double v = Double.parseDouble(pair.get(1).toString());
                if (!Double.isNaN(v)) {
                    result.put(instance.toString(), v);
                }
            } catch (NumberFormatException ignored) {
                // "NaN"/"+Inf" 등 수치가 아닌 샘플은 미수집으로 취급
            }
        }
        return result;
    }

    private static Double round1(Double value) {
        return value == null ? null : Math.round(value * 10) / 10.0;
    }
}
