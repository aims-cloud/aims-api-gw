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
 * 물리 호스트 1대의 상세 지표를 Thanos 에서 읽는다 — 노드 상세 패널(주요 지표 카드·요약 차트·일간 최대치) 전용.
 *
 * node_exporter 는 {@code instance} 라벨(address:port)로, GPU/VRAM 은 dcgm_exporter 의 instance 로 필터한다.
 * 표준 PromQL(MDC 수집 SW 인터페이스 분석)을 그대로 쓰고, Thanos 장애나 시계열 부재는 null(미수집)로 돌려준다.
 */
@Component
public class HostDetailMetricsQueryService {

    private static final Logger log = LoggerFactory.getLogger(HostDetailMetricsQueryService.class);
    private static final double GB = 1024.0 * 1024 * 1024;
    private static final double MB_TO_GB = 1024.0;
    private static final String RATE_WINDOW = "5m";
    private static final String FS_FILTER = "fstype!~\"tmpfs|overlay\"";
    private static final String NET_FILTER = "device!~\"lo|veth.*|docker.*|br.*|virbr.*\"";

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

    static String cpuPercent(String instance, String window) {
        return "100 - (avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\",instance=\"" + instance + "\"}["
                + window + "])) * 100)";
    }

    static String coreCount(String instance) {
        return "count by (instance) (node_cpu_seconds_total{mode=\"idle\",instance=\"" + instance + "\"})";
    }

    static String memoryPercent(String instance) {
        return "(1 - node_memory_MemAvailable_bytes{instance=\"" + instance + "\"} / node_memory_MemTotal_bytes{instance=\""
                + instance + "\"}) * 100";
    }

    static String memoryTotalBytes(String instance) {
        return "node_memory_MemTotal_bytes{instance=\"" + instance + "\"}";
    }

    static String memoryAvailableBytes(String instance) {
        return "node_memory_MemAvailable_bytes{instance=\"" + instance + "\"}";
    }

    static String fileSystemSizeBytes(String instance) {
        return "sum by (instance) (node_filesystem_size_bytes{" + FS_FILTER + ",instance=\"" + instance + "\"})";
    }

    static String fileSystemAvailBytes(String instance) {
        return "sum by (instance) (node_filesystem_avail_bytes{" + FS_FILTER + ",instance=\"" + instance + "\"})";
    }

    static String fileSystemPercent(String instance) {
        return "(1 - " + fileSystemAvailBytes(instance) + " / " + fileSystemSizeBytes(instance) + ") * 100";
    }

    static String receiveBytesPerSec(String instance, String window) {
        return "sum by (instance) (rate(node_network_receive_bytes_total{" + NET_FILTER + ",instance=\"" + instance + "\"}["
                + window + "]))";
    }

    static String transmitBytesPerSec(String instance, String window) {
        return "sum by (instance) (rate(node_network_transmit_bytes_total{" + NET_FILTER + ",instance=\"" + instance + "\"}["
                + window + "]))";
    }

    static String bootTimeSeconds(String instance) {
        return "node_boot_time_seconds{instance=\"" + instance + "\"}";
    }

    static String gpuPercent(String dcgmInstance) {
        return "avg by (instance) (DCGM_FI_DEV_GPU_UTIL{instance=\"" + dcgmInstance + "\"})";
    }

    static String gpuCount(String dcgmInstance) {
        return "count by (instance) (DCGM_FI_DEV_GPU_UTIL{instance=\"" + dcgmInstance + "\"})";
    }

    static String vramUsedMb(String dcgmInstance) {
        return "sum by (instance) (DCGM_FI_DEV_FB_USED{instance=\"" + dcgmInstance + "\"})";
    }

    static String vramFreeMb(String dcgmInstance) {
        return "sum by (instance) (DCGM_FI_DEV_FB_FREE{instance=\"" + dcgmInstance + "\"})";
    }

    static String vramPercent(String dcgmInstance) {
        return vramUsedMb(dcgmInstance) + " / (" + vramUsedMb(dcgmInstance) + " + " + vramFreeMb(dcgmInstance) + ") * 100";
    }

    // ── 조회 ────────────────────────────────────────────────────

