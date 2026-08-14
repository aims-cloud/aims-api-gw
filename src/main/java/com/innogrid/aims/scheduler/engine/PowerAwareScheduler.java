package com.innogrid.aims.scheduler.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;

/**
 * 전력 효율성 기반 스케줄러.
 *
 * 논문 수준 알고리즘 — Energy-Proportional Computing:
 * 1. 전력 효율성 지표(PEI) 계산: PEI = utilization / powerConsumption
 *    → 단위 전력당 작업 처리량이 높은 서버를 선호
 * 2. 전력 비례성 점수: 이상적인 전력 비례 곡선과의 편차를 최소화
 *    → P_ideal = P_idle + (P_max - P_idle) × utilization
 *    → deviation = |P_actual - P_ideal| / P_max
 *    → score = 1 - deviation
 * 3. 온도 보정: 고온 서버에 패널티 (냉각 비용 반영)
 *    → cooling_cost = temperature / max_temperature
 *    → adjusted_score = score × (1 - cooling_cost × 0.3)
 * 4. 전력 통합(Power Consolidation): 서버 수 최소화로 유휴 전력 절감
 *    → 이미 가동 중인 서버를 선호 (utilization > 10%)
 *    → 빈 서버는 페널티 부여
 */
@Component
public class PowerAwareScheduler implements SchedulingEngine {

    private static final double IDLE_POWER_RATIO = 0.3; // 유휴 시 최대 전력의 30%
    private static final double COOLING_PENALTY_FACTOR = 0.3;
    private static final double CONSOLIDATION_BONUS = 0.15;

    @Override
    public String getName() {
        return "PowerAwareScheduler";
    }

    @Override
    public SchedulingResult schedule(Workload workload, List<NodeResource> candidates,
                                     SchedulingPolicy policy) {
        if (candidates.isEmpty()) {
            return SchedulingResult.failure("후보 노드가 없습니다");
        }

        List<NodeScore> scores = new ArrayList<>();
        NodeScore bestScore = null;

        for (NodeResource node : candidates) {
            boolean eligible = node.canAccommodate(
                    workload.getRequiredCpu(), workload.getRequiredMemoryMb(), workload.getRequiredGpu());
            String exclusionReason = eligible ? null : "자원 부족";

            if (eligible && node.getCpuUsagePercent() > policy.getMaxCpuUsagePercent()) {
                eligible = false;
                exclusionReason = "CPU 사용률 초과";
            }

            // ── PEI (Power Efficiency Index) ─────────────────────
            double pei = calculatePowerEfficiencyIndex(node);

            // ── 전력 비례성 점수 ─────────────────────────────────
            double proportionalityScore = calculatePowerProportionality(node);

            // ── 온도 보정 ───────────────────────────────────────
            double coolingCost = node.getTemperatureCelsius() / Math.max(policy.getMaxTemperatureCelsius(), 1);
            double tempAdjustedScore = proportionalityScore * (1.0 - coolingCost * COOLING_PENALTY_FACTOR);

            // ── 전력 통합 보너스 ─────────────────────────────────
            double consolidationBonus = (node.getCpuUsagePercent() > 10) ? CONSOLIDATION_BONUS : 0;

            // ── 자원 여유율 ─────────────────────────────────────
            double cpuScore = (100.0 - node.getCpuUsagePercent()) / 100.0;
            double memScore = (100.0 - node.getMemoryUsagePercent()) / 100.0;

            // ── 최종 점수 ───────────────────────────────────────
            double totalScore = tempAdjustedScore * 0.35
                    + pei * 0.25
                    + cpuScore * 0.15
                    + memScore * 0.15
                    + consolidationBonus * 0.10;

            totalScore = Math.round(totalScore * 10000.0) / 10000.0;

            NodeScore ns = new NodeScore(
                    node.getMdcId(), node.getNodeId(),
                    round(cpuScore), round(memScore),
                    round(pei), round(tempAdjustedScore),
                    round(consolidationBonus),
                    totalScore, eligible, exclusionReason
            );
            scores.add(ns);

            if (eligible && (bestScore == null || totalScore > bestScore.totalScore())) {
                bestScore = ns;
            }
        }

        scores.sort(Comparator.comparingDouble(NodeScore::totalScore).reversed());

        if (bestScore == null) {
            return new SchedulingResult(false, null, null, 0,
                    "전력 제약을 충족하는 노드가 없습니다", scores);
        }

        String reason = String.format(
                "[전력최적화] 점수 %.4f (PEI:%.2f 비례성:%.2f 냉각보정:%.2f 통합:%.2f) — %d개 후보",
                bestScore.totalScore(), bestScore.powerScore(), bestScore.temperatureScore(),
                bestScore.temperatureScore(), bestScore.networkScore(), candidates.size());

        return new SchedulingResult(true, bestScore.mdcId(), bestScore.nodeId(),
                bestScore.totalScore(), reason, scores);
    }

    /**
     * Power Efficiency Index (PEI):
     * 단위 전력당 CPU 활용률. 높을수록 전력 대비 작업량이 많다.
     */
    private double calculatePowerEfficiencyIndex(NodeResource node) {
        if (node.getPowerWatts() <= 0) return 0.5;
        double efficiency = node.getCpuUsagePercent() / node.getPowerWatts();
        // 정규화: 0~1 범위로 (최대 효율 = 0.5%/W 가정)
        return Math.min(1.0, efficiency / 0.5);
    }

    /**
     * 전력 비례성 (Energy Proportionality):
     * 이상적인 전력 소비 곡선과의 편차. 1에 가까울수록 전력 비례적.
     * P_ideal = P_idle + (P_max - P_idle) × utilization
     */
    private double calculatePowerProportionality(NodeResource node) {
        double pMax = node.getPowerCapacityWatts();
        if (pMax <= 0) return 0.5;

        double pIdle = pMax * IDLE_POWER_RATIO;
        double utilization = node.getCpuUsagePercent() / 100.0;
        double pIdeal = pIdle + (pMax - pIdle) * utilization;
        double deviation = Math.abs(node.getPowerWatts() - pIdeal) / pMax;

        return Math.max(0, 1.0 - deviation);
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
