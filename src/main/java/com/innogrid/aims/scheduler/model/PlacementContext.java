package com.innogrid.aims.scheduler.model;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
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
     * 후보 전체의 평균 온도. 열적 균형 계산에 쓴다.
     * 제외된 노드까지 포함해야 클러스터 전체의 열 분포를 반영할 수 있다.
     */
    public double averageTemperature() {
        return nodes.stream()
                .mapToDouble(NodeResource::getTemperatureCelsius)
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
     */
    public int activeNodeCount(double activeCpuPercent) {
        return (int) nodes.stream()
                .filter(n -> n.getCpuUsagePercent() > activeCpuPercent)
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
     */
    public double reclaimableIdleWatts(double activeCpuPercent, double idleRatio) {
        return nodes.stream()
                .filter(n -> n.getCpuUsagePercent() <= activeCpuPercent)
                .mapToDouble(n -> n.getPowerCapacityWatts() * idleRatio)
                .sum();
    }

    public Optional<NodeResource> node(String nodeId) {
        return nodes.stream().filter(n -> n.getNodeId().equals(nodeId)).findFirst();
    }

    /** 배치가 확정된 워크로드의 요구량을 해당 노드의 예약으로 잡는다. */
    public void reserve(String nodeId, Workload workload) {
        node(nodeId).ifPresent(n -> n.reserve(
                workload.getRequiredCpu(),
                workload.getRequiredMemoryMb(),
                workload.getRequiredGpu()));
    }
}