    /** 주요 지표 카드용 현재값. instance 가 null 인 exporter 그룹은 조회하지 않고 미수집으로 둔다. */
    public HostSummaryMetrics summary(String nodeInstance, String dcgmInstance) {
        try {
            Double cpu = null, memTotal = null, memAvail = null, fsSize = null, fsAvail = null, rx = null, tx = null, boot = null;
            Integer cores = null;
            if (nodeInstance != null) {
                cpu = scalar(cpuPercent(nodeInstance, RATE_WINDOW));
                cores = toInt(scalar(coreCount(nodeInstance)));
                memTotal = scalar(memoryTotalBytes(nodeInstance));
                memAvail = scalar(memoryAvailableBytes(nodeInstance));
                fsSize = scalar(fileSystemSizeBytes(nodeInstance));
                fsAvail = scalar(fileSystemAvailBytes(nodeInstance));
                rx = scalar(receiveBytesPerSec(nodeInstance, RATE_WINDOW));
                tx = scalar(transmitBytesPerSec(nodeInstance, RATE_WINDOW));
                boot = scalar(bootTimeSeconds(nodeInstance));
            }
            Double gpu = null, vramUsed = null, vramFree = null;
            Integer gpus = null;
            if (dcgmInstance != null) {
                gpu = scalar(gpuPercent(dcgmInstance));
                gpus = toInt(scalar(gpuCount(dcgmInstance)));
                vramUsed = scalar(vramUsedMb(dcgmInstance));
                vramFree = scalar(vramFreeMb(dcgmInstance));
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
            log.warn("Thanos 노드 상세 지표 조회 실패 — 카드를 미수집으로 표시합니다: {}", e.getMessage());
            return HostSummaryMetrics.EMPTY;
        }
    }

    /** 요약 차트 시계열. 미수집이면 빈 목록, NETWORK 는 receive/transmit 2개 시리즈. */
    public List<Series> trend(NodeDetailMetric metric, String nodeInstance, String dcgmInstance,
                              Instant from, Instant to, String step) {
        try {
            return switch (metric) {
                case CPU -> series("cpu", nodeInstance == null ? null : cpuPercent(nodeInstance, step), from, to, step);
                case MEMORY -> series("memory", nodeInstance == null ? null : memoryPercent(nodeInstance), from, to, step);
                case FILE_SYSTEM -> series("file_system", nodeInstance == null ? null : fileSystemPercent(nodeInstance), from, to, step);
                case NETWORK -> {
                    List<Series> both = new ArrayList<>();
                    if (nodeInstance != null) {
                        both.addAll(series("receive", receiveBytesPerSec(nodeInstance, step), from, to, step));
                        both.addAll(series("transmit", transmitBytesPerSec(nodeInstance, step), from, to, step));
                    }
                    yield both;
                }
                case GPU -> series("gpu", dcgmInstance == null ? null : gpuPercent(dcgmInstance), from, to, step);
                case VRAM -> series("vram", dcgmInstance == null ? null : vramPercent(dcgmInstance), from, to, step);
            };
        } catch (RuntimeException e) {
            log.warn("Thanos 노드 요약 차트 조회 실패 — 빈 시계열을 돌려줍니다: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 일자별 일간 최대 사용률 — {@code max_over_time((expr)[1d:5m])} 을 하루 step 으로 range 조회한다.
     * 각 샘플의 평가 시각은 그 날의 끝(다음날 00:00)이라 당일 구간 [00:00, 24:00) 의 최대가 된다.
     */
    public List<DailyMax> dailyMax(String nodeInstance, LocalDate fromDate, LocalDate toDate) {
        if (nodeInstance == null || fromDate.isAfter(toDate)) {
            return List.of();
        }
        ZoneId zone = ZoneId.systemDefault();
        Instant start = fromDate.plusDays(1).atStartOfDay(zone).toInstant();
        Instant end = toDate.plusDays(1).atStartOfDay(zone).toInstant();
        try {
            Map<LocalDate, Double> cpu = byDay(dailyMaxQuery(cpuPercent(nodeInstance, RATE_WINDOW)), start, end, zone);
            Map<LocalDate, Double> memory = byDay(dailyMaxQuery(memoryPercent(nodeInstance)), start, end, zone);
            Map<LocalDate, Double> fs = byDay(dailyMaxQuery(fileSystemPercent(nodeInstance)), start, end, zone);

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

    static String dailyMaxQuery(String expr) {
        return "max_over_time((" + expr + ")[1d:5m])";
    }

    // ── 내부 ────────────────────────────────────────────────────

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
    private static List<Object[]> values(Map<String, Object> result) {
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

    private static Double parse(Object raw) {
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

    private static Double round1(Double value) {
        return value == null ? null : Math.round(value * 10) / 10.0;
    }
}
