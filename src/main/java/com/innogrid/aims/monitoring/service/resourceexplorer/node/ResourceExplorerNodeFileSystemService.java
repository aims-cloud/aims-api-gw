package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemGroupSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMountInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMountListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMountSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.FileSystemPanelAssembler;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostFileSystemMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostFileSystemMetricsQueryService.MountUsage;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 노드 File system 카테고리 패널 조회 서비스.
 * 그룹(마운트 전체 집계)과 개별 마운트의 개요·모니터링 탭 데이터를 함께 제공한다.
 *
 * nodeId 는 assets.id 이며 역할 스코프 밖이면 404({@link NodeAccessService}).
 * 지표는 노드 node_exporter 의 node_filesystem_* 를 {@code instance} 라벨로 좁혀 읽는다(tmpfs·overlay 제외).
 * node_exporter 타겟이 없는 노드는 Thanos 를 조회하지 않고 미수집(0·빈 목록·빈 시리즈)으로 내린다.
 * 개별 마운트는 mountPath 로 식별하며 표본이 없는 마운트의 개요·정보는 404 다.
 * 응답 조립 규칙은 VM File system 패널과 공유한다({@link FileSystemPanelAssembler}).
 * 노드 상세 패널은 ResourceExplorerNodeService 가 담당한다.
 */
@Service
public class ResourceExplorerNodeFileSystemService {

    /** Near Full 판정 기준 — 조립기와 같은 값 */
    static final double NEAR_FULL_PERCENT = FileSystemPanelAssembler.NEAR_FULL_PERCENT;

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
        return FileSystemPanelAssembler.groupSummary(selector == null ? List.of() : metrics.mounts(selector));
    }

    /** 그룹 개요 File System 목록 — 마운트별 용량·사용률, 페이징(1부터)·컬럼 정렬 */
    public PageResponse<FileSystemMountListItemResponse> getMounts(String nodeId, int page, int size, String sort) {
        Comparator<FileSystemMountListItemResponse> comparator = FileSystemPanelAssembler.comparator(sort);   // 미지원 필드는 조회 전에 400
        MetricSelector selector = selector(nodeId);
        List<MountUsage> mounts = selector == null ? List.of() : metrics.mounts(selector);
        return FileSystemPanelAssembler.mountPage(mounts, comparator, page, size);
    }

    /** 그룹 모니터링 차트 — 마운트 전체 합산 시계열 */
    public FileSystemMetricTrendResponse getGroupMetricTrend(String nodeId, FileSystemMetric metric, NodeMetricInterval interval) {
        MetricSelector selector = selector(nodeId);
        return FileSystemPanelAssembler.trend(metric, interval, (from, to, step) ->
                selector == null ? List.of() : metrics.groupTrend(metric, selector, from, to, step));
    }

    // ── 개별 마운트 (트리에서 마운트 경로 선택) ─────────────────

    /** 개별 마운트 개요 KPI 카드 6종 — Total / Used % / Used / Free / Growth(24h) / Inode. 표본 없는 마운트는 404 */
    public FileSystemMountSummaryResponse getMountSummary(String nodeId, String mountPath) {
        MetricSelector selector = selector(nodeId);
        MountUsage mount = requireMount(selector, mountPath);
        return FileSystemPanelAssembler.mountSummary(mount, metrics.growth24hGb(selector, mountPath));
    }

    /** 개별 마운트 정보 표 — 마운트 경로 / 종류 / 수집 상태 / 마지막 수집. 표본 없는 마운트는 404 */
    public FileSystemMountInfoResponse getMountInfo(String nodeId, String mountPath) {
        return FileSystemPanelAssembler.mountInfo(requireMount(selector(nodeId), mountPath));
    }

    /** 개별 마운트 모니터링 차트 — 해당 마운트 시계열. 미수집이면 빈 시리즈 */
    public FileSystemMetricTrendResponse getMountMetricTrend(String nodeId, String mountPath,
                                                             FileSystemMetric metric, NodeMetricInterval interval) {
        MetricSelector selector = selector(nodeId);
        return FileSystemPanelAssembler.trend(metric, interval, (from, to, step) ->
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
        return mount.orElseThrow(() -> FileSystemPanelAssembler.mountNotFound(mountPath));
    }
}
