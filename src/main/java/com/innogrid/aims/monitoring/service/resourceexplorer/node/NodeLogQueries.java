package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.LokiQueryClient;
import com.innogrid.aims.monitoring.LokiQueryClient.Point;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.*;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 등록한 노드/경로로만 LogQL을 생성한다. 원격 응답에 조회 권한 결정을 맡기지 않는다. */
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
    public static boolean configured(ScopedHost host) {
        return host.instance() != null && !host.instance().isBlank() && host.mdcId() != null && !host.mdcId().isBlank();
    }
    private static void requireConfigured(ScopedHost host) {
        if (!configured(host)) throw new BusinessException(HttpStatus.CONFLICT, "노드의 Loki 라벨 매핑이 미설정입니다.");
    }

    public record Observation(boolean configured, boolean failed, Map<String, Long> counts) {
        public LogStatus status(Collection<String> paths) {
            if (!configured || paths.isEmpty()) return LogStatus.UNCONFIGURED;
            if (failed) return LogStatus.UNKNOWN;
            return paths.stream().anyMatch(p -> counts.getOrDefault(p, 0L) > 0)
                    ? LogStatus.RECENT_LOGS : LogStatus.NO_RECENT_LOGS;
        }
    }
    public Observation recent(ScopedHost host, Collection<String> paths) {
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
    public Map<String, Long> fileCounts(ScopedHost host, List<String> paths, Window window) {
        requireConfigured(host);
        if (paths.isEmpty()) return Map.of();
        return byFile(loki.instant(count(selector(host, paths, null), window.seconds(), "filename"), window.to()));
    }
    public Map<ServiceLogLevel, Long> levelCounts(ScopedHost host, List<String> paths, Window window) {
        requireConfigured(host);
        if (paths.isEmpty()) return Map.of();
        return byLevel(loki.instant(count(selector(host, paths, null), window.seconds(), "level"), window.to()));
    }

    public ServiceLogTrendResponse trend(ScopedHost host, List<String> paths, Window window, String interval) {
        Long step = INTERVALS.get(interval);
        if (step == null) throw bad("interval은 1m/5m/15m/1h/6h/1d입니다.");
        long bucketCount = (window.seconds() + step - 1) / step;
        if (bucketCount > 1000) throw bad("차트는 최대 1000개 버킷입니다. interval을 늘려주세요.");
        requireConfigured(host);
        Map<Long, Map<ServiceLogLevel, Long>> counts = new HashMap<>();
        long full = window.seconds() / step;
        if (!paths.isEmpty() && full > 0) {
            List<Point> points = loki.range(count(selector(host, paths, null), step, "level"),
                    window.from().plusSeconds(step), window.from().plusSeconds(full * step), step);
            for (Point point : points) {
                long start = point.time().getEpochSecond() - step;
                Map<ServiceLogLevel, Long> levels = counts.computeIfAbsent(start, ignored -> new EnumMap<>(ServiceLogLevel.class));
                levels.merge(ServiceLogLevel.normalize(point.labels().get("level")), point.count(), Long::sum);
            }
        }
        long remainder = window.seconds() % step;
        if (!paths.isEmpty() && remainder > 0) {
            counts.put(window.from().plusSeconds(full * step).getEpochSecond(),
                    byLevel(loki.instant(count(selector(host, paths, null), remainder, "level"), window.to())));
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

    public ServiceLogListResponse search(ScopedHost host, List<String> paths, List<ServiceLogLevel> levels,
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
                        ServiceLogLevel.normalize(line.labels().get("level")), line.labels().get("filename"), line.message())).toList();
        return new ServiceLogListResponse(total, entries);
    }

    static String selector(ScopedHost host, List<String> paths, List<ServiceLogLevel> levels) {
        if (paths.isEmpty()) throw bad("조회 경로가 필요합니다.");
        String base = nodeSelector(host);
        StringBuilder result = new StringBuilder(base.substring(0, base.length() - 1));
        if (paths.size() == 1) result.append(", filename=").append(quote(paths.get(0)));
        else result.append(", filename=~").append(quote(paths.stream().map(NodeLogQueries::regexLiteral).collect(Collectors.joining("|", "(", ")"))));
        if (levels != null && !levels.isEmpty()) {
            Set<ServiceLogLevel> selected = EnumSet.copyOf(levels);
            boolean unknown = selected.contains(ServiceLogLevel.UNKNOWN);
            List<String> patterns = new ArrayList<>();
            for (ServiceLogLevel level : List.of(ServiceLogLevel.INFO, ServiceLogLevel.WARNING, ServiceLogLevel.ERROR, ServiceLogLevel.DEBUG)) {
                if (selected.contains(level) != unknown) patterns.add(level == ServiceLogLevel.WARNING ? "warn|warning" : level.name().toLowerCase(Locale.ROOT));
            }
            if (!patterns.isEmpty()) result.append(unknown ? ", level!~" : ", level=~")
                    .append(quote("(?i:" + String.join("|", patterns) + ")"));
        }
        return result.append('}').toString();
    }
    private static String nodeSelector(ScopedHost host) {
        requireConfigured(host);
        return "{instance=" + quote(host.instance()) + ", mdc_id=" + quote(host.mdcId()) + "}";
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
        for (Point point : points) result.merge(ServiceLogLevel.normalize(point.labels().get("level")), point.count(), Long::sum);
        return result;
    }
    private static BusinessException bad(String message) { return new BusinessException(HttpStatus.BAD_REQUEST, message); }
}
