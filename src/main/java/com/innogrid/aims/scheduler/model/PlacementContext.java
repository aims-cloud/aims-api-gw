package com.innogrid.aims.scheduler.model;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.constraint.Exclusion;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;

/**
 * 한 번의 스케줄링 실행에서 공유하는 배치 컨텍스트.
 *
 * <p>노드 상태를 실행 시작 시 한 번만 수집해 담아 두고, 워크로드를 배치할 때마다
 * {@link #reserve} 로 예약분을 차감한다. 그래야 여러 워크로드를 잇달아 배치해도
 * 같은 노드에 과도하게 몰리지 않는다.</p>
 *
 * <p>제약 평가기를 함께 들고 다녀 엔진이 별도 의존성 없이 같은 제약을 적용할 수 있다.</p>
 */
public class PlacementContext {

    private final List<NodeResource> nodes;
    private final SchedulingPolicy policy;
    private final ConstraintEvaluator evaluator;

    /**
     * 이번 실행 전체에 적용할 전력 예산(W). 스케줄이 지정했을 때만 값이 있다.
     * 워크로드마다 따로 지정한 예산과 함께 검사한다 — 둘 중 하나라도 넘으면 제외된다.
     */
    private Double powerBudgetWatts;

    /** 수집 단계에서 핵심 메트릭을 알 수 없어 후보에 넣지 않은 노드. 진단이 "왜 빠졌는지"를 말할 근거다. */
    private List<ExcludedNode> excluded = Collections.emptyList();

    /**
     * 이번 실행이 보는 MDC 링크 원장 스냅샷. 정책이 {@code affinityEnabled} 일 때만 채워지고,
     * 아니면 {@link LinkMatrix#EMPTY} 다 — 게이트가 꺼진 실행은 원장을 읽지도 않는다.
     */
    private LinkMatrix linkMatrix = LinkMatrix.EMPTY;

    /**
     * 이번 실행이 보는 MDC 전력 예산 원장 스냅샷. 비어 있으면 C6 은 아무 노드도 막지 않는다 —
     * 원장에 값을 넣기 전까지 배치 동작이 이 기능 이전과 완전히 같다.
     */
    private MdcPowerBudgets mdcBudgets = MdcPowerBudgets.EMPTY;

    private PlacementContext(List<NodeResource> nodes, SchedulingPolicy policy, ConstraintEvaluator evaluator) {
        this.nodes = nodes == null ? Collections.emptyList() : nodes;
        this.policy = policy;
        this.evaluator = evaluator;
    }

    /**
     * 실행 단위 전력 예산을 설정한다.
     * 워크로드 엔티티를 건드리지 않고 제약을 적용하기 위한 통로다.
     */
    public PlacementContext withPowerBudget(Double watts) {
        this.powerBudgetWatts = watts;
        return this;
    }

    /** 실행 단위 전력 예산. 지정하지 않았으면 {@code null}. */
    public Double powerBudgetWatts() {
        return powerBudgetWatts;
    }

    /** 수집기가 뺀 노드를 컨텍스트에 실어 둔다. 배치 판단에는 쓰지 않고 진단·사유 문구에만 쓴다. */
    public PlacementContext withExcluded(List<ExcludedNode> excludedNodes) {
        this.excluded = excludedNodes == null ? Collections.emptyList() : List.copyOf(excludedNodes);
        return this;
    }

    /**
     * 수집 단계에서 제외된 노드. 후보({@link #nodes()})에는 없다.
     * 이름에 {@code get} 을 붙이지 않는 관례를 따른다(직렬화 대상이 아니다).
     */
    public List<ExcludedNode> excludedNodes() {
        return excluded;
    }

    /** MDC 링크 스냅샷을 싣는다. null 이면 빈 매트릭스. */
    public PlacementContext withLinkMatrix(LinkMatrix matrix) {
        this.linkMatrix = matrix == null ? LinkMatrix.EMPTY : matrix;
        return this;
    }

    /** 이번 실행의 MDC 링크 스냅샷. 게이트가 꺼진 실행은 항상 비어 있다. */
    public LinkMatrix linkMatrix() {
        return linkMatrix;
    }

