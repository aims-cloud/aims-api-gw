package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemGroupSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMetricTrendResponse.Series;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMountInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMountListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMountSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostFileSystemMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostFileSystemMetricsQueryService.MountUsage;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 노드 File system 카테고리 패널 조회 서비스.
 * 그룹(마운트 전체 집계)과 개별 마운트의 개요·모니터링 탭 데이터를 함께 제공한다.
 *
 * nodeId 는 assets.id 이며 역할 스코프 밖이면 404({@link NodeAccessService}).
 * 지표는 노드 node_exporter 의 node_filesystem_* 를 {@code instance} 라벨로 좁혀 읽는다(tmpfs·overlay 제외).
 * node_exporter 타겟이 없는 노드는 Thanos 를 조회하지 않고 미수집(0·빈 목록·빈 시리즈)으로 내린다.
 * 개별 마운트는 mountPath 로 식별하며 표본이 없는 마운트의 개요·정보는 404 다.
 * 노드 상세 패널은 ResourceExplorerNodeService 가 담당한다.
 */
@Service
public class ResourceExplorerNodeFileSystemService {

    /** Near Full 판정 — 마운트 사용률이 이 값 이상이면 임박으로 센다 */
    static final double NEAR_FULL_PERCENT = 90.0;
    private static final double GB = 1024.0 * 1024 * 1024;

    private final NodeAccessService nodeAccess;
    private final HostFileSystemMetricsQueryService metrics;

    public ResourceExplorerNodeFileSystemService(NodeAccessService nodeAccess, HostFileSystemMetricsQueryService metrics) {
        this.nodeAccess = nodeAccess;
        this.metrics = metrics;
    }

    // ── 그룹 (트리에서 file system 카테고리 선택) ───────────────

    /** 그룹 개요 KPI 카드 8종 — Total Mounts / Near Full / Max Used(%·GB, 대상 마운트 포함) / Total Size·Used·Free / Max Inode */
    public FileSystemGroupSummaryResponse getGroupSummary(String nodeId) {
        MetricSelector selector = selector(nodeId);
        List<MountUsage> mounts = selector == null ? List.of() : metrics.mounts(selector);

        int nearFull = 0;
        double totalSize = 0, totalUsed = 0, totalFree = 0;
        MountUsage maxUsedPercent = null, maxUsedBytes = null;
        Double maxInode = null;
        for (MountUsage mount : mounts) {
            if (mount.usedPercent() >= NEAR_FULL_PERCENT) {
                nearFull++;
            }
            totalSize += mount.sizeBytes();
            totalUsed += mount.usedBytes();
            totalFree += mount.availBytes();
            if (maxUsedPercent == null || mount.usedPercent() > maxUsedPercent.usedPercent()) {
                maxUsedPercent = mount;
            }
            if (maxUsedBytes == null || mount.usedBytes() > maxUsedBytes.usedBytes()) {
                maxUsedBytes = mount;
            }
            Double inode = mount.inodeUsagePercent();
            if (inode != null && (maxInode == null || inode > maxInode)) {
                maxInode = inode;
            }
        }
        return new FileSystemGroupSummaryResponse(
                mounts.size(),
                nearFull,
                maxUsedPercent == null ? 0.0 : round1(maxUsedPercent.usedPercent()),
                maxUsedPercent == null ? null : maxUsedPercent.mountPath(),
                maxUsedBytes == null ? 0.0 : gb(maxUsedBytes.usedBytes()),
                maxUsedBytes == null ? null : maxUsedBytes.mountPath(),
                gb(totalSize),
                gb(totalUsed),
                gb(totalFree),
                round1(maxInode));
    }

    /** 그룹 개요 File System 목록 — 마운트별 용량·사용률, 페이징(1부터)·컬럼 정렬 */
    public PageResponse<FileSystemMountListItemResponse> getMounts(String nodeId, int page, int size, String sort) {
        Comparator<FileSystemMountListItemResponse> comparator = comparator(sort);   // 미지원 필드는 조회 전에 400
        MetricSelector selector = selector(nodeId);
        if (selector == null) {
            return PageResponse.of(List.of(), page, size);
        }
        List<FileSystemMountListItemResponse> rows = metrics.mounts(selector).stream()
                .map(ResourceExplorerNodeFileSystemService::row)
                .sorted(comparator)
                .toList();
        return PageResponse.of(rows, page, size);
    }

    /** 그룹 모니터링 차트 — 마운트 전체 합산 시계열. 조회 구간은 지금 기준 interval 기간, 버킷 간격은 기간별 defaultStep */
    public FileSystemMetricTrendResponse getGroupMetricTrend(String nodeId, FileSystemMetric metric, NodeMetricInterval interval) {
        MetricSelector selector = selector(nodeId);
        return trend(metric, interval, (from, to, step) ->
                selector == null ? List.of() : metrics.groupTrend(metric, selector, from, to, step));
    }

    // ── 개별 마운트 (트리에서 마운트 경로 선택) ─────────────────

    /** 개별 마운트 개요 KPI 카드 6종 — Total / Used % / Used / Free / Growth(24h) / Inode. 표본 없는 마운트는 404 */
    public FileSystemMountSummaryResponse getMountSummary(String nodeId, String mountPath) {
        MetricSelector selector = selector(nodeId);
        MountUsage mount = requireMount(selector, mountPath);
        Double growth = metrics.growth24hGb(selector, mountPath);
        return new FileSystemMountSummaryResponse(
                gb(mount.sizeBytes()),
                round1(mount.usedPercent()),
                gb(mount.usedBytes()),
                gb(mount.availBytes()),
                round1(growth),
                round1(mount.inodeUsagePercent()));
    }

