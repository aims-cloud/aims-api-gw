package com.innogrid.aims.scheduler.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;

/**
 * 온도 인지 스케줄러 (Thermal-Aware Scheduler).
 *
 * 논문 수준 알고리즘:
 * 1. 열적 여유(Thermal Headroom) 기반 1차 스코어링
 *    → headroom = (T_max - T_current) / T_max
 *    → 열적 여유가 클수록 높은 점수
 *
 * 2. 열적 균형(Thermal Balancing) 보정
 *    → 전체 노드의 평균 온도 계산
 *    → 평균보다 낮은 노드에 보너스, 높은 노드에 패널티
 *    → balance_factor = (T_avg - T_current) / T_avg
 *    → 핫스팟(hot spot) 방지
 *
 * 3. 열관성(Thermal Inertia) 예측
 *    → 워크로드 배치 후 예상 온도 상승량 추정 (ConstraintEvaluator 가 제외 판정)
 *    → ΔT = k × (requiredCPU / totalCPU) × T_current
 *
 * 4. 냉각 비용 최소화
 *    → 높은 온도의 서버에 워크로드를 추가하면 냉각 비용이 비선형적으로 증가
 *    → cooling_cost = (T / T_max)^2 (제곱 비용 모델)
 */
@Component
public class TemperatureAwareScheduler implements SchedulingEngine {

    private static final double BALANCE_WEIGHT = 0.25;
    private static final double HEADROOM_WEIGHT = 0.30;
    private static final double COOLING_COST_WEIGHT = 0.20;
    private static final double RESOURCE_WEIGHT = 0.25;

    private final ThermalModel thermalModel;

    @Autowired
    public TemperatureAwareScheduler(ThermalModel thermalModel) {
        this.thermalModel = thermalModel;
    }

    /** 스프링 컨텍스트 없이 쓰는 기본 생성자 (단위 테스트). 설정 기본값을 사용한다. */
    public TemperatureAwareScheduler() {
        this(new ThermalModel(new SchedulerProperties()));
    }

    @Override
    public String getId() {
        return "temperature";
    }

    @Override
    public String getName() {
        return "TemperatureAwareScheduler";
    }

    @Override
    public SchedulingResult schedule(Workload workload, PlacementContext context) {
        List<NodeResource> candidates = context.nodes();
        if (candidates.isEmpty()) {
            return SchedulingResult.failure("후보 노드가 없습니다");
        }

        SchedulingPolicy policy = context.policy();
        double maxTemp = policy.getMaxTemperatureCelsius();

        // 전체 노드 평균 온도 (열적 균형용)
        double avgTemperature = context.averageTemperature();

        List<NodeScore> scores = new ArrayList<>();
        NodeScore bestScore = null;

        for (NodeResource node : candidates) {
            // ── 하드 제약 (배치 후 예상 온도 포함) ─────────────────
            String exclusionReason = context.exclusionReason(workload, node);
            boolean eligible = (exclusionReason == null);

            // ── 1. 열적 여유 스코어 ─────────────────────────────
            double headroomScore = thermalModel.headroomScore(node.getTemperatureCelsius(), maxTemp);

            // ── 2. 열적 균형 스코어 ─────────────────────────────
            double balanceScore = thermalModel.balanceScore(node.getTemperatureCelsius(), avgTemperature);

            // ── 3. 냉각 비용 스코어 (제곱 비용 모델) ─────────────
            double coolingScore = thermalModel.coolingScore(node.getTemperatureCelsius(), maxTemp);

            // ── 4. 자원 여유 스코어 ─────────────────────────────
            double resourceScore = ((100 - node.getCpuUsagePercent()) / 100.0 * 0.5)
                    + ((100 - node.getMemoryUsagePercent()) / 100.0 * 0.5);

            // ── 가중합 ─────────────────────────────────────────
            double totalScore = headroomScore * HEADROOM_WEIGHT
                    + balanceScore * BALANCE_WEIGHT
                    + coolingScore * COOLING_COST_WEIGHT
                    + resourceScore * RESOURCE_WEIGHT;

            totalScore = round(totalScore);

            Map<String, Double> details = new LinkedHashMap<>();
            details.put("열적여유", round(headroomScore));
            details.put("열적균형", round(balanceScore));
            details.put("냉각점수", round(coolingScore));
            details.put("자원여유", round(resourceScore));
            details.put("배치후예상온도C", round(thermalModel.predictedTemperature(node, workload.getRequiredCpu())));
            details.put("클러스터평균온도C", round(avgTemperature));

            NodeScore ns = new NodeScore(
                    node.getMdcId(), node.getNodeId(),
                    round(resourceScore), round(resourceScore),
                    round(coolingScore), round(headroomScore),
                    round(balanceScore),
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
                    "온도 제약을 충족하는 노드가 없습니다", scores);
        }

        String reason = String.format(
                "[온도최적화] 점수 %.4f (여유:%.2f 균형:%.2f 냉각:%.2f 자원:%.2f) 평균온도:%.1f°C — %d개 후보",
                bestScore.totalScore(), bestScore.temperatureScore(), bestScore.networkScore(),
                bestScore.powerScore(), bestScore.cpuScore(), avgTemperature, candidates.size());

        return new SchedulingResult(true, bestScore.mdcId(), bestScore.nodeId(),
                bestScore.totalScore(), reason, scores);
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
