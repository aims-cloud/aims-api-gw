package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
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
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostFileSystemMetricsQueryService.MountUsage;

/**
 * File system 카테고리 패널 응답 조립 — 노드·VM 패널 서비스가 공유하는 순수 계산 부분.
 *
 * Thanos 표본({@link MountUsage})을 KPI 카드·목록 행·정보 표·시계열 응답으로 바꾸는 규칙만 담고,
 * 자원 식별(노드 assets.id / VM nsId·infraId·vmId)과 스코프 검증은 각 패널 서비스가 맡는다.
 * 노드와 VM 이 같은 응답 DTO 를 쓰므로 프론트는 패널 컴포넌트를 공유할 수 있다.
 */
public final class FileSystemPanelAssembler {

    /** Near Full 판정 — 마운트 사용률이 이 값 이상이면 임박으로 센다 */
    public static final double NEAR_FULL_PERCENT = 90.0;
    private static final double GB = 1024.0 * 1024 * 1024;

    private FileSystemPanelAssembler() {
    }

    /** 시계열 조회 — from/to/step 을 받아 DataPoint 목록을 돌려준다(미수집은 빈 목록) */
    @FunctionalInterface
    public interface TrendQuery {
        List<DataPoint> points(Instant from, Instant to, String step);
    }

    // ── 그룹 ────────────────────────────────────────────────────

    /** 그룹 개요 KPI 카드 8종 — Total Mounts / Near Full / Max Used(%·GB, 대상 마운트 포함) / Total Size·Used·Free / Max Inode */
    public static FileSystemGroupSummaryResponse groupSummary(List<MountUsage> mounts) {
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

    /** 그룹 개요 File System 목록 — 마운트별 용량·사용률을 정렬해 페이징(1부터)한다 */
    public static PageResponse<FileSystemMountListItemResponse> mountPage(List<MountUsage> mounts,
                                                                          Comparator<FileSystemMountListItemResponse> comparator,
                                                                          int page, int size) {
        List<FileSystemMountListItemResponse> rows = mounts.stream()
                .map(FileSystemPanelAssembler::row)
                .sorted(comparator)
                .toList();
        return PageResponse.of(rows, page, size);
    }

    // ── 개별 마운트 ─────────────────────────────────────────────

    /** 개별 마운트 개요 KPI 카드 6종 — Total / Used % / Used / Free / Growth(24h) / Inode */
    public static FileSystemMountSummaryResponse mountSummary(MountUsage mount, Double growth24hGb) {
        return new FileSystemMountSummaryResponse(
                gb(mount.sizeBytes()),
                round1(mount.usedPercent()),
                gb(mount.usedBytes()),
                gb(mount.availBytes()),
                round1(growth24hGb),
                round1(mount.inodeUsagePercent()));
    }

    /** 개별 마운트 정보 표 — 마운트 경로 / 종류 / 수집 상태 / 마지막 수집. 표본이 있는 마운트만 오므로 COLLECTING */
    public static FileSystemMountInfoResponse mountInfo(MountUsage mount) {
        return new FileSystemMountInfoResponse(
                mount.mountPath(),
                mount.fsType(),
                MonitoringStatus.COLLECTING.name(),
                toOffsetDateTime(mount.collectedAt()));
    }

    /** 표본이 없는 마운트의 개요·정보 요청에 대한 404 */
    public static BusinessException mountNotFound(String mountPath) {
        return BusinessException.notFound("마운트를 찾을 수 없습니다: " + mountPath);
    }

    // ── 시계열 ──────────────────────────────────────────────────

    /** 모니터링 차트 — 조회 구간은 지금 기준 interval 기간, 버킷 간격은 기간별 defaultStep. 미수집이면 빈 시리즈 */
    public static FileSystemMetricTrendResponse trend(FileSystemMetric metric, NodeMetricInterval interval, TrendQuery query) {
        Instant to = Instant.now();
        Instant from = to.minus(interval.window());
        String step = interval.defaultStep();
        List<DataPoint> points = query.points(from, to, step);
        return new FileSystemMetricTrendResponse(metric, metric.unit(), from.getEpochSecond(), to.getEpochSecond(), step,
                points.isEmpty() ? List.of() : List.of(new Series("file_system", points)));
    }

    // ── 행·정렬 ─────────────────────────────────────────────────

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

    /**
     * sort 파라미터("{필드},{asc|desc}") — mountPath|fsType|totalGb|usedPercent|usedGb|freeGb|inodeUsagePercent,
     * 동률·값 없음은 경로순. 미지원 필드는 400 — 호출 측은 Thanos 조회 전에 먼저 부른다.
     */
    public static Comparator<FileSystemMountListItemResponse> comparator(String sort) {
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
            default -> throw BusinessException.badRequest("지원하지 않는 정렬 필드입니다: " + field);
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

    // ── 단위 ────────────────────────────────────────────────────

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
