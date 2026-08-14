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
 *    → 워크로드 배치 후 예상 온도 상승량 추정
 *    → ΔT = k × (requiredCPU / totalCPU) × T_current
 *    → 예상 온도가 임계치를 초과하면 제외
 *
 * 4. 냉각 비용 최소화
 *    → 높은 온도의 서버에 워크로드를 추가하면 냉각 비용이 비선형적으로 증가
 *    → cooling_cost = (T / T_max)^2 (제곱 비용 모델)
 */
@Component
public class TemperatureAwareScheduler implements SchedulingEngine {

    private static final double THERMAL_INERTIA_K = 0.15; // 열관성 계수
    private static final double BALANCE_WEIGHT = 0.25;
    private static final double HEADROOM_WEIGHT = 0.30;
    private static final double COOLING_COST_WEIGHT = 0.20;
    private static final double RESOURCE_WEIGHT = 0.25;

    @Override
    public String getName() {
        return "TemperatureAwareScheduler";
    }

    @Override
    public SchedulingResult schedule(Workload workload, List<NodeResource> candidates,
                                     SchedulingPolicy policy) {
        if (candidates.isEmpty()) {
            return SchedulingResult.failure("후보 노드가 없습니다");
        }

        double maxTemp = policy.getMaxTemperatureCelsius();

        // 전체 노드 평균 온도 (열적 균형용)
        double avgTemperature = candidates.stream()
                .mapToDouble(NodeResource::getTemperatureCelsius)
                .average().orElse(0);

        List<NodeScore> scores = new ArrayList<>();
        NodeScore bestScore = null;

        for (NodeResource node : candidates) {
            // ── 하드 제약 ───────────────────────────────────────
            boolean eligible = node.canAccommodate(
                    workload.getRequiredCpu(), workload.getRequiredMemoryMb(), workload.getRequiredGpu());
            String exclusionReason = eligible ? null : "자원 부족";

            // 열관성 예측: 배치 후 예상 온도
            double predictedTempRise = THERMAL_INERTIA_K
                    * ((double) workload.getRequiredCpu() / Math.max(node.getTotalCpu(), 1))
                    * node.getTemperatureCelsius();
            double predictedTemp = node.getTemperatureCelsius() + predictedTempRise;

            if (eligible && predictedTemp > maxTemp) {
                eligible = false;
                exclusionReason = String.format("배치 후 예상 온도 %.1f°C > 임계치 %.1f°C", predictedTemp, maxTemp);
            }

            // ── 1. 열적 여유 스코어 ─────────────────────────────
            double headroomScore = Math.max(0, (maxTemp - node.getTemperatureCelsius()) / maxTemp);

            // ── 2. 열적 균형 스코어 ─────────────────────────────
            double balanceScore = 0.5;
            if (avgTemperature > 0) {
                balanceScore = 0.5 + 0.5 * (avgTemperature - node.getTemperatureCelsius()) / avgTemperature;
                balanceScore = Math.max(0, Math.min(1, balanceScore));
            }

            // ── 3. 냉각 비용 스코어 (제곱 비용 모델) ─────────────
            double coolingCost = Math.pow(node.getTemperatureCelsius() / maxTemp, 2);
            double coolingScore = 1.0 - coolingCost;

            // ── 4. 자원 여유 스코어 ─────────────────────────────
            double resourceScore = ((100 - node.getCpuUsagePercent()) / 100.0 * 0.5)
                    + ((100 - node.getMemoryUsagePercent()) / 100.0 * 0.5);

            // ── 가중합 ─────────────────────────────────────────
            double totalScore = headroomScore * HEADROOM_WEIGHT
                    + balanceScore * BALANCE_WEIGHT
                    + coolingScore * COOLING_COST_WEIGHT
                    + resourceScore * RESOURCE_WEIGHT;

            totalScore = Math.round(totalScore * 10000.0) / 10000.0;

            NodeScore ns = new NodeScore(
                    node.getMdcId(), node.getNodeId(),
                    round(resourceScore), round(resourceScore),
                    round(coolingScore), round(headroomScore),
                    round(balanceScore),
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
