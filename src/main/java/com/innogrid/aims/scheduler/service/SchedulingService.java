package com.innogrid.aims.scheduler.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
import com.innogrid.aims.scheduler.dto.EngineResponse;
import com.innogrid.aims.scheduler.dto.ThermalCooldownResponse;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.PlacementDispatch;
import com.innogrid.aims.scheduler.entity.PlacementInput;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.ExcludedNode;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.PolicyChoice;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.MdcLinkRepository;
import com.innogrid.aims.scheduler.repository.MdcPowerBudgetRepository;
import com.innogrid.aims.scheduler.repository.PlacementInputRepository;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

/**
 * 스케줄링 조율 — 노드 수집, 정책 선택, 예약 반영까지 준비한 뒤 배치를 위임한다.
 *
 * <p>메트릭 조회(Thanos)는 트랜잭션 밖에서 하고, DB 저장은 {@link PlacementService} 의
 * 짧은 트랜잭션에 맡긴다.</p>
 *
 * <p>배치 컨텍스트 조립(노드 수집 · 링크·예산 원장 · 예약 반영)은 {@link PlacementContextAssembler} 에, 노드 조회(열 상태 ·
 * 단건 404 · 냉각 대기)는 {@link NodeSnapshotReader} 에 맡긴다.</p>
 */
@Service
public class SchedulingService {

    private static final Logger log = LoggerFactory.getLogger(SchedulingService.class);

    private final WorkloadRepository workloadRepository;
    private final SchedulingPolicyRepository policyRepository;
    private final PlacementService placementService;
    private final EngineRegistry engineRegistry;
    private final SchedulerProperties properties;

    /** 실행 계층 연동. null 이면(직접 조립한 테스트 경로) 결정만 하고 넘기지 않는다. */
    private final PlacementDispatchService dispatchService;

    /** 배치 컨텍스트 조립. 빈이 아니라 여기서 만든다. */
    private final PlacementContextAssembler contextAssembler;

    /** 노드 조회. 빈이 아니라 여기서 만든다 — 조립과 같은 평가기(냉각 대기 기록)를 쓴다. */
    private final NodeSnapshotReader nodeReader;

    /** 단건 배치 입력 저장소. null 이면(직접 조립한 테스트 경로) 입력을 남기지 않는다. */
    private final PlacementInputRepository inputRepository;

    /** 단건 배치 입력을 문자열로 만든다. 저장소가 없으면 null. 빈이 아니라 여기서 만든다 — 스케줄 실행과 같은 형식을 쓴다. */
    private final RunInputCapture inputCapture;

    /**
     * 유일한 생성자다. 선택 원장(링크·디스패치·예산·단건 배치 입력)은 {@code null} 이면 그 기능이 꺼진다.
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
                             MdcPowerBudgetRepository budgetRepository,
                             PlacementInputRepository inputRepository,
                             ThermalModel thermalModel) {
        this.workloadRepository = workloadRepository;
        this.policyRepository = policyRepository;
        this.placementService = placementService;
        this.engineRegistry = engineRegistry;
        this.properties = properties;
        this.dispatchService = dispatchService;
        this.contextAssembler = new PlacementContextAssembler(nodeCollector, constraintEvaluator, workloadRepository,
                properties, linkRepository, budgetRepository, dispatchService);
        this.nodeReader = new NodeSnapshotReader(contextAssembler, constraintEvaluator);
        this.inputRepository = inputRepository;
        this.inputCapture = inputRepository == null ? null : new RunInputCapture(thermalModel, properties);
    }

    /**
     * 워크로드를 스케줄링한다.
     *
     * @param workloadId 스케줄링할 워크로드 ID
     * @param engineId   사용할 엔진 (weighted, power, temperature) — 비우면 정책 기본 엔진, 그것도 없으면 weighted
     */
    public SchedulingResult scheduleWorkload(Long workloadId, String engineId) {
        Workload workload = workloadRepository.findById(workloadId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "워크로드를 찾을 수 없습니다: " + workloadId));

        String requested = requestedEngine(engineId);

        PlacementContext context = createContext(resolvePolicy(workload.resolveResourceType()));
        if (context.isEmpty()) {
            return SchedulingResult.failure(noActiveNodesReason(context), policyNameOf(context));
        }

