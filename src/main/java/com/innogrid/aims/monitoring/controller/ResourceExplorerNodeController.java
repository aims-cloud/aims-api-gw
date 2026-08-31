package com.innogrid.aims.monitoring.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeDetailMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeVmListItemResponse;
import com.innogrid.aims.monitoring.service.ResourceExplorerNodeService;
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
 * 자원 탐색기 > 노드 상세 패널 API.
 *
 * 트리에서 물리 노드를 선택했을 때 오른쪽 패널의 탭별 데이터를 제공한다.
 *   개요 탭        — 주요 지표 카드 6종, 모니터링 요약 차트, 노드 정보 표
 *   가상머신 탭    — 노드에서 실행 중인 VM 목록
 *   자원 사용 현황 탭 — 카테고리별 상세 (화면 설계 확정 전)
 * 알람·로그 탭은 기존 AlarmController / LogController 를 instance 필터로 재사용한다.
 * 왼쪽 트리는 ResourceExplorerTreeController, 노드그룹 패널은 ResourceExplorerNodeGroupController 가 담당한다.
 */
@Tag(name = "자원 탐색기 (노드)", description = "노드 상세 — 주요 지표, 모니터링 요약 차트, 노드 정보, 가상머신 목록, 자원 사용 현황")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/node/{nodeId}")
public class ResourceExplorerNodeController {

    private final ResourceExplorerNodeService resourceExplorerNodeService;

    public ResourceExplorerNodeController(ResourceExplorerNodeService resourceExplorerNodeService) {
        this.resourceExplorerNodeService = resourceExplorerNodeService;
    }

    @Operation(summary = "노드 주요 지표",
            description = "개요 탭 카드 6종 — CPU/Memory/File System/GPU/VRAM 사용률(용량 카드는 Free/Total GB 포함), "
                    + "네트워크 수신·송신(bytes/sec, 단위 변환은 프론트), 최근 업데이트 시각을 조회합니다.")
    @GetMapping("/summary")
    public ApiResponse<NodeSummaryResponse> getNodeSummary(@PathVariable String nodeId) {
        return ApiResponse.ok(resourceExplorerNodeService.getNodeSummary(nodeId));
    }

    @Operation(summary = "노드 모니터링 요약 차트",
            description = "metric(CPU|MEMORY|FILE_SYSTEM|NETWORK|GPU|VRAM)별 시계열을 조회합니다. "
                    + "NETWORK 는 receive/transmit 2개 시리즈를 반환합니다. "
                    + "from/to 미지정 시 오늘 00시~현재입니다.")
    @GetMapping("/metrics/trend")
    public ApiResponse<NodeMetricTrendResponse> getNodeMetricTrend(
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "CPU") NodeDetailMetric metric,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "300s") String step) {
        Instant toInstant = to != null ? Instant.parse(to) : Instant.now();
        Instant fromInstant = from != null ? Instant.parse(from) : todayStart();
        return ApiResponse.ok(resourceExplorerNodeService.getNodeMetricTrend(
                nodeId, metric, fromInstant, toInstant, step));
    }

    @Operation(summary = "노드 정보",
            description = "개요 탭 노드 정보 표 — 이름/ID/IP/가동일수/유형/가상서버 수/CPU·Memory 할당/스토리지와 "
                    + "우상단 배지용 프로바이더(provider)·플랫폼 유형(platformType)을 조회합니다.")
    @GetMapping("/info")
    public ApiResponse<NodeInfoResponse> getNodeInfo(@PathVariable String nodeId) {
        return ApiResponse.ok(resourceExplorerNodeService.getNodeInfo(nodeId));
    }

    @Operation(summary = "노드 가상머신 목록",
            description = "가상머신 탭 — 이 노드에서 실행 중인 VM 목록. 상태·키워드 필터와 컬럼 정렬, 페이징을 지원합니다. "
                    + "page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다.")
    @GetMapping("/vms")
    public ApiResponse<PageResponse<NodeVmListItemResponse>> getNodeVms(
            @PathVariable String nodeId,
            @RequestParam(required = false) NodeStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name,asc") String sort) {
        return ApiResponse.ok(resourceExplorerNodeService.getNodeVms(
                nodeId, status, keyword, page, size, sort));
    }

    @Operation(summary = "노드 자원 사용 현황",
            description = "자원 사용 현황 탭 — 카테고리별 상세 시계열. 화면 설계 확정 전이며 응답 구조가 변경될 수 있습니다.")
    @GetMapping("/usage")
    public ApiResponse<List<NodeMetricTrendResponse>> getNodeUsage(
            @PathVariable String nodeId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        Instant toInstant = to != null ? Instant.parse(to) : Instant.now();
        Instant fromInstant = from != null ? Instant.parse(from) : todayStart();
        return ApiResponse.ok(resourceExplorerNodeService.getNodeUsage(
                nodeId, category, fromInstant, toInstant));
    }

    /** from 기본값: 오늘 00:00:00 (서버 타임존) */
    private static Instant todayStart() {
        return LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }
}

