package com.innogrid.aims.monitoring.controller.resourceexplorer.mdc;

import java.util.List;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.MdcSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeSearchField;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeTopResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.mdc.ResourceExplorerMdcService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자원 탐색기 > MDC 패널 API.
 *
 * 트리에서 역할 하위 MDC(물리 노드 가지)를 선택했을 때 오른쪽 개요 탭에 표시되는
 * KPI 카드(물리 호스트 수/Running/Pending/Stop/Error), 노드 Top-N, 노드 목록을 제공한다.
 * 조회 대상은 현재 사용자 토큰에 부여된 역할 스코프의 물리 호스트로 제한된다(트리와 동일) —
 * roleName/mdcId 는 스코프 안에서의 필터이며, 토큰에 없는 역할을 지정하면 빈 결과다.
 * 왼쪽 트리는 ResourceExplorerTreeController 가 담당한다.
 */
@Tag(name = "관측 > 자원 탐색기 (MDC)", description = "MDC 개요 KPI, 노드 Top-N, 노드 목록 조회")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/mdc")
public class ResourceExplorerMdcController {

    private final ResourceExplorerMdcService resourceExplorerMdcService;

    public ResourceExplorerMdcController(ResourceExplorerMdcService resourceExplorerMdcService) {
        this.resourceExplorerMdcService = resourceExplorerMdcService;
    }

    @Operation(summary = "MDC 개요 KPI",
            description = "선택한 MDC(역할·mdcId)의 물리 호스트 수, Running, Pending, Stop, Error 카드 값을 조회합니다. "
                    + "roleName 을 생략하면 토큰의 모든 역할을 합치되 같은 호스트는 한 번만 셉니다.")
    @GetMapping("/summary")
    public ApiResponse<MdcSummaryResponse> getMdcSummary(
            @RequestParam(required = false) String roleName,
            @RequestParam(required = false) String mdcId) {
        return ApiResponse.ok(resourceExplorerMdcService.getMdcSummary(roleName, mdcId));
    }

    @Operation(summary = "MDC 노드 Top-N",
            description = "MDC 내에서 metric(CPU|MEMORY|FILE_SYSTEM) 기준 상위 물리 호스트를 조회합니다. "
                    + "행마다 세 지표를 모두 담아 지표 토글 시 재조회가 필요 없습니다. "
                    + "interval(LAST_10M|LAST_1H|LAST_6H|LAST_24H|LAST_30D|LAST_90D)은 Thanos 기간 평균 윈도우입니다 — 기간 변경 시 재조회합니다. "
                    + "요청 지표가 미수집인 호스트는 제외됩니다.")
    @GetMapping("/top")
    public ApiResponse<List<NodeTopResponse>> getMdcTop(
            @RequestParam(required = false) String roleName,
            @RequestParam(required = false) String mdcId,
            @RequestParam(defaultValue = "CPU") NodeMetric metric,
            @RequestParam(defaultValue = "LAST_10M") NodeMetricInterval interval,
            @RequestParam(defaultValue = "5") @Min(1) @Max(20) int limit) {
        return ApiResponse.ok(resourceExplorerMdcService.getMdcTop(roleName, mdcId, metric, interval, limit));
    }

    @Operation(summary = "MDC 노드 목록",
            description = "MDC 내 물리 호스트 목록 — 호스트명/별칭/상태/IP 주소(exporter 주소, 복수)/CPU·Memory·File system 사용률(최근 10분 평균). "
                    + "사용률이 null 이면 미수집(\"-\")입니다. "
                    + "상태 필터와 검색(searchField 로 NAME|ALIAS|IP 중 대상 필드 선택 + keyword), "
                    + "컬럼 정렬, 페이징을 지원합니다. page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다.")
    @GetMapping("/nodes")
    public ApiResponse<PageResponse<NodeListItemResponse>> getMdcNodes(
            @RequestParam(required = false) String roleName,
            @RequestParam(required = false) String mdcId,
            @RequestParam(required = false) NodeStatus status,
            @RequestParam(defaultValue = "NAME") NodeSearchField searchField,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name,asc") String sort) {
        return ApiResponse.ok(resourceExplorerMdcService.getMdcNodes(
                roleName, mdcId, status, searchField, keyword, page, size, sort));
    }
}
