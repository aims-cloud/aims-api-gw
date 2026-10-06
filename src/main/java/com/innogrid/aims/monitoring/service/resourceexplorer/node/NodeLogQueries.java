package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.LokiQueryClient;
import com.innogrid.aims.monitoring.LokiQueryClient.Point;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.*;
import com.innogrid.aims.monitoring.service.resourceexplorer.LogScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 등록한 대상/경로로만 LogQL을 생성한다. 원격 응답에 조회 권한 결정을 맡기지 않는다.
 * 대상은 {@link LogScope} — 물리 노드는 instance·mdc_id, Tumblebug VM 은 tb_ns·tb_infra·tb_node 라벨로 고른다.
 */
@Component
public class NodeLogQueries {
    private final LokiQueryClient loki;
    private static final Map<String, Long> INTERVALS = Map.of(
            "1m", 60L, "5m", 300L, "15m", 900L, "1h", 3600L, "6h", 21600L, "1d", 86400L);

    public NodeLogQueries(LokiQueryClient loki) { this.loki = loki; }

    /** 초 단위 (from, to] 계약. 로그 스트림에도 같은 경계를 적용한다. */
    public record Window(Instant from, Instant to) {
        long seconds() { return Duration.between(from, to).getSeconds(); }
    }
    public static Window window(Instant from, Instant to) {
        if (from == null || to == null) throw bad("조회 시작/종료 시간이 필요합니다.");
        from = from.truncatedTo(ChronoUnit.SECONDS);
        to = to.truncatedTo(ChronoUnit.SECONDS);
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(30)) > 0)
            throw bad("조회 기간은 1초 이상 30일 이하여야 합니다.");
        return new Window(from, to);
    }
    /** 조회 대상(물리 노드 instance·mdc_id 또는 VM tb_* 라벨)의 라벨 매핑이 모두 있는가 */
    public static boolean configured(LogScope host) {
        return host.logConfigured();
    }
    private static void requireConfigured(LogScope host) {
        if (!configured(host)) throw new BusinessException(HttpStatus.CONFLICT, "조회 대상의 Loki 라벨 매핑이 미설정입니다.");
    }

    public record Observation(boolean configured, boolean failed, Map<String, Long> counts) {
        public LogStatus status(Collection<String> paths) {
            if (!configured || paths.isEmpty()) return LogStatus.UNCONFIGURED;
            if (failed) return LogStatus.UNKNOWN;
            return paths.stream().anyMatch(p -> counts.getOrDefault(p, 0L) > 0)
                    ? LogStatus.RECENT_LOGS : LogStatus.NO_RECENT_LOGS;
        }
    }
    public Observation recent(LogScope host, Collection<String> paths) {
        if (!configured(host) || paths.isEmpty()) return new Observation(false, false, Map.of());
        try {
            // 노드별 1회. 원장 경로가 많아져도 URL 크기가 증가하지 않는다.
            List<Point> points = loki.instant("sum by (filename) (count_over_time(" + nodeSelector(host) + "[5m]))", Instant.now());
            return new Observation(true, false, byFile(points));
        } catch (BusinessException e) {
            if (e.getStatus() != HttpStatus.BAD_GATEWAY) throw e;
            return new Observation(true, true, Map.of());
        }
    }
    /** 수집기가 최근 lookback 초 동안 이 노드에서 보낸 job·파일별 건수. job 라벨이 없는 스트림은 job=null. */
    public record DiscoveredFile(String job, String path, long count) {}
    /** 라벨 매핑이 없으면 빈 목록. Loki 실패는 목록 조회와 달리 감추지 않고 502 그대로 올린다(후보를 비운 것과 구분). */
    public List<DiscoveredFile> recentFiles(LogScope host, long lookbackSeconds) {
        if (!configured(host)) return List.of();
        List<DiscoveredFile> rows = new ArrayList<>();
        for (Point point : loki.instant(count(nodeSelector(host), lookbackSeconds, "job, filename"), Instant.now())) {
            String path = point.labels().get("filename");
            if (path != null && point.count() > 0) rows.add(new DiscoveredFile(point.labels().get("job"), path, point.count()));
        }
        return rows;
    }
    public Map<String, Long> fileCounts(LogScope host, List<String> paths, Window window) {
        requireConfigured(host);
        if (paths.isEmpty()) return Map.of();
        return byFile(loki.instant(count(selector(host, paths, null), window.seconds(), "filename"), window.to()));
    }
    public Map<ServiceLogLevel, Long> levelCounts(LogScope host, List<String> paths, Window window) {
        requireConfigured(host);
        if (paths.isEmpty()) return Map.of();
        return byLevel(loki.instant(count(selector(host, paths, null), window.seconds(), LEVEL_GROUP), window.to()));
    }

    public ServiceLogTrendResponse trend(LogScope host, List<String> paths, Window window, String interval) {
        Long step = INTERVALS.get(interval);
        if (step == null) throw bad("interval은 1m/5m/15m/1h/6h/1d입니다.");
        long bucketCount = (window.seconds() + step - 1) / step;
        if (bucketCount > 1000) throw bad("차트는 최대 1000개 버킷입니다. interval을 늘려주세요.");
        requireConfigured(host);
        Map<Long, Map<ServiceLogLevel, Long>> counts = new HashMap<>();
        long full = window.seconds() / step;
        if (!paths.isEmpty() && full > 0) {
            List<Point> points = loki.range(count(selector(host, paths, null), step, LEVEL_GROUP),
                    window.from().plusSeconds(step), window.from().plusSeconds(full * step), step);
            for (Point point : points) {
                long start = point.time().getEpochSecond() - step;
                Map<ServiceLogLevel, Long> levels = counts.computeIfAbsent(start, ignored -> new EnumMap<>(ServiceLogLevel.class));
                levels.merge(effectiveLevel(point.labels()), point.count(), Long::sum);
            }
        }
        long remainder = window.seconds() % step;
        if (!paths.isEmpty() && remainder > 0) {
            counts.put(window.from().plusSeconds(full * step).getEpochSecond(),
                    byLevel(loki.instant(count(selector(host, paths, null), remainder, LEVEL_GROUP), window.to())));
        }
        List<ServiceLogTrendResponse.Bucket> buckets = new ArrayList<>();
        for (long i = 0; i < bucketCount; i++) {
            long start = window.from().plusSeconds(i * step).getEpochSecond();
            Map<ServiceLogLevel, Long> levels = counts.getOrDefault(start, Map.of());
            buckets.add(new ServiceLogTrendResponse.Bucket(start, levels.getOrDefault(ServiceLogLevel.INFO, 0L),
                    levels.getOrDefault(ServiceLogLevel.WARNING, 0L), levels.getOrDefault(ServiceLogLevel.DEBUG, 0L),
                    levels.getOrDefault(ServiceLogLevel.ERROR, 0L), levels.getOrDefault(ServiceLogLevel.UNKNOWN, 0L)));
        }
        return new ServiceLogTrendResponse(window.from().getEpochSecond(), window.to().getEpochSecond(), interval, buckets);
    }

    public ServiceLogListResponse search(LogScope host, List<String> paths, List<ServiceLogLevel> levels,
                                         String keyword, Window window, int limit) {
        requireConfigured(host);
        if (paths.isEmpty()) return new ServiceLogListResponse(0, List.of());
        String query = selector(host, paths, levels);
        if (keyword != null && !keyword.isEmpty()) query += " |= " + quote(keyword);
        long total = loki.instant(count(query, window.seconds(), null), window.to()).stream().mapToLong(Point::count).sum();
        // Loki streams의 [start,end)를 집계와 같은 (from,to]로 맞춘다.
        List<LokiQueryClient.LogLine> lines = loki.logs(query, window.from().plusNanos(1), window.to().plusNanos(1), limit);
        List<ServiceLogListResponse.Entry> entries = lines.stream().sorted(Comparator.comparing(LokiQueryClient.LogLine::time).reversed()
                        .thenComparing(line -> line.labels().getOrDefault("filename", "")).thenComparing(LokiQueryClient.LogLine::message))
                .limit(limit).map(line -> new ServiceLogListResponse.Entry(line.time().atOffset(ZoneOffset.UTC),
                        effectiveLevel(line.labels()), line.labels().get("filename"), line.message())).toList();
        return new ServiceLogListResponse(total, entries);
    }

    /**
     * 집계 그룹. 파이프라인이 붙인 {@code level} 과 Loki 3.x 가 붙이는 {@code detected_level}(구조화 메타데이터)을 함께 받아
     * {@link #effectiveLevel} 로 합친다. Loki 는 detected_level 을 level 라벨에서 먼저 유도하므로 둘이 어긋나지 않는다(docs/11 §3).
     */
    static final String LEVEL_GROUP = "level, detected_level";

    /** 파이프라인 level 이 있으면 그것, 없으면 Loki detected_level. 둘 다 없으면 UNKNOWN */
    static ServiceLogLevel effectiveLevel(Map<String, String> labels) {
        String level = labels.get("level");
        if (level == null || level.isBlank()) level = labels.get("detected_level");
        return ServiceLogLevel.normalize(level);
    }

    /** 레벨별 Loki 값. detected_level 은 소문자이고 critical·fatal 도 낸다 */
    private static String levelPattern(ServiceLogLevel level) {
        return switch (level) {
            case WARNING -> "warn|warning";
            case ERROR -> "error|critical|fatal";
            default -> level.name().toLowerCase(Locale.ROOT);
        };
    }

    /**
     * 스트림 선택자 + (레벨 필터가 있으면) 파이프라인 라벨 필터. 레벨은 스트림 라벨이 아니라 {@code detected_level} 로 거른다 —
     * 레벨 추출 파이프라인이 없는 파일(동적 수집, syslog)도 Loki 의 감지값으로 걸러지고, level 라벨이 있는 파일은 Loki 가 그 값을
     * detected_level 로 옮겨 주므로 결과가 같다. UNKNOWN 을 고르면 선택하지 않은 레벨을 제외하는 부정 매처라 감지값이 없는 줄도 남는다.
     */
    static String selector(LogScope host, List<String> paths, List<ServiceLogLevel> levels) {
        if (paths.isEmpty()) throw bad("조회 경로가 필요합니다.");
        String base = nodeSelector(host);
        StringBuilder result = new StringBuilder(base.substring(0, base.length() - 1));
        if (paths.size() == 1) result.append(", filename=").append(quote(paths.get(0)));
        else result.append(", filename=~").append(quote(paths.stream().map(NodeLogQueries::regexLiteral).collect(Collectors.joining("|", "(", ")"))));
        result.append('}');
        if (levels != null && !levels.isEmpty()) {
            Set<ServiceLogLevel> selected = EnumSet.copyOf(levels);
            boolean unknown = selected.contains(ServiceLogLevel.UNKNOWN);
            List<String> patterns = new ArrayList<>();
            for (ServiceLogLevel level : List.of(ServiceLogLevel.INFO, ServiceLogLevel.WARNING, ServiceLogLevel.ERROR, ServiceLogLevel.DEBUG)) {
                if (selected.contains(level) != unknown) patterns.add(levelPattern(level));
            }
            if (!patterns.isEmpty()) result.append(unknown ? " | detected_level!~" : " | detected_level=~")
                    .append(quote("(?i:" + String.join("|", patterns) + ")"));
        }
        return result.toString();
    }
    private static String nodeSelector(LogScope host) {
        requireConfigured(host);
        return host.logLabels().entrySet().stream()
                .map(label -> label.getKey() + "=" + quote(label.getValue()))
                .collect(Collectors.joining(", ", "{", "}"));
    }
    static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (Character.isISOControl(c)) result.append(String.format("\\u%04x", (int) c));
                    else result.append(c);
                }
            }
        }
        return result.append('"').toString();
    }
    static String regexLiteral(String value) {
        StringBuilder result = new StringBuilder();
        for (char c : value.toCharArray()) {
            if ("\\.^$|?*+()[]{}".indexOf(c) >= 0) result.append('\\');
            result.append(c);
        }
        return result.toString();
    }
    private static String count(String query, long seconds, String group) {
        return "sum" + (group == null ? "" : " by (" + group + ")") + " (count_over_time(" + query + "[" + seconds + "s]))";
    }
    private static Map<String, Long> byFile(List<Point> points) {
        Map<String, Long> result = new HashMap<>();
        for (Point point : points) {
            String path = point.labels().get("filename");
            if (path != null) result.merge(path, point.count(), Long::sum);
        }
        return result;
    }
    private static Map<ServiceLogLevel, Long> byLevel(List<Point> points) {
        Map<ServiceLogLevel, Long> result = new EnumMap<>(ServiceLogLevel.class);
        for (Point point : points) result.merge(effectiveLevel(point.labels()), point.count(), Long::sum);
        return result;
    }
    private static BusinessException bad(String message) { return new BusinessException(HttpStatus.BAD_REQUEST, message); }
}
