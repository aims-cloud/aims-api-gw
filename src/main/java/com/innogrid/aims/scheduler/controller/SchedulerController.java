package com.innogrid.aims.scheduler.controller;

import java.util.Map;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;
import com.innogrid.aims.scheduler.service.SchedulingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "스케줄러", description = "워크로드 스케줄링 (제약조건/전력/온도 기반)")
@RestController
@RequestMapping("/api/v1/scheduler")
public class SchedulerController {

    private final SchedulingService schedulingService;
    private final WorkloadRepository workloadRepository;
    private final SchedulingHistoryRepository historyRepository;

    public SchedulerController(SchedulingService schedulingService,
                               WorkloadRepository workloadRepository,
                               SchedulingHistoryRepository historyRepository) {
        this.schedulingService = schedulingService;
        this.workloadRepository = workloadRepository;
        this.historyRepository = historyRepository;
    }

    // ── 워크로드 ────────────────────────────────────────────────

    @Operation(summary = "워크로드 목록 조회")
    @GetMapping("/workloads")
    public ApiResponse<?> listWorkloads(@RequestParam(required = false) String status) {
        if (status != null) {
            return ApiResponse.ok(workloadRepository.findByStatusOrderByCreatedAtDesc(
                    Workload.WorkloadStatus.valueOf(status.toUpperCase())));
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
            workload.setPriority(Workload.WorkloadPriority.valueOf(((String) request.get("priority")).toUpperCase()));

        return ApiResponse.ok(workloadRepository.save(workload));
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
               description = "engine: weighted(가중치), power(전력최적화), temperature(온도최적화)")
    @PostMapping("/workloads/{workloadId}/schedule")
    public ApiResponse<?> scheduleWorkload(@PathVariable Long workloadId,
                                           @RequestParam(defaultValue = "weighted") String engine) {
        return ApiResponse.ok(schedulingService.scheduleWorkload(workloadId, engine));
    }

    // ── 노드 자원 현황 ──────────────────────────────────────────

    @Operation(summary = "실시간 노드 자원 현황", description = "스케줄링 전 후보 노드 상태 미리보기")
    @GetMapping("/nodes")
    public ApiResponse<?> getNodeResources() {
        return ApiResponse.ok(schedulingService.getNodeResources());
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
