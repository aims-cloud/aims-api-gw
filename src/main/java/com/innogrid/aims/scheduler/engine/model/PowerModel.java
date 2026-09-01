package com.innogrid.aims.scheduler.engine.model;

import org.springframework.stereotype.Component;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.model.NodeResource;

/**
 * 전력 모델 — 서버별 전력 효율성 지표 계산.
 *
 * 엔진과 평가 API 가 같은 수식을 쓰도록 계산만 담당하는 순수 컴포넌트로 분리했다.
 * 수식은 기존 PowerAwareScheduler 의 것을 그대로 옮겼고, 배치 후 예측 전력과
 * 한계 전력(ΔP)만 새로 추가했다.
 */
@Component
public class PowerModel {

    private final SchedulerProperties.Power props;

    public PowerModel(SchedulerProperties properties) {
        this.props = properties.getPower();
    }

    /**
     * 전력 효율 지표(PEI) — 단위 전력당 CPU 활용률을 0~1 로 정규화한다.
     * 전력을 수집하지 못한 노드는 판단 근거가 없으므로 중립값 0.5 를 준다.
     */
    public double normalizedPei(NodeResource node) {
        if (node.getPowerWatts() <= 0) return 0.5;
        double efficiency = node.getCpuUsagePercent() / node.getPowerWatts();
        return Math.min(1.0, efficiency / props.getPeiNormalizer());
    }

    /** 정규화 전 PEI 원값(%/W). 평가 API 에서 그대로 보여준다. */
    public double rawPei(NodeResource node) {
        if (node.getPowerWatts() <= 0) return 0;
        return node.getCpuUsagePercent() / node.getPowerWatts();
    }

    /**
     * 전력 비례성 — 이상적인 전력 곡선과의 편차. 1 에 가까울수록 비례적이다.
     * P_ideal = P_idle + (P_max − P_idle) × utilization
     */
    public double proportionality(NodeResource node) {
        double pMax = node.getPowerCapacityWatts();
        if (pMax <= 0) return 0.5;

        double pIdle = idlePower(node);
        double utilization = node.getCpuUsagePercent() / 100.0;
        double pIdeal = pIdle + (pMax - pIdle) * utilization;
        double deviation = Math.abs(node.getPowerWatts() - pIdeal) / pMax;

        return Math.max(0, 1.0 - deviation);
    }

    /** 유휴 전력. P_idle = P_max × idleRatio */
    public double idlePower(NodeResource node) {
        return node.getPowerCapacityWatts() * props.getIdleRatio();
    }

    /** 전력 여유율 점수 (0~1). */
    public double headroomScore(NodeResource node) {
        return node.powerHeadroomPercent() / 100.0;
    }

    /**
     * 예약분까지 반영한 소비 전력(W).
     *
     * <p>실측 전력은 이미 실행 중인 부하만 담는다. 한 실행에서 여러 워크로드를 배치하면
     * 예약만 쌓이고 실측값은 그대로라, "배치 후 예상 전력" 검사가 매번 <b>워크로드 하나만
     * 얹은 값</b>을 돌려준다. 그 상태로는 용량을 넘겨도 걸러내지 못한다.</p>
     *
     * <p>전력을 수집하지 못한 노드(0W)는 더할 기준이 없으므로 0 을 유지한다 — 예약이 있다고
     * 없던 전력을 만들어내면 판단 근거가 없는 숫자가 생긴다.</p>
     */
    public double effectivePowerWatts(NodeResource node) {
        if (node.getPowerWatts() <= 0) return node.getPowerWatts();

        double pMax = node.getPowerCapacityWatts();
        if (pMax <= 0) return node.getPowerWatts();

        return node.getPowerWatts() + (pMax - idlePower(node)) * node.reservedCpuRatio();
    }

    /**
     * 예약분까지 반영한 전력 여유율 (%).
     *
     * <p>제약 검사용이다. 실측 여유율({@link NodeResource#powerHeadroomPercent})만 보면 예약이
     * 아무리 쌓여도 여유가 그대로라, 전력 상한이 배치 누적에 대해 적용되지 않는다.</p>
     *
     * <p>점수 계산용 {@link #headroomScore} 는 실측을 그대로 쓴다 — 그쪽은 "지금 이 노드에
     * 얼마나 여유가 있나"를 재는 지표라 예측치를 섞으면 의미가 흐려진다.</p>
     */
    public double effectiveHeadroomPercent(NodeResource node) {
        double pMax = node.getPowerCapacityWatts();
        if (pMax <= 0) return 100;
        return (1.0 - effectivePowerWatts(node) / pMax) * 100;
    }

    /**
     * 워크로드를 배치했을 때의 예상 전력(W).
     * P_after = P_effective + (P_max − P_idle) × ΔU,  ΔU = 요구 CPU / 전체 CPU
     *
     * <p>기준은 실측이 아니라 <b>예약분까지 반영한 실효 전력</b>이다. 한 실행에서 여러
     * 워크로드를 배치할 때 실측만 보면 상승분이 누적되지 않아, 몇 개를 얹든 "하나 얹은 값"이
     * 나오고 용량 초과를 걸러내지 못한다.</p>
     *
     * <p>지금 평가 중인 워크로드는 <b>아직 예약되지 않은 상태</b>다 — 예약은 배치가 확정된
     * 뒤에 잡는다. 그래서 실효 전력에 이 워크로드가 이중으로 들어가지 않는다.</p>
     */
    public double predictedPower(NodeResource node, int requiredCpu) {
        return effectivePowerWatts(node) + marginalPower(node, requiredCpu);
    }

    /** 배치로 늘어나는 전력(W) — 한계 전력 ΔP. 작을수록 전력 효율적인 배치다. */
    public double marginalPower(NodeResource node, int requiredCpu) {
        double pMax = node.getPowerCapacityWatts();
        if (pMax <= 0) return 0;

        double deltaUtilization = (double) requiredCpu / Math.max(node.getTotalCpu(), 1);
        return (pMax - idlePower(node)) * deltaUtilization;
    }

    /**
     * 노드의 전력 용량(W).
     * IPMI 로는 정격 전력을 알 수 없어 설정값을 쓰되, 노드별 override 를 우선한다.
     */
    public double capacityOf(String nodeId) {
        Double override = props.getCapacityByNode().get(nodeId);
        return override != null ? override : props.getDefaultCapacityWatts();
    }
}