    /** 개별 마운트 정보 표 — 마운트 경로 / 종류 / 수집 상태 / 마지막 수집. 표본 없는 마운트는 404 */
    public FileSystemMountInfoResponse getMountInfo(String nodeId, String mountPath) {
        MountUsage mount = requireMount(selector(nodeId), mountPath);
        return new FileSystemMountInfoResponse(
                mount.mountPath(),
                mount.fsType(),
                MonitoringStatus.COLLECTING.name(),
                toOffsetDateTime(mount.collectedAt()));
    }

    /** 개별 마운트 모니터링 차트 — 해당 마운트 시계열. 미수집이면 빈 시리즈 */
    public FileSystemMetricTrendResponse getMountMetricTrend(String nodeId, String mountPath,
                                                             FileSystemMetric metric, NodeMetricInterval interval) {
        MetricSelector selector = selector(nodeId);
        return trend(metric, interval, (from, to, step) ->
                selector == null ? List.of() : metrics.mountTrend(metric, selector, mountPath, from, to, step));
    }

    // ── 조회 대상 ───────────────────────────────────────────────

    /** 스코프 검증(404) 뒤 node_exporter 선택자. exporter 타겟이 없으면 null — 호출 측은 조회 없이 미수집으로 처리한다 */
    private MetricSelector selector(String nodeId) {
        String instance = nodeAccess.require(nodeId).host().instance();
        return instance == null ? null : MetricSelector.instance(instance);
    }

    /** 표본이 있는 마운트만 개별 패널을 연다 — exporter 가 없거나 Thanos 에 mountpoint 가 없으면 404 */
    private MountUsage requireMount(MetricSelector selector, String mountPath) {
        Optional<MountUsage> mount = selector == null ? Optional.empty() : metrics.mount(selector, mountPath);
        return mount.orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "마운트를 찾을 수 없습니다: " + mountPath));
    }

    // ── 행·시계열 구성 ──────────────────────────────────────────

    @FunctionalInterface
    private interface TrendQuery {
        List<DataPoint> points(Instant from, Instant to, String step);
    }

    private static FileSystemMetricTrendResponse trend(FileSystemMetric metric, NodeMetricInterval interval, TrendQuery query) {
        Instant to = Instant.now();
        Instant from = to.minus(interval.window());
        String step = interval.defaultStep();
        List<DataPoint> points = query.points(from, to, step);
        return new FileSystemMetricTrendResponse(metric, metric.unit(), from.getEpochSecond(), to.getEpochSecond(), step,
                points.isEmpty() ? List.of() : List.of(new Series("file_system", points)));
    }

    static FileSystemMountListItemResponse row(MountUsage mount) {
        return new FileSystemMountListItemResponse(
                mount.mountPath(),
                mount.fsType(),
                gb(mount.sizeBytes()),
                round1(mount.usedPercent()),
                gb(mount.usedBytes()),
                gb(mount.availBytes()),
                round1(mount.inodeUsagePercent()));
    }

    /** sort 파라미터("{필드},{asc|desc}") — mountPath|fsType|totalGb|usedPercent|usedGb|freeGb|inodeUsagePercent, 동률·값 없음은 경로순 */
    private static Comparator<FileSystemMountListItemResponse> comparator(String sort) {
        String[] parts = sort.split(",", 2);
        String field = parts[0].trim();
        boolean desc = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim());

        Comparator<FileSystemMountListItemResponse> comparator = switch (field) {
            case "mountPath" -> Comparator.comparing(FileSystemMountListItemResponse::mountPath);
            case "fsType" -> Comparator.comparing(FileSystemMountListItemResponse::fsType,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "totalGb" -> Comparator.comparingDouble(FileSystemMountListItemResponse::totalGb);
            case "usedPercent" -> Comparator.comparingDouble(FileSystemMountListItemResponse::usedPercent);
            case "usedGb" -> Comparator.comparingDouble(FileSystemMountListItemResponse::usedGb);
            case "freeGb" -> Comparator.comparingDouble(FileSystemMountListItemResponse::freeGb);
            case "inodeUsagePercent" -> nullsLast(FileSystemMountListItemResponse::inodeUsagePercent, desc);
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "지원하지 않는 정렬 필드입니다: " + field);
        };
        Comparator<FileSystemMountListItemResponse> stable = comparator.thenComparing(FileSystemMountListItemResponse::mountPath);
        return desc ? stable.reversed() : stable;
    }

    /** 값 없음(null)이 오름/내림 어느 방향에서도 마지막에 오는 비교자 — desc 는 바깥에서 reversed() 되므로 미리 뒤집어 상쇄한다 */
    private static <R, T extends Comparable<T>> Comparator<R> nullsLast(Function<R, T> extractor, boolean desc) {
        Comparator<T> nullHandling = desc
                ? Comparator.nullsFirst(Comparator.naturalOrder())
                : Comparator.nullsLast(Comparator.naturalOrder());
        return Comparator.comparing(extractor, nullHandling);
    }

    private static double gb(double bytes) {
        return Math.round(bytes / GB * 10) / 10.0;
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static Double round1(Double value) {
        return value == null ? null : round1(value.doubleValue());
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
