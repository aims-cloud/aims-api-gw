package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

        PlacementContext context = createContext(resolvePolicy(workload.getResourceType()));
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
        NodeResourceCollector.Snapshot snapshot = nodeCollector.collect();
        PlacementContext context =
                PlacementContext.of(snapshot.nodes(), policy, constraintEvaluator)
                        .withExcluded(snapshot.excluded());
        applyReservations(context);
        return context;
    }

    /**
     * 현재 노드 자원 상태를 조회한다 (스케줄링 전 미리보기).
     */
    public List<NodeResource> getNodeResources() {
        SchedulingPolicy policy = policyRepository.findFirstByActiveTrueAndResourceTypeIsNull()
                .orElseGet(() -> new SchedulingPolicy("default", "기본 스케줄링 정책"));

        PlacementContext context = createContext(policy);
        for (NodeResource node : context.nodes()) {
            node.setThermalState(constraintEvaluator.thermalState(node, policy));
        }
        return context.nodes();
    }

    /**
     * 노드 한 대의 현재 상태. 없으면 404.
     */
    public NodeResource getNodeResource(String nodeId) {
        return getNodeResources().stream()
                .filter(n -> n.getNodeId().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "노드를 찾을 수 없습니다: " + nodeId));
    }

    /**
     * 임계 온도를 넘겨 냉각 대기 중인 노드 목록.
     * 온도 제어(연구계획 B3)가 실제로 배치를 막고 있는지 밖에서 확인할 수단이다.
     */
    public List<Map<String, Object>> getCoolingDownNodes() {
        return getNodeResources().stream()
                .filter(n -> constraintEvaluator.isCoolingDown(n.getNodeId()))
                .map(n -> Map.<String, Object>of(
                        "mdcId", n.getMdcId(),
                        "nodeId", n.getNodeId(),
                        "temperatureCelsius", n.getTemperatureCelsius(),
                        "thermalState", String.valueOf(n.getThermalState()),
                        "remainingCooldownMinutes",
                        constraintEvaluator.remainingCooldownMinutes(n.getNodeId())))
                .toList();
    }

    /**
     * 배치하지 않고 제약 평가만 수행한다.
     *
     * <p>워크로드 상태와 배치 이력을 전혀 건드리지 않는다. 어떤 노드가 왜 제외됐는지
     * 확인해 제약 조건 기반 스케줄링(연구계획 A1)의 근거를 보이는 데 쓴다.</p>
     */
    public SchedulingResult dryRun(Long workloadId, String engineId) {
        Workload workload = workloadRepository.findById(workloadId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "워크로드를 찾을 수 없습니다: " + workloadId));

        if (engineId != null && !engineRegistry.contains(engineId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "알 수 없는 스케줄링 엔진입니다: " + engineId);
        }

        PlacementContext context = createContext(resolvePolicy(workload.getResourceType()));
        if (context.isEmpty()) {
            return SchedulingResult.failure("활성 노드가 없습니다");
        }
        return engineRegistry.resolve(engineId).schedule(workload, context);
    }

    /**
     * 사용 가능한 스케줄링 엔진 목록.
     */
    public List<Map<String, String>> getAvailableEngines() {
        return engineRegistry.describe();
    }

    /**
     * 리소스 종류에 맞는 활성 정책을 고른다.
     * 종류별 활성 → 공통 활성 → 기본 정책 생성 순으로 찾는다.
     */
    public SchedulingPolicy resolvePolicy(Workload.ResourceType resourceType) {
        if (resourceType != null) {
            Optional<SchedulingPolicy> byType =
                    policyRepository.findFirstByActiveTrueAndResourceType(resourceType);
            if (byType.isPresent()) return byType.get();
        }
        return policyRepository.findFirstByActiveTrueAndResourceTypeIsNull()
                .orElseGet(() -> policyRepository.save(new SchedulingPolicy("default", "기본 스케줄링 정책")));
    }

    /** 종류를 지정하지 않은 호출(공통 정책 기준). */
    public SchedulingPolicy resolvePolicy() {
        return resolvePolicy(null);
    }

    /**
     * 워크로드의 실행 상태를 전이한다.
     * 배치 이후의 흐름(SCHEDULED→RUNNING→COMPLETED|FAILED)만 허용하며,
     * 상태가 종료되면 예약 회계에서 빠져 노드 자원이 풀린다.
     */
    @Transactional
    public Workload changeStatus(Long workloadId, Workload.WorkloadStatus target) {
        Workload workload = workloadRepository.findById(workloadId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "워크로드를 찾을 수 없습니다: " + workloadId));

        if (!workload.transitionTo(target)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, String.format(
                    "허용되지 않는 상태 전이입니다: %s → %s", workload.getStatus(), target));
        }
        return workloadRepository.save(workload);
    }

    /**
     * 이미 배치됐지만 아직 메트릭에 드러나지 않은 워크로드를 노드 예약으로 반영한다.
     * 오래 남아 있는 워크로드가 자원을 영구히 묶지 않도록 TTL 을 넘긴 것은 제외한다.
     *
     * <p><b>실행 중인 워크로드는 메트릭에 잡힌 뒤부터 예약에서 뺀다.</b> 그러지 않으면 같은
     * 워크로드가 노드 사용률 메트릭에 한 번, 예약으로 또 한 번 계산된다. 이 클래스의 예약은
     * "배치됐지만 아직 메트릭에 없는 것"을 뜻하므로, 메트릭 지연 시간이 지난 RUNNING 은
     * 정의상 예약이 아니다.</p>
     */
    private void applyReservations(PlacementContext context) {
        if (context.isEmpty()) return;

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime cutoff = now.minusHours(properties.getReservationTtlHours());
        OffsetDateTime metricCutoff = now.minusMinutes(properties.getMetricLagMinutes());

        for (Workload workload : workloadRepository.findByStatusIn(RESERVED_STATUSES)) {
            if (workload.getAssignedNodeId() == null) continue;
            if (workload.getScheduledAt() != null && workload.getScheduledAt().isBefore(cutoff)) continue;
            if (alreadyInMetrics(workload, metricCutoff)) continue;

            context.reserve(workload.getAssignedNodeId(), workload);
        }
    }

    /**
     * 워크로드의 부하가 이미 노드 사용률 메트릭에 반영됐는지.
     *
     * <p>SCHEDULED 는 아직 실행 전이라 메트릭에 있을 수 없으므로 언제나 예약으로 남긴다.
     * RUNNING 은 시작한 지 메트릭 지연 시간이 지났으면 메트릭에 있다고 본다. 배치 시각을
     * 모르는 행(기존 데이터)은 판단 근거가 없으니 예약으로 남겨 보수적으로 다룬다.</p>
     */
    private boolean alreadyInMetrics(Workload workload, OffsetDateTime metricCutoff) {
        if (workload.getStatus() != Workload.WorkloadStatus.RUNNING) return false;
        if (workload.getScheduledAt() == null) return false;
        return workload.getScheduledAt().isBefore(metricCutoff);
    }
}
