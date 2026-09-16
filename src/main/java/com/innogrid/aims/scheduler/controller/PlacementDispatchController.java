package com.innogrid.aims.scheduler.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.DispatchResultRequest;
import com.innogrid.aims.scheduler.dto.PlacementDispatchResponse;
import com.innogrid.aims.scheduler.service.PlacementDispatchService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 디스패치(실행 계층 연동) API — 결정을 넘긴 기록의 조회와, 실행 계층이 결과를 되돌려 주는 콜백.
 *
 * <p>콜백 {@code POST /dispatches/{id}/result} 는 실행 계층(1세부)이 부른다고 가정한다. 인증은 다른 API 와 같은 JWT 라
 * 실행 계층에는 서비스 계정이 필요하다(운영 설정). 기록형 모드에서는 시연 스크립트·운영자가 같은 API 로 진행을 알린다.</p>
 */
@Tag(name = "스케줄러 디스패치", description = "배치 결정을 실행 계층으로 넘긴 기록과 결과 콜백. 실행 계층 API 미정 — 기본은 기록형")
@RestController
@RequestMapping("/api/v1/scheduler")
public class PlacementDispatchController {

    private final PlacementDispatchService service;

    public PlacementDispatchController(PlacementDispatchService service) {
        this.service = service;
    }

    @Operation(summary = "디스패치 목록(페이지)",
               description = "status(REQUESTED·ACCEPTED·RUNNING·COMPLETED·FAILED·REJECTED·UNAVAILABLE·TIMED_OUT, 대소문자 무시. "
                       + "TIMED_OUT 은 record 모드에서는 나오지 않습니다)·"
                       + "workloadId·scheduleRunId 필터는 선택. page 는 1부터, size 는 1~100. sort 는 `필드,방향`(id·requestedAt·respondedAt·status·workloadId).")
    @GetMapping("/dispatches")
    public ApiResponse<PageResponse<PlacementDispatchResponse>> page(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long workloadId,
            @RequestParam(required = false) Long scheduleRunId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(service.page(status, workloadId, scheduleRunId, page, size, sort));
    }

    @Operation(summary = "디스패치 상세")
    @GetMapping("/dispatches/{dispatchId}")
    public ApiResponse<PlacementDispatchResponse> get(@PathVariable Long dispatchId) {
        return ApiResponse.ok(PlacementDispatchResponse.from(service.require(dispatchId)));
    }

    @Operation(summary = "실행 결과 콜백",
               description = "실행 계층이 진행 결과를 알립니다. status 는 RUNNING | COMPLETED | FAILED. 같은 상태 재보고는 200 무변경, "
                       + "전이표(ACCEPTED→RUNNING/FAILED, RUNNING→COMPLETED/FAILED) 밖이면 409. 워크로드 상태도 함께 바뀝니다.")
    @PostMapping("/dispatches/{dispatchId}/result")
    public ApiResponse<PlacementDispatchResponse> result(@PathVariable Long dispatchId,
                                                         @RequestBody(required = false) DispatchResultRequest request) {
        if (request == null) throw new BusinessException(HttpStatus.BAD_REQUEST, "요청 본문이 필요합니다");
        return ApiResponse.ok(PlacementDispatchResponse.from(
                service.applyResult(dispatchId, request.status(), request.externalRef(), request.message())));
    }

    @Operation(summary = "워크로드의 디스패치 기록", description = "최신순 배열. 없으면 빈 배열.")
    @GetMapping("/workloads/{workloadId}/dispatches")
    public ApiResponse<List<PlacementDispatchResponse>> byWorkload(
            @Parameter(description = "워크로드 ID") @PathVariable Long workloadId) {
        return ApiResponse.ok(service.byWorkload(workloadId).stream().map(PlacementDispatchResponse::from).toList());
    }
}
