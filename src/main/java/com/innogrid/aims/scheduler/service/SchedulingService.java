package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.ExcludedNode;
import com.innogrid.aims.scheduler.model.LinkMatrix;
import com.innogrid.aims.scheduler.model.MdcPowerBudgets;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.MdcLinkRepository;
import com.innogrid.aims.scheduler.repository.MdcPowerBudgetRepository;
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

    private static final Logger log = LoggerFactory.getLogger(SchedulingService.class);

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

    /** MDC 링크 원장. null 이면(원장 없이 조립한 테스트 경로) 링크 스냅샷은 항상 비어 있다. */
    private final MdcLinkRepository linkRepository;

    /** 실행 계층 연동. null 이면(직접 조립한 테스트 경로) 결정만 하고 넘기지 않는다. */
    private final PlacementDispatchService dispatchService;

    /** MDC 전력 예산 원장. null 이면(직접 조립한 테스트 경로) MDC 총량 제약이 걸리지 않는다. */
    private final MdcPowerBudgetRepository budgetRepository;

    /**
     * 유일한 생성자다. 선택 원장(링크·디스패치·예산)은 {@code null} 이면 그 기능이 꺼진다.
     *
     * <p>예전에는 "옛 인자 수" 생성자를 하나씩 남겨 7·8·9·10인자 4단이었다. 테스트가 위치 인자로 직접
     * 조립하고 있어 인자를 늘릴 때마다 테스트가 깨졌기 때문인데, 인자 열 개가 대부분 리포지토리·서비스
     * 타입이라 <b>순서를 바꿔 넣어도 컴파일되는 조합</b>이 생기는 것이 더 큰 위험이었다. 조립은 테스트
     * 소스의 {@code SchedulingServiceBuilder} 한 곳으로 모았다.</p>
     */
    @Autowired
    public SchedulingService(WorkloadRepository workloadRepository,
                             SchedulingPolicyRepository policyRepository,
                             NodeResourceCollector nodeCollector,
                             PlacementService placementService,
                             EngineRegistry engineRegistry,
                             ConstraintEvaluator constraintEvaluator,
                             SchedulerProperties properties,
                             MdcLinkRepository linkRepository,
                             PlacementDispatchService dispatchService,
                             MdcPowerBudgetRepository budgetRepository) {
        this.workloadRepository = workloadRepository;
        this.policyRepository = policyRepository;
        this.nodeCollector = nodeCollector;
        this.placementService = placementService;
        this.engineRegistry = engineRegistry;
        this.constraintEvaluator = constraintEvaluator;
        this.properties = properties;
        this.linkRepository = linkRepository;
        this.dispatchService = dispatchService;
        this.budgetRepository = budgetRepository;
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
            return SchedulingResult.failure(noActiveNodesReason(context));
        }

        SchedulingResult result = placementService.place(workload, context, engineId);
        return dispatch(workload, result, null);
    }

    /**
     * 결정을 실행 계층으로 넘기고, 실행 계층이 받지 않았으면 결과를 실패로 낮춰 돌려준다(워크로드는 이미 FAILED·배정 해제).
     * {@link PlacementService#place} 의 트랜잭션이 끝난 뒤라 결정은 이미 커밋되어 있다. 스케줄 실행 경로도 같은 메서드를 쓴다.
     */
    public SchedulingResult dispatch(Workload workload, SchedulingResult result, Long scheduleRunId) {
        if (dispatchService == null) return result;
        return PlacementDispatchService.downgrade(result, dispatchService.afterPlacement(workload, result, scheduleRunId));
    }

    /**
     * 후보가 하나도 없을 때의 사유.
     *
     * <p>수집기가 핵심 메트릭을 알 수 없어 뺀 노드가 있으면 그 사실을 함께 적는다. "활성 노드가
     * 없습니다"만 남기면 노드가 다 꺼진 것인지 메트릭 저장소가 아픈 것인지 구분할 수 없다.
     * 뺀 노드가 없으면 문구는 예전과 같다.</p>
     */
    public static String noActiveNodesReason(PlacementContext context) {
        List<ExcludedNode> excluded = context.excludedNodes();
        if (excluded.isEmpty()) return "활성 노드가 없습니다";
        return String.format("활성 노드가 없습니다 — 핵심 메트릭을 알 수 없어 제외한 노드 %d대: %s",
                excluded.size(),
                excluded.stream().map(ExcludedNode::nodeId).collect(Collectors.joining(", ")));
    }

    /**
     * 노드 상태를 수집하고 예약분을 반영한 배치 컨텍스트를 만든다.
     * 실행 1회당 한 번만 호출해 모든 워크로드가 같은 스냅샷을 보게 한다.
     */
    public PlacementContext createContext(SchedulingPolicy policy) {
        NodeResourceCollector.Snapshot snapshot = nodeCollector.collect();
        PlacementContext context =
                PlacementContext.of(snapshot.nodes(), policy, constraintEvaluator)
                        .withExcluded(snapshot.excluded())
                        .withLinkMatrix(loadLinkMatrix(policy))
                        .withMdcBudgets(loadMdcBudgets());
        applyReservations(context);
        return context;
    }

    /**
     * 정책이 링크 반영을 켰을 때만 원장을 <b>실행당 1회</b> 읽는다. 꺼져 있으면 조회 자체를 하지 않아
     * 기존 정책(전부 false)의 배치 비용과 결과가 그대로다.
     */
    private LinkMatrix loadLinkMatrix(SchedulingPolicy policy) {
        if (linkRepository == null || policy == null || !policy.isAffinityEnabled()) {
            return LinkMatrix.EMPTY;
        }
        return LinkMatrix.of(linkRepository.findAll());
    }

    /**
     * MDC 전력 예산 원장을 <b>실행당 1회</b> 읽는다. 링크와 달리 정책 게이트가 없다 — 운영자가 원장에 직접
     * 넣은 값이라 정책 설정과 무관하게 적용한다(워크로드·스케줄 전력 예산과 같은 관례). 원장이 비어 있으면
     * 스냅샷도 비고 C6 은 아무 노드도 막지 않으므로, 배포 직후 배치 결과는 그대로다.
     */
    private MdcPowerBudgets loadMdcBudgets() {
        return budgetRepository == null ? MdcPowerBudgets.EMPTY : MdcPowerBudgets.of(budgetRepository.findAll());
    }

    /**
     * 현재 노드 상태 스냅샷 — 후보 노드(열 상태 부여)와 수집 단계에서 제외된 노드를 함께 담는다.
     * 조회·진단이 공유한다. 배치 실행은 {@link #createContext} 를 직접 쓴다.
     */
    public PlacementContext nodeSnapshot() {
        SchedulingPolicy policy = policyRepository.findFirstByActiveTrueAndResourceTypeIsNull()
                .orElseGet(() -> new SchedulingPolicy("default", "기본 스케줄링 정책"));

        PlacementContext context = createContext(policy);
        for (NodeResource node : context.nodes()) {
            node.setThermalState(constraintEvaluator.thermalState(node, policy));
        }
        return context;
    }

    /**
     * 현재 노드 자원 상태를 조회한다 (스케줄링 전 미리보기).
     * 제외 노드는 담지 않는다 — 이 목록은 DTO 없이 그대로 응답으로 나가 필드를 늘릴 수 없다.
     */
    public List<NodeResource> getNodeResources() {
        return nodeSnapshot().nodes();
    }

    /**
     * 노드 한 대의 현재 상태. 없으면 404.
     * 수집 단계에서 제외된 노드라면 404 사유에 그 이유를 적는다 — "없다"와 "알 수 없다"는 다르다.
     */
    public NodeResource getNodeResource(String nodeId) {
        PlacementContext snapshot = nodeSnapshot();
        return snapshot.nodes().stream()
                .filter(n -> n.getNodeId().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        snapshot.excludedNodes().stream()
                                .filter(e -> e.nodeId().equals(nodeId))
                                .findFirst()
                                .map(e -> "노드를 찾을 수 없습니다: " + nodeId + " — 수집 제외 (" + e.reason() + ")")
                                .orElse("노드를 찾을 수 없습니다: " + nodeId)));
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
            return SchedulingResult.failure(noActiveNodesReason(context));
        }
        return engineRegistry.resolve(engineId).schedule(workload, context);
    }

    /**
     * 사용 가능한 스케줄링 엔진 목록.
     */
    public List<Map<String, String>> getAvailableEngines() {
        return engineRegistry.describe();
    }

    /** 활성 정책이 하나도 없을 때 쓰는 기본 정책의 이름. */
    public static final String DEFAULT_POLICY_NAME = "default";

    /**
     * 리소스 종류에 맞는 활성 정책을 고른다.
     * 종류별 활성 → 공통 활성 → 기본 정책 순으로 찾는다.
     *
     * <p>활성 정책은 종류당 하나가 규칙이지만(단계 13, {@code SchedulingPolicyService} 가 409 로 지킨다) API 밖에서
     * 들어온 데이터로 둘 이상일 수 있다. 그때는 <b>이름 사전순 첫 번째</b>를 골라 결과가 DB 순서에 흔들리지 않게
     * 하고 WARN 을 남긴다.</p>
     *
     * <p>공통 활성이 없으면 기본 정책을 쓴다. 이름이 {@value #DEFAULT_POLICY_NAME} 인 정책이 이미 있으면(전환이나
     * 비활성화로 꺼진 경우) 그 행을 <b>저장·재활성화하지 않고 값만</b> 쓴다 — 예전에는 같은 이름으로 다시 저장하려다
     * 이름 유니크 제약에 걸려 배치 경로 전체가 500 이 났다(2026-09-02 단계 13 점검). 없을 때만 만들어 저장한다.</p>
     */
    public SchedulingPolicy resolvePolicy(Workload.ResourceType resourceType) {
        if (resourceType != null) {
            Optional<SchedulingPolicy> byType =
                    pickOne(policyRepository.findByActiveTrueAndResourceTypeOrderByNameAsc(resourceType), resourceType.name())
                            .or(() -> policyRepository.findFirstByActiveTrueAndResourceType(resourceType));
            if (byType.isPresent()) return byType.get();
        }
        // 목록이 비면 단건 조회로 한 번 더 본다. 실제 DB 에서는 같은 답이라 활성이 없을 때만 한 번 더 도는 가벼운 질의이고,
        // 단건 조회만 준비한 호출자(기존 단위 테스트의 대역)와의 호환을 유지한다.
        Optional<SchedulingPolicy> common = pickOne(policyRepository.findByActiveTrueAndResourceTypeIsNullOrderByNameAsc(), "공통")
                .or(policyRepository::findFirstByActiveTrueAndResourceTypeIsNull);
        if (common.isPresent()) return common.get();

        Optional<SchedulingPolicy> existing = policyRepository.findByName(DEFAULT_POLICY_NAME);
        if (existing.isPresent()) {
            log.warn("활성 배치 정책이 없어 비활성 상태의 '{}' 정책 값으로 배치합니다 (저장·상태 변경 없음)", DEFAULT_POLICY_NAME);
            return existing.get();
        }
        return policyRepository.save(new SchedulingPolicy(DEFAULT_POLICY_NAME, "기본 스케줄링 정책"));
    }

    /** 활성 정책 목록에서 하나를 결정적으로 고른다(이름순 첫 번째). 둘 이상이면 WARN. */
    private static Optional<SchedulingPolicy> pickOne(List<SchedulingPolicy> actives, String scope) {
        if (actives.isEmpty()) return Optional.empty();
        if (actives.size() > 1) {
            log.warn("{} 활성 배치 정책이 {}개입니다 — 이름순 첫 번째 '{}' 를 씁니다: {}", scope, actives.size(),
                    actives.get(0).getName(), actives.stream().map(SchedulingPolicy::getName).toList());
        }
        return Optional.of(actives.get(0));
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