    /**
     * MDC 전력 예산 스냅샷을 싣는다. null 이면 빈 원장.
     *
     * <p>이름을 {@code withPowerBudget} 과 구분한 이유: 그쪽은 <b>노드 한 대</b>의 전력을 비교하는
     * 실행 단위 상한이고(12장 T10 의 이름 문제), 이쪽은 <b>MDC 한 곳</b>의 합계 상한이다.</p>
     */
    public PlacementContext withMdcBudgets(MdcPowerBudgets budgets) {
        this.mdcBudgets = budgets == null ? MdcPowerBudgets.EMPTY : budgets;
        return this;
    }

    /** 이번 실행의 MDC 전력 예산 스냅샷. 원장이 비어 있으면 빈 값이다. */
    public MdcPowerBudgets mdcBudgets() {
        return mdcBudgets;
    }

    /**
     * 링크 반영 게이트 — 정책이 {@code affinityEnabled} 이고 워크로드에 {@code dataMdcId} 가 있을 때만 참.
     * 제약(C5)과 weighted 점수 혼합이 같은 조건을 봐야 하므로 한곳에 둔다.
     * 기존 정책은 전부 false 라 배포 직후 배치 결과가 바뀌지 않는다.
     */
    public boolean linkAware(Workload workload) {
        return policy != null && policy.isAffinityEnabled()
                && workload != null && workload.getDataMdcId() != null && !workload.getDataMdcId().isBlank();
    }

    /**
     * 스프링이 관리하는 평가기를 넘겨 만드는 형태. 실행 경로에서 쓴다.
     * 평가기의 냉각 대기 상태가 실행 간에 유지된다.
     */
    public static PlacementContext of(List<NodeResource> nodes, SchedulingPolicy policy,
                                      ConstraintEvaluator evaluator) {
        return new PlacementContext(nodes, policy, evaluator);
    }

    /**
     * 기본 설정으로 평가기를 만들어 쓰는 형태. 단발 호출과 단위 테스트에서 쓴다.
     */
    public static PlacementContext of(List<NodeResource> nodes, SchedulingPolicy policy) {
        return new PlacementContext(nodes, policy, ConstraintEvaluator.withDefaults());
    }

    public List<NodeResource> nodes() {
        return nodes;
    }

