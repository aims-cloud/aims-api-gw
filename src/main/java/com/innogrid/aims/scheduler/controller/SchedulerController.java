package com.innogrid.aims.scheduler.controller;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.common.log.PortalLog;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.scheduler.dto.ConstraintDescriptionResponse;
import com.innogrid.aims.scheduler.dto.EngineResponse;
import com.innogrid.aims.scheduler.dto.ExcludedNodeResponse;
import com.innogrid.aims.scheduler.dto.ExclusionSummaryResponse;
import com.innogrid.aims.scheduler.dto.NodeResponse;
import com.innogrid.aims.scheduler.dto.PowerEfficiencyResponse;
import com.innogrid.aims.scheduler.dto.PowerEfficiencyTrendResponse;
import com.innogrid.aims.scheduler.dto.ResourceTypeOptionResponse;
import com.innogrid.aims.scheduler.dto.ResumeExhaustedResponse;
import com.innogrid.aims.scheduler.dto.SchedulingHistoryResponse;
import com.innogrid.aims.scheduler.dto.StatusChangeRequest;
import com.innogrid.aims.scheduler.dto.ThermalCooldownReleaseResponse;
import com.innogrid.aims.scheduler.dto.ThermalCooldownResponse;
import com.innogrid.aims.scheduler.dto.WorkloadReleaseResponse;
import com.innogrid.aims.scheduler.dto.WorkloadRequest;
import com.innogrid.aims.scheduler.dto.WorkloadResponse;
import com.innogrid.aims.scheduler.dto.WorkloadUpdateRequest;
import com.innogrid.aims.scheduler.engine.constraint.ExclusionReason;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;
import com.innogrid.aims.scheduler.service.EnumSupport;
import com.innogrid.aims.scheduler.service.PowerEfficiencyService;
import com.innogrid.aims.scheduler.service.SchedulingHistoryQueryService;
import com.innogrid.aims.scheduler.service.SchedulingService;
import com.innogrid.aims.scheduler.service.WorkloadQueryService;
import com.innogrid.aims.scheduler.service.WorkloadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 워크로드·노드·배치 이력 API.
 *
 * <p>응답은 전부 {@code dto} 패키지의 record 로 나간다. 예전에는 엔티티·모델을 그대로 직렬화해
 * {@code get} 메서드 하나를 추가하면 API 필드가 조용히 늘었다. 응답 형태의 주인은 이제 DTO 이고,
 * 필드 계약은 {@code ResponseJsonGoldenTest}·{@code NodeResourceSerializationTest} 가 고정한다.</p>
 */
@Tag(name = "스케줄러", description = "워크로드 스케줄링 (제약조건/전력/온도 기반)")
@RestController
@RequestMapping("/api/v1/scheduler")
public class SchedulerController {

    private static final Logger log = LoggerFactory.getLogger(SchedulerController.class);

    /**
     * 배열로 전량 반환하던 이력 API 의 반환 상한.
     *
     * <p>{@code scheduling_history} 는 연구 근거라 지우지 않는다 — 즉 단조 증가한다. 상한이 없으면
     * 응답 하나가 표 전체가 되어 메모리와 응답 크기가 행 수를 따라간다. 응답 <b>형태</b>는 배열 그대로 두고
     * (프런트 영향 없음) 건수만 최신 기준으로 자른다. 더 필요하면 {@code /history/page} 를 쓴다.</p>
     */
    private static final int HISTORY_MAX = 1000;

    /**
     * 실제로 읽어 오는 건수 — 상한보다 하나 많다.
     *
     * <p>딱 상한만 읽으면 "정말 잘렸는지"와 "우연히 상한과 같은 건수인지"를 구분할 수 없어, 잘리지 않았는데도
     * 경고가 찍힌다. 한 건을 더 읽어 보면 그 구분이 확실해진다. 남는 한 건은 응답에서 버린다.</p>
     */
    private static final int PROBE = HISTORY_MAX + 1;

    private final SchedulingService schedulingService;
    private final PowerEfficiencyService powerEfficiencyService;
    private final WorkloadService workloadService;
    private final WorkloadRepository workloadRepository;
    private final SchedulingHistoryRepository historyRepository;
    private final SchedulingHistoryQueryService historyQueryService;
    private final WorkloadQueryService workloadQueryService;

