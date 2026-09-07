package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeDetailMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeMetricTrendResponse.Series;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 자원 1개(물리 호스트 또는 Tumblebug VM)의 상세 지표를 Thanos 에서 읽는다 — 상세 패널(주요 지표 카드·요약 차트·일간 최대치) 전용.
 *
 * 자원은 {@link MetricSelector} 로 가리킨다. 물리 호스트는 node_exporter·dcgm_exporter 의 {@code instance} 라벨로,
 * Tumblebug VM 은 {@code tb_ns/tb_infra/tb_node} 라벨로 좁힌다(두 exporter 에 같은 라벨이 붙으므로 선택자 하나로 충분).
 * 표준 PromQL(MDC 수집 SW 인터페이스 분석)을 그대로 쓰고, Thanos 장애나 시계열 부재는 null(미수집)로 돌려준다.
 */
@Component
public class HostDetailMetricsQueryService {

    private static final Logger log = LoggerFactory.getLogger(HostDetailMetricsQueryService.class);
    private static final double GB = 1024.0 * 1024 * 1024;
    private static final double MB_TO_GB = 1024.0;
    private static final String RATE_WINDOW = "5m";
    private static final String FS_FILTER = "fstype!~\"tmpfs|overlay\"";
    static final String NET_FILTER = "device!~\"lo|veth.*|docker.*|br.*|virbr.*\"";

