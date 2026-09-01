package com.innogrid.aims.scheduler.controller;

import java.time.OffsetDateTime;
import java.util.Map;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.ConstraintDescriptionResponse;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;
import com.innogrid.aims.scheduler.service.PowerEfficiencyService;
import com.innogrid.aims.scheduler.service.SchedulingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "스케줄러", description = "워크로드 스케줄링 (제약조건/전력/온도 기반)")
@RestController
@RequestMapping("/api/v1/scheduler")
public class SchedulerController {

    private final SchedulingService schedulingService;
    private final PowerEfficiencyService powerEfficiencyService;
    private final WorkloadRepository workloadRepository;
    private final SchedulingHistoryRepository historyRepository;

    public SchedulerController(SchedulingService schedulingService,
                               PowerEfficiencyService powerEfficiencyService,
                               WorkloadRepository workloadRepository,
                               SchedulingHistoryRepository historyRepository) {
        this.schedulingService = schedulingService;
        this.powerEfficiencyService = powerEfficiencyService;
        this.workloadRepository = workloadRepository;
        this.historyRepository = historyRepository;
    }

    // ── 워크로드 ────────────────────────────────────────────────

    @Operation(summary = "워크로드 목록 조회")
    @GetMapping("/workloads")
    public ApiResponse<?> listWorkloads(@RequestParam(required = false) String status) {
        if (status != null) {
            return ApiResponse.ok(workloadRepository.findByStatusOrderByCreatedAtDesc(
                    parseEnum(Workload.WorkloadStatus.class, status, "상태")));
        }
        return ApiResponse.ok(workloadRepository.findAllByOrderByCreatedAtDesc());
    }

    @Operation(summary = "워크로드 생성")
    @PostMapping("/workloads")
    public ApiResponse<?> createWorkload(@RequestBody Map<String, Object> request) {
        Workload workload = new Workload(
                (String) request.get("name"),
                ((Number) request.get("requiredCpu")).intValue(),
                ((Number) request.get("requiredMemoryMb")).intValue());
        if (request.containsKey("description"))
            workload.setDescription((String) request.get("description"));
        if (request.containsKey("requiredGpu"))
            workload.setRequiredGpu(((Number) request.get("requiredGpu")).intValue());
        if (request.containsKey("requiredDiskGb"))
            workload.setRequiredDiskGb(((Number) request.get("requiredDiskGb")).intValue());
        if (request.containsKey("preferredMdcId"))
            workload.setPreferredMdcId((String) request.get("preferredMdcId"));
        if (request.containsKey("maxPowerBudgetWatts"))
            workload.setMaxPowerBudgetWatts(((Number) request.get("maxPowerBudgetWatts")).doubleValue());
        if (request.containsKey("maxTemperatureCelsius"))
            workload.setMaxTemperatureCelsius(((Number) request.get("maxTemperatureCelsius")).doubleValue());
        if (request.containsKey("priority"))
            workload.setPriority(parseEnum(Workload.WorkloadPriority.class,
                    (String) request.get("priority"), "우선순위"));
        if (request.containsKey("resourceType"))
            workload.setResourceType(parseEnum(Workload.ResourceType.class,
                    (String) request.get("resourceType"), "리소스 종류"));
        if (request.containsKey("dataMdcId"))
            workload.setDataMdcId((String) request.get("dataMdcId"));

        return ApiResponse.ok(workloadRepository.save(workload));
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
    public ApiResponse<?> changeWorkloadStatus(@PathVariable Long workloadId,
                                               @RequestBody Map<String, Object> request) {
        Object raw = request.get("status");
        if (raw == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "status 는 필수입니다");
        }
        Workload.WorkloadStatus target =
                parseEnum(Workload.WorkloadStatus.class, raw.toString(), "상태");
        return ApiResponse.ok(schedulingService.changeStatus(workloadId, target));
    }

    /** 잘못된 열거형 값이 500 이 아니라 400 이 되도록 감싼다. */
    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, String label) {
        if (value == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, label + " 값이 필요합니다");
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, String.format(
                    "지원하지 않는 %s 입니다: %s (가능한 값: %s)",
                    label, value, java.util.Arrays.toString(type.getEnumConstants())));
        }
    }

    @Operation(summary = "워크로드 상세 조회")
    @GetMapping("/workloads/{workloadId}")
    public ApiResponse<?> getWorkload(@PathVariable Long workloadId) {
        return ApiResponse.ok(workloadRepository.findById(workloadId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "워크로드를 찾을 수 없습니다")));
    }

    @Operation(summary = "워크로드 삭제")
    @DeleteMapping("/workloads/{workloadId}")
    public ApiResponse<Void> deleteWorkload(@PathVariable Long workloadId) {
        workloadRepository.deleteById(workloadId);
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
    public ApiResponse<?> getNodeResources() {
        return ApiResponse.ok(schedulingService.getNodeResources());
    }

    @Operation(summary = "노드 단건 자원 현황",
               description = "가용량·예약분·전력·온도를 한 대만 조회합니다.")
    @GetMapping("/nodes/{nodeId}")
    public ApiResponse<?> getNodeResource(@PathVariable String nodeId) {
        return ApiResponse.ok(schedulingService.getNodeResource(nodeId));
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
    public ApiResponse<?> getHistory() {
        return ApiResponse.ok(historyRepository.findAllByOrderByCreatedAtDesc());
    }

    @Operation(summary = "워크로드별 스케줄링 이력")
    @GetMapping("/workloads/{workloadId}/history")
    public ApiResponse<?> getWorkloadHistory(@PathVariable Long workloadId) {
        return ApiResponse.ok(historyRepository.findByWorkloadIdOrderByCreatedAtDesc(workloadId));
    }
}
