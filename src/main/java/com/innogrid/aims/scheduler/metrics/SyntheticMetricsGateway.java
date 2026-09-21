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
 *
 * <p><b>익스포터별 instance 형식을 실환경대로 낸다.</b> 노드 익스포터 시리즈는 {@code host:9100}, DCGM 은
 * {@code host:9400}, IPMI 는 {@code host:9290} 이다. 예전에는 모든 시리즈에 노드 id 하나를 붙여, 수집기가 노드
 * instance 로 GPU 개수·전력·온도를 찾는 결함이 시험에서 드러나지 않았다. 실장비에서는 익스포터마다 포트가 달라
 * 그 결합이 빈손이었다. 이제 IPMI·DCGM 메트릭을 노드 instance 로 물으면 <b>빈 결과</b>다 — 실환경과 같다.</p>
 *
 * <p><b>구간 집계 함수를 해석한다.</b> {@code avg_over_time(메트릭[24h])} 같은 질의는 감싼 함수를 무시하면 그 순간
 * 값을 돌려주게 되어, 시험은 초록불인데 값이 틀린다. 실환경 저장소는 창 안의 표본을 모아 답하므로 여기서도 창을
 * 걸어 표본을 모아 답한다. 표본 간격은 실환경 스크레이프 주기와 같은 {@link #SAMPLE_STEP_SECONDS} 로 둔다.</p>
 */
public final class SyntheticMetricsGateway implements SchedulerMetricsGateway {

    public static final String SOURCE = "synthetic";

    /** DCGM 익스포터 포트 — 배포 서버 실측 {@code mdc1-gpu-node1:9400}. */
    public static final int DCGM_PORT = 9400;
    /** IPMI 익스포터 포트 — 배포 서버 실측 {@code ipmi-exporter:9290}. 모의에서는 노드 호스트에 붙인다. */
    public static final int IPMI_PORT = 9290;

    private static final Pattern LABEL = Pattern.compile("(\\w+)\\s*(=~|!~|!=|=)\\s*\"([^\"]*)\"");
    private static final Pattern METRIC = Pattern.compile(
            "\\b(up|node_cpu_seconds_total|node_memory_MemTotal_bytes|node_memory_MemAvailable_bytes|node_filesystem_size_bytes|"
            + "node_filesystem_avail_bytes|ipmi_dcmi_power_consumption_watts|ipmi_temperature_celsius|"
            + "node_network_receive_bytes_total|node_network_transmit_bytes_total|"
            + "DCGM_FI_DEV_GPU_UTIL|DCGM_FI_DEV_GPU_TEMP|DCGM_FI_DEV_POWER_USAGE)\\b");
    /** 질의 맨 앞의 집계 함수 — {@code avg by(instance) (…)} 의 {@code avg}. {@code avg_over_time} 은 구간 함수라 여기 걸리지 않는다. */
    private static final Pattern LEADING_AGG = Pattern.compile("^\\s*(avg|max|min|sum|count)\\s*(by\\s*\\([^)]*\\))?\\s*\\(");
    private static final Pattern BY_INSTANCE = Pattern.compile("by\\s*\\(\\s*instance\\s*\\)");

    /** 구간 집계 함수와 창 — {@code avg_over_time(... [24h])} 의 함수 이름과 창 길이를 뽑는다. */
    private static final Pattern OVER_TIME = Pattern.compile(
            "\\b(avg|min|max|count|sum|last)_over_time\\s*\\(.*?\\[(\\d+)([smhdw])\\]", Pattern.DOTALL);

    /**
     * 구간 집계가 창 안에서 표본을 뜨는 간격(초).
     *
     * <p>실환경 스크레이프 주기와 같은 눈금이라야 모의 값이 실환경과 같은 성격을 갖는다. 이 값을 줄이면 표본이
     * 늘어 계산만 무거워지고, 늘리면 사인 곡선의 봉우리를 건너뛰어 평균이 흔들린다.</p>
     */
    static final long SAMPLE_STEP_SECONDS = 60;

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
        Window window = windowOf(promQL);
        for (SyntheticCluster.Node node : select(promQL)) {
            Double v = window == null ? value(promQL, node, now) : aggregate(promQL, node, now, window);
            if (v == null) continue;
            out.add(series(node, promQL, List.of(now, format(v)), null));
        }
        return out;
    }

    /** 질의가 건 구간 집계 — 없으면 null. */
    private static Window windowOf(String promQL) {
        Matcher m = OVER_TIME.matcher(promQL);
        if (!m.find()) return null;
        long seconds = Long.parseLong(m.group(2)) * switch (m.group(3)) {
            case "s" -> 1L; case "m" -> 60L; case "h" -> 3600L; case "d" -> 86_400L; default -> 604_800L;
        };
        return new Window(m.group(1), Math.max(SAMPLE_STEP_SECONDS, seconds));
    }

    /**
     * 창 {@code [now - seconds, now]} 안의 표본으로 집계한다.
     *
     * <p>메트릭이 없는 노드는 표본이 하나도 없어 {@code null} 이다 — 실환경에서 그 시리즈가 통째로 없는 것과 같다.
     * 0 으로 채우지 않는 것이 이 게이트웨이의 규칙이고, 집계에서도 같다.</p>
     */
    private Double aggregate(String promQL, SyntheticCluster.Node node, long now, Window window) {
        double sum = 0; double min = Double.MAX_VALUE; double max = -Double.MAX_VALUE;
        long count = 0; Double last = null;
        for (long t = now - window.seconds(); t <= now; t += SAMPLE_STEP_SECONDS) {
            Double v = value(promQL, node, t);
            if (v == null) continue;
            sum += v; count++; last = v;
            min = Math.min(min, v); max = Math.max(max, v);
        }
        if (count == 0) return null;
        return switch (window.function()) {
            case "avg" -> round(sum / count);
            case "min" -> min;
            case "max" -> max;
            case "sum" -> round(sum);
            case "count" -> (double) count;
            default -> last;
        };
    }

    /** 구간 집계 함수 이름과 창 길이(초). */
    private record Window(String function, long seconds) {}

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
            if (!values.isEmpty()) out.add(series(node, promQL, null, values));
        }
        return out;
    }

    // ── 해석 ──────────────────────────────────────────────────

    /**
     * 질의가 가리키는 노드들 — instance 라벨이 있으면 그 노드만, job 라벨이 다르면 없음.
     *
     * <p>instance 는 <b>그 메트릭의 익스포터 형식</b>이어야 한다. IPMI 메트릭을 {@code host:9100} 으로 물으면
     * 실환경에서 시리즈가 없듯 여기서도 없다. 그래서 수집기는 해석기를 거쳐 {@code host:9290} 으로 물어야 값을 얻는다.</p>
     */
    private List<SyntheticCluster.Node> select(String promQL) {
        Map<String, String> labels = labels(promQL);
        String job = labels.get("job");
        if (job != null && !job.equals(cluster.job())) return List.of();
        String instance = labels.get("instance");
        if (instance == null) return cluster.nodes();
        Integer port = exporterPort(promQL);
        if (port == null) return cluster.node(instance).map(List::of).orElse(List.of());
        // 익스포터 메트릭 — 라벨의 포트가 그 익스포터의 것이고 호스트가 노드와 같아야 한다.
        String expectedPort = ":" + port;
        if (!instance.endsWith(expectedPort)) return List.of();
        String host = instance.substring(0, instance.length() - expectedPort.length());
        return cluster.nodes().stream().filter(n -> hostOf(n.nodeId()).equals(host)).findFirst()
                .map(List::of).orElse(List.of());
    }

    /** 질의 메트릭이 어느 익스포터의 것인가 — DCGM 9400, IPMI 9290, 노드 익스포터는 null. */
    private static Integer exporterPort(String promQL) {
        Matcher m = METRIC.matcher(promQL);
        if (!m.find()) return null;
        String metric = m.group(1);
        if (metric.startsWith("DCGM_")) return DCGM_PORT;
        if (metric.startsWith("ipmi_")) return IPMI_PORT;
        return null;
    }

    /** {@code host:port} 의 호스트. */
    private static String hostOf(String instance) {
        int colon = instance.lastIndexOf(':');
        return colon < 0 ? instance : instance.substring(0, colon);
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
            case "DCGM_FI_DEV_GPU_UTIL":
            case "DCGM_FI_DEV_GPU_TEMP":
            case "DCGM_FI_DEV_POWER_USAGE":
                return dcgm(metric, promQL, node, t);
            default: return null;
        }
    }

    /**
     * DCGM 은 <b>카드마다 시리즈</b>다. 실환경에서 {@code by(instance)} 집계는 카드 값들을 노드 하나로 접는데, 모의는 카드별
     * 시리즈를 내보내지 않고 <b>감싼 함수를 읽어 그 자리에서 접는다</b> — 그래서 {@code avg}·{@code max}·{@code sum} 이 서로
     * 다른 값이고, 온도에 {@code sum} 을 쓰는 실수는 모의에서도 다른 값으로 드러난다. 감싼 함수가 없으면 첫 카드 값이다
     * (스칼라 질의가 첫 시리즈만 읽는 것과 같다). GPU 없는 노드는 시리즈가 없다.
     */
    private Double dcgm(String metric, String promQL, SyntheticCluster.Node node, long t) {
        if (node.gpus() <= 0) return null;
        Matcher agg = LEADING_AGG.matcher(promQL);
        String fn = agg.find() ? agg.group(1) : null;
        if ("count".equals(fn)) return (double) node.gpus();
        double sum = 0, max = -Double.MAX_VALUE, min = Double.MAX_VALUE;
        for (int card = 0; card < node.gpus(); card++) {
            double v = switch (metric) {
                case "DCGM_FI_DEV_GPU_UTIL" -> cluster.gpuUsage(node, t, card);
                case "DCGM_FI_DEV_GPU_TEMP" -> cluster.gpuTemperature(node, t, card);
                default -> cluster.gpuPowerWatts(node, t, card);
            };
            if (fn == null) return round(v);
            sum += v; max = Math.max(max, v); min = Math.min(min, v);
        }
        return round(switch (fn) {
            case "avg" -> sum / node.gpus();
            case "max" -> max;
            case "min" -> min;
            default -> sum;
        });
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

    private Map<String, Object> series(SyntheticCluster.Node node, String promQL, List<Object> value, List<List<Object>> values) {
        Map<String, String> labels = new LinkedHashMap<>();
        // 시리즈의 instance 는 그 메트릭을 낸 익스포터의 주소다 — 노드 익스포터와 다르다.
        Integer port = exporterPort(promQL);
        labels.put("instance", port == null ? node.nodeId() : hostOf(node.nodeId()) + ":" + port);
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
