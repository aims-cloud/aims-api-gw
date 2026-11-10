package com.innogrid.aims.scheduler.controller;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.log.PortalLog;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.scheduler.dto.CronPreviewResponse;
import com.innogrid.aims.scheduler.dto.ScheduleDryRunResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.dto.ScheduleUpdateRequest;
import com.innogrid.aims.scheduler.dto.ScheduleResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRunDetailResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRunPreviewResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRunEngineReplayResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRunReplayResponse;
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
 *
 * <p>포털 로그(docs/08 §10.1 묶음 5): 생성·수정·삭제는 {@code @PortalLog(AUDIT)}, 즉시 실행은 {@code ACTION}(동기),
 * 목록·상세·실행 이력은 {@code ACCESS} 로 남긴다(화면 "스케줄러" · ref = SCHEDULE, 즉시 실행은 SCHEDULE_RUN).
 * 미리보기·모의 실행·실행 주기 검증·실행 상세·실행 재생은 남기지 않는다.</p>
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
    @PortalLog(type = PortalLogType.ACCESS, screen = "스케줄러", action = "조회",
               content = "'스케줄 목록 조회 (키워드: ' + (#keyword ?: '-') + ', 리소스 종류: ' + (#resourceType ?: '전체')"
                       + " + ', 사용 여부: ' + (#enabled == null ? '전체' : (#enabled ? '사용' : '미사용')) + ')'")
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
    @PortalLog(type = PortalLogType.ACCESS, screen = "스케줄러", action = "조회",
               content = "'스케줄 조회: ' + (#result?.data?.scheduleId ?: '-') + ' (id ' + #id + ')'",
               refType = "SCHEDULE", refId = "#id")
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
    @PortalLog(screen = "스케줄러", action = "생성",
               content = "'스케줄 생성: ' + (#request?.scheduleId ?: '-') + ' (' + (#request?.name ?: '-') + ')'",
               refType = "SCHEDULE", refId = "#result.data.id")
    @PostMapping
    public ApiResponse<ScheduleResponse> createSchedule(@RequestBody ScheduleRequest request) {
        return ApiResponse.ok(scheduleService.create(request));
    }

    @Operation(summary = "스케줄 편집",
            description = "부분 갱신 — 보낸 키만 바꾸고 없는 키는 유지합니다. 스케줄 ID 는 업무 키라 변경할 수 없습니다. "
                    + "policyId·engine·maxPowerBudgetWatts·maxTemperatureCelsius·description 은 null 을 보내면 비웁니다"
                    + "(정책·온도 상한은 상속으로, 전력 상한은 제약 없음으로 복귀). 이 다섯 칸은 JSON 타입도 검사해 "
                    + "숫자 칸에 숫자 문자열, 정수 칸에 소수를 보내면 400 입니다. 나머지 칸의 null 은 유지이고 "
                    + "resourceType·placementOrder 는 비울 수 없습니다(배치 순서를 기본으로 되돌리려면 age). "
                    + "상세 조회 응답을 그대로 보내면 아무것도 바뀌지 않습니다. "
                    + "본문이 없으면 400, 스케줄 ID 가 다르면 400, 칸 검증은 생성과 같은 규칙이며 한 칸이라도 400 이면 아무것도 바뀌지 않습니다. "
                    + "실행 주기나 사용 여부를 바꾸면 다음 실행 예정 시각이 현재 기준으로 다시 계산됩니다. "
                    + "실행 중에도 편집할 수 있으며 최근 실행 상태·시각·메시지 칸은 편집이 쓰지 않습니다(편집 응답의 그 칸은 편집 시점 값).")
    @PortalLog(screen = "스케줄러", action = "수정",
               content = "'스케줄 수정: ' + (#result?.data?.scheduleId ?: '-') + ' (id ' + #id + ')'",
               refType = "SCHEDULE", refId = "#id")
    @PutMapping("/{id}")
    public ApiResponse<ScheduleResponse> updateSchedule(@PathVariable Long id,
                                                        @RequestBody(required = false) ScheduleUpdateRequest request) {
        return ApiResponse.ok(scheduleService.update(id, request));
    }

    @Operation(summary = "스케줄 삭제",
            description = "목록 체크박스로 선택한 스케줄들을 삭제합니다. ids 는 콤마 구분 다중 지정입니다. "
                    + "실행 중인 스케줄이 하나라도 포함되면 409 로 전체를 거부하며, 없는 ID 는 무시합니다. "
                    + "실행 이력은 함께 지우고 배치 결정 이력은 연결만 끊습니다.")
    @PortalLog(screen = "스케줄러", action = "삭제",
               content = "'스케줄 삭제: ' + (#ids?.size() ?: 0) + '건 (id ' + #ids + ')'",
               refType = "SCHEDULE", refId = "#ids")
    @DeleteMapping
    public ApiResponse<Void> deleteSchedules(@RequestParam List<Long> ids) {
        scheduleService.delete(ids);
        return ApiResponse.ok(null);
    }

    // ── 실행 ───────────────────────────────────────────────────

    @Operation(summary = "저장 전 스케줄 미리보기",
            description = "생성 폼의 값을 그대로 보내면 저장하지 않고 '이 값으로 지금 실행하면' 적용될 정책·엔진과 "
                    + "대상·재시도 대기·재시도 소진 건수를 돌려줍니다. 응답은 실행 미리보기와 같은 열네 키이고 id 는 null 입니다. "
                    + "결과를 바꾸는 칸은 resourceType·policyId·engine 셋이고, maxTemperatureCelsius·placementOrder 는 실행과 같은 해석에 "
                    + "쓰이지만 응답 값에는 드러나지 않습니다. scheduleId·enabled 는 보낸 값을 그대로 돌려줍니다. "
                    + "스케줄명·실행 주기·자동 실행·전력 상한은 보지 않고(전력 상한은 실행 때 노드 단위로 적용) 설명은 300자 상한만 검사합니다. "
                    + "scheduleId 형식·중복과 실행 주기도 검사하지 않으므로 200 이 저장 성공을 뜻하지는 않습니다. "
                    + "키를 빼면 기존 값이 아니라 기본값(VM · 사용 중 · 정책 상속)으로 해석하므로 편집 폼은 병합한 전체 값을 보내십시오. "
                    + "본문이 없으면 400, 엔진·배치 순서·설명 형식 오류는 400, 없는 정책 id 는 404 입니다. "
                    + "노드를 모으지 않고 실행 권한을 잡지 않으며 default 정책도 만들지 않습니다.")
    @PostMapping("/preview")
    public ApiResponse<ScheduleRunPreviewResponse> previewDraft(@RequestBody(required = false) ScheduleRequest request) {
        return ApiResponse.ok(executionService.previewDraft(request));
    }

    @Operation(summary = "저장 전 모의 실행",
            description = "생성 폼 값으로 모의 실행과 같은 결과를 저장 없이 돌려줍니다 — 같은 열일곱 키이고 id 는 null, "
                    + "scheduleId·enabled 는 보낸 값입니다. 검증은 저장 전 미리보기와 같고(본문 없음·엔진·배치 순서·설명 400, 없는 정책 id 404), "
                    + "전력 상한과 온도 상한은 폼 값을 적용하고, 온도 상한을 지정하면 냉각 대기를 남기지 않습니다. "
                    + "노드를 모으므로 버튼 단위로 호출하고, 어떤 행도 쓰지 않으며 200 이 저장 성공을 뜻하지는 않습니다.")
    @PostMapping("/dry-run")
    public ApiResponse<ScheduleDryRunResponse> dryRunDraft(@RequestBody(required = false) ScheduleRequest request) {
        return ApiResponse.ok(executionService.dryRunDraft(request));
    }

    @Operation(summary = "실행 미리보기",
            description = "이 스케줄을 지금 실행하면 적용될 배치 정책·엔진(과 고른 단계)과 대상·재시도 대기·재시도 소진 "
                    + "건수를 저장 없이 돌려줍니다. 실행 권한을 잡지 않고 노드도 모으지 않습니다. "
                    + "대상 건수는 시도할 건수이며 성공할 건수가 아닙니다(어떻게 배치될지는 모의 실행으로). "
                    + "대상이 0건이면 emptyReason·emptyMessage 에 실행이 남길 사유와 같은 문구가 실립니다. "
                    + "사용 여부가 꺼진 스케줄과 실행 중인 스케줄도 200 입니다. 스케줄이 없거나 지정한 정책이 지워졌으면 404 입니다. "
                    + "화면 분기는 emptyReason 으로 하고 emptyMessage 는 표시용으로만 쓰십시오.")
    @GetMapping("/{id}/preview")
    public ApiResponse<ScheduleRunPreviewResponse> preview(@PathVariable Long id) {
        return ApiResponse.ok(executionService.preview(id));
    }

    @Operation(summary = "모의 실행",
            description = "이 스케줄을 지금 실행하면 어떻게 배치될지를 저장 없이 돌려줍니다 — 실행과 같은 정책·엔진·대상·순서·"
                    + "상한·전력 상한으로 노드 상태 하나 위에 대상을 차례로 놓아 봅니다(앞 건의 예약이 뒤 건에 보임). "
                    + "실행 계층 전달은 하지 않으며(전부 받는다고 봄) 실행 권한을 잡지 않고 워크로드·배치 이력·전달·실행 이력·"
                    + "default 정책 어느 행도 쓰지 않습니다. status·message 는 전부 받았을 때 실행이 남길 값과 같고, "
                    + "targetCount 는 실행 1회 상한을 적용한 뒤 값입니다(미리보기와 다름). 대상이 0건이면 노드를 모으지 않습니다. "
                    + "사용 여부가 꺼진 스케줄과 실행 중인 스케줄도 200 이며 호출 시점 값입니다. "
                    + "스케줄이 없거나 지정한 정책이 지워졌으면 404 입니다. 메트릭을 한 번 수집하므로 버튼 단위로 호출하십시오.")
    @PostMapping("/{id}/dry-run")
    public ApiResponse<ScheduleDryRunResponse> dryRun(@PathVariable Long id) {
        return ApiResponse.ok(executionService.dryRun(id));
    }

    @Operation(summary = "즉시 실행",
            description = "선택한 스케줄을 지금 한 번 실행하고 완료된 실행 이력을 돌려줍니다(동기). "
                    + "사용 여부가 꺼져 있거나 이미 실행 중이면 409 입니다. "
                    + "대상 워크로드가 없어도 정상이며 '대상 없음' 으로 기록됩니다.")
    @PortalLog(type = PortalLogType.ACTION, screen = "스케줄러", action = "실행",
               content = "#result == null ? '스케줄 즉시 실행: id ' + #id"
                       + " : '스케줄 즉시 실행: ' + #result.data.scheduleKey + ' (id ' + #id + ') · 처리 상태 ' + #result.data.status",
               refType = "SCHEDULE_RUN", refId = "#result.data.runId")
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
                    + "keyword 는 실행 메시지·스케줄 ID 의 부분 일치이고 다른 필터와 AND 로 겹칩니다. "
                    + "정렬 가능 필드: startedAt·finishedAt·status·triggerType·targetCount·successCount·failedCount·message·scheduleKey. "
                    + "자동 실행이 대상 0건으로 끝난 경우는 이력을 남기지 않습니다 — "
                    + "그 사실은 스케줄 상세의 최근 실행 메시지에서 확인할 수 있습니다.")
    @PortalLog(type = PortalLogType.ACCESS, screen = "스케줄러", action = "조회",
               content = "'스케줄 실행 이력 조회: id ' + #id + ' (상태: ' + (#status ?: '전체') + ', 실행 주체: ' + (#triggerType ?: '전체')"
                       + " + (#keyword == null or #keyword.isBlank() ? '' : ', 키워드: ' + #keyword.trim()) + ')'",
               refType = "SCHEDULE", refId = "#id")
    @GetMapping("/{id}/runs")
    public ApiResponse<PageResponse<ScheduleRunResponse>> getRuns(
            @PathVariable Long id,
            @Parameter(description = "실행 메시지·스케줄 ID(업무 키)의 부분 일치(대소문자 무시 · 앞뒤 공백 제거). % 와 _ 는 문자 그대로 찾습니다. 비우면 필터 없음")
            @RequestParam(required = false) String keyword,
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
        return ApiResponse.ok(scheduleService.runs(id, keyword, status, triggerType, from, to, page, size, sort));
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

    @Operation(summary = "실행 재생",
            description = "실행 1건이 저장한 배치 입력으로 그 실행의 배치를 지금 코드로 다시 계산해, 워크로드별로 배치 이력의 "
                    + "선택 노드·노드 배치 스코어(finalScore)·성공 여부와 대조합니다. 화면이 부르지 않는 검증용이고 DB 에 아무것도 저장하지 않습니다. "
                    + "실행 도중 편집된 워크로드나 실행 중 만료된 냉각 대기처럼 저장한 입력에 없는 일은 재생하지 않으므로, "
                    + "MISMATCH 가 곧 결함을 뜻하지는 않습니다. "
                    + "실행이 없거나 다른 스케줄의 실행이거나 저장한 입력이 없으면 404, 실행 중이거나 형식 판·지문·엔진을 재생할 수 없거나 "
                    + "원문이 없거나 입력을 지금 코드로 읽을 수 없거나 저장한 설정 노브를 지금 코드에 적용할 수 없으면 409, "
                    + "그 밖에 다시 조립·계산하다 오류가 나면 500.")
    @GetMapping("/{id}/runs/{runId}/replay")
    public ApiResponse<ScheduleRunReplayResponse> replayRun(@PathVariable Long id,
                                                            @PathVariable Long runId) {
        return ApiResponse.ok(scheduleService.replay(id, runId));
    }

    @Operation(summary = "엔진별 재계산",
            description = "실행 1건이 저장한 배치 입력 하나로 운영 엔진 세 개(weighted·power·temperature)를 각각 다시 계산해, "
                    + "워크로드별 선택 노드·성공 여부를 배치 이력과 나란히 돌려줍니다. 같은 정책에서 스케줄 엔진만 바꿨다면 어떻게 배치됐을지를 "
                    + "보는 검증·연구용이고, 화면이 부르지 않으며 DB 에 아무것도 저장하지 않습니다. 노드 배치 스코어·평가 지표·순위는 싣지 않으며 "
                    + "이 응답으로 엔진 우열을 말하지 않습니다(엔진 우열 판정은 배치 전략 비교의 판정판에서만). "
                    + "건너뛴 워크로드는 모든 엔진에서 건너뜁니다. 실행 계층이 받지 않아 예약을 되돌린 워크로드는 모든 엔진에서 예약을 되돌리고, "
                    + "그 밖의 배치는 엔진이 고른 노드가 기록과 달라도 실행 계층이 받았다고 보고 예약을 유지합니다 — 실행 계층이 노드마다 다르게 "
                    + "받게 되면 rolledBack 이 0 이어도 다른 엔진 열은 실제와 다를 수 있습니다. replayVerdict 는 실제로 쓴 엔진을 다시 계산한 "
                    + "실행 재생 판정이고, MATCH 가 아니면 다른 엔진 열을 같은 조건의 대안으로 읽을 수 없습니다. "
                    + "404·409 는 실행 재생과 같은 검사를 거칩니다. 엔진 계산 전에 저장한 입력을 다시 조립하지 못하면 실행 재생과 같은 "
                    + "500 입니다(사유에 엔진 id 없음). 그 밖에 세 엔진 중 하나라도 다시 계산하다 예외를 내면 500 이며(사유에 엔진 id), "
                    + "실행 재생이 200 인 실행에서도 날 수 있습니다.")
    @GetMapping("/{id}/runs/{runId}/replay/engines")
    public ApiResponse<ScheduleRunEngineReplayResponse> replayRunByEngine(@PathVariable Long id,
                                                                          @PathVariable Long runId) {
        return ApiResponse.ok(scheduleService.replayEngines(id, runId));
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