    /** 주요 지표 카드용 현재값 — null 은 미수집 */
    public record HostSummaryMetrics(Double cpuPercent, Integer coreCount,
                                     Double memoryUsedGb, Double memoryTotalGb,
                                     Double fileSystemUsedGb, Double fileSystemTotalGb,
                                     Double receiveBytesPerSec, Double transmitBytesPerSec,
                                     Double gpuPercent, Integer gpuCount,
                                     Double vramUsedGb, Double vramTotalGb,
                                     Long bootTimeSeconds) {
        public static final HostSummaryMetrics EMPTY =
                new HostSummaryMetrics(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** 일자별 일간 최대 사용률 — null 은 해당 일자 미수집 */
    public record DailyMax(LocalDate date, Double cpuPercent, Double memoryPercent, Double fileSystemPercent) {}

    private final VictoriaMetricsClient client;

    public HostDetailMetricsQueryService(VictoriaMetricsClient client) {
        this.client = client;
    }

    // ── PromQL ──────────────────────────────────────────────────

    static String cpuPercent(MetricSelector s, String window) {
        return "100 - (avg by (instance) (rate(node_cpu_seconds_total" + s.braces("mode=\"idle\"") + "[" + window + "])) * 100)";
    }

    static String coreCount(MetricSelector s) {
        return "count by (instance) (node_cpu_seconds_total" + s.braces("mode=\"idle\"") + ")";
    }

    static String memoryPercent(MetricSelector s) {
        return "(1 - node_memory_MemAvailable_bytes" + s.braces() + " / node_memory_MemTotal_bytes" + s.braces() + ") * 100";
    }

    static String memoryTotalBytes(MetricSelector s) {
        return "node_memory_MemTotal_bytes" + s.braces();
    }

    static String memoryAvailableBytes(MetricSelector s) {
        return "node_memory_MemAvailable_bytes" + s.braces();
    }

    static String fileSystemSizeBytes(MetricSelector s) {
        return "sum by (instance) (node_filesystem_size_bytes" + s.braces(FS_FILTER) + ")";
    }

    static String fileSystemAvailBytes(MetricSelector s) {
        return "sum by (instance) (node_filesystem_avail_bytes" + s.braces(FS_FILTER) + ")";
    }

    static String fileSystemPercent(MetricSelector s) {
        return "(1 - " + fileSystemAvailBytes(s) + " / " + fileSystemSizeBytes(s) + ") * 100";
    }

    static String receiveBytesPerSec(MetricSelector s, String window) {
        return "sum by (instance) (rate(node_network_receive_bytes_total" + s.braces(NET_FILTER) + "[" + window + "]))";
    }

    static String transmitBytesPerSec(MetricSelector s, String window) {
        return "sum by (instance) (rate(node_network_transmit_bytes_total" + s.braces(NET_FILTER) + "[" + window + "]))";
    }

    static String bootTimeSeconds(MetricSelector s) {
        return "node_boot_time_seconds" + s.braces();
    }

    static String gpuPercent(MetricSelector dcgm) {
        return "avg by (instance) (DCGM_FI_DEV_GPU_UTIL" + dcgm.braces() + ")";
    }

    static String gpuCount(MetricSelector dcgm) {
        return "count by (instance) (DCGM_FI_DEV_GPU_UTIL" + dcgm.braces() + ")";
    }

    static String vramUsedMb(MetricSelector dcgm) {
        return "sum by (instance) (DCGM_FI_DEV_FB_USED" + dcgm.braces() + ")";
    }

    static String vramFreeMb(MetricSelector dcgm) {
        return "sum by (instance) (DCGM_FI_DEV_FB_FREE" + dcgm.braces() + ")";
    }

    static String vramPercent(MetricSelector dcgm) {
        return vramUsedMb(dcgm) + " / (" + vramUsedMb(dcgm) + " + " + vramFreeMb(dcgm) + ") * 100";
    }

    // ── 조회 (선택자) ───────────────────────────────────────────

    /** 주요 지표 카드용 현재값. 선택자가 null 인 exporter 그룹은 조회하지 않고 미수집으로 둔다. */
    public HostSummaryMetrics summary(MetricSelector node, MetricSelector dcgm) {
        try {
            Double cpu = null, memTotal = null, memAvail = null, fsSize = null, fsAvail = null, rx = null, tx = null, boot = null;
            Integer cores = null;
            if (node != null) {
                cpu = scalar(cpuPercent(node, RATE_WINDOW));
                cores = toInt(scalar(coreCount(node)));
                memTotal = scalar(memoryTotalBytes(node));
                memAvail = scalar(memoryAvailableBytes(node));
                fsSize = scalar(fileSystemSizeBytes(node));
                fsAvail = scalar(fileSystemAvailBytes(node));
                rx = scalar(receiveBytesPerSec(node, RATE_WINDOW));
                tx = scalar(transmitBytesPerSec(node, RATE_WINDOW));
                boot = scalar(bootTimeSeconds(node));
            }
            Double gpu = null, vramUsed = null, vramFree = null;
            Integer gpus = null;
            if (dcgm != null) {
                gpu = scalar(gpuPercent(dcgm));
                gpus = toInt(scalar(gpuCount(dcgm)));
                vramUsed = scalar(vramUsedMb(dcgm));
                vramFree = scalar(vramFreeMb(dcgm));
            }
            return new HostSummaryMetrics(
                    round1(cpu), cores,
                    memTotal == null || memAvail == null ? null : round1((memTotal - memAvail) / GB), gb(memTotal),
                    fsSize == null || fsAvail == null ? null : round1((fsSize - fsAvail) / GB), gb(fsSize),
                    round1(rx), round1(tx),
                    round1(gpu), gpus,
                    vramUsed == null ? null : round1(vramUsed / MB_TO_GB),
                    vramUsed == null || vramFree == null ? null : round1((vramUsed + vramFree) / MB_TO_GB),
                    boot == null ? null : boot.longValue());
        } catch (RuntimeException e) {
            log.warn("Thanos 상세 지표 조회 실패 — 카드를 미수집으로 표시합니다: {}", e.getMessage());
            return HostSummaryMetrics.EMPTY;
        }
    }

    /** 요약 차트 시계열. 미수집이면 빈 목록, NETWORK 는 receive/transmit 2개 시리즈. */
    public List<Series> trend(NodeDetailMetric metric, MetricSelector node, MetricSelector dcgm,
                              Instant from, Instant to, String step) {
        try {
            String rate = rateWindow(step);
            return switch (metric) {
                case CPU -> series("cpu", node == null ? null : cpuPercent(node, rate), from, to, step);
                case MEMORY -> series("memory", node == null ? null : memoryPercent(node), from, to, step);
                case FILE_SYSTEM -> series("file_system", node == null ? null : fileSystemPercent(node), from, to, step);
                case NETWORK -> {
                    List<Series> both = new ArrayList<>();
                    if (node != null) {
                        both.addAll(series("receive", receiveBytesPerSec(node, rate), from, to, step));
                        both.addAll(series("transmit", transmitBytesPerSec(node, rate), from, to, step));
                    }
                    yield both;
                }
                case GPU -> series("gpu", dcgm == null ? null : gpuPercent(dcgm), from, to, step);
                case VRAM -> series("vram", dcgm == null ? null : vramPercent(dcgm), from, to, step);
            };
        } catch (RuntimeException e) {
            log.warn("Thanos 요약 차트 조회 실패 — 빈 시계열을 돌려줍니다: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 일자별 일간 최대 사용률 — {@code max_over_time((expr)[1d:5m])} 을 하루 step 으로 range 조회한다.
     * 각 샘플의 평가 시각은 그 날의 끝(다음날 00:00)이라 당일 구간 [00:00, 24:00) 의 최대가 된다.
     */
    public List<DailyMax> dailyMax(MetricSelector node, LocalDate fromDate, LocalDate toDate) {
        if (node == null || fromDate.isAfter(toDate)) {
            return List.of();
        }
        ZoneId zone = ZoneId.systemDefault();
        Instant start = fromDate.plusDays(1).atStartOfDay(zone).toInstant();
        Instant end = toDate.plusDays(1).atStartOfDay(zone).toInstant();
        try {
            Map<LocalDate, Double> cpu = byDay(dailyMaxQuery(cpuPercent(node, RATE_WINDOW)), start, end, zone);
            Map<LocalDate, Double> memory = byDay(dailyMaxQuery(memoryPercent(node)), start, end, zone);
            Map<LocalDate, Double> fs = byDay(dailyMaxQuery(fileSystemPercent(node)), start, end, zone);

            List<DailyMax> rows = new ArrayList<>();
            for (LocalDate d = fromDate; !d.isAfter(toDate); d = d.plusDays(1)) {
                rows.add(new DailyMax(d, round1(cpu.get(d)), round1(memory.get(d)), round1(fs.get(d))));
            }
            return rows;
        } catch (RuntimeException e) {
            log.warn("Thanos 일간 최대 사용률 조회 실패 — 전 일자를 미수집으로 표시합니다: {}", e.getMessage());
            List<DailyMax> rows = new ArrayList<>();
            for (LocalDate d = fromDate; !d.isAfter(toDate); d = d.plusDays(1)) {
                rows.add(new DailyMax(d, null, null, null));
            }
            return rows;
        }
    }

    // ── 내부 ────────────────────────────────────────────────────

    /**
     * rate() 범위 창 — 버킷 간격(step)이 5분보다 짧으면 5분을 쓴다.
     * rate 는 창 안에 샘플이 2개 이상 있어야 값이 나오므로 스크랩 간격(15~30초)의 4배 이상을 확보한다.
     */
    static String rateWindow(String step) {
        try {
            String s = step.trim().toLowerCase();
            long value = Long.parseLong(s.substring(0, s.length() - 1));
            long seconds = switch (s.charAt(s.length() - 1)) {
                case 's' -> value;
                case 'm' -> value * 60;
                case 'h' -> value * 3600;
                case 'd' -> value * 86_400;
                default -> 0;
            };
            return seconds >= 300 ? step : RATE_WINDOW;
        } catch (RuntimeException e) {
            return RATE_WINDOW;
        }
    }

    static String dailyMaxQuery(String expr) {
        return "max_over_time((" + expr + ")[1d:5m])";
    }

    private List<Series> series(String name, String query, Instant from, Instant to, String step) {
        if (query == null) {
            return List.of();
        }
        List<Map<String, Object>> results = client.queryRangeResults(query, from, to, step);
        if (results.isEmpty()) {
            return List.of();
        }
        List<DataPoint> points = new ArrayList<>();
        for (Object[] pair : values(results.get(0))) {
            points.add(new DataPoint(((Number) pair[0]).longValue(), round1((Double) pair[1])));
        }
        return points.isEmpty() ? List.of() : List.of(new Series(name, points));
    }

    private Map<LocalDate, Double> byDay(String query, Instant start, Instant end, ZoneId zone) {
        Map<LocalDate, Double> byDay = new java.util.HashMap<>();
        List<Map<String, Object>> results = client.queryRangeResults(query, start, end, "1d");
        if (results.isEmpty()) {
            return byDay;
        }
        for (Object[] pair : values(results.get(0))) {
            // 평가 시각(다음날 00:00)의 1초 전이 해당 일자
            LocalDate day = Instant.ofEpochSecond(((Number) pair[0]).longValue() - 1).atZone(zone).toLocalDate();
            byDay.put(day, (Double) pair[1]);
        }
        return byDay;
    }

    /** range 결과 1개의 values → [timestamp(Number), value(Double)] 목록. 비수치 샘플은 건너뛴다. */
    static List<Object[]> values(Map<String, Object> result) {
        List<Object[]> out = new ArrayList<>();
        Object values = result.get("values");
        if (!(values instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof List<?> pair && pair.size() >= 2 && pair.get(0) instanceof Number ts) {
                Double v = parse(pair.get(1));
                if (v != null) {
                    out.add(new Object[]{ts, v});
                }
            }
        }
        return out;
    }

    /** 즉시 쿼리의 첫 벡터 값. 결과가 없거나 비수치면 null(미수집). */
    private Double scalar(String query) {
        List<Map<String, Object>> results = client.queryResults(query);
        if (results.isEmpty()) {
            return null;
        }
        Object value = results.get(0).get("value");
        if (value instanceof List<?> pair && pair.size() >= 2) {
            return parse(pair.get(1));
        }
        return null;
    }

    static Double parse(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            double v = Double.parseDouble(raw.toString());
            return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer toInt(Double value) {
        return value == null ? null : (int) Math.round(value);
    }

    private static Double gb(Double bytes) {
        return bytes == null ? null : round1(bytes / GB);
    }

    static Double round1(Double value) {
        return value == null ? null : Math.round(value * 10) / 10.0;
    }
}