    public SchedulingPolicy policy() {
        return policy;
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /**
     * 노드가 워크로드를 받을 수 없는 이유. 배치 가능하면 {@code null}.
     */
    public String exclusionReason(Workload workload, NodeResource node) {
        return evaluator.exclusionReason(workload, node, this);
    }

    /**
     * 노드가 워크로드를 받을 수 없는 까닭과 문장. 배치 가능하면 {@code null}.
     *
     * <p>{@link #exclusionReason} 과 같은 검사를 한 번만 돌린다 — 둘을 따로 부르면 냉각 대기 표시처럼
     * 상태를 남기는 검사가 두 번 실행된다.</p>
     */
    public Exclusion exclusion(Workload workload, NodeResource node) {
        return evaluator.exclusion(workload, node, this);
    }

    /**
     * 후보 전체의 평균 온도(실효). 열적 균형 계산에 쓴다.
     * 제외된 노드까지 포함해야 클러스터 전체의 열 분포를 반영할 수 있다.
     *
     * <p><b>예약분까지 반영한 실효 온도의 평균</b>이다. 엔진이 노드 온도를 실효로 보면서 평균만
     * 실측으로 두면 균형 점수의 두 피연산자가 다른 시점을 보게 된다 — 예약이 쌓일수록 노드만
     * 뜨거워지고 기준선은 그대로라 "0.5 = 평균 수준"이라는 의미가 흐려진다. 순위를 바꾸는
     * 수정은 아니고 기준점의 일관성을 지키는 것이다.</p>
     *
     * <p><b>온도를 수집하지 못한 노드(0°C)는 평균에서 뺀다.</b> 0 은 "0도로 측정됐다"가 아니라
     * "모른다"인데, 그것을 표본으로 세면 기준선이 통째로 내려간다. 균형 점수가
     * {@code 1 − 0.5 × T / 평균} 이라 <b>0 으로 잘리는 지점(평균의 두 배)까지 함께 내려와</b>,
     * 정상 범위의 계측 노드가 없는 근거 때문에 감점을 받는다 — 모의 8대(계측 5·미계측 3)에서
     * 평균이 52.2°C → 32.6°C 로, 잘림 임계가 104.4°C → 65.3°C 로 내려갔다.</p>
     *
     * <p>계측된 노드가 하나도 없으면 0 이다. 그때는 엔진이 노드마다 이미 중립값을 쓰므로 이 값이
     * 점수에 닿지 않는다({@code TemperatureAwareScheduler} 의 미계측 가드).</p>
     */
    public double averageTemperature(ThermalModel thermalModel) {
        return nodes.stream()
                .filter(n -> n.getTemperatureCelsius() > 0)
                .mapToDouble(thermalModel::effectiveTemperature)
                .average().orElse(0);
    }

    /**
     * 후보 전체의 현재 소비 전력 합(W).
     *
     * <p>배치 결정은 노드 하나만 보고 내리지만, 전력 최적화의 목표는 <b>클러스터 총 전력</b>이다.
     * 열 쪽에는 {@link #averageTemperature} 라는 클러스터 지표가 있는데 전력에는 없어서,
     * 엔진이 "이 배치가 전체 전력을 얼마나 올리는가"를 볼 수단이 없었다.</p>
     *
     * <p>전력을 수집하지 못한 노드는 0 이므로 그대로 더해도 합계를 부풀리지 않는다.</p>
     */
    public double totalPowerWatts() {
        return nodes.stream().mapToDouble(NodeResource::getPowerWatts).sum();
    }

    /**
     * 가동 중인 노드 수. 기준 CPU 사용률(%)을 넘는 노드를 센다.
     *
     * <p>클러스터 총 전력 = Σ(가동 노드의 유휴 전력 + 동적 전력) 이므로, 이 수를 줄이는 것이
     * 곧 전력 절감이다. 판정 기준은 엔진이 {@code PowerModel} 설정에서 가져와 넘긴다 —
     * 이 클래스는 설정을 알지 못한다.</p>
     *
     * <p>{@code PowerModel.isActive} 와 같은 <b>실효 사용률</b>로 판정한다. 노드별 "가동중"은 실효,
     * 클러스터 요약은 실측이면 같은 응답 안에서 기준이 어긋난다. 이번 실행에서 워크로드를 받아
     * 깨어난 빈 노드는 이후 요약에서 가동 중으로 세어야 맞다.</p>
     */
    public int activeNodeCount(double activeCpuPercent) {
        return (int) nodes.stream()
                .filter(n -> n.effectiveCpuUsagePercent() > activeCpuPercent)
                .count();
    }

    /**
     * 비어 있는 노드들의 유휴 전력 합(W) — <b>반납 가능 전력</b>.
     *
     * <p>워크로드를 통합해 노드를 비우면 이만큼을 아낄 수 있다는 뜻이다. 다만 이 시스템에는
     * 노드 전원을 끄는 수단도, 이미 배치된 워크로드를 옮기는 수단도 없다. 그래서 이 값은
     * <b>실현된 절감이 아니라 "통합했다면 아낄 수 있었을 양"</b>이다. 실현 불가능한 절감을
     * 주장하지 않으면서 알고리즘 효과를 재기 위한 지표이며, 나중에 전원 제어가 생기면 그대로
     * 실현 값이 된다.</p>
     *
     * <p>노드별 유휴 전력은 {@code PowerModel.wakeupPower} 가 정한다 — 비율 인자를 받으면
     * 모델과 갈라진다.</p>
     */
    public double reclaimableIdleWatts(double activeCpuPercent, PowerModel powerModel) {
        return nodes.stream()
                .filter(n -> n.effectiveCpuUsagePercent() <= activeCpuPercent)   // activeNodeCount 와 같은 기준
                .mapToDouble(powerModel::wakeupPower)
                .sum();
    }

    public Optional<NodeResource> node(String nodeId) {
        return nodes.stream().filter(n -> n.getNodeId().equals(nodeId)).findFirst();
    }

    /** 배치가 확정된 워크로드의 요구량(CPU·메모리·GPU·디스크)을 해당 노드의 예약으로 잡는다. */
    public void reserve(String nodeId, Workload workload) {
        node(nodeId).ifPresent(n -> n.reserve(
                workload.getRequiredCpu(),
                workload.getRequiredMemoryMb(),
                workload.getRequiredGpu(),
                workload.getRequiredDiskGb()));
    }

    /**
     * {@link #reserve(String, Workload)} 를 되돌린다 — 실행 계층이 받지 않은 배치의 예약을 같은 실행 안에서 푼다.
     * 없는 노드는 무시한다.
     */
    public void unreserve(String nodeId, Workload workload) {
        node(nodeId).ifPresent(n -> n.unreserve(
                workload.getRequiredCpu(),
                workload.getRequiredMemoryMb(),
                workload.getRequiredGpu(),
                workload.getRequiredDiskGb()));
    }
}
