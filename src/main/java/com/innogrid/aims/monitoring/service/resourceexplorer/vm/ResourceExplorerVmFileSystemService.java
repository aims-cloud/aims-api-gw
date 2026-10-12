package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.util.Comparator;

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
 * 자원 탐색기 VM File system 카테고리 패널 조회 서비스.
 * 그룹(마운트 전체 집계)과 개별 마운트의 개요·모니터링 탭 데이터를 함께 제공한다.
 *
 * VM 은 nsId·infraId·vmId 로 식별하고(스코프 밖·부재는 404), 지표는 VM 안 node_exporter 의 node_filesystem_* 를
 * {@code tb_ns/tb_infra/tb_node} 라벨로 좁혀 읽는다({@link MetricSelector#tumblebugNode}). VM 은 exporter 원장이 없어
 * 항상 Thanos 를 조회하고 표본이 없으면 미수집(0·빈 목록·빈 시리즈)이다. 표본 없는 마운트의 개요·정보는 404 다.
 * 응답 DTO·조립 규칙은 노드 File system 패널과 같다({@link FileSystemPanelAssembler}) — 프론트가 패널 컴포넌트를 공유한다.
 */
@Service
public class ResourceExplorerVmFileSystemService {

    private final TumblebugPanelSupport support;
    private final HostFileSystemMetricsQueryService metrics;

    public ResourceExplorerVmFileSystemService(TumblebugPanelSupport support, HostFileSystemMetricsQueryService metrics) {
        this.support = support;
        this.metrics = metrics;
    }

    // ── 그룹 (트리에서 file system 카테고리 선택) ───────────────

    /** 그룹 개요 KPI 카드 8종 — Total Mounts / Near Full / Max Used(%·GB) / Total Size·Used·Free / Max Inode */
    public FileSystemGroupSummaryResponse getGroupSummary(String roleName, String nsId, String infraId, String vmId) {
        return FileSystemPanelAssembler.groupSummary(metrics.mounts(selector(roleName, nsId, infraId, vmId)));
    }

    /** 그룹 개요 File System 목록 — 마운트별 용량·사용률, 페이징(1부터)·컬럼 정렬 */
    public PageResponse<FileSystemMountListItemResponse> getMounts(String roleName, String nsId, String infraId, String vmId,
                                                                   int page, int size, String sort) {
        Comparator<FileSystemMountListItemResponse> comparator = FileSystemPanelAssembler.SORT.comparator(sort);   // 미지원 필드는 조회 전에 400
        return FileSystemPanelAssembler.mountPage(metrics.mounts(selector(roleName, nsId, infraId, vmId)), comparator, page, size);
    }

    /** 그룹 모니터링 차트 — 마운트 전체 합산 시계열 */
    public FileSystemMetricTrendResponse getGroupMetricTrend(String roleName, String nsId, String infraId, String vmId,
                                                             FileSystemMetric metric, NodeMetricInterval interval) {
        MetricSelector sel = selector(roleName, nsId, infraId, vmId);
        return FileSystemPanelAssembler.trend(metric, interval, (from, to, step) -> metrics.groupTrend(metric, sel, from, to, step));
    }

    // ── 개별 마운트 (트리에서 마운트 경로 선택) ─────────────────

    /** 개별 마운트 개요 KPI 카드 6종 — Total / Used % / Used / Free / Growth(24h) / Inode. 표본 없는 마운트는 404 */
    public FileSystemMountSummaryResponse getMountSummary(String roleName, String nsId, String infraId, String vmId, String mountPath) {
        MetricSelector sel = selector(roleName, nsId, infraId, vmId);
        MountUsage mount = requireMount(sel, mountPath);
        return FileSystemPanelAssembler.mountSummary(mount, metrics.growth24hGb(sel, mountPath));
    }

    /** 개별 마운트 정보 표 — 마운트 경로 / 종류 / 수집 상태 / 마지막 수집. 표본 없는 마운트는 404 */
    public FileSystemMountInfoResponse getMountInfo(String roleName, String nsId, String infraId, String vmId, String mountPath) {
        return FileSystemPanelAssembler.mountInfo(requireMount(selector(roleName, nsId, infraId, vmId), mountPath));
    }

    /** 개별 마운트 모니터링 차트 — 해당 마운트 시계열. 미수집이면 빈 시리즈 */
    public FileSystemMetricTrendResponse getMountMetricTrend(String roleName, String nsId, String infraId, String vmId,
                                                             String mountPath, FileSystemMetric metric, NodeMetricInterval interval) {
        MetricSelector sel = selector(roleName, nsId, infraId, vmId);
        return FileSystemPanelAssembler.trend(metric, interval, (from, to, step) ->
                metrics.mountTrend(metric, sel, mountPath, from, to, step));
    }

    // ── 조회 대상 ───────────────────────────────────────────────

    /** 스코프·존재 검증(404) 뒤 tb_* 라벨 선택자 */
    private MetricSelector selector(String roleName, String nsId, String infraId, String vmId) {
        support.accessibleNode(roleName, nsId, infraId, vmId);
        return MetricSelector.tumblebugNode(nsId, infraId, vmId);
    }

    /** 표본이 있는 마운트만 개별 패널을 연다 — Thanos 에 mountpoint 가 없으면 404 */
    private MountUsage requireMount(MetricSelector sel, String mountPath) {
        return metrics.mount(sel, mountPath).orElseThrow(() -> FileSystemPanelAssembler.mountNotFound(mountPath));
    }
}
