package com.innogrid.aims.scheduler.controller;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.scheduler.dto.CronPreviewResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.dto.ScheduleResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRunDetailResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRunResponse;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.service.ScheduleActorResolver;
import com.innogrid.aims.scheduler.service.ScheduleExecutionService;
import com.innogrid.aims.scheduler.service.ScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 지능형 예측 진단 &gt; 스케줄러 API.
 *
 * <p>스케줄은 "언제 어떤 리소스 종류의 워크로드를 배치할지"를 정하는 트리거다. 무엇을
 * 어떻게 배치할지는 배치 정책과 엔진이 정한다.</p>
 *
 * <p><b>시각은 전부 UTC 로 주고받는다.</b> 표출 변환은 프런트가 한다. 다만 실행 주기
 * (cron) 는 시간대 정보를 담을 수 없어 <b>한국 시간(UTC+9) 입력으로 가정</b>해 해석한다
 * — 근거는 {@code CronSupport} 참고.</p>
 */
@Tag(name = "스케줄러 (스케줄)", description = "스케줄 목록·상세·CRUD, 즉시 실행, 실행 이력, 실행 주기 검증")
@RestController
@RequestMapping("/api/v1/scheduler/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final ScheduleExecutionService executionService;
    private final ScheduleActorResolver actorResolver;

    public ScheduleController(ScheduleService scheduleService,
                              ScheduleExecutionService executionService,
                              ScheduleActorResolver actorResolver) {
        this.scheduleService = scheduleService;
        this.executionService = executionService;
        this.actorResolver = actorResolver;
    }

    // ── 목록·상세 ──────────────────────────────────────────────

    @Operation(summary = "스케줄 목록",
            description = "스케줄 ID/스케줄명/리소스 종류/실행 주기/설명/자동 실행/사용 여부/시작·종료 시간/처리 상태. "
                    + "검색어와 리소스 종류·사용 여부 필터, 컬럼 정렬, 페이징을 지원합니다. "
                    + "page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다. " + "같은 값으로 정렬된 행은 id 로 한 번 더 정렬해 페이지 경계에서 겹치거나 빠지지 않습니다.")
    @GetMapping
    public ApiResponse<PageResponse<ScheduleResponse>> getSchedules(
            @Parameter(description = "스케줄 ID·스케줄명·설명의 부분 일치(대소문자 무시 · 앞뒤 공백 제거). % 와 _ 는 문자 그대로 찾습니다. 비우면 필터 없음")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "리소스 종류. VM 은 종류를 비워 둔 스케줄(생성 때 생략)도 포함합니다(실행이 VM 으로 해석하는 것과 같음). 비우면 전체")
            @RequestParam(required = false) Workload.ResourceType resourceType,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return ApiResponse.ok(scheduleService.list(keyword, resourceType, enabled, page, size, sort));
    }

    @Operation(summary = "스케줄 상세",
            description = "하단 상세 탭과 상세 페이지에 쓰는 전체 필드를 조회합니다. "
                    + "화면 상세 카드에는 리소스 종류가 빠져 있지만 응답에는 포함됩니다.")
    @GetMapping("/{id}")
    public ApiResponse<ScheduleResponse> getSchedule(@PathVariable Long id) {
        return ApiResponse.ok(scheduleService.get(id));
    }

    // ── 생성·수정·삭제 ─────────────────────────────────────────

    @Operation(summary = "스케줄 생성",
            description = "스케줄 ID·스케줄명·실행 주기가 필수입니다. 리소스 종류는 비우면 실행 때 VM 으로 해석합니다. "
                    + "엔진·정책·전력/온도 파라미터는 선택이며, 비우면 리소스 종류별 활성 정책을 따릅니다. "
                    + "스케줄 ID 가 중복되면 409, 실행 주기가 잘못되면 400 입니다. "
                    + "배치 순서(placementOrder)의 선택지와 뜻은 요청 스키마의 필드 설명을 따릅니다.")
    @PostMapping
    public ApiResponse<ScheduleResponse> createSchedule(@RequestBody ScheduleRequest request) {
        return ApiResponse.ok(scheduleService.create(request));
    }

    @Operation(summary = "스케줄 편집",
            description = "스케줄 ID 는 업무 키라 변경할 수 없습니다. "
                    + "실행 주기나 사용 여부를 바꾸면 다음 실행 예정 시각이 현재 기준으로 다시 계산됩니다.")
    @PutMapping("/{id}")
    public ApiResponse<ScheduleResponse> updateSchedule(@PathVariable Long id,
                                                        @RequestBody ScheduleRequest request) {
        return ApiResponse.ok(scheduleService.update(id, request));
    }

    @Operation(summary = "스케줄 삭제",
            description = "목록 체크박스로 선택한 스케줄들을 삭제합니다. ids 는 콤마 구분 다중 지정입니다. "
                    + "실행 중인 스케줄이 하나라도 포함되면 409 로 전체를 거부하며, 없는 ID 는 무시합니다. "
                    + "실행 이력은 함께 지우고 배치 결정 이력은 연결만 끊습니다.")
    @DeleteMapping
    public ApiResponse<Void> deleteSchedules(@RequestParam List<Long> ids) {
        scheduleService.delete(ids);
        return ApiResponse.ok(null);
    }

    // ── 실행 ───────────────────────────────────────────────────

    @Operation(summary = "즉시 실행",
            description = "선택한 스케줄을 지금 한 번 실행하고 완료된 실행 이력을 돌려줍니다(동기). "
                    + "사용 여부가 꺼져 있거나 이미 실행 중이면 409 입니다. "
                    + "대상 워크로드가 없어도 정상이며 '대상 없음' 으로 기록됩니다.")
    @PostMapping("/{id}/run")
    public ApiResponse<ScheduleRunResponse> runNow(@PathVariable Long id) {
        ScheduleActorResolver.Actor actor = actorResolver.current();
        ScheduleRun run = executionService.runNow(id, actor.email());
        return ApiResponse.ok(ScheduleRunResponse.from(run));
    }

    @Operation(summary = "실행 이력",
            description = "스케줄 ID/시작 시간/종료 시간/처리 상태/메시지와 집계 건수를 조회합니다. "
                    + "status·triggerType·from·to 필터는 선택이고 AND 로 겹칩니다(totalElements 는 필터를 반영). "
                    + "sort 는 '{필드},{asc|desc}' 형식이고 같은 값으로 정렬된 행은 id 로 한 번 더 정렬해 페이지 경계에서 겹치거나 빠지지 않습니다. "
                    + "자동 실행이 대상 0건으로 끝난 경우는 이력을 남기지 않습니다 — "
                    + "그 사실은 스케줄 상세의 최근 실행 메시지에서 확인할 수 있습니다.")
    @GetMapping("/{id}/runs")
    public ApiResponse<PageResponse<ScheduleRunResponse>> getRuns(
            @PathVariable Long id,
            @Parameter(description = "처리 상태 — RUNNING·COMPLETED·FAILED. 대문자 그대로, 다른 값은 400. 비우면 전체")
            @RequestParam(required = false) ScheduleRun.RunStatus status,
            @Parameter(description = "실행 주체 — AUTO(주기 실행)·MANUAL(즉시 실행). 대문자 그대로, 다른 값은 400. 비우면 전체")
            @RequestParam(required = false) ScheduleRun.TriggerType triggerType,
            @Parameter(description = "시작 시각(startedAt) 창의 시작, 포함 (ISO-8601, 오프셋 필수 — 쿼리의 + 는 %2B 로). 비우면 하한 없음")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @Parameter(description = "시작 시각(startedAt) 창의 끝, 미포함 (ISO-8601, 오프셋 필수 — 쿼리의 + 는 %2B 로). 비우면 상한 없음. from 이 to 보다 뒤면 400")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "startedAt,desc") String sort) {
        return ApiResponse.ok(scheduleService.runs(id, status, triggerType, from, to, page, size, sort));
    }

    @Operation(summary = "실행 상세",
            description = "실행 1건이 내린 워크로드별 배치 결정(선택 노드·점수·사유)을 함께 돌려줍니다. "
                    + "결정은 결정 시각·id 오름차순이라 /history/page?scheduleRunId=&sort=createdAt,asc 와 행 순서가 같습니다. "
                    + "제약 조건 기반 스케줄링이 무엇을 근거로 배치했는지 확인하는 용도입니다.")
    @GetMapping("/{id}/runs/{runId}")
    public ApiResponse<ScheduleRunDetailResponse> getRunDetail(@PathVariable Long id,
                                                               @PathVariable Long runId) {
        return ApiResponse.ok(scheduleService.runDetail(id, runId));
    }

    // ── 실행 주기 검증 ──────────────────────────────────────────

    @Operation(summary = "실행 주기 검증",
            description = "요청 본문의 cronExpression(Quartz 표기)을 검증하고 다음 5회 실행 시각을 요일 이름과 함께 돌려줍니다. "
                    + "입력은 한국 시간(UTC+9) 기준으로 해석하며 응답 시각은 UTC 입니다. "
                    + "연도 필드(7번째), 특수문자 C, 일·요일 동시 지정은 지원하지 않습니다.")
    @PostMapping("/cron/validate")
    public ApiResponse<CronPreviewResponse> validateCron(@RequestBody Map<String, String> request) {
        return ApiResponse.ok(scheduleService.previewCron(request.get("cronExpression")));
    }
}
