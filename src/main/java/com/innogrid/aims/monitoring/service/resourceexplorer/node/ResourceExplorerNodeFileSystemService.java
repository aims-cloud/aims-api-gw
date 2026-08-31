package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemGroupSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMountInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMountListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMountSummaryResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 자원 탐색기 노드 File system 카테고리 패널 조회 서비스.
 * 그룹(마운트 전체 집계)과 개별 마운트의 개요·모니터링 탭 데이터를 함께 제공한다.
 * 노드 상세 패널은 ResourceExplorerNodeService 가 담당한다.
 */
@Service
public class ResourceExplorerNodeFileSystemService {

    // ── 그룹 (트리에서 file system 카테고리 선택) ───────────────

    /** 그룹 개요 KPI 카드 8종 — Total Mounts / Near Full / Max Used(%·GB, 대상 마운트 포함) / Total Size·Used·Free / Max Inode */
    public FileSystemGroupSummaryResponse getGroupSummary(String nodeId) {
        // TODO:: Thanos(node_filesystem_size·avail·files 계열) 마운트 전체 집계로 카드 구성
        // TODO:: Near Full 판정 임계치(예: 사용률 90% 이상) 확정 필요
        throw dataSourceNotImplemented();
    }

    /** 그룹 개요 File System 목록 — 마운트별 용량·사용률, 페이징(1부터)·컬럼 정렬 */
    public PageResponse<FileSystemMountListItemResponse> getMounts(String nodeId,
                                                                   int page, int size, String sort) {
        // TODO:: node_filesystem_* 레이블(mountpoint, fstype) 기준 마운트 목록 구성
        throw dataSourceNotImplemented();
    }

    /** 그룹 모니터링 차트 — 마운트 전체 합산 시계열 */
    public FileSystemMetricTrendResponse getGroupMetricTrend(String nodeId, FileSystemMetric metric,
                                                             Instant from, Instant to, String step) {
        // TODO:: 모니터링 탭 화면 설계 확정 후 지표 세트 조정, Thanos range 쿼리 구현
        throw dataSourceNotImplemented();
    }

    // ── 개별 마운트 (트리에서 마운트 경로 선택) ─────────────────

    /** 개별 마운트 개요 KPI 카드 6종 — Total / Used % / Used / Free / Growth(24h) / Inode */
    public FileSystemMountSummaryResponse getMountSummary(String nodeId, String mountPath) {
        // TODO:: Growth(24h) 는 delta(node_filesystem 사용량[24h]) 로 계산
        throw dataSourceNotImplemented();
    }

    /** 개별 마운트 정보 표 — 마운트 경로 / 종류 / 수집 상태 / 마지막 수집 */
    public FileSystemMountInfoResponse getMountInfo(String nodeId, String mountPath) {
        // TODO:: fstype 레이블과 수집 메타(수집 상태/마지막 수집)를 병합해 구성
        throw dataSourceNotImplemented();
    }

    /** 개별 마운트 모니터링 차트 — 해당 마운트 시계열 */
    public FileSystemMetricTrendResponse getMountMetricTrend(String nodeId, String mountPath,
                                                             FileSystemMetric metric,
                                                             Instant from, Instant to, String step) {
        // TODO:: 모니터링 탭 화면 설계 확정 후 지표 세트 조정, mountpoint 레이블 필터로 구현
        throw dataSourceNotImplemented();
    }

    private ResponseStatusException dataSourceNotImplemented() {
        return new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "File system 지표 데이터와 자원 탐색기 File system 패널의 매핑 구현 전입니다.");
    }
}