    public SchedulerController(SchedulingService schedulingService,
                               PowerEfficiencyService powerEfficiencyService,
                               WorkloadService workloadService,
                               WorkloadRepository workloadRepository,
                               SchedulingHistoryRepository historyRepository,
                               SchedulingHistoryQueryService historyQueryService,
                               WorkloadQueryService workloadQueryService) {
        this.schedulingService = schedulingService;
        this.powerEfficiencyService = powerEfficiencyService;
        this.workloadService = workloadService;
        this.workloadRepository = workloadRepository;
        this.historyRepository = historyRepository;
        this.historyQueryService = historyQueryService;
        this.workloadQueryService = workloadQueryService;
    }

    // ── 워크로드 ────────────────────────────────────────────────

    @Operation(summary = "워크로드 목록 조회",
               description = "배열로 상한 없이 전량을 돌려줍니다(최신순). 표 화면처럼 페이지·총건수·필터·정렬이 필요하면 /workloads/page 를 쓰십시오.")
    @GetMapping("/workloads")
    public ApiResponse<List<WorkloadResponse>> listWorkloads(@RequestParam(required = false) String status) {
        List<Workload> found = status != null
                ? workloadRepository.findByStatusOrderByCreatedAtDesc(
                        parseEnum(Workload.WorkloadStatus.class, status, "상태"))
                : workloadRepository.findAllByOrderByCreatedAtDesc();
        return ApiResponse.ok(found.stream().map(WorkloadResponse::from).toList());
    }

