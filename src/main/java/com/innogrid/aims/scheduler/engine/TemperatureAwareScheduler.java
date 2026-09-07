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
 *
 * <p><b>온도·자원 점수는 예약분까지 반영한 실효값으로 계산한다.</b> 한 실행에서 워크로드를
 * 잇달아 배치하면 방금 배치한 부하는 메트릭에 없다. 실측만 보면 점수가 매번 같아 가장
 * 시원한 노드 하나로 몰린다 — 열관성 예측({@code predictedTemperature})은 이미 실효 기준이었는데
 * 점수만 실측이었다. 열적 균형의 기준인 <b>클러스터 평균도 실효 온도의 평균</b>으로 맞춘다.
 * 두 피연산자가 다른 시점을 보면 "0.5 = 평균 수준"이라는 기준점의 의미가 흐려진다.
 * 예약이 없으면 실효=실측이므로 단건 배치의 결과는 달라지지 않는다.</p>
 *
 * <p><b>온도를 수집하지 못한 노드(0°C)는 열 점수를 중립 0.5 로 둔다.</b> 0 을 그대로 계산하면
 * 열적 여유와 냉각이 만점이 되고 균형도 상한에 붙어, 판단 근거가 없는 노드가 <b>가중치 0.75 를
 * 통째로</b> 가져간다. IPMI 가 없는 노드가 계측된 노드보다 유리해지는 것은 근거 없는 편향이라
 * {@code weighted}·{@code power} 엔진과 같은 중립값을 준다.</p>
 *
 * <p>같은 이유로 <b>클러스터 평균 온도도 계측된 노드만으로</b> 낸다({@code PlacementContext}).
 * 미계측 0°C 를 평균에 넣으면 기준선이 내려가고, 균형 점수가 {@code 1 − 0.5 × T / 평균} 이라
 * <b>평균의 두 배를 넘는 온도가 0 으로 잘리는 지점까지 함께 내려온다</b> — 정상 범위의 계측 노드가
 * 없는 근거 때문에 감점을 받는다.</p>
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

        // 전체 노드 평균 온도 (열적 균형용) — 노드 온도와 같은 기준(실효)으로 맞춘다
        double avgTemperature = context.averageTemperature(thermalModel);

        List<NodeScore> scores = new ArrayList<>();
        NodeScore bestScore = null;
        boolean anyThermal = false;   // 온도로 판단할 수 있었는가 — 선택 사유가 근거를 밝힌다

        for (NodeResource node : candidates) {
            // ── 하드 제약 (배치 후 예상 온도 포함) ─────────────────
            String exclusionReason = context.exclusionReason(workload, node);
            boolean eligible = (exclusionReason == null);

            // 온도를 수집하지 못한 노드(0°C)는 판단 근거가 없다. 0 을 그대로 계산하면 열적 여유·냉각이
            // 만점, 균형도 상한이 되어 가중치 0.75 를 통째로 가져간다 — 다른 두 엔진과 같이 중립 0.5 를 준다.
            // 계측 여부는 실측의 성질이라 실효 온도가 아니라 실측 온도로 판정한다(power 엔진과 같은 철자).
            // 부등호를 써야 한다 — headroomScore 에는 상한 클램프가 없어 음수 온도가 1.0 을 넘긴다.
            boolean hasThermal = node.getTemperatureCelsius() > 0 && maxTemp > 0;
            anyThermal |= hasThermal;

            // 예약분까지 반영한 온도. 실측만 보면 다건 배치가 한 노드로 몰린다.
            double temperature = thermalModel.effectiveTemperature(node);

            // ── 1. 열적 여유 스코어 ─────────────────────────────
            double headroomScore = hasThermal ? thermalModel.headroomScore(temperature, maxTemp) : 0.5;

            // ── 2. 열적 균형 스코어 ─────────────────────────────
            double balanceScore = hasThermal ? thermalModel.balanceScore(temperature, avgTemperature) : 0.5;

            // ── 3. 냉각 비용 스코어 (제곱 비용 모델) ─────────────
            double coolingScore = hasThermal ? thermalModel.coolingScore(temperature, maxTemp) : 0.5;

            // ── 4. 자원 여유 스코어 (예약분 반영) ─────────────────
            double resourceScore = ((100 - node.effectiveCpuUsagePercent()) / 100.0 * 0.5)
                    + ((100 - node.effectiveMemoryUsagePercent()) / 100.0 * 0.5);

            // ── 가중합 ─────────────────────────────────────────
            double totalScore = headroomScore * HEADROOM_WEIGHT
                    + balanceScore * BALANCE_WEIGHT
                    + coolingScore * COOLING_COST_WEIGHT
                    + resourceScore * RESOURCE_WEIGHT;

            totalScore = round(totalScore);

            Map<String, Double> details = new LinkedHashMap<>();
            // 중립 0.5 가 "재 봤더니 0.5" 로 읽히지 않게 계측 여부를 함께 낸다(power 엔진과 같은 관례).
            details.put("온도계측", hasThermal ? 1.0 : 0.0);
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

        // 온도 메트릭이 하나도 없으면 그 사실을 밝힌다 — 그러지 않으면 온도로 고른 것처럼 보이고,
        // 평균온도 0.0°C 가 "클러스터가 0도" 로 읽힌다(power 엔진과 같은 관례).
        String reason = anyThermal
                ? String.format(
                        "[온도최적화] 점수 %.4f (여유:%.2f 균형:%.2f 냉각:%.2f 자원:%.2f) 평균온도:%.1f°C — %d개 후보",
                        bestScore.totalScore(), bestScore.temperatureScore(), bestScore.networkScore(),
                        bestScore.powerScore(), bestScore.cpuScore(), avgTemperature, candidates.size())
                : String.format(
                        "[온도최적화] 점수 %.4f — 온도 메트릭이 없어 열 점수를 중립으로 두고 자원 여유로 선택 (%d개 후보)",
                        bestScore.totalScore(), candidates.size());

        return new SchedulingResult(true, bestScore.mdcId(), bestScore.nodeId(),
                bestScore.totalScore(), reason, scores);
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
