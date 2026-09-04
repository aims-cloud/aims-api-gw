package com.innogrid.aims.scheduler.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 가상 클러스터로 PromQL 에 답하는 구현 — 스케줄러가 실제로 보내는 질의 형태만 해석한다.
 *
 * <p>해석 규칙: 질의문에서 <b>메트릭 이름</b>과 <b>라벨 선택자</b>({@code instance="…"}, {@code job="…"})를 뽑고,
 * {@code by (instance)} 가 있으면 노드별 시리즈로 나눈다. 라벨의 instance 가 클러스터에 없으면 <b>빈 결과</b>다 —
 * 실환경에서 "메트릭 없음"이 어떻게 처리되는지(핵심은 제외, 부가는 0)를 그대로 재현하기 위해서다.
 * 해석할 수 없는 질의도 빈 결과다.</p>
 *
 * <p>스케줄러가 쓰는 질의(2026-09-03 기준 13종): {@code up}, CPU 사용률(idle 역산), 코어 수, MemTotal/MemAvailable,
 * filesystem size/avail, IPMI 전력, IPMI 온도(avg), 네트워크 rx/tx(sum irate), DCGM GPU 수(count by instance),
 * 그리고 CPU·전력의 구간 질의.</p>
 */
public final class SyntheticMetricsGateway implements SchedulerMetricsGateway {

    public static final String SOURCE = "synthetic";

    private static final Pattern LABEL = Pattern.compile("(\\w+)\\s*(=~|!~|!=|=)\\s*\"([^\"]*)\"");
    private static final Pattern METRIC = Pattern.compile(
            "\\b(up|node_cpu_seconds_total|node_memory_MemTotal_bytes|node_memory_MemAvailable_bytes|node_filesystem_size_bytes|"
            + "node_filesystem_avail_bytes|ipmi_dcmi_power_consumption_watts|ipmi_temperature_celsius|"
            + "node_network_receive_bytes_total|node_network_transmit_bytes_total|DCGM_FI_DEV_GPU_UTIL)\\b");
    private static final Pattern BY_INSTANCE = Pattern.compile("by\\s*\\(\\s*instance\\s*\\)");

    private final SyntheticCluster cluster;
    private final Supplier<Instant> clock;

    public SyntheticMetricsGateway(SyntheticCluster cluster) {
        this(cluster, Instant::now);
    }

    /** 시각을 주입할 수 있다(결정성 테스트). */
    public SyntheticMetricsGateway(SyntheticCluster cluster, Supplier<Instant> clock) {
        this.cluster = cluster;
        this.clock = clock;
    }

    public SyntheticCluster cluster() { return cluster; }

    @Override public String source() { return SOURCE; }

