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
 * 전력 효율성 기반 스케줄러 — 에너지 비례 컴퓨팅(Energy-Proportional Computing).
 *
 * <p>총점은 두 계층으로 나뉜다.</p>
 * <pre>
 * 총점 = (1−자원몫) × 전력·열 복합 + 자원몫 × 자원 여유
 *
 * 전력점수 = w1×PEI + w2×비낭비 + w3×실질한계전력
 * 열점수   = 냉각점수 × (1 − 경고감점)
 * </pre>
 *
 * <p><b>정책 가중치를 그대로 쓰지 않는다.</b> 정책 기본값은 {@code cpu .25 + memory .25 = 0.50}
 * 대 {@code power .20} 이라, 그대로 쓰면 전력 항이 소수파가 되어 전력 엔진이 전력 엔진이 아니게
 * 된다. 정책 가중치는 <b>전력↔열·CPU↔메모리의 비율로만</b> 반영하고, 전력·열 대 자원의 배분은
 * 엔진 고유 설정({@code resourceShare})으로 둔다.</p>
 *
 * <p><b>전력 통합은 별도 항이 아니다.</b> 예전에는 "CPU 가 10% 를 넘으면 보너스"라는 임의 규칙을
 * 뒀지만, 실효 기여가 명목 10% 가 아닌 1.5% 에 그쳤고 근거도 없었다. 지금은 빈 노드에 배치할 때
 * 그 노드의 유휴 전력이 새로 든다는 사실을 {@code realMarginalPower} 가 직접 계산한다 — 통합이
 * 물리량으로 설명된다.</p>
 *
 * <p><b>전력·온도를 수집하지 못한 노드는 중립값 0.5 를 준다.</b> 0 을 주면 판단 근거가 없는 노드가
 * 최악으로 평가되고, 감점을 아예 하지 않으면 오히려 유리해진다. IPMI 가 없는 환경에서는 모든
 * 노드가 중립이 되어 자원 여유로 결정되며, 그 사실이 {@code reason} 에 드러난다.</p>
 */
@Component
public class PowerAwareScheduler implements SchedulingEngine {

    private final PowerModel powerModel;
    private final ThermalModel thermalModel;
    private final NetworkModel networkModel;
    private final SchedulerProperties.Power props;

    @Autowired
    public PowerAwareScheduler(PowerModel powerModel, ThermalModel thermalModel,
                               NetworkModel networkModel, SchedulerProperties properties) {
        this.powerModel = powerModel;
        this.thermalModel = thermalModel;
        this.networkModel = networkModel;
        this.props = properties.getPower();
    }

    /** 스프링 컨텍스트 없이 쓰는 기본 생성자 (단위 테스트). 설정 기본값을 사용한다. */
    public PowerAwareScheduler() {
        this(new SchedulerProperties());
    }

