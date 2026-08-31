package com.innogrid.aims.scheduler.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
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

    private static final double COOLING_PENALTY_FACTOR = 0.3;
    private static final double CONSOLIDATION_BONUS = 0.15;

    private final PowerModel powerModel;
    private final ThermalModel thermalModel;

    @Autowired
    public PowerAwareScheduler(PowerModel powerModel, ThermalModel thermalModel) {
        this.powerModel = powerModel;
        this.thermalModel = thermalModel;
    }

    /** 스프링 컨텍스트 없이 쓰는 기본 생성자 (단위 테스트). 설정 기본값을 사용한다. */
    public PowerAwareScheduler() {
        this(new SchedulerProperties());
    }

    private PowerAwareScheduler(SchedulerProperties props) {
        this(new PowerModel(props), new ThermalModel(props));
    }

    @Override
    public String getId() {
        return "power";
    }

    @Override
    public String getName() {
        return "PowerAwareScheduler";
    }

    @Override
    public SchedulingResult schedule(Workload workload, PlacementContext context) {
        List<NodeResource> candidates = context.nodes();
        if (candidates.isEmpty()) {
            return SchedulingResult.failure("후보 노드가 없습니다");
        }

        SchedulingPolicy policy = context.policy();
        List<NodeScore> scores = new ArrayList<>();
        NodeScore bestScore = null;

        for (NodeResource node : candidates) {
            String exclusionReason = context.exclusionReason(workload, node);
            boolean eligible = (exclusionReason == null);

            // ── PEI (Power Efficiency Index) ─────────────────────
            double pei = powerModel.normalizedPei(node);

            // ── 전력 비례성 점수 ─────────────────────────────────
            double proportionalityScore = powerModel.proportionality(node);

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

            totalScore = round(totalScore);

            Map<String, Double> details = new LinkedHashMap<>();
            details.put("PEI", round(pei));
            details.put("전력비례성", round(proportionalityScore));
            details.put("냉각보정점수", round(tempAdjustedScore));
            details.put("전력통합보너스", round(consolidationBonus));
            details.put("cpu여유", round(cpuScore));
            details.put("메모리여유", round(memScore));
            details.put("한계전력W", round(powerModel.marginalPower(node, workload.getRequiredCpu())));
            details.put("배치후예상전력W", round(powerModel.predictedPower(node, workload.getRequiredCpu())));
            details.put("배치후예상온도C", round(thermalModel.predictedTemperature(node, workload.getRequiredCpu())));

            NodeScore ns = new NodeScore(
                    node.getMdcId(), node.getNodeId(),
                    round(cpuScore), round(memScore),
                    round(pei), round(tempAdjustedScore),
                    round(consolidationBonus),
                    totalScore, eligible, exclusionReason, details
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
                "[전력최적화] 점수 %.4f (PEI:%.2f 냉각보정:%.2f 통합:%.2f) — %d개 후보",
                bestScore.totalScore(), bestScore.powerScore(), bestScore.temperatureScore(),
                bestScore.networkScore(), candidates.size());

        return new SchedulingResult(true, bestScore.mdcId(), bestScore.nodeId(),
                bestScore.totalScore(), reason, scores);
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
