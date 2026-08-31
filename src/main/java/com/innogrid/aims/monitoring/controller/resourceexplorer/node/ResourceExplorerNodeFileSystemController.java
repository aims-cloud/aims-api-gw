package com.innogrid.aims.monitoring.controller.resourceexplorer.node;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemGroupSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMountInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMountListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMountSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.node.ResourceExplorerNodeFileSystemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자원 탐색기 > 노드 File system 카테고리 패널 API.
 *
 * 그룹(트리에서 file system 카테고리 선택)과 개별 마운트(하위 마운트 경로 선택)의
 * 개요·모니터링 탭을 하나의 컨트롤러에서 제공한다.
 *   그룹 개요        — KPI 카드 8종(Near Full Count 포함), File system 목록 테이블
 *   개별 마운트 개요 — KPI 카드 6종(Growth 24h 포함), File System 정보 표
 *   모니터링         — 지표별 시계열 (화면 설계 확정 전, 잠정 지표 세트)
 * 마운트 경로에 '/' 가 포함되므로 개별 마운트는 mountPath 쿼리 파라미터로 식별한다.
 * 노드 상세 패널은 ResourceExplorerNodeController 가 담당한다.
 */
@Tag(name = "자원 탐색기 (노드 File system)",
        description = "노드 File system 카테고리 — 그룹/개별 마운트 KPI, 마운트 목록, 모니터링 차트")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/node/{nodeId}/file-system")
public class ResourceExplorerNodeFileSystemController {

    private final ResourceExplorerNodeFileSystemService resourceExplorerNodeFileSystemService;

    public ResourceExplorerNodeFileSystemController(
            ResourceExplorerNodeFileSystemService resourceExplorerNodeFileSystemService) {
        this.resourceExplorerNodeFileSystemService = resourceExplorerNodeFileSystemService;
    }

    // ── 그룹 (file system 카테고리 선택) ────────────────────────

    @Operation(summary = "File system 그룹 개요 KPI",
            description = "그룹 개요 탭 카드 8종 — Total Mounts, Near Full Count, Max Used(%)·Max Used(GB)"
                    + "(각각 대상 마운트 경로 포함), Total Size(GB), Total Used(GB), Total Free(GB), "
                    + "Max Inode Usage(%). 수집 불가 값은 null 로 내려갑니다.")
    @GetMapping("/summary")
    public ApiResponse<FileSystemGroupSummaryResponse> getGroupSummary(@PathVariable String nodeId) {
        return ApiResponse.ok(resourceExplorerNodeFileSystemService.getGroupSummary(nodeId));
    }

    @Operation(summary = "File system 목록",
            description = "그룹 개요 탭 목록 — 마운트 경로/타입/Total(GB)/Used(%)/Used(GB)/Free(GB)/Inode(%). "
                    + "컬럼 정렬과 페이징을 지원합니다. page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다.")
    @GetMapping("/mounts")
    public ApiResponse<PageResponse<FileSystemMountListItemResponse>> getMounts(
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "mountPath,asc") String sort) {
        return ApiResponse.ok(resourceExplorerNodeFileSystemService.getMounts(nodeId, page, size, sort));
    }

    @Operation(summary = "File system 그룹 모니터링 차트",
            description = "그룹 모니터링 탭 — metric(FS_USED|FS_USED_PERCENT|FS_FREE|FS_FREE_PERCENT|"
                    + "FS_TOTAL|FS_INODE_USAGE_PERCENT)별 마운트 전체 합산 시계열. "
                    + "화면 설계 확정 전으로 지표 세트가 변경될 수 있습니다. from/to 미지정 시 오늘 00시~현재입니다.")
    @GetMapping("/metrics/trend")
    public ApiResponse<FileSystemMetricTrendResponse> getGroupMetricTrend(
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "FS_USED") FileSystemMetric metric,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "300s") String step) {
        Instant toInstant = to != null ? Instant.parse(to) : Instant.now();
        Instant fromInstant = from != null ? Instant.parse(from) : todayStart();
        return ApiResponse.ok(resourceExplorerNodeFileSystemService.getGroupMetricTrend(
                nodeId, metric, fromInstant, toInstant, step));
    }

    // ── 개별 마운트 (마운트 경로 선택) ──────────────────────────

    @Operation(summary = "마운트 개요 KPI",
            description = "개별 마운트 개요 탭 카드 6종 — Total(GB), Used(%), Used(GB), Free(GB), "
                    + "Growth(24h, 감소 시 음수), Inode Usage(%). 수집 불가 값은 null 로 내려갑니다.")
    @GetMapping("/mount/summary")
    public ApiResponse<FileSystemMountSummaryResponse> getMountSummary(
            @PathVariable String nodeId,
            @RequestParam String mountPath) {
        return ApiResponse.ok(resourceExplorerNodeFileSystemService.getMountSummary(nodeId, mountPath));
    }

    @Operation(summary = "마운트 정보",
            description = "개별 마운트 개요 탭 정보 표 — 마운트 경로, 종류, 수집 상태, 마지막 수집 시각을 조회합니다.")
    @GetMapping("/mount/info")
    public ApiResponse<FileSystemMountInfoResponse> getMountInfo(
            @PathVariable String nodeId,
            @RequestParam String mountPath) {
        return ApiResponse.ok(resourceExplorerNodeFileSystemService.getMountInfo(nodeId, mountPath));
    }

    @Operation(summary = "마운트 모니터링 차트",
            description = "개별 마운트 모니터링 탭 — 해당 마운트의 지표별 시계열. "
                    + "화면 설계 확정 전으로 지표 세트가 변경될 수 있습니다. from/to 미지정 시 오늘 00시~현재입니다.")
    @GetMapping("/mount/metrics/trend")
    public ApiResponse<FileSystemMetricTrendResponse> getMountMetricTrend(
            @PathVariable String nodeId,
            @RequestParam String mountPath,
            @RequestParam(defaultValue = "FS_USED") FileSystemMetric metric,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "300s") String step) {
        Instant toInstant = to != null ? Instant.parse(to) : Instant.now();
        Instant fromInstant = from != null ? Instant.parse(from) : todayStart();
        return ApiResponse.ok(resourceExplorerNodeFileSystemService.getMountMetricTrend(
                nodeId, mountPath, metric, fromInstant, toInstant, step));
    }

    /** from 기본값: 오늘 00:00:00 (서버 타임존) */
    private static Instant todayStart() {
        return LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }
}
