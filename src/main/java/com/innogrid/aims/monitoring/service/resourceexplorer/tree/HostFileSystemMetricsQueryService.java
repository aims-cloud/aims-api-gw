package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 자원 1개의 File system 카테고리 패널 지표를 Thanos 에서 읽는다 — 마운트 목록·그룹/마운트 집계·시계열 전용.
 *
 * 전부 node_exporter filesystem 콜렉터 지표({@code node_filesystem_size_bytes / avail_bytes / files / files_free})이며
 * 상세 패널과 같은 필터({@link HostDetailMetricsQueryService#FS_FILTER}, tmpfs·overlay 제외)를 쓴다.
 * Used 는 size - avail(예약 블록 제외), Free 는 avail 이라 Used + Free = Total 이 성립한다.
 * 자원은 {@link MetricSelector} 로 가리키고, Thanos 장애나 시계열 부재는 빈 목록/null(미수집)로 돌려준다.
 */
@Component
public class HostFileSystemMetricsQueryService {

    private static final Logger log = LoggerFactory.getLogger(HostFileSystemMetricsQueryService.class);
    private static final double GB = 1024.0 * 1024 * 1024;
    private static final String FS_FILTER = HostDetailMetricsQueryService.FS_FILTER;
    private static final String GROWTH_WINDOW = "24h";

    /**
     * 마운트 1개의 현재 용량 표본 — 바이트 단위 원값. inode 계열은 파일시스템이 보고하지 않으면 null.
     * collectedAt 은 size 표본의 시각이다.
     */
    public record MountUsage(String mountPath, String fsType, String device,
                             double sizeBytes, double availBytes,
                             Double filesTotal, Double filesFree,
                             Instant collectedAt) {

        public double usedBytes() {
            return Math.max(0.0, sizeBytes - availBytes);
        }

        public double usedPercent() {
            return sizeBytes <= 0 ? 0.0 : usedBytes() / sizeBytes * 100;
        }

        /** inode 사용률(%) — files 가 없거나 0(xfs·btrfs 등 미보고)이면 null */
        public Double inodeUsagePercent() {
            if (filesTotal == null || filesFree == null || filesTotal <= 0) {
                return null;
            }
            return (1 - filesFree / filesTotal) * 100;
        }
    }

    private final VictoriaMetricsClient client;

    public HostFileSystemMetricsQueryService(VictoriaMetricsClient client) {
        this.client = client;
    }

    // ── PromQL ──────────────────────────────────────────────────

    static String mountMatchers(String mountPath) {
        return FS_FILTER + "," + MetricSelector.label("mountpoint", mountPath);
    }

    /** 마운트 전체 합산 지표 — 그룹 모니터링 차트 */
    static String groupQuery(FileSystemMetric metric, MetricSelector s) {
        String size = "sum by (instance) (node_filesystem_size_bytes" + s.braces(FS_FILTER) + ")";
        String avail = "sum by (instance) (node_filesystem_avail_bytes" + s.braces(FS_FILTER) + ")";
        String files = "sum by (instance) (node_filesystem_files" + s.braces(FS_FILTER) + ")";
        String filesFree = "sum by (instance) (node_filesystem_files_free" + s.braces(FS_FILTER) + ")";
        return query(metric, size, avail, files, filesFree);
    }

    /** 마운트 1개 지표 — 개별 마운트 모니터링 차트 */
    static String mountQuery(FileSystemMetric metric, MetricSelector s, String mountPath) {
        String matchers = mountMatchers(mountPath);
        String size = "node_filesystem_size_bytes" + s.braces(matchers);
        String avail = "node_filesystem_avail_bytes" + s.braces(matchers);
        String files = "node_filesystem_files" + s.braces(matchers);
        String filesFree = "node_filesystem_files_free" + s.braces(matchers);
        return query(metric, size, avail, files, filesFree);
    }

    /** 최근 24시간 사용량 증가분(GB) — size 는 상수이므로 avail 감소분이 사용량 증가분이다. 감소 시 음수 */
    static String growthGbQuery(MetricSelector s, String mountPath) {
        return "-delta(node_filesystem_avail_bytes" + s.braces(mountMatchers(mountPath)) + "[" + GROWTH_WINDOW + "]) / " + (long) GB;
    }

    private static String query(FileSystemMetric metric, String size, String avail, String files, String filesFree) {
        return switch (metric) {
            case FS_USED -> "(" + size + " - " + avail + ") / " + (long) GB;
            case FS_USED_PERCENT -> "(1 - " + avail + " / " + size + ") * 100";
            case FS_FREE -> avail + " / " + (long) GB;
            case FS_FREE_PERCENT -> avail + " / " + size + " * 100";
            case FS_TOTAL -> size + " / " + (long) GB;
            case FS_INODE_USAGE_PERCENT -> "(1 - " + filesFree + " / " + files + ") * 100";
        };
    }

    // ── 조회 ────────────────────────────────────────────────────

    /** 자원의 마운트 전체 현재 표본 — mountpoint 기준으로 size/avail/files/files_free 를 합친다. 장애·부재는 빈 목록 */
    public List<MountUsage> mounts(MetricSelector s) {
        return collect(s.braces(FS_FILTER));
    }

    /** 마운트 1개의 현재 표본 — 없으면 empty */
    public Optional<MountUsage> mount(MetricSelector s, String mountPath) {
        return collect(s.braces(mountMatchers(mountPath))).stream()
                .filter(m -> mountPath.equals(m.mountPath()))
                .findFirst();
    }

    /** 최근 24시간 사용량 증가분(GB) — 미수집은 null */
    public Double growth24hGb(MetricSelector s, String mountPath) {
        try {
            return firstValue(client.queryResults(growthGbQuery(s, mountPath)));
        } catch (RuntimeException e) {
            log.warn("Thanos 마운트 증가분 조회 실패 — 미수집으로 표시합니다: {}", e.getMessage());
            return null;
        }
    }

    /** 그룹(마운트 전체 합산) 시계열 — 미수집이면 빈 목록 */
    public List<DataPoint> groupTrend(FileSystemMetric metric, MetricSelector s, Instant from, Instant to, String step) {
        return points(groupQuery(metric, s), from, to, step);
    }

    /** 개별 마운트 시계열 — 미수집이면 빈 목록 */
    public List<DataPoint> mountTrend(FileSystemMetric metric, MetricSelector s, String mountPath,
                                      Instant from, Instant to, String step) {
        return points(mountQuery(metric, s, mountPath), from, to, step);
    }

    // ── 내부 ────────────────────────────────────────────────────

    /** size 표본이 있는 mountpoint 만 행으로 만들고 avail/files/files_free 를 같은 mountpoint 에 붙인다 */
    private List<MountUsage> collect(String braces) {
        try {
            Map<String, Map<String, Object>> size = byMount(client.queryResults("node_filesystem_size_bytes" + braces));
            if (size.isEmpty()) {
                return List.of();
            }
            Map<String, Double> avail = valuesByMount(client.queryResults("node_filesystem_avail_bytes" + braces));
            Map<String, Double> files = valuesByMount(client.queryResults("node_filesystem_files" + braces));
            Map<String, Double> filesFree = valuesByMount(client.queryResults("node_filesystem_files_free" + braces));

            List<MountUsage> rows = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> entry : size.entrySet()) {
                String mountPath = entry.getKey();
                Map<String, Object> result = entry.getValue();
                Double sizeBytes = value(result);
                Double availBytes = avail.get(mountPath);
                if (sizeBytes == null || availBytes == null) {
                    continue;                                       // 용량 표본이 짝이 안 맞으면 행을 만들지 않는다
                }
                rows.add(new MountUsage(mountPath, label(result, "fstype"), label(result, "device"),
                        sizeBytes, availBytes, files.get(mountPath), filesFree.get(mountPath), sampleTime(result)));
            }
            rows.sort(Comparator.comparing(MountUsage::mountPath));
            return rows;
        } catch (RuntimeException e) {
            log.warn("Thanos 마운트 표본 조회 실패 — 빈 목록을 돌려줍니다: {}", e.getMessage());
            return List.of();
        }
    }

    private static Map<String, Map<String, Object>> byMount(List<Map<String, Object>> results) {
        Map<String, Map<String, Object>> byMount = new LinkedHashMap<>();
        for (Map<String, Object> result : results) {
            String mountPath = label(result, "mountpoint");
            if (mountPath != null) {
                byMount.putIfAbsent(mountPath, result);
            }
        }
        return byMount;
    }

    private static Map<String, Double> valuesByMount(List<Map<String, Object>> results) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (Map<String, Object> result : results) {
            String mountPath = label(result, "mountpoint");
            Double value = value(result);
            if (mountPath != null && value != null) {
                values.putIfAbsent(mountPath, value);
            }
        }
        return values;
    }

    private List<DataPoint> points(String query, Instant from, Instant to, String step) {
        try {
            List<Map<String, Object>> results = client.queryRangeResults(query, from, to, step);
            if (results.isEmpty()) {
                return List.of();
            }
            List<DataPoint> points = new ArrayList<>();
            for (Object[] pair : HostDetailMetricsQueryService.values(results.get(0))) {
                points.add(new DataPoint(((Number) pair[0]).longValue(), HostDetailMetricsQueryService.round1((Double) pair[1])));
            }
            return points;
        } catch (RuntimeException e) {
            log.warn("Thanos File system 차트 조회 실패 — 빈 시계열을 돌려줍니다: {}", e.getMessage());
            return List.of();
        }
    }

    private static String label(Map<String, Object> result, String name) {
        Object metric = result.get("metric");
        if (!(metric instanceof Map<?, ?> labels)) {
            return null;
        }
        Object raw = labels.get(name);
        return raw == null || raw.toString().isBlank() ? null : raw.toString();
    }

    private static Double value(Map<String, Object> result) {
        Object value = result.get("value");
        if (value instanceof List<?> pair && pair.size() >= 2) {
            return HostDetailMetricsQueryService.parse(pair.get(1));
        }
        return null;
    }

    private static Double firstValue(List<Map<String, Object>> results) {
        return results.isEmpty() ? null : value(results.get(0));
    }

    private static Instant sampleTime(Map<String, Object> result) {
        Object value = result.get("value");
        if (value instanceof List<?> pair && !pair.isEmpty() && pair.get(0) instanceof Number ts) {
            return Instant.ofEpochSecond(ts.longValue());
        }
        return null;
    }
}