    /** 설정을 직접 넣어 만드는 형태. 가중치·볼록 지수를 바꿔 보는 테스트에서 쓴다. */
    PowerAwareScheduler(SchedulerProperties properties) {
        this(new PowerModel(properties), new ThermalModel(properties), new NetworkModel(), properties);
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
        boolean anyPowerMetric = false;

        for (NodeResource node : candidates) {
            String exclusionReason = context.exclusionReason(workload, node);
            boolean eligible = (exclusionReason == null);

            boolean hasPower = node.getPowerWatts() > 0 && node.getPowerCapacityWatts() > 0;
            boolean hasThermal = node.getTemperatureCelsius() > 0 && policy.getMaxTemperatureCelsius() > 0;
            anyPowerMetric |= hasPower;

            // ── 전력 복합 점수 ──────────────────────────────────
            double pei = powerModel.normalizedPei(node);
            double waste = powerModel.wasteScore(node);
            double marginal = powerModel.marginalScore(node, workload.getRequiredCpu());
            double powerScore = hasPower
                    ? props.getPeiWeight() * pei
                        + props.getWasteWeight() * waste
                        + props.getMarginalWeight() * marginal
                    : 0.5;

            // ── 열 점수 ────────────────────────────────────────
            double thermalScore = hasThermal ? thermalScore(node, policy) : 0.5;

            // ── 자원 여유 ──────────────────────────────────────
            // 예약분을 반영한다. 실측만 보면 한 번의 실행에서 워크로드를 잇달아 배치할 때
            // 점수가 고정되어 같은 노드가 계속 뽑히고, 제약이 막을 때까지 한쪽으로 몰린다.
            double cpuScore = (100.0 - node.effectiveCpuUsagePercent()) / 100.0;
            double memScore = (100.0 - node.effectiveMemoryUsagePercent()) / 100.0;

            double totalScore = round(combine(policy, powerScore, thermalScore, cpuScore, memScore));

            // 네트워크는 이 엔진의 점수에 쓰지 않지만 실측값은 남긴다. 0 을 넣으면 "포화" 로
            // 오해되고, weighted 엔진이 같은 칸에 담는 값과도 의미가 어긋난다.
            double netScore = networkModel.localTrafficScore(node);

            Map<String, Double> details = new LinkedHashMap<>();
            details.put("PEI", round(pei));
            details.put("비낭비", round(waste));
            details.put("실질한계전력점수", round(marginal));
            details.put("전력점수", round(powerScore));
            details.put("열점수", round(thermalScore));
            details.put("cpu여유", round(cpuScore));
            details.put("메모리여유", round(memScore));
            details.put("네트워크한산도", round(netScore));
            details.put("실질한계전력W", round(powerModel.realMarginalPower(node, workload.getRequiredCpu())));
            details.put("배치후예상전력W", round(powerModel.predictedPower(node, workload.getRequiredCpu())));
            details.put("배치후예상온도C", round(thermalModel.predictedTemperature(node, workload.getRequiredCpu())));
            details.put("가동중", powerModel.isActive(node) ? 1.0 : 0.0);

            NodeScore ns = new NodeScore(
                    node.getMdcId(), node.getNodeId(),
                    round(cpuScore), round(memScore),
                    round(powerScore), round(thermalScore), round(netScore),
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

        return new SchedulingResult(true, bestScore.mdcId(), bestScore.nodeId(),
                bestScore.totalScore(), reason(bestScore, context, candidates.size(), anyPowerMetric), scores);
    }

    /**
     * 두 계층을 합친다.
     *
     * <p>정책 가중치의 <b>비율만</b> 쓴다. 합이 1 이 아니거나 일부가 0 이어도 각 계층 안에서
     * 정규화되므로 총점은 0~1 을 벗어나지 않는다. 한 계층의 가중치가 전부 0 이면 그 안의 두
     * 항을 반씩 본다.</p>
     */
    private double combine(SchedulingPolicy policy,
                           double powerScore, double thermalScore,
                           double cpuScore, double memScore) {
        double share = clamp01(props.getResourceShare());

        double ptSum = policy.getPowerWeight() + policy.getTemperatureWeight();
        double ptPart = ptSum > 0
                ? (policy.getPowerWeight() * powerScore + policy.getTemperatureWeight() * thermalScore) / ptSum
                : (powerScore + thermalScore) / 2.0;

        double resSum = policy.getCpuWeight() + policy.getMemoryWeight();
        double resPart = resSum > 0
                ? (policy.getCpuWeight() * cpuScore + policy.getMemoryWeight() * memScore) / resSum
                : (cpuScore + memScore) / 2.0;

        return (1.0 - share) * ptPart + share * resPart;
    }

    /** 냉각 비용과 경고 구간 감점을 함께 본 열 점수. */
    private double thermalScore(NodeResource node, SchedulingPolicy policy) {
        double maxTemp = policy.getMaxTemperatureCelsius();
        double warnTemp = policy.getWarningTemperatureCelsius() != null
                ? policy.getWarningTemperatureCelsius()
                : thermalModel.defaultWarningCelsius();

        double cooling = clamp01(thermalModel.coolingScore(node.getTemperatureCelsius(), maxTemp));
        double penalty = thermalModel.warningPenalty(node.getTemperatureCelsius(), warnTemp, maxTemp);

        return cooling * (1.0 - penalty);
    }

    /**
     * 선택 근거 한 줄.
     * 전력 메트릭이 하나도 없으면 그 사실을 밝힌다 — 그러지 않으면 전력으로 고른 것처럼 보인다.
     */
    private String reason(NodeScore best, PlacementContext context, int candidateCount, boolean anyPowerMetric) {
        if (!anyPowerMetric) {
            return String.format(
                    "[전력최적화] 점수 %.4f — 전력 메트릭이 없어 전력·열을 중립으로 두고 자원 여유로 선택 (%d개 후보)",
                    best.totalScore(), candidateCount);
        }
        return String.format(
                "[전력최적화] 점수 %.4f (전력:%.2f 열:%.2f) — %d개 후보, 클러스터 %.0fW / 가동 %d대 / 반납가능 %.0fW",
                best.totalScore(), best.powerScore(), best.temperatureScore(), candidateCount,
                context.totalPowerWatts(),
                context.activeNodeCount(props.getActiveCpuPercent()),
                context.reclaimableIdleWatts(props.getActiveCpuPercent(), props.getIdleRatio()));
    }

    private double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
