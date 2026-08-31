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
