package com.innogrid.aims.scheduler.controller;

import java.time.OffsetDateTime;
import java.util.Map;

import java.util.List;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.ConstraintDescriptionResponse;
import com.innogrid.aims.scheduler.dto.ExcludedNodeResponse;
import com.innogrid.aims.scheduler.dto.NodeResponse;
import com.innogrid.aims.scheduler.dto.SchedulingHistoryResponse;
import com.innogrid.aims.scheduler.dto.StatusChangeRequest;
import com.innogrid.aims.scheduler.dto.WorkloadRequest;
import com.innogrid.aims.scheduler.dto.WorkloadResponse;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;
import com.innogrid.aims.scheduler.service.EnumSupport;
import com.innogrid.aims.scheduler.service.PowerEfficiencyService;
import com.innogrid.aims.scheduler.service.SchedulingHistoryQueryService;
import com.innogrid.aims.scheduler.service.SchedulingService;
import com.innogrid.aims.scheduler.service.WorkloadService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    private final SchedulingService schedulingService;
    private final PowerEfficiencyService powerEfficiencyService;
    private final WorkloadService workloadService;
    private final WorkloadRepository workloadRepository;
    private final SchedulingHistoryRepository historyRepository;
    private final SchedulingHistoryQueryService historyQueryService;

    public SchedulerController(SchedulingService schedulingService,
                               PowerEfficiencyService powerEfficiencyService,
                               WorkloadService workloadService,
                               WorkloadRepository workloadRepository,
                               SchedulingHistoryRepository historyRepository,
                               SchedulingHistoryQueryService historyQueryService) {
        this.schedulingService = schedulingService;
        this.powerEfficiencyService = powerEfficiencyService;
        this.workloadService = workloadService;
        this.workloadRepository = workloadRepository;
        this.historyRepository = historyRepository;
        this.historyQueryService = historyQueryService;
    }

    // ── 워크로드 ────────────────────────────────────────────────

    @Operation(summary = "워크로드 목록 조회")
    @GetMapping("/workloads")
    public ApiResponse<List<WorkloadResponse>> listWorkloads(@RequestParam(required = false) String status) {
        List<Workload> found = status != null
                ? workloadRepository.findByStatusOrderByCreatedAtDesc(
                        parseEnum(Workload.WorkloadStatus.class, status, "상태"))
                : workloadRepository.findAllByOrderByCreatedAtDesc();
        return ApiResponse.ok(found.stream().map(WorkloadResponse::from).toList());
    }

    @Operation(summary = "워크로드 생성",
               description = "name·requiredCpu(≥1)·requiredMemoryMb(≥1) 는 필수입니다. 값이 잘못되면 400 과 사유를 돌려줍니다. "
                       + "resourceType·priority 는 대소문자를 가리지 않습니다.")
    @PostMapping("/workloads")
    public ApiResponse<WorkloadResponse> createWorkload(@RequestBody WorkloadRequest request) {
        return ApiResponse.ok(WorkloadResponse.from(workloadService.create(request)));
    }

    @Operation(summary = "리소스 종류 목록", description = "워크로드·스케줄 생성 화면의 선택지")
    @GetMapping("/workloads/resource-types")
    public ApiResponse<?> getResourceTypes() {
        return ApiResponse.ok(java.util.Arrays.stream(Workload.ResourceType.values())
                .map(t -> Map.of("code", t.name(), "label", t.name()))
                .toList());
    }

    @Operation(summary = "워크로드 실행 상태 전이",
               description = "SCHEDULED → RUNNING → COMPLETED|FAILED. 종료 상태가 되면 노드 예약이 해제된다.")
    @PatchMapping("/workloads/{workloadId}/status")
    public ApiResponse<WorkloadResponse> changeWorkloadStatus(@PathVariable Long workloadId,
                                                              @RequestBody StatusChangeRequest request) {
        if (request == null || request.status() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "status 는 필수입니다");
        }
        Workload.WorkloadStatus target = parseEnum(Workload.WorkloadStatus.class, request.status(), "상태");
        return ApiResponse.ok(WorkloadResponse.from(schedulingService.changeStatus(workloadId, target)));
    }

    /** 잘못된 열거형 값이 500 이 아니라 400 이 되도록 감싼다. 요청 DTO 와 같은 규칙({@link EnumSupport}). */
    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, String label) {
        return EnumSupport.parse(type, value, label);
    }

    @Operation(summary = "워크로드 상세 조회")
    @GetMapping("/workloads/{workloadId}")
    public ApiResponse<WorkloadResponse> getWorkload(@PathVariable Long workloadId) {
        return ApiResponse.ok(workloadRepository.findById(workloadId)
                .map(WorkloadResponse::from)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "워크로드를 찾을 수 없습니다")));
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
                       + "dryRun=true 로 부르면 배치하지 않고 제약 평가 결과만 돌려줍니다 — "
                       + "워크로드 상태와 배치 이력을 전혀 바꾸지 않습니다.")
    @PostMapping("/workloads/{workloadId}/schedule")
    public ApiResponse<?> scheduleWorkload(@PathVariable Long workloadId,
                                           @RequestParam(defaultValue = "weighted") String engine,
                                           @RequestParam(defaultValue = "false") boolean dryRun) {
        return ApiResponse.ok(dryRun
                ? schedulingService.dryRun(workloadId, engine)
                : schedulingService.scheduleWorkload(workloadId, engine));
    }

    // ── 노드 자원 현황 ──────────────────────────────────────────

    @Operation(summary = "실시간 노드 자원 현황", description = "스케줄링 전 후보 노드 상태 미리보기")
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
               description = "가용량·예약분·전력·온도를 한 대만 조회합니다.")
    @GetMapping("/nodes/{nodeId}")
    public ApiResponse<NodeResponse> getNodeResource(@PathVariable String nodeId) {
        return ApiResponse.ok(NodeResponse.from(schedulingService.getNodeResource(nodeId)));
    }

    // ── 전력 효율성 평가 ────────────────────────────────────────

    @Operation(summary = "서버별 전력 효율성 평가",
               description = "노드별 전력 효율 지표(PEI)·전력 비례성·여유율과 클러스터 내 상대 등급(A~D)·순위·권고를 조회합니다. "
                       + "전력 메트릭이 없는 노드는 evaluable=false 이고 파생 지표가 null 입니다. "
                       + "상대 등급은 평가 가능한 노드가 4대 이상일 때만 매겨집니다.")
    @GetMapping("/nodes/power-efficiency")
    public ApiResponse<?> getPowerEfficiency() {
        return ApiResponse.ok(powerEfficiencyService.evaluateCurrent());
    }

    @Operation(summary = "노드 전력 효율 추세",
               description = "전력·CPU 사용률·PEI 의 시계열을 조회합니다. 메트릭 저장소에서 그때그때 읽고 저장하지 않습니다. "
                       + "from·to 를 비우면 최근 24시간, step 기본값은 5m 입니다(예: 30s, 5m, 1h, 1d). "
                       + "데이터가 없으면 빈 배열과 사유를 돌려줍니다.")
    @GetMapping("/nodes/{nodeId}/power-efficiency/trend")
    public ApiResponse<?> getPowerEfficiencyTrend(
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
    public ApiResponse<?> getConstraints() {
        return ApiResponse.ok(ConstraintDescriptionResponse.all());
    }

    @Operation(summary = "냉각 대기 노드",
               description = "임계 온도를 넘겨 일정 시간 배치 대상에서 제외된 노드와 남은 대기 시간입니다. "
                       + "냉각 대기는 메모리에만 두므로 앱을 다시 시작하면 초기화됩니다.")
    @GetMapping("/thermal/cooldowns")
    public ApiResponse<?> getThermalCooldowns() {
        return ApiResponse.ok(schedulingService.getCoolingDownNodes());
    }

    // ── 스케줄링 엔진 ───────────────────────────────────────────

    @Operation(summary = "사용 가능한 스케줄링 엔진 목록")
    @GetMapping("/engines")
    public ApiResponse<?> getEngines() {
        return ApiResponse.ok(schedulingService.getAvailableEngines());
    }

    // ── 스케줄링 이력 ───────────────────────────────────────────

    @Operation(summary = "스케줄링 이력 조회")
    @GetMapping("/history")
    public ApiResponse<List<SchedulingHistoryResponse>> getHistory() {
        return ApiResponse.ok(historyRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(SchedulingHistoryResponse::from).toList());
    }

    @Operation(summary = "스케줄링 이력 페이징 조회",
               description = "전량 배열인 /history 와 달리 한 페이지만 돌려줍니다(대량 이력은 이 API 를 쓰세요). page 는 1부터, size 최대 100, "
                       + "sort 는 '{필드},{asc|desc}' (createdAt·finalScore·workloadName·success). workloadId·success·engineName 필터는 선택입니다.")
    @GetMapping("/history/page")
    public ApiResponse<PageResponse<SchedulingHistoryResponse>> getHistoryPage(
            @RequestParam(required = false) Long workloadId,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) String engineName,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return ApiResponse.ok(historyQueryService.page(workloadId, success, engineName, page, size, sort));
    }

    @Operation(summary = "워크로드별 스케줄링 이력")
    @GetMapping("/workloads/{workloadId}/history")
    public ApiResponse<List<SchedulingHistoryResponse>> getWorkloadHistory(@PathVariable Long workloadId) {
        return ApiResponse.ok(historyRepository.findByWorkloadIdOrderByCreatedAtDesc(workloadId).stream()
                .map(SchedulingHistoryResponse::from).toList());
    }
}
