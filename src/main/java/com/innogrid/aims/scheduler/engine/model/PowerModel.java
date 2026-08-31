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
     * 워크로드를 배치했을 때의 예상 전력(W).
     * P_after = P_now + (P_max − P_idle) × ΔU,  ΔU = 요구 CPU / 전체 CPU
     */
    public double predictedPower(NodeResource node, int requiredCpu) {
        return node.getPowerWatts() + marginalPower(node, requiredCpu);
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
