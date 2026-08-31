package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

/**
 * 스케줄링 조율 — 노드 수집, 정책 선택, 예약 반영까지 준비한 뒤 배치를 위임한다.
 *
 * <p>메트릭 조회(Thanos)는 트랜잭션 밖에서 하고, DB 저장은 {@link PlacementService} 의
 * 짧은 트랜잭션에 맡긴다.</p>
 */
@Service
public class SchedulingService {

    /** 예약으로 잡아 둘 워크로드 상태. 배치됐지만 아직 끝나지 않은 것들이다. */
    private static final List<Workload.WorkloadStatus> RESERVED_STATUSES =
            List.of(Workload.WorkloadStatus.SCHEDULED, Workload.WorkloadStatus.RUNNING);

    private final WorkloadRepository workloadRepository;
    private final SchedulingPolicyRepository policyRepository;
    private final NodeResourceCollector nodeCollector;
    private final PlacementService placementService;
    private final EngineRegistry engineRegistry;
    private final ConstraintEvaluator constraintEvaluator;
    private final SchedulerProperties properties;

    public SchedulingService(WorkloadRepository workloadRepository,
                             SchedulingPolicyRepository policyRepository,
                             NodeResourceCollector nodeCollector,
                             PlacementService placementService,
                             EngineRegistry engineRegistry,
                             ConstraintEvaluator constraintEvaluator,
                             SchedulerProperties properties) {
        this.workloadRepository = workloadRepository;
        this.policyRepository = policyRepository;
        this.nodeCollector = nodeCollector;
        this.placementService = placementService;
        this.engineRegistry = engineRegistry;
        this.constraintEvaluator = constraintEvaluator;
        this.properties = properties;
    }

    /**
     * 워크로드를 스케줄링한다.
     *
     * @param workloadId 스케줄링할 워크로드 ID
     * @param engineId   사용할 엔진 (weighted, power, temperature)
     */
    public SchedulingResult scheduleWorkload(Long workloadId, String engineId) {
        Workload workload = workloadRepository.findById(workloadId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "워크로드를 찾을 수 없습니다: " + workloadId));

        if (engineId != null && !engineRegistry.contains(engineId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "알 수 없는 스케줄링 엔진입니다: " + engineId);
        }

        PlacementContext context = createContext(resolvePolicy());
        if (context.isEmpty()) {
            return SchedulingResult.failure("활성 노드가 없습니다");
        }

        return placementService.place(workload, context, engineId);
    }

    /**
     * 노드 상태를 수집하고 예약분을 반영한 배치 컨텍스트를 만든다.
     * 실행 1회당 한 번만 호출해 모든 워크로드가 같은 스냅샷을 보게 한다.
     */
    public PlacementContext createContext(SchedulingPolicy policy) {
        PlacementContext context =
                PlacementContext.of(nodeCollector.collectAll(), policy, constraintEvaluator);
        applyReservations(context);
        return context;
    }

    /**
     * 현재 노드 자원 상태를 조회한다 (스케줄링 전 미리보기).
     */
    public List<NodeResource> getNodeResources() {
        SchedulingPolicy policy = policyRepository.findByActiveTrue()
                .orElseGet(() -> new SchedulingPolicy("default", "기본 스케줄링 정책"));

        PlacementContext context = createContext(policy);
        for (NodeResource node : context.nodes()) {
            node.setThermalState(constraintEvaluator.thermalState(node, policy));
        }
        return context.nodes();
    }

    /**
     * 사용 가능한 스케줄링 엔진 목록.
     */
    public List<Map<String, String>> getAvailableEngines() {
        return engineRegistry.describe();
    }

    /** 활성 정책을 찾고, 없으면 기본 정책을 만들어 둔다. */
    public SchedulingPolicy resolvePolicy() {
        return policyRepository.findByActiveTrue()
                .orElseGet(() -> policyRepository.save(new SchedulingPolicy("default", "기본 스케줄링 정책")));
    }

    /**
     * 이미 배치됐지만 아직 메트릭에 드러나지 않은 워크로드를 노드 예약으로 반영한다.
     * 오래 남아 있는 워크로드가 자원을 영구히 묶지 않도록 TTL 을 넘긴 것은 제외한다.
     */
    private void applyReservations(PlacementContext context) {
        if (context.isEmpty()) return;

        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(properties.getReservationTtlHours());

        for (Workload workload : workloadRepository.findByStatusIn(RESERVED_STATUSES)) {
            if (workload.getAssignedNodeId() == null) continue;
            if (workload.getScheduledAt() != null && workload.getScheduledAt().isBefore(cutoff)) continue;

            context.reserve(workload.getAssignedNodeId(), workload);
        }
    }
}