    @Override
    public Map<String, Object> query(String promQL) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("resultType", "vector");
        data.put("result", queryResults(promQL));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("status", "success");
        envelope.put("data", data);
        return envelope;
    }

    @Override
    public List<Map<String, Object>> queryResults(String promQL) {
        long now = clock.get().getEpochSecond();
        List<Map<String, Object>> out = new ArrayList<>();
        for (SyntheticCluster.Node node : select(promQL)) {
            Double v = value(promQL, node, now);
            if (v == null) continue;
            out.add(series(node, List.of(now, format(v)), null));
        }
        return out;
    }

    @Override
    public double queryScalar(String promQL) {
        List<Map<String, Object>> results = queryResults(promQL);
        if (results.isEmpty()) return 0;
        Object value = results.get(0).get("value");
        if (!(value instanceof List<?> pair) || pair.size() < 2) return 0;
        try { return Double.parseDouble(String.valueOf(pair.get(1))); } catch (NumberFormatException e) { return 0; }
    }

    @Override
    public List<Map<String, Object>> queryRangeResults(String promQL, Instant start, Instant end, String step) {
        long stepSeconds = Math.max(1, parseStep(step));
        List<Map<String, Object>> out = new ArrayList<>();
        for (SyntheticCluster.Node node : select(promQL)) {
            List<List<Object>> values = new ArrayList<>();
            for (long t = start.getEpochSecond(); t <= end.getEpochSecond(); t += stepSeconds) {
                Double v = value(promQL, node, t);
                if (v == null) break;
                values.add(List.of(t, format(v)));
            }
            if (!values.isEmpty()) out.add(series(node, null, values));
        }
        return out;
    }

    // ── 해석 ──────────────────────────────────────────────────

    /** 질의가 가리키는 노드들 — instance 라벨이 있으면 그 노드만, job 라벨이 다르면 없음. */
    private List<SyntheticCluster.Node> select(String promQL) {
        Map<String, String> labels = labels(promQL);
        String job = labels.get("job");
        if (job != null && !job.equals(cluster.job())) return List.of();
        String instance = labels.get("instance");
        if (instance != null) return cluster.node(instance).map(List::of).orElse(List.of());
        return cluster.nodes();
    }

    /** 노드 한 대에 대한 질의 값. 그 노드에 없는 메트릭(IPMI 없는 노드의 전력·온도, GPU 없는 노드의 DCGM)이면 null. */
    private Double value(String promQL, SyntheticCluster.Node node, long t) {
        Matcher m = METRIC.matcher(promQL);
        if (!m.find()) return null;
        String metric = m.group(1);
        boolean idle = promQL.contains("mode=\"idle\"");
        switch (metric) {
            case "up": return 1.0;
            case "node_cpu_seconds_total":
                if (promQL.startsWith("count")) return (double) node.cores();
                if (idle) return round(cluster.cpuUsage(node, t));   // 100 - idle*100 형태를 사용률 그 자체로 답한다
                return null;
            case "node_memory_MemTotal_bytes": return node.memoryTotalBytes();
            case "node_memory_MemAvailable_bytes": return node.memoryTotalBytes() * (1 - cluster.memoryUsage(node, t) / 100.0);
            case "node_filesystem_size_bytes": return node.diskTotalBytes();
            case "node_filesystem_avail_bytes": return node.diskTotalBytes() * (1 - cluster.diskUsage(node, t) / 100.0);
            case "ipmi_dcmi_power_consumption_watts": return node.ipmi() ? round(cluster.powerWatts(node, t)) : null;
            case "ipmi_temperature_celsius": return node.ipmi() ? round(cluster.temperature(node, t)) : null;
            case "node_network_receive_bytes_total": return round(cluster.networkRx(node, t));
            case "node_network_transmit_bytes_total": return round(cluster.networkTx(node, t));
            case "DCGM_FI_DEV_GPU_UTIL": return node.gpus() > 0 ? (double) node.gpus() : null;   // count by(instance) → GPU 수
            default: return null;
        }
    }

    static Map<String, String> labels(String promQL) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = LABEL.matcher(promQL);
        while (m.find()) {
            if ("=".equals(m.group(2)) && !out.containsKey(m.group(1))) out.put(m.group(1), m.group(3));
        }
        return out;
    }

    static boolean byInstance(String promQL) { return BY_INSTANCE.matcher(promQL).find(); }

    /** {@code 5m}, {@code 30s}, {@code 1h}, 숫자(초). 못 읽으면 300. */
    static long parseStep(String step) {
        if (step == null || step.isBlank()) return 300;
        String s = step.trim().toLowerCase(Locale.ROOT);
        try {
            if (s.endsWith("ms")) return Math.max(1, Long.parseLong(s.substring(0, s.length() - 2)) / 1000);
            char unit = s.charAt(s.length() - 1);
            if (Character.isDigit(unit)) return Long.parseLong(s);
            long n = Long.parseLong(s.substring(0, s.length() - 1));
            return switch (unit) { case 's' -> n; case 'm' -> n * 60; case 'h' -> n * 3600; case 'd' -> n * 86400; default -> 300; };
        } catch (NumberFormatException e) {
            return Duration.ofMinutes(5).toSeconds();
        }
    }

    private Map<String, Object> series(SyntheticCluster.Node node, List<Object> value, List<List<Object>> values) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("instance", node.nodeId());
        labels.put("mdc_id", node.mdcId());
        labels.put("job", cluster.job());
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("metric", labels);
        if (value != null) s.put("value", value);
        if (values != null) s.put("values", values);
        return s;
    }

    private static String format(double v) { return String.valueOf(v); }
    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
