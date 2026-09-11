package com.innogrid.aims.scheduler.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.model.NetworkModel;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;

/**
 * 가중치 스코어 기반 스케줄러.
 *
 * 논문 수준 알고리즘:
 * 1. 하드 제약 조건 필터링 (ConstraintEvaluator 가 담당)
 * 2. 소프트 스코어링 (5개 차원의 가중합)
 *    - CPU 여유율 스코어: (100 - cpuUsage) / 100
 *    - 메모리 여유율 스코어: (100 - memUsage) / 100
 *    - 전력 효율성 스코어: powerHeadroom / 100 (전력 여유가 클수록 높음)
 *    - 온도 스코어: (maxTemp - currentTemp) / maxTemp (열적 여유가 클수록 높음)
 *    - 네트워크 스코어: 1 / (1 + networkLoad) (트래픽이 적을수록 높음)
 * 3. 가중합 = Σ(weight_i × score_i)
 *    - 네트워크 스코어는 게이트(정책 affinityEnabled + 워크로드 dataMdcId)가 켜지면
 *      데이터 MDC ↔ 노드 MDC 링크 점수와 linkWeight 비율로 섞인다 (NetworkModel 참고)
 * 4. 선호도 보너스: preferredMdcId 일치 시 +10%
 * 5. 최고 점수 노드 선택
 *
 * <p><b>점수는 실측이 아니라 예약분까지 반영한 실효값으로 계산한다.</b> 스케줄 실행은
 * 메트릭을 실행당 한 번만 수집하므로, 한 실행에서 워크로드를 잇달아 배치하면 방금 배치한
 * 부하는 메트릭에 없다. 실측만 보면 점수가 매번 같아 최고점 노드 하나로 몰리고, 제약
 * (C1·C2)이 막아설 때까지 계속된다 — 제약은 실효값을 보는데 점수는 실측을 보는 셈이다.
 * 배포 VM 에서 2코어 노드에 1코어를 예약한 뒤에도 cpu여유가 0.8733 그대로였던 것이 이 결함이다.
 * 예약이 없으면 실효=실측이므로 단건 배치의 결과는 달라지지 않는다.</p>
 *
 * <p>네트워크 점수만 실측을 유지한다 — 예약(코어·메모리)을 트래픽으로 환산할 근거가 없다.</p>
 *
 * <p><b>전력·온도를 수집하지 못한 노드는 그 차원에서 중립 0.5 를 받는다.</b> 0W·0°C 를 그대로
 * 계산하면 여유 100% 로 읽혀 IPMI 가 없는 노드가 전력·온도 만점(가중치 합 0.35)을 받고, IPMI 가
 * 섞인 클러스터에서 계측된 노드보다 체계적으로 유리해진다. {@code power} 엔진과 같은 원칙이다.</p>
 */
@Component
public class WeightedScoreScheduler implements SchedulingEngine {

    private final PowerModel powerModel;
    private final ThermalModel thermalModel;
    private final NetworkModel networkModel;

    @Autowired
    public WeightedScoreScheduler(PowerModel powerModel, ThermalModel thermalModel, NetworkModel networkModel) {
        this.powerModel = powerModel;
        this.thermalModel = thermalModel;
        this.networkModel = networkModel;
    }

    /** 스프링 컨텍스트 없이 쓰는 기본 생성자 (단위 테스트). 설정 기본값을 사용한다. */
    public WeightedScoreScheduler() {
        this(new SchedulerProperties());
    }

    private WeightedScoreScheduler(SchedulerProperties props) {
        this(new PowerModel(props), new ThermalModel(props), new NetworkModel());
    }

    @Override
    public String getId() {
        return "weighted";
    }

    @Override
    public String getName() {
        return "WeightedScoreScheduler";
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
            // ── 1단계: 하드 제약 조건 필터링 ──────────────────────
            String exclusionReason = context.exclusionReason(workload, node);
            boolean eligible = (exclusionReason == null);

            // ── 2단계: 소프트 스코어링 (5차원, 예약분 반영 실효값) ──
            double cpuScore = (100.0 - node.effectiveCpuUsagePercent()) / 100.0;
            double memScore = (100.0 - node.effectiveMemoryUsagePercent()) / 100.0;
            // 전력·온도를 수집하지 못한 노드(0W·0°C)는 판단 근거가 없으므로 중립 0.5.
            // 실측 0 을 그대로 계산하면 여유 100% 로 읽혀 미계측 노드가 만점을 받는다.
            double powerScore = !policy.isPowerAwareEnabled() || node.getPowerWatts() <= 0
                    ? 0.5
                    : powerModel.effectiveHeadroomPercent(node) / 100.0;
            double tempScore = !policy.isTemperatureAwareEnabled() || node.getTemperatureCelsius() <= 0
                    ? 0.5
                    : thermalModel.headroomScore(thermalModel.effectiveTemperature(node),
                                                 policy.getMaxTemperatureCelsius());
            double netScore = networkModel.localTrafficScore(node);   // 예약을 트래픽으로 환산할 근거 없음
            // 게이트(정책 affinityEnabled + 워크로드 dataMdcId)가 켜졌을 때만 데이터 MDC ↔ 노드 MDC 링크 품질을 섞는다.
            // 같은 MDC 는 1.0, 미등록 원격은 설정값(기본 0.5)이라 등록된 양호 링크 아래로 간다. DOWN 은 C5 가 이미 제외했다.
            Double linkScore = null;
            if (context.linkAware(workload)) {
                linkScore = networkModel.linkScore(workload.getDataMdcId(), node.getMdcId(), context.linkMatrix());
                netScore = networkModel.blend(netScore, linkScore);
            }

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

            totalScore = round(totalScore);

            Map<String, Double> details = new LinkedHashMap<>();
            details.put("cpu여유", round(cpuScore));
            details.put("메모리여유", round(memScore));
            details.put("전력여유", round(powerScore));
            details.put("온도여유", round(tempScore));
            details.put("네트워크한산도", round(netScore));
            if (linkScore != null) details.put("링크품질", round(linkScore));   // 게이트 on 일 때만 — 기존 응답 키 불변

            NodeScore ns = new NodeScore(
                    node.getMdcId(), node.getNodeId(),
                    round(cpuScore), round(memScore), round(powerScore),
                    round(tempScore), round(netScore), totalScore,
                    eligible, exclusionReason, details
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
