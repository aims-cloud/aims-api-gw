package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 자원 1개의 System 카테고리 패널 지표를 Thanos 에서 읽는다 — 부하·코어·컨텍스트 스위치·인터럽트 현재값, 시스템 정보, 시계열 전용.
 *
 * node_exporter 기본 콜렉터 지표만 쓴다: loadavg({@code node_load1/5/15}), stat({@code node_context_switches_total, node_intr_total}),
 * time/boottime({@code node_boot_time_seconds}), uname({@code node_uname_info} 라벨), os({@code node_os_info} 라벨),
 * cpu({@code node_cpu_seconds_total} 로 코어 수).
 * 자원은 {@link MetricSelector} 로 가리키고, Thanos 장애나 시계열 부재는 null/빈 목록(미수집)으로 돌려준다.
 */
@Component
public class HostSystemMetricsQueryService {

    private static final Logger log = LoggerFactory.getLogger(HostSystemMetricsQueryService.class);
    private static final String RATE_WINDOW = "5m";

    /** KPI 현재값 — null 은 미수집 */
    public record SystemSummary(Long bootTimeSeconds, Double load1, Double load5, Double load15, Integer coreCount,
                                Double contextSwitchesPerSec, Double interruptsPerSec) {
        public static final SystemSummary EMPTY = new SystemSummary(null, null, null, null, null, null, null);
    }

    /** 시스템 정보 — uname·os-release 라벨과 부팅 시각, 표본 시각 */
    public record SystemInfo(String hostname, String osName, String osVersion, String kernelRelease, String architecture,
                             Long bootTimeSeconds, Instant collectedAt) {}

    private final VictoriaMetricsClient client;

    public HostSystemMetricsQueryService(VictoriaMetricsClient client) {
        this.client = client;
    }

    // ── PromQL ──────────────────────────────────────────────────

    static String load(int minutes, MetricSelector s) {
        return "node_load" + minutes + s.braces();
    }

    static String bootTime(MetricSelector s) {
        return "node_boot_time_seconds" + s.braces();
    }

    static String coreCount(MetricSelector s) {
        return "count by (instance) (node_cpu_seconds_total" + s.braces("mode=\"idle\"") + ")";
    }

    static String contextSwitchesPerSec(MetricSelector s, String window) {
        return "rate(node_context_switches_total" + s.braces() + "[" + window + "])";
    }

    static String interruptsPerSec(MetricSelector s, String window) {
        return "rate(node_intr_total" + s.braces() + "[" + window + "])";
    }

    static String unameInfo(MetricSelector s) {
        return "node_uname_info" + s.braces();
    }

    static String osInfo(MetricSelector s) {
        return "node_os_info" + s.braces();
    }

    static String trendQuery(SystemMetric metric, MetricSelector s, String window) {
        return switch (metric) {
            case LOAD_1 -> load(1, s);
            case LOAD_5 -> load(5, s);
            case LOAD_15 -> load(15, s);
            case CONTEXT_SWITCHES -> contextSwitchesPerSec(s, window);
            case INTERRUPTS -> interruptsPerSec(s, window);
        };
    }

    // ── 조회 ────────────────────────────────────────────────────

    /** KPI 현재값 — 부하는 소수 2자리, rate 는 소수 1자리. 장애는 전부 null */
    public SystemSummary summary(MetricSelector s) {
        try {
            Double boot = scalar(bootTime(s));
            Double cores = scalar(coreCount(s));
            return new SystemSummary(
                    boot == null ? null : boot.longValue(),
                    round2(scalar(load(1, s))),
                    round2(scalar(load(5, s))),
                    round2(scalar(load(15, s))),
                    cores == null ? null : (int) Math.round(cores),
                    HostDetailMetricsQueryService.round1(scalar(contextSwitchesPerSec(s, RATE_WINDOW))),
                    HostDetailMetricsQueryService.round1(scalar(interruptsPerSec(s, RATE_WINDOW))));
        } catch (RuntimeException e) {
            log.warn("Thanos System KPI 조회 실패 — 카드를 미수집으로 표시합니다: {}", e.getMessage());
            return SystemSummary.EMPTY;
        }
    }

    /** 시스템 정보 — uname 표본이 없으면 empty(미수집). os-release·부팅 시각은 있는 것만 채운다 */
    public Optional<SystemInfo> info(MetricSelector s) {
        try {
            List<Map<String, Object>> uname = client.queryResults(unameInfo(s));
            if (uname.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> unameRow = uname.get(0);
            List<Map<String, Object>> os = client.queryResults(osInfo(s));
            Map<String, Object> osRow = os.isEmpty() ? Map.of() : os.get(0);
            Double boot = scalar(bootTime(s));

            String osName = label(osRow, "pretty_name");
            if (osName == null) {
                osName = label(osRow, "name");
            }
            return Optional.of(new SystemInfo(
                    label(unameRow, "nodename"),
                    osName,
                    label(osRow, "version_id"),
                    label(unameRow, "release"),
                    label(unameRow, "machine"),
                    boot == null ? null : boot.longValue(),
                    sampleTime(unameRow)));
        } catch (RuntimeException e) {
            log.warn("Thanos System 정보 조회 실패 — 미수집으로 표시합니다: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 지표별 시계열 — 미수집이면 빈 목록. 부하는 소수 2자리, rate 는 소수 1자리 */
    public List<DataPoint> trend(SystemMetric metric, MetricSelector s, Instant from, Instant to, String step) {
        String query = trendQuery(metric, s, HostDetailMetricsQueryService.rateWindow(step));
        boolean load = metric == SystemMetric.LOAD_1 || metric == SystemMetric.LOAD_5 || metric == SystemMetric.LOAD_15;
        try {
            List<Map<String, Object>> results = client.queryRangeResults(query, from, to, step);
            if (results.isEmpty()) {
                return List.of();
            }
            List<DataPoint> points = new ArrayList<>();
            for (Object[] pair : HostDetailMetricsQueryService.values(results.get(0))) {
                Double value = (Double) pair[1];
                points.add(new DataPoint(((Number) pair[0]).longValue(),
                        load ? round2(value) : HostDetailMetricsQueryService.round1(value)));
            }
            return points;
        } catch (RuntimeException e) {
            log.warn("Thanos System 차트 조회 실패 — 빈 시계열을 돌려줍니다: {}", e.getMessage());
            return List.of();
        }
    }

    // ── 내부 ────────────────────────────────────────────────────

    private Double scalar(String query) {
        List<Map<String, Object>> results = client.queryResults(query);
        if (results.isEmpty()) {
            return null;
        }
        Object value = results.get(0).get("value");
        if (value instanceof List<?> pair && pair.size() >= 2) {
            return HostDetailMetricsQueryService.parse(pair.get(1));
        }
        return null;
    }

    private static String label(Map<String, Object> result, String name) {
        Object metric = result.get("metric");
        if (!(metric instanceof Map<?, ?> labels)) {
            return null;
        }
        Object raw = labels.get(name);
        return raw == null || raw.toString().isBlank() ? null : raw.toString();
    }

    private static Instant sampleTime(Map<String, Object> result) {
        Object value = result.get("value");
        if (value instanceof List<?> pair && !pair.isEmpty() && pair.get(0) instanceof Number ts) {
            return Instant.ofEpochSecond(ts.longValue());
        }
        return null;
    }

    private static Double round2(Double value) {
        return value == null ? null : Math.round(value * 100) / 100.0;
    }
}