        String engine = engineFor(requested, context.policy());
        PendingInput input = prepareInput(workload, context, engine);
        SchedulingResult result = placementService.place(workload, context, engine).withPolicyName(policyNameOf(context));
        saveInput(workload.getId(), input, result);
        return dispatch(workload, result, null);
    }

    /** 배치 직전 입력 — 저장 시각·실제로 쓸 엔진·문자열로 바꾼 입력. */
    private record PendingInput(Instant capturedAt, String engineId, String payload) {}

    /**
     * 배치 직전 입력을 문자열로 만든다. {@link PlacementService#place} 가 워크로드 상태를 바꾸므로 그 전에 만든다.
     * 저장을 끈 조립이거나 만들다 실패하면 null — 배치는 그대로 한다.
     */
    private PendingInput prepareInput(Workload workload, PlacementContext context, String engineId) {
        if (inputCapture == null) return null;
        try {
            String resolved = engineRegistry.resolve(engineId).getId();
            Instant now = Instant.now();
            return new PendingInput(now, resolved, inputCapture.payloadOf(resolved, now, context, List.of(workload)));
        } catch (RuntimeException | LinkageError e) {
            log.warn("단건 배치 입력 만들기 실패(배치는 계속한다): workload={}", workload.getId(), e);
            return null;
        }
    }

    /**
     * 엔진 결정과 함께 입력을 한 행으로 남긴다 — 실패한 결정도 남긴다. {@link PlacementService#place} 의 트랜잭션이 끝난 뒤라,
     * 저장이 실패해도 결정은 되돌아가지 않는다. 실패는 로그만 남기고 응답은 그대로 둔다.
     */
    private void saveInput(Long workloadId, PendingInput input, SchedulingResult result) {
        if (input == null) return;
        try {
            inputRepository.save(new PlacementInput(workloadId, RunInputCapture.FORMAT_VERSION,
                    OffsetDateTime.ofInstant(input.capturedAt(), ZoneOffset.UTC), input.engineId(),
                    result.success(), result.selectedNodeId(), RunClusterSnapshot.knobsDigestOf(properties),
                    RunInputCapture.digest(input.payload()), input.payload()));
        } catch (RuntimeException | LinkageError e) {
            log.warn("단건 배치 입력 저장 실패(응답은 그대로): workload={}", workloadId, e);
        }
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
        return contextAssembler.createContext(policy);
    }

    /**
     * 현재 노드 상태 스냅샷 — 후보 노드(열 상태 부여)와 수집 단계에서 제외된 노드를 함께 담는다.
     * 조회·진단이 공유한다. 배치 실행은 {@link #createContext} 를 직접 쓴다.
     */
    public PlacementContext nodeSnapshot() {
        SchedulingPolicy policy = policyRepository.findFirstByActiveTrueAndResourceTypeIsNull()
                .orElseGet(() -> new SchedulingPolicy("default", "기본 스케줄링 정책"));

        return nodeReader.snapshot(policy);
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
        return nodeReader.find(nodeSnapshot(), nodeId);
    }

    /**
     * 임계 온도를 넘겨 냉각 대기 중인 노드 목록.
     * 온도 제어(연구계획 B3)가 실제로 배치를 막고 있는지 밖에서 확인할 수단이다.
     *
     * <p>대기 노드가 하나도 없으면 노드를 수집하지 않는다 — 수집해도 응답이 비기 때문이다. 대기 노드가
     * 있을 때는 수집한 뒤 노드마다 다시 확인하므로, 수집하는 동안 만료된 노드는 목록에서 빠진다.</p>
     */
    public List<ThermalCooldownResponse> getCoolingDownNodes() {
        return nodeReader.coolingDown(this::getNodeResources);
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
        String requested = requestedEngine(engineId);
        // 저장된 워크로드의 모의 배치는 단건 배치와 같은 정책 경로 — default 가 없으면 만들어 저장한다
        return evaluate(workload, requested, resolvePolicy(workload.resolveResourceType()));
    }

    /**
     * 저장하지 않은 워크로드 폼의 모의 배치. 초안은 id 가 없고 어떤 행도 쓰지 않는다 — 정책이 하나도 없어도
     * {@code default} 를 저장하지 않는다(실행 미리보기와 같은 규칙). 종류를 비우면 VM 으로 읽는다.
     */
    public SchedulingResult dryRun(Workload draft, String engineId) {
        String requested = requestedEngine(engineId);
        return evaluate(draft, requested, choosePolicy(draft.resolveResourceType()).policy());
    }

    /** 요청한 엔진 — 비었거나 공백이면 null(정책에서 고른다), 등록되지 않은 이름이면 400. */
    private String requestedEngine(String engineId) {
        if (engineId == null || engineId.isBlank()) return null;
        if (!engineRegistry.contains(engineId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "알 수 없는 스케줄링 엔진입니다: " + engineId);
        }
        return engineId;
    }

    /**
     * 단건 배치·모의 배치가 쓸 엔진 — 요청한 엔진, 없으면 정책 기본 엔진(등록된 것일 때만), 그것도 없으면 서버 기본값.
     * 스케줄 실행의 엔진 선택({@code ScheduleExecutionService.engineChoiceFor})에서 스케줄 단계를 요청으로 바꾼 것이다.
     */
    private String engineFor(String requested, SchedulingPolicy policy) {
        if (requested != null) return requested;
        if (policy != null && policy.getDefaultEngine() != null && engineRegistry.contains(policy.getDefaultEngine())) {
            return policy.getDefaultEngine();
        }
        return EngineRegistry.DEFAULT_ENGINE_ID;
    }

    /** 제약 평가만 수행한다 — 정책은 호출자가 고른다(저장 여부가 거기서 갈린다). 워크로드 상태와 이력은 건드리지 않는다. */
    private SchedulingResult evaluate(Workload workload, String engineId, SchedulingPolicy policy) {
        PlacementContext context = createContext(policy);
        if (context.isEmpty()) {
            return SchedulingResult.failure(noActiveNodesReason(context), policyNameOf(context));
        }
        return evaluateIn(workload, engineFor(engineId, policy), context);
    }

    /**
     * 주어진 컨텍스트 위에서 한 건을 저장 없이 평가한다 — 예약은 잡지 않는다(호출자가 성공 건을 {@link PlacementContext#reserve}
     * 로 누적한다). 스케줄 모의 실행이 대상 여러 건을 한 컨텍스트에 차례로 놓아 볼 때 쓴다. 엔진 id 는 실행과 같이
     * 등록되지 않은 값이면 기본 엔진으로 대체된다(400 이 아니다).
     */
    public SchedulingResult evaluateIn(Workload workload, String engineId, PlacementContext context) {
        SchedulingResult result = engineRegistry.resolve(engineId).schedule(workload, context).withPolicyName(policyNameOf(context));
        // 모의 배치(dryRun)도 알린다 — 운영자가 실제 배치 전에 확인하는 경로다
        ConstraintEvaluator.warnUncheckedDisk(log, workload, result, context, properties.getDiskMountPoint());
        return result;
    }

    /** 응답에 실을 적용 정책 이름 — 컨텍스트에 정책이 없으면 null. */
    private static String policyNameOf(PlacementContext context) {
        return context.policy() == null ? null : context.policy().getName();
    }

    /**
     * 사용 가능한 스케줄링 엔진 목록.
     */
    public List<EngineResponse> getAvailableEngines() {
        return engineRegistry.describe().stream().map(EngineResponse::of).toList();
    }

    /** 활성 정책이 하나도 없을 때 쓰는 기본 정책의 이름. */
    public static final String DEFAULT_POLICY_NAME = "default";

    /**
     * 리소스 종류에 맞는 활성 정책을 고른다.
     * 종류별 활성 → 공통 활성 → 기본 정책 순으로 찾는다.
     *
     * <p>활성 정책은 종류당 하나가 규칙이지만({@code SchedulingPolicyService} 가 409 로 지킨다) API 밖에서
     * 들어온 데이터로 둘 이상일 수 있다. 그때는 <b>이름 사전순 첫 번째</b>를 골라 결과가 DB 순서에 흔들리지 않게
     * 하고 WARN 을 남긴다.</p>
     *
     * <p>공통 활성이 없으면 기본 정책을 쓴다. 이름이 {@value #DEFAULT_POLICY_NAME} 인 정책이 이미 있으면(전환이나
     * 비활성화로 꺼진 경우) 그 행을 <b>저장·재활성화하지 않고 값만</b> 쓴다 — 예전에는 같은 이름으로 다시 저장하려다
     * 이름 유니크 제약에 걸려 배치 경로 전체가 500 이 났다(2026-09-02 점검). 없을 때만 만들어 저장한다.</p>
     */
    public SchedulingPolicy resolvePolicy(Workload.ResourceType resourceType) {
        return resolvePolicyChoice(resourceType).policy();
    }

    /**
     * {@link #resolvePolicy(Workload.ResourceType)} 와 같은 선택에 <b>출처</b>를 붙인 것. ④ 단계에서 {@code default} 행이
     * 없으면 여기서 만들어 저장한다(실행·배치 경로 — 이력이 정책 이름을 가리켜야 한다).
     */
    public PolicyChoice resolvePolicyChoice(Workload.ResourceType resourceType) {
        PolicyChoice choice = choosePolicy(resourceType);
        if (choice.persisted()) return choice;
        return new PolicyChoice(policyRepository.save(choice.policy()), choice.source(), true);
    }

    /**
     * 정책을 고르되 <b>아무것도 저장하지 않는다</b>. ④ 단계에서 {@code default} 행이 없으면 저장하지 않은 기본값 객체를
     * {@code persisted = false} 로 돌려준다 — 미리보기처럼 읽기 요청이 행을 만들면 안 되는 호출자가 쓴다.
     * 선택 규칙·WARN 은 {@link #resolvePolicy(Workload.ResourceType)} 와 같다.
     */
    public PolicyChoice choosePolicy(Workload.ResourceType resourceType) {
        if (resourceType != null) {
            Optional<SchedulingPolicy> byType =
                    pickOne(policyRepository.findByActiveTrueAndResourceTypeOrderByNameAsc(resourceType), resourceType.name())
                            .or(() -> policyRepository.findFirstByActiveTrueAndResourceType(resourceType));
            if (byType.isPresent()) return new PolicyChoice(byType.get(), PolicyChoice.Source.RESOURCE_TYPE, true);
        }
        // 목록이 비면 단건 조회로 한 번 더 본다. 실제 DB 에서는 같은 답이라 활성이 없을 때만 한 번 더 도는 가벼운 질의이고,
        // 단건 조회만 준비한 호출자(기존 단위 테스트의 대역)와의 호환을 유지한다.
        Optional<SchedulingPolicy> common = pickOne(policyRepository.findByActiveTrueAndResourceTypeIsNullOrderByNameAsc(), "공통")
                .or(policyRepository::findFirstByActiveTrueAndResourceTypeIsNull);
        if (common.isPresent()) return new PolicyChoice(common.get(), PolicyChoice.Source.COMMON, true);

        Optional<SchedulingPolicy> existing = policyRepository.findByName(DEFAULT_POLICY_NAME);
        if (existing.isPresent()) {
            log.warn("활성 배치 정책이 없어 비활성 상태의 '{}' 정책 값으로 배치합니다 (저장·상태 변경 없음)", DEFAULT_POLICY_NAME);
            return new PolicyChoice(existing.get(), PolicyChoice.Source.DEFAULT, true);
        }
        return new PolicyChoice(new SchedulingPolicy(DEFAULT_POLICY_NAME, "기본 스케줄링 정책"), PolicyChoice.Source.DEFAULT, false);
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

    /**
     * 재시도 상한에 걸린 워크로드를 <b>여러 건 한 번에</b> 되살린다.
     *
     * <p>단건 재개는 이미 {@link #changeStatus} 로 된다 — 실패에서 대기로 바꾸면 재시도 횟수와
     * 마지막 실패 시각이 지워진다. 이 메서드가 더하는 것은 <b>편의</b>이지 새 능력이 아니다.</p>
     *
     * <p>조건 없는 전체 일괄은 받지 않는다. 되살린 워크로드는 다음 실행에 한꺼번에 몰리는데,
     * 한 실행이 처리하는 상한이 {@code max-workloads-per-run} 이라 그보다 많이 되살리면 나머지가
     * 밀려 상태가 어긋난다. 그래서 리소스 종류·기간 중 하나는 반드시 받고, 대상이 상한을 넘으면
     * 되살리지 않고 조건을 좁히라고 답한다.</p>
     *
     * @return 되살린 워크로드 목록 (호출부가 건수를 센다)
     */
    @Transactional
    public List<Workload> resumeExhausted(Workload.ResourceType resourceType,
                                          OffsetDateTime from, OffsetDateTime to) {
        if (resourceType == null && from == null && to == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "리소스 종류나 기간 중 하나는 지정해야 합니다 — 조건 없는 전체 재개는 받지 않습니다");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "기간의 시작이 끝보다 뒤입니다");
        }

        // 널 파라미터는 PostgreSQL 이 타입을 추론하지 못한다 — 경계값과 깃발로 바꿔 넘긴다.
        boolean anyType = resourceType == null;
        boolean matchNullType = resourceType == Workload.ResourceType.VM;
        boolean anyPeriod = from == null && to == null;
        List<Workload> exhausted = workloadRepository.findRetryExhausted(
                Workload.WorkloadStatus.FAILED, properties.getMaxRetryCount(),
                anyType, anyType ? Workload.ResourceType.VM : resourceType,
                matchNullType,
                anyPeriod,
                from != null ? from : OffsetDateTime.parse("1970-01-01T00:00:00Z"),
                to != null ? to : OffsetDateTime.now().plusYears(100));
        int limit = properties.getMaxWorkloadsPerRun();
        if (exhausted.size() > limit) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, String.format(
                    "조건에 걸린 워크로드가 %d건으로 한 번에 되살릴 수 있는 %d건을 넘습니다 — 기간이나 리소스 종류로 좁히세요",
                    exhausted.size(), limit));
        }

        List<Workload> resumed = new ArrayList<>();
        for (Workload workload : exhausted) {
            if (workload.transitionTo(Workload.WorkloadStatus.PENDING)) {
                resumed.add(workloadRepository.save(workload));
            }
        }
        return resumed;
    }
    /**
     * 워크로드의 실행 상태를 전이한다.
     * 배치 이후의 흐름(SCHEDULED→RUNNING→COMPLETED|FAILED)만 허용하며,
     * 상태가 종료되면 예약 회계에서 빠져 노드 자원이 풀리고, 현재 결정의 전달 기록이 열려 있으면 같은 끝 상태로
     * 마감한다({@link PlacementDispatchService#closeCurrent}).
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
        if (dispatchService != null
                && (target == Workload.WorkloadStatus.COMPLETED || target == Workload.WorkloadStatus.FAILED)) {
            dispatchService.closeCurrent(workload, target);
        }
        return workloadRepository.save(workload);
    }

    /**
     * 배치 예약을 취소한다 — SCHEDULED 워크로드를 대기로 되돌리고 배정을 풀며, 현재 결정의 전달 기록을 마감한다.
     *
     * <p>상태 변경 API 의 SCHEDULED→FAILED→PENDING 조합과 다른 점: 배정 컬럼이 남지 않고, 재시도 회계를 지우지 않으며,
     * 중간 FAILED 가 드러나지 않는다(전달 기록은 두 경로 모두 FAILED 로 마감되며 비고 표지가 다르다). 배치 이력은 연구 근거라 건드리지 않는다.
     * 노드 예약은 워크로드 행에서 매번 다시 계산하므로 PENDING 이 되면 다음 컨텍스트부터 저절로 빠진다.</p>
     *
     * @return 취소 뒤 워크로드 · 풀린 배정 · 마감된(또는 없는) 전달 기록
     */
    @Transactional
    public Release release(Long workloadId) {
        Workload workload = workloadRepository.findById(workloadId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "워크로드를 찾을 수 없습니다: " + workloadId));
        if (workload.getStatus() != Workload.WorkloadStatus.SCHEDULED) {
            throw new BusinessException(HttpStatus.CONFLICT, workload.getStatus() == Workload.WorkloadStatus.RUNNING
                    ? "실행 중인 워크로드는 예약을 취소할 수 없습니다 — 실행 계층 결과로 끝내십시오"
                    : String.format("취소할 예약이 없습니다 — 워크로드가 %s 상태입니다(SCHEDULED 만 가능)", workload.getStatus()));
        }
        String mdcId = workload.getAssignedMdcId();
        String nodeId = workload.getAssignedNodeId();
        PlacementDispatch dispatch = dispatchService == null ? null : dispatchService.cancelCurrent(workload);
        workload.release("예약 취소 — 이전 배정 " + mdcId + "/" + nodeId);
        return new Release(workloadRepository.save(workload), mdcId, nodeId, dispatch);
    }

    /** 예약 취소의 결과 묶음. */
    public record Release(Workload workload, String releasedMdcId, String releasedNodeId, PlacementDispatch dispatch) {}
}
