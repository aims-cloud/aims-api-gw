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
 * 가중치 스코어 기반 스케줄러.
 *
 * 논문 수준 알고리즘:
 * 1. 하드 제약 조건 필터링 (자원 부족, 임계치 초과 노드 제외)
 * 2. 소프트 스코어링 (5개 차원의 가중합)
 *    - CPU 여유율 스코어: (100 - cpuUsage) / 100
 *    - 메모리 여유율 스코어: (100 - memUsage) / 100
 *    - 전력 효율성 스코어: powerHeadroom / 100 (전력 여유가 클수록 높음)
 *    - 온도 스코어: (maxTemp - currentTemp) / maxTemp (열적 여유가 클수록 높음)
 *    - 네트워크 스코어: 1 / (1 + networkLoad) (트래픽이 적을수록 높음)
 * 3. 가중합 = Σ(weight_i × score_i)
 * 4. 선호도 보너스: preferredMdcId 일치 시 +10%
 * 5. 최고 점수 노드 선택
 */
@Component
public class WeightedScoreScheduler implements SchedulingEngine {

    @Override
    public String getName() {
        return "WeightedScoreScheduler";
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
            // ── 1단계: 하드 제약 조건 필터링 ──────────────────────
            String exclusionReason = checkHardConstraints(workload, node, policy);
            boolean eligible = (exclusionReason == null);

            // ── 2단계: 소프트 스코어링 (5차원) ────────────────────
            double cpuScore = calculateCpuScore(node);
            double memScore = calculateMemoryScore(node);
            double powerScore = calculatePowerScore(node, policy);
            double tempScore = calculateTemperatureScore(node, policy);
            double netScore = calculateNetworkScore(node);

            // ── 3단계: 가중합 ────────────────────────────────────
            double totalScore = cpuScore * policy.getCpuWeight()
                    + memScore * policy.getMemoryWeight()
                    + powerScore * policy.getPowerWeight()
                    + tempScore * policy.getTemperatureWeight()
                    + netScore * policy.getNetworkWeight();

            // ── 4단계: 선호도 보너스 ─────────────────────────────
            if (workload.getPreferredMdcId() != null
                    && workload.getPreferredMdcId().equals(node.getMdcId())) {
                totalScore *= 1.10; // +10% 보너스
            }

            // 우선순위 보너스
            totalScore *= getPriorityMultiplier(workload.getPriority());

            totalScore = Math.round(totalScore * 10000.0) / 10000.0;

            NodeScore ns = new NodeScore(
                    node.getMdcId(), node.getNodeId(),
                    round(cpuScore), round(memScore), round(powerScore),
                    round(tempScore), round(netScore), totalScore,
                    eligible, exclusionReason
            );
            scores.add(ns);

            if (eligible && (bestScore == null || totalScore > bestScore.totalScore())) {
                bestScore = ns;
            }
        }

        // 점수 내림차순 정렬
        scores.sort(Comparator.comparingDouble(NodeScore::totalScore).reversed());

        if (bestScore == null) {
            return new SchedulingResult(false, null, null, 0,
                    "모든 후보 노드가 제약 조건을 충족하지 못합니다", scores);
        }

        String reason = String.format(
                "최고 점수 %.4f (CPU:%.2f MEM:%.2f PWR:%.2f TEMP:%.2f NET:%.2f) — %d개 후보 중 선택",
                bestScore.totalScore(), bestScore.cpuScore(), bestScore.memoryScore(),
                bestScore.powerScore(), bestScore.temperatureScore(), bestScore.networkScore(),
                candidates.size());

        return new SchedulingResult(true, bestScore.mdcId(), bestScore.nodeId(),
                bestScore.totalScore(), reason, scores);
    }

    // ── 하드 제약 조건 ──────────────────────────────────────────

    private String checkHardConstraints(Workload workload, NodeResource node, SchedulingPolicy policy) {
        if (!node.canAccommodate(workload.getRequiredCpu(), workload.getRequiredMemoryMb(), workload.getRequiredGpu())) {
            return String.format("자원 부족 (필요 CPU:%d MEM:%dMB GPU:%d, 가용 CPU:%.1f MEM:%dMB)",
                    workload.getRequiredCpu(), workload.getRequiredMemoryMb(), workload.getRequiredGpu(),
                    node.availableCpu(), node.availableMemoryMb());
        }
        if (node.getCpuUsagePercent() > policy.getMaxCpuUsagePercent()) {
            return String.format("CPU 사용률 초과 (%.1f%% > %.1f%%)", node.getCpuUsagePercent(), policy.getMaxCpuUsagePercent());
        }
        if (node.getMemoryUsagePercent() > policy.getMaxMemoryUsagePercent()) {
            return String.format("메모리 사용률 초과 (%.1f%% > %.1f%%)", node.getMemoryUsagePercent(), policy.getMaxMemoryUsagePercent());
        }
        if (policy.isTemperatureAwareEnabled() && node.getTemperatureCelsius() > policy.getMaxTemperatureCelsius()) {
            return String.format("온도 초과 (%.1f°C > %.1f°C)", node.getTemperatureCelsius(), policy.getMaxTemperatureCelsius());
        }
        if (policy.isPowerAwareEnabled() && node.powerHeadroomPercent() < (100 - policy.getMaxPowerUsagePercent())) {
            return String.format("전력 여유 부족 (여유 %.1f%%)", node.powerHeadroomPercent());
        }
        if (workload.getMaxTemperatureCelsius() != null && node.getTemperatureCelsius() > workload.getMaxTemperatureCelsius()) {
            return String.format("워크로드 온도 제약 초과 (%.1f°C > %.1f°C)", node.getTemperatureCelsius(), workload.getMaxTemperatureCelsius());
        }
        if (workload.getMaxPowerBudgetWatts() != null && node.getPowerWatts() > workload.getMaxPowerBudgetWatts()) {
            return String.format("워크로드 전력 제약 초과 (%.1fW > %.1fW)", node.getPowerWatts(), workload.getMaxPowerBudgetWatts());
        }
        return null;
    }

    // ── 소프트 스코어링 ─────────────────────────────────────────

    private double calculateCpuScore(NodeResource node) {
        return (100.0 - node.getCpuUsagePercent()) / 100.0;
    }

    private double calculateMemoryScore(NodeResource node) {
        return (100.0 - node.getMemoryUsagePercent()) / 100.0;
    }

    private double calculatePowerScore(NodeResource node, SchedulingPolicy policy) {
        if (!policy.isPowerAwareEnabled()) return 0.5;
        return node.powerHeadroomPercent() / 100.0;
    }

    private double calculateTemperatureScore(NodeResource node, SchedulingPolicy policy) {
        if (!policy.isTemperatureAwareEnabled()) return 0.5;
        double maxTemp = policy.getMaxTemperatureCelsius();
        if (maxTemp <= 0) return 0.5;
        return Math.max(0, (maxTemp - node.getTemperatureCelsius()) / maxTemp);
    }

    private double calculateNetworkScore(NodeResource node) {
        double totalBps = node.getNetworkRxBps() + node.getNetworkTxBps();
        double loadMbps = totalBps / (1024 * 1024);
        return 1.0 / (1.0 + loadMbps / 100.0); // 100Mbps 기준 감쇠
    }

    private double getPriorityMultiplier(Workload.WorkloadPriority priority) {
        return switch (priority) {
            case CRITICAL -> 1.2;
            case HIGH -> 1.1;
            case NORMAL -> 1.0;
            case LOW -> 0.9;
        };
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