    @Operation(summary = "워크로드 페이징 조회",
               description = "배열로 전량을 주는 /workloads 와 달리 페이지·총건수·필터·정렬을 지원합니다. page 는 1부터, size 최대 100, "
                       + "sort 는 '{필드},{asc|desc}' (createdAt·name·status·resourceType·priority·requiredCpu·requiredMemoryMb·requiredGpu·requiredDiskGb·retryCount·scheduledAt·lastFailedAt — "
                       + "priority 는 DB 에 이름 문자열로 저장되지만 등급 순 LOW→NORMAL→HIGH→CRITICAL 로 정렬합니다). 같은 값으로 정렬된 행은 id 로 한 번 더 정렬해 페이지 경계에서 겹치거나 빠지지 않습니다. "
                       + "status·priority·resourceType·keyword·assignedNodeId·retryExhausted 필터는 선택이고 AND 로 겹칩니다(totalElements 는 필터를 반영). "
                       + "status·priority 는 여러 값을 받아 서로 OR 입니다(status=SCHEDULED&status=RUNNING · priority=HIGH,CRITICAL 처럼 반복 또는 쉼표). "
                       + "retryExhausted=true 는 재시도 소진(FAILED 이고 retryCount 가 /workloads/retry-policy 의 상한 이상) 행만, false 는 그 나머지입니다.")
    @GetMapping("/workloads/page")
    public ApiResponse<PageResponse<WorkloadResponse>> getWorkloadPage(
            @Parameter(description = "상태 — 여러 개면 반복(status=SCHEDULED&status=RUNNING)이나 쉼표로, 서로 OR. 대소문자 무시, 다른 값은 400. 비우면 전체",
                       schema = @Schema(allowableValues = {"PENDING", "SCHEDULED", "RUNNING", "COMPLETED", "FAILED"}))
            @RequestParam(required = false) List<String> status,
            @Parameter(description = "리소스 종류 — 대소문자 무시, 다른 값은 400. VM 은 종류를 비워 둔 행(생성 때 생략·옛 행)도 포함합니다(실행이 VM 으로 해석하는 것과 같음). 비우면 전체",
                       schema = @Schema(allowableValues = {"VM", "GPU", "NPU", "CONTAINER"}))
            @RequestParam(required = false) String resourceType,
            @Parameter(description = "이름·설명만의 부분 일치(대소문자 무시 · 앞뒤 공백 제거). id·노드는 찾지 않습니다. % 와 _ 는 문자 그대로 찾습니다. 비우면 필터 없음")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "마지막으로 배정된 노드 id(/nodes 의 nodeId 와 같은 값)와 정확히 일치하는 행만(앞뒤 공백은 뺌). 완료·실패 뒤에도 남으므로 지금 도는 것만 보려면 status 와 함께. 비우면 필터 없음")
            @RequestParam(required = false) String assignedNodeId,
            @Parameter(description = "true 면 재시도 소진 행만(status FAILED 이고 retryCount ≥ 상한 — 일괄 재개 대상), false 면 그 나머지. 비우면 필터 없음. 값은 true·false(해석할 수 없는 값은 400)")
            @RequestParam(required = false) Boolean retryExhausted,
            @Parameter(description = "우선순위 — 여러 개면 반복(priority=HIGH&priority=CRITICAL)이나 쉼표로, 서로 OR. 대소문자 무시, 다른 값은 400. 생성 때 생략한 행은 NORMAL. sort=priority 는 등급 순. 비우면 전체",
                       schema = @Schema(allowableValues = {"LOW", "NORMAL", "HIGH", "CRITICAL"}))
            @RequestParam(required = false) List<String> priority,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        Set<Workload.WorkloadStatus> statuses = parseEnums(Workload.WorkloadStatus.class, status, "상태");
        Set<Workload.WorkloadPriority> priorities = parseEnums(Workload.WorkloadPriority.class, priority, "우선순위");
        Workload.ResourceType typeValue = resourceType == null || resourceType.isBlank() ? null
                : parseEnum(Workload.ResourceType.class, resourceType, "리소스 종류");
        return ApiResponse.ok(workloadQueryService.page(statuses, typeValue, keyword, assignedNodeId, retryExhausted, priorities, page, size, sort));
    }

    @Operation(summary = "워크로드 생성",
               description = "name·requiredCpu(≥1)·requiredMemoryMb(≥1) 는 필수입니다. 값이 잘못되면 400 과 사유를 돌려줍니다. "
                       + "resourceType·priority 는 대소문자를 가리지 않습니다. priority 는 LOW·NORMAL·HIGH·CRITICAL 중 하나이고 생략하면 NORMAL 입니다. "
                       + "requiredBandwidthMbps(0 초과)는 선택이며, 데이터 MDC 친화도가 켜진 배치에서 링크 대역폭이 모자란 만큼 링크 점수를 깎습니다.")
    @PostMapping("/workloads")
    public ApiResponse<WorkloadResponse> createWorkload(@RequestBody WorkloadRequest request) {
        return ApiResponse.ok(WorkloadResponse.from(workloadService.create(request)));
    }

    @Operation(summary = "리소스 종류 목록", description = "워크로드·스케줄 생성 화면의 선택지")
    @PortalLog(type = PortalLogType.ACCESS, screen = "스케줄러", action = "조회", content = "'리소스 종류 목록 조회'")
    @GetMapping("/workloads/resource-types")
    public ApiResponse<List<ResourceTypeOptionResponse>> getResourceTypes() {
        return ApiResponse.ok(java.util.Arrays.stream(Workload.ResourceType.values())
                .map(ResourceTypeOptionResponse::of)
                .toList());
    }

    @Operation(summary = "재시도 소진 워크로드 일괄 재개",
               description = "재시도 상한에 걸려 배치 대상에서 빠진 워크로드를 한 번에 대기 상태로 되돌립니다. "
                       + "되살리면 재시도 횟수와 마지막 실패 시각이 지워져 다음 실행의 대상이 됩니다. "
                       + "단건은 상태 전이 API 로도 되며 이 API 는 편의입니다. "
                       + "리소스 종류나 기간 중 하나는 반드시 주어야 하며 조건 없는 전체 재개는 받지 않습니다. "
                       + "되살린 워크로드는 다음 실행에 한꺼번에 몰리므로, 대상이 한 실행 처리 상한을 넘으면 "
                       + "되살리지 않고 400 으로 조건을 좁히라고 답합니다. 기간은 마지막 실패 시각 기준입니다.")
    @PostMapping("/workloads/resume-exhausted")
    public ApiResponse<ResumeExhaustedResponse> resumeExhaustedWorkloads(
            @Parameter(description = "되살릴 리소스 종류. 비우면 종류를 가리지 않는다(기간 필수)")
            @RequestParam(required = false) Workload.ResourceType resourceType,
            @Parameter(description = "마지막 실패 시각의 시작 (ISO-8601)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @Parameter(description = "마지막 실패 시각의 끝 (ISO-8601)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ApiResponse.ok(ResumeExhaustedResponse.of(schedulingService.resumeExhausted(resourceType, from, to)));
    }

    @Operation(summary = "워크로드 실행 상태 전이",
               description = "SCHEDULED → RUNNING → COMPLETED|FAILED. 종료 상태가 되면 노드 예약이 해제된다. "
                       + "재시도 상한에 걸린 FAILED 는 PENDING 으로만 되살릴 수 있다.")
    @PatchMapping("/workloads/{workloadId}/status")
    public ApiResponse<WorkloadResponse> changeWorkloadStatus(@PathVariable Long workloadId,
                                                              @RequestBody StatusChangeRequest request) {
        if (request == null || request.status() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "status 는 필수입니다");
        }
        Workload.WorkloadStatus target = parseEnum(Workload.WorkloadStatus.class, request.status(), "상태");
        return ApiResponse.ok(WorkloadResponse.from(schedulingService.changeStatus(workloadId, target)));
    }

    @Operation(summary = "워크로드 배치 예약 취소",
               description = "SCHEDULED 워크로드의 배정을 풀고 PENDING 으로 되돌립니다(재대기 — 다음 스케줄 실행이 다시 대상으로 잡습니다). "
                       + "재시도 횟수는 바뀌지 않고 배치 이력은 그대로입니다. 현재 결정의 전달 기록이 ACCEPTED 면 FAILED 로 마감되고 비고가 '예약 취소 — 운영자 요청(호출 계정)' 으로 시작합니다(기존 비고는 뒤에 이어짐). "
                       + "없으면 404. SCHEDULED 가 아니거나(RUNNING 은 실행 계층 결과로 끝내십시오) 전달 기록이 전달 중·실행 중이면 409. "
                       + "삭제하려면 먼저 이 API 로 예약을 풀어야 합니다. 재시도 상한에 걸린 워크로드를 단건 배치로 올린 뒤 취소하면 "
                       + "재시도 횟수가 그대로라 스케줄 실행 대상이 아니며 단건 배치로만 다시 배치됩니다.")
    @PostMapping("/workloads/{workloadId}/release")
    public ApiResponse<WorkloadReleaseResponse> releaseWorkload(@PathVariable Long workloadId) {
        SchedulingService.Release r = schedulingService.release(workloadId);
        return ApiResponse.ok(WorkloadReleaseResponse.from(r.workload(), r.releasedMdcId(), r.releasedNodeId(), r.dispatch()));
    }

    /** 잘못된 열거형 값이 500 이 아니라 400 이 되도록 감싼다. 요청 DTO 와 같은 규칙({@link EnumSupport}). */
    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, String label) {
        return EnumSupport.parse(type, value, label);
    }

    /** 반복 파라미터와 쉼표 목록을 모두 받아 열거형 집합으로 — 빈 조각은 건너뛰고 모르는 값은 400. 비면 빈 집합(필터 없음). */
    private <E extends Enum<E>> Set<E> parseEnums(Class<E> type, List<String> values, String label) {
        Set<E> out = EnumSet.noneOf(type);
        if (values == null) return out;
        for (String one : values) {
            if (one == null) continue;
            for (String piece : one.split(",")) {
                if (!piece.isBlank()) out.add(parseEnum(type, piece, label));
            }
        }
        return out;
    }

    @Operation(summary = "워크로드 상세 조회")
    @GetMapping("/workloads/{workloadId}")
    public ApiResponse<WorkloadResponse> getWorkload(@PathVariable Long workloadId) {
        return ApiResponse.ok(workloadRepository.findById(workloadId)
                .map(WorkloadResponse::from)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "워크로드를 찾을 수 없습니다")));
    }

    @Operation(summary = "워크로드 편집",
               description = "부분 갱신 — 보낸 키만 바꾸고 없는 키는 유지합니다. 검증과 사유 문구는 생성과 같습니다. "
                       + "description·dataMdcId·preferredMdcId·maxPowerBudgetWatts·maxTemperatureCelsius·requiredBandwidthMbps 는 null 을 보내면 비웁니다. "
                       + "이 여섯 칸은 JSON 타입도 검사해 문자열 칸에 숫자, 숫자 칸에 숫자 문자열을 보내면 400 입니다(생성은 받아 줌). "
                       + "나머지 칸의 null 은 유지이고 resourceType 은 비울 수 없습니다. "
                       + "상태·배정·재시도·시각 키는 무시하므로 상세 조회 응답을 그대로 보내면 아무것도 바뀌지 않습니다. "
                       + "PENDING·FAILED 만 편집할 수 있고 SCHEDULED·RUNNING·COMPLETED 는 409 입니다(SCHEDULED 는 먼저 예약 취소). "
                       + "재시도 횟수·사유·배정은 바뀌지 않습니다. 오류 순서는 404 → 409 → 400 이고, "
                       + "깨진 JSON 처럼 본문을 읽지 못한 400 은 그보다 먼저 납니다.")
    @PutMapping("/workloads/{workloadId}")
    public ApiResponse<WorkloadResponse> updateWorkload(@PathVariable Long workloadId,
                                                       @RequestBody(required = false) WorkloadUpdateRequest request) {
        return ApiResponse.ok(WorkloadResponse.from(workloadService.update(workloadId, request)));
    }

    @Operation(summary = "워크로드 삭제",
               description = "없으면 404. 실행 중(SCHEDULED·RUNNING)이면 409 — 먼저 상태를 COMPLETED/FAILED 로 바꿔야 합니다. "
                       + "배치 이력은 연구 근거라 함께 지우지 않습니다.")
    @DeleteMapping("/workloads/{workloadId}")
    public ApiResponse<Void> deleteWorkload(@PathVariable Long workloadId) {
        workloadService.delete(workloadId);
        return ApiResponse.ok(null);
    }

    // ── 스케줄링 실행 ───────────────────────────────────────────

    @Operation(summary = "워크로드 스케줄링 실행",
               description = "engine: weighted(가중치), power(전력최적화), temperature(온도최적화). "
                       + "engine 을 비우면 적용 정책의 기본 엔진(defaultEngine), 그것도 없으면 weighted 를 씁니다. "
                       + "dryRun=true 로 부르면 배치하지 않고 제약 평가 결과만 돌려줍니다 — "
                       + "워크로드 상태와 배치 이력을 전혀 바꾸지 않습니다. "
                       + "응답의 policyName 은 이 결정에 적용된 배치 정책 이름이며 실패·모의 배치 결과에도 있습니다.")
    @PostMapping("/workloads/{workloadId}/schedule")
    public ApiResponse<SchedulingResult> scheduleWorkload(@PathVariable Long workloadId,
                                                          @RequestParam(required = false) String engine,
                                                          @RequestParam(defaultValue = "false") boolean dryRun) {
        return ApiResponse.ok(dryRun
                ? schedulingService.dryRun(workloadId, engine)
                : schedulingService.scheduleWorkload(workloadId, engine));
    }

    @Operation(summary = "저장 전 워크로드 모의 배치",
               description = "워크로드 생성 폼 값으로 저장 없이 모의 배치 결과를 돌려줍니다 — 단건 배치 dryRun=true 와 같은 일곱 키. "
                       + "검증·사유 문구는 생성과 같고, 종류를 비우면 VM 으로 읽어 정책을 고릅니다. "
                       + "engine 을 비우면 그 정책의 기본 엔진(defaultEngine), 그것도 없으면 weighted 를 씁니다. "
                       + "워크로드·배치 이력·정책을 만들지 않으며(정책이 하나도 없어도 default 를 저장하지 않음) "
                       + "200 이 실제 배치 성공을 보장하지 않습니다(호출 시점 노드 상태). 호출마다 메트릭을 수집하므로 버튼 단위로 부릅니다.")
    @PostMapping("/workloads/dry-run")
    public ApiResponse<SchedulingResult> dryRunDraft(@RequestBody WorkloadRequest request,
                                                     @RequestParam(required = false) String engine) {
        return ApiResponse.ok(schedulingService.dryRun(workloadService.draft(request), engine));
    }

    // ── 노드 자원 현황 ──────────────────────────────────────────

    @Operation(summary = "실시간 노드 자원 현황",
               description = "스케줄링 전 후보 노드 상태 미리보기. 디스크 축(diskCapacityGb·reservedDiskGb·availableDiskGb, GB)은 "
                       + "C1 디스크 검사와 같은 식이며 총량 0 은 모름 — 그 노드는 디스크 요구 배치에서 디스크 검사를 건너뜁니다.")
    @GetMapping("/nodes")
    public ApiResponse<List<NodeResponse>> getNodeResources() {
        return ApiResponse.ok(schedulingService.getNodeResources().stream().map(NodeResponse::from).toList());
    }

    @Operation(summary = "수집 제외 노드 목록",
               description = "메트릭 저장소가 상한 안에 응답하지 않아 뺀 노드(사유: 메트릭 질의 응답 없음)도 함께 나옵니다 — 저장소가 멈춰도 배치는 나머지 노드로 계속됩니다. 핵심 메트릭(CPU 사용률·코어 수·메모리)을 조회 실패나 시리즈 없음으로 알 수 없어 후보에서 뺀 노드와 사유입니다. "
                       + "노드 목록(/nodes)은 배열 계약을 지키느라 이들을 담지 않으므로 여기서 확인합니다. 정상이면 빈 배열입니다.")
    @GetMapping("/nodes/excluded")
    public ApiResponse<List<ExcludedNodeResponse>> getExcludedNodes() {
        return ApiResponse.ok(schedulingService.nodeSnapshot().excludedNodes().stream()
                .map(ExcludedNodeResponse::from).toList());
    }

    @Operation(summary = "노드 단건 자원 현황",
               description = "가용량·예약분·전력·온도·디스크 축(총량 0 = 모름)을 한 대만 조회합니다.")
    @GetMapping("/nodes/{nodeId}")
    public ApiResponse<NodeResponse> getNodeResource(@PathVariable String nodeId) {
        return ApiResponse.ok(NodeResponse.from(schedulingService.getNodeResource(nodeId)));
    }

    // ── 전력 효율성 평가 ────────────────────────────────────────

    @Operation(summary = "서버별 전력 효율성 평가",
               description = "노드별 전력 효율 지표(PEI)·전력 비례성·여유율과 클러스터 내 상대 등급(A~D)·순위·권고를 조회합니다. "
                       + "전력 메트릭이 없는 노드는 evaluable=false 이고 파생 지표가 null 입니다. "
                       + "상대 등급은 평가 가능한 노드가 4대 이상일 때만 매겨집니다. "
                       + "코어 수가 다른 서버를 견줄 때는 usedCoresPerKw(사용 코어/kW)·coreRank 를 보십시오. rank·grade·recommendation 과 요약의 averagePeiRaw·minPeiRaw·maxPeiRaw 는 PEI 원값(사용률 ÷ W) 기준이라 코어 수를 보지 않습니다.")
    @GetMapping("/nodes/power-efficiency")
    public ApiResponse<PowerEfficiencyResponse> getPowerEfficiency() {
        return ApiResponse.ok(powerEfficiencyService.evaluateCurrent());
    }

    @Operation(summary = "노드 전력 효율 추세",
               description = "전력·CPU 사용률·PEI 의 시계열을 조회합니다. 메트릭 저장소에서 그때그때 읽고 저장하지 않습니다. "
                       + "from·to 를 비우면 최근 24시간, step 기본값은 5m 입니다(예: 30s, 5m, 1h, 1d). "
                       + "데이터가 없으면 빈 배열과 사유를 돌려줍니다.")
    @GetMapping("/nodes/{nodeId}/power-efficiency/trend")
    public ApiResponse<PowerEfficiencyTrendResponse> getPowerEfficiencyTrend(
            @PathVariable String nodeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String step) {
        return ApiResponse.ok(powerEfficiencyService.trend(nodeId, from, to, step));
    }

    // ── 배치 진단 ──────────────────────────────────────────────

    @Operation(summary = "배치 제약 조건 설명",
               description = "배치 후보를 거를 때 어떤 제약이 어떤 순서로 적용되는지, "
                       + "각 임계값이 어디서 오는지 설명합니다.")
    @GetMapping("/constraints")
    public ApiResponse<List<ConstraintDescriptionResponse>> getConstraints() {
        return ApiResponse.ok(ConstraintDescriptionResponse.all());
    }

    @Operation(summary = "냉각 대기 노드",
               description = "임계 온도를 넘겨 일정 시간 배치 대상에서 제외된 노드와 남은 대기 시간입니다. "
                       + "냉각 대기는 그 대기를 건 임계 온도와 같거나 더 낮은 임계 온도로 평가하는 배치만 막으며, "
                       + "남은 대기 시간은 그 노드의 냉각 대기가 모두 풀릴 때까지입니다. "
                       + "냉각 대기는 메모리에만 두므로 앱을 다시 시작하면 초기화됩니다.")
    @GetMapping("/thermal/cooldowns")
    public ApiResponse<List<ThermalCooldownResponse>> getThermalCooldowns() {
        return ApiResponse.ok(schedulingService.getCoolingDownNodes());
    }

    @Operation(summary = "냉각 대기 해제",
               description = "노드의 냉각 대기를 모든 임계 온도에서 한 번에 지우고, 지운 임계 온도(오름차순)와 해제 직전에 남아 있던 대기 시간을 돌려줍니다. "
                       + "대기 시간만 건너뜁니다 — 다음 평가가 현재 온도로 다시 판정하므로 아직 임계 온도를 넘는 노드는 다시 빠지고 대기가 다시 걸립니다. "
                       + "현재 온도가 임계 온도 아래로 내려간 것을 확인한 뒤 쓰십시오. "
                       + "노드를 수집하지 않으므로 목록에 없는(수집에서 빠진) 노드의 대기도 풀 수 있습니다. "
                       + "살아 있는 대기가 없으면(만료만 남았어도) 404. DB 에 쓰지 않고, 냉각 대기는 앱을 다시 시작하면 초기화되는 그대로입니다.")
    @DeleteMapping("/thermal/cooldowns/{nodeId}")
    public ApiResponse<ThermalCooldownReleaseResponse> releaseThermalCooldown(@PathVariable String nodeId) {
        return ApiResponse.ok(schedulingService.releaseCooldown(nodeId));
    }

    // ── 스케줄링 엔진 ───────────────────────────────────────────

    @Operation(summary = "사용 가능한 스케줄링 엔진 목록")
    @GetMapping("/engines")
    public ApiResponse<List<EngineResponse>> getEngines() {
        return ApiResponse.ok(schedulingService.getAvailableEngines());
    }

    // ── 스케줄링 이력 ───────────────────────────────────────────

    @Operation(summary = "스케줄링 이력 조회",
               description = "최신순 배열로 돌려줍니다. 이력은 삭제되지 않아 계속 늘어나므로 최근 "
                       + HISTORY_MAX + "건까지만 반환합니다. 전체가 필요하면 /history/page 를 쓰세요.")
    @GetMapping("/history")
    public ApiResponse<List<SchedulingHistoryResponse>> getHistory() {
        return ApiResponse.ok(capped(historyRepository.findAllByOrderByCreatedAtDesc(Limit.of(PROBE)),
                "전체 이력"));
    }

    @Operation(summary = "스케줄링 이력 페이징 조회",
               description = "배열로 최근 " + HISTORY_MAX + "건까지만 주는 /history 와 달리, 여기는 상한 없이 페이지로 전량에 닿습니다"
                       + "(총건수도 잘리지 않습니다). page 는 1부터, size 최대 100, "
                       + "sort 는 '{필드},{asc|desc}' (createdAt·finalScore·workloadName·success). 같은 값으로 정렬된 행은 id 로 한 번 더 정렬해 페이지 경계에서 겹치거나 빠지지 않습니다. "
                       + "workloadId·success·engineName·scheduleRunId·exclusionCode·from·to 필터는 선택입니다. "
                       + "success=true 는 성공 여부가 비어 있는 기존 행도 포함하고(비어 있으면 성공으로 봅니다), success=false 는 명시적 실패만 돌려줍니다. "
                       + "scheduleRunId·exclusionCode·from·to 의 규칙은 각 파라미터 설명에 있습니다.")
    @GetMapping("/history/page")
    public ApiResponse<PageResponse<SchedulingHistoryResponse>> getHistoryPage(
            @RequestParam(required = false) Long workloadId,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) String engineName,
            @Parameter(description = "지정한 스케줄 실행이 만든 결정만 — 실행 상세(/schedules/{id}/runs/{runId})의 decisions[] 와 같은 결정 집합이라 "
                    + "총건수가 같고 결정의 historyId 가 여기 행의 id 다. 실행 없이 만든 결정, 스케줄 삭제나 실행 이력 보존 기간 정리로 연결이 끊긴 "
                    + "결정은 실행 id 가 비어 있어 어느 값에도 걸리지 않고, 없는 실행 id 는 빈 페이지")
            @RequestParam(required = false) Long scheduleRunId,
            @Parameter(description = "그 코드로 센 후보가 하나 이상 있는 결정만(한 노드는 먼저 걸린 제약 하나로만 센다). 코드 이름 대문자 그대로 — "
                    + "소문자·모르는 값은 400 과 가능한 값 목록, 빈 값은 필터 없음. 집계가 없는 결정과 옛 행은 어떤 코드에도 걸리지 않는다")
            @RequestParam(required = false) ExclusionReason exclusionCode,
            @Parameter(description = "결정 시각(createdAt) 창의 시작, 포함 (ISO-8601, 오프셋 필수 — 쿼리의 + 는 %2B 로). 비우면 하한 없음")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @Parameter(description = "결정 시각(createdAt) 창의 끝, 미포함 (ISO-8601, 오프셋 필수 — 쿼리의 + 는 %2B 로). 비우면 상한 없음. from 이 to 보다 뒤면 400")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return ApiResponse.ok(historyQueryService.page(workloadId, success, engineName, scheduleRunId, exclusionCode,
                from, to, page, size, sort));
    }

    @Operation(summary = "스케줄링 이력 제외 사유 코드 합계",
               description = "창·필터 안 배치 결정의 제외 사유 코드 집계(exclusionCodes)를 서버가 더해 코드별 결정 수·후보 수를 돌려줍니다. "
                       + "/history/page 와 같은 조건식을 쓰므로 응답의 from·to 를 그대로 넘긴 같은 필터의 exclusionCode=CODE 총건수가 codes[CODE].decisions 와 같습니다(skipped 가 0 일 때). "
                       + "from·to 를 둘 다 비우면 최근 " + SchedulingHistoryQueryService.DEFAULT_SUMMARY_WINDOW_DAYS + "일, 한쪽만 비우면 그쪽은 경계 없음이고 "
                       + "응답이 실제 창을 돌려줍니다. 등장한 코드만 실리며, 집계가 없는 결정은 decisions 에만 셉니다. "
                       + "집계 열에 인덱스가 없어 이력이 크면 창과 workloadId·scheduleRunId 로 좁혀 부르십시오.")
    @GetMapping("/history/exclusion-summary")
    public ApiResponse<ExclusionSummaryResponse> getHistoryExclusionSummary(
            @RequestParam(required = false) Long workloadId,
            @Parameter(description = "true 는 성공 여부가 비어 있는 기존 행 포함, false 는 명시적 실패만 — /history/page 와 같다")
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) String engineName,
            @Parameter(description = "지정한 스케줄 실행이 만든 결정만 — /history/page 와 같다")
            @RequestParam(required = false) Long scheduleRunId,
            @Parameter(description = "결정 시각(createdAt) 창의 시작, 포함 (ISO-8601, 오프셋 필수 — 쿼리의 + 는 %2B 로). 둘 다 비우면 최근 "
                    + SchedulingHistoryQueryService.DEFAULT_SUMMARY_WINDOW_DAYS + "일, to 만 주면 하한 없음")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @Parameter(description = "결정 시각(createdAt) 창의 끝, 미포함 (ISO-8601, 오프셋 필수 — 쿼리의 + 는 %2B 로). 둘 다 비우면 지금, from 만 주면 상한 없음. from 이 to 보다 뒤면 400")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ApiResponse.ok(historyQueryService.summary(workloadId, success, engineName, scheduleRunId, from, to));
    }

    @Operation(summary = "워크로드별 스케줄링 이력",
               description = "최신순 배열입니다. 전체 이력과 같은 이유로 최근 " + HISTORY_MAX + "건까지만 반환합니다.")
    @GetMapping("/workloads/{workloadId}/history")
    public ApiResponse<List<SchedulingHistoryResponse>> getWorkloadHistory(@PathVariable Long workloadId) {
        return ApiResponse.ok(capped(
                historyRepository.findByWorkloadIdOrderByCreatedAtDesc(workloadId, Limit.of(PROBE)),
                "워크로드 " + workloadId + " 이력"));
    }

    /**
     * 이력 목록을 응답 DTO 로 옮기고, 상한에 닿았으면 로그로 알린다.
     *
     * <p>응답에는 잘렸다는 표시를 넣지 않는다 — 넣으면 배열이던 응답 형태가 바뀌어 프런트가 영향을 받는다.
     * 대신 운영 로그에 남겨, 잘린 목록을 화면에서 보고 있다는 사실을 알 수 있게 한다.</p>
     */
    private List<SchedulingHistoryResponse> capped(List<SchedulingHistory> rows, String what) {
        List<SchedulingHistory> kept = rows;
        if (rows.size() > HISTORY_MAX) {
            log.warn("{}가 반환 상한 {}건을 넘어 잘렸습니다 — 더 오래된 이력은 /history/page 로 조회하세요",
                    what, HISTORY_MAX);
            kept = rows.subList(0, HISTORY_MAX);
        }
        return kept.stream().map(SchedulingHistoryResponse::from).toList();
    }
}
