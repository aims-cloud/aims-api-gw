package com.innogrid.aims.scheduler.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.constraint.Exclusion;
import com.innogrid.aims.scheduler.engine.model.NetworkModel;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
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
 * <p><b>우선순위 배수·선호 MDC 보너스·MDC 링크 점수는 이 엔진에 적용하지 않는다.</b> 셋은 {@code weighted}
 * 엔진에만 있다. 정책이 링크 게이트를 켜도 이 엔진은 DOWN 링크 제외(C5)만 공유하고 링크 품질을 점수에 섞지
 * 않는다. 스케줄의 엔진 선택이 곧 이 항목들의 적용 여부를 결정하므로, 우선순위나 데이터 MDC 를 쓰는 워크로드는
 * 엔진을 바꾸면 그 효과가 사라진다는 점을 운영 문서가 알려야 한다.</p>
 *
 * <p><b>전력 통합은 별도 항이 아니다.</b> 예전에는 "CPU 가 10% 를 넘으면 보너스"라는 임의 규칙을
 * 뒀지만, 실효 기여가 명목 10% 가 아닌 1.5% 에 그쳤고 근거도 없었다. 지금은 빈 노드에 배치할 때
 * 그 노드의 유휴 전력이 새로 든다는 사실을 {@code realMarginalPower} 가 직접 계산한다 — 통합이
 * W 단위로 설명된다(동적항은 모델식, 깨우기항은 계측 노드의 실측 역산 — 총량의 실측 검증은 아니다).</p>
 *
 * <p><b>전력·온도를 수집하지 못한 노드는 중립값 0.5 를 준다.</b> 0 을 주면 판단 근거가 없는 노드가
 * 최악으로 평가되고, 감점을 아예 하지 않으면 오히려 유리해진다. IPMI 가 없는 환경에서는 모든
 * 노드가 중립이 되어 자원 여유로 결정되며, 그 사실이 {@code reason} 에 드러난다.</p>
 * <p>실질 한계 전력 점수는 노드 정격이 아니라 W 눈금({@code marginalScaleWatts})으로 정규화한다 —
 * 깨우기 벌점이 유휴비가 아니라 절대 W 로 총점에 들어간다.</p>
 */
@Component
public class PowerAwareScheduler extends AbstractScoringEngine<PowerAwareScheduler.PowerRun> {

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
        // 네트워크 모델은 예전 기본 생성자처럼 기본 설정으로 만든다 — 넘겨받은 설정을 쓰지 않던 동작 그대로
        this(new PowerModel(properties), new ThermalModel(properties), new NetworkModel(new SchedulerProperties()), properties);
    }

    @Override
    public String getId() {
        return "power";
    }

    @Override
    public String getName() {
        return "PowerAwareScheduler";
    }

    /** 호출 한 번의 누적값 — 후보 중 전력을 잰 노드가 하나라도 있는가(제외된 후보도 센다 · 현재 동작). */
    static final class PowerRun extends Run {
        boolean anyPowerMetric;
    }

    @Override
    protected PowerRun newRun(Workload workload, PlacementContext context) {
        return new PowerRun();
    }

    @Override
    protected NodeScore scoreNode(Workload workload, PlacementContext context, NodeResource node, Exclusion exclusion, PowerRun run) {
        SchedulingPolicy policy = context.policy();

        boolean hasPower = Measured.powerEnginePower(node);
        boolean hasThermal = Measured.thermal(node);
        run.anyPowerMetric |= hasPower;

        // ── 전력 복합 점수 ──────────────────────────────────
        // PEI·비낭비도 실효 기저 — 한계 전력·가동 판정·자원 여유와 같은 입력(예약 반영)을 본다
        double pei = powerModel.placementPei(node);
        double waste = powerModel.placementWasteScore(node);
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

        double totalScore = Scores.round(combine(policy, powerScore, thermalScore, cpuScore, memScore));

        // 네트워크는 이 엔진의 점수에 쓰지 않지만 실측값은 남긴다. 0 을 넣으면 "포화" 로
        // 오해되고, weighted 엔진이 같은 칸에 담는 값과도 의미가 어긋난다.
        double netScore = networkModel.localTrafficScore(node);

        // 계측하지 못한 값에서 파생된 항목은 숫자를 채우지 않고 null 로 둔다. 0 이나 0.5 를
        // 넣으면 "측정했더니 그 값" 으로 읽혀, 판단 근거가 없다는 사실이 가려진다.
        // 전력 효율 평가 API 도 같은 방식을 쓴다(PowerEfficiencyResponse 주석).
        //
        // 실질 한계 전력은 가동 노드에서는 용량과 CPU 사용률로 동적 증가분을 계산하지만,
        // 유휴 노드의 깨우기 비용은 설정에 따라 실측 전력에서 유휴 전력을 역산할 수 있다.
        // 반대로 배치 후 예상 전력은 실측값을 기준으로 더하는 절대값이라 기준을 모르면 의미가 없다.
        Map<String, Double> details = new LinkedHashMap<>();
        details.put("전력계측", hasPower ? 1.0 : 0.0);
        details.put("온도계측", hasThermal ? 1.0 : 0.0);
        details.put("PEI", Measured.valueOrNull(hasPower, pei));
        details.put("비낭비", Measured.valueOrNull(hasPower, waste));
        details.put("실질한계전력점수", Scores.round(marginal));
        details.put("전력점수", Scores.round(powerScore));
        details.put("열점수", Scores.round(thermalScore));
        details.put("cpu여유", Scores.round(cpuScore));
        details.put("메모리여유", Scores.round(memScore));
        details.put("네트워크한산도", Scores.round(netScore));
        putDiskCheck(details, workload, node);
        details.put("실질한계전력W", Scores.round(powerModel.realMarginalPower(node, workload.getRequiredCpu())));
        details.put("배치후예상전력W",
                Measured.valueOrNull(hasPower, powerModel.predictedPower(node, workload.getRequiredCpu())));
        details.put("배치후예상온도C",
                Measured.valueOrNull(hasThermal, thermalModel.predictedTemperature(node, workload.getRequiredCpu())));
        details.put("가동중", powerModel.isActive(node) ? 1.0 : 0.0);

        return nodeScore(node, Scores.round(cpuScore), Scores.round(memScore),
                Scores.round(powerScore), Scores.round(thermalScore), Scores.round(netScore),
                totalScore, exclusion, details);
    }

    @Override
    protected void afterRanking(List<NodeScore> ranked) {
        annotateConsolidationMargin(ranked);
    }

    @Override
    protected String noEligibleReason() {
        return "전력 제약을 충족하는 노드가 없습니다";
    }

    /**
     * 선택 근거 한 줄.
     * 전력 메트릭이 하나도 없으면 그 사실을 밝힌다 — 그러지 않으면 전력으로 고른 것처럼 보인다.
     */
    @Override
    protected String reason(NodeScore best, PlacementContext context, int candidateCount, PowerRun run) {
        if (!run.anyPowerMetric) {
            return String.format(
                    "[전력최적화] 점수 %.4f — 전력 메트릭이 없어 전력·열을 중립으로 두고 자원 여유로 선택 (%d개 후보)",
                    best.totalScore(), candidateCount);
        }
        return String.format(
                "[전력최적화] 점수 %.4f (전력:%.2f 열:%.2f) — %d개 후보, 클러스터 %.0fW / 가동 %d대 / 반납가능 %.0fW",
                best.totalScore(), best.powerScore(), best.temperatureScore(), candidateCount,
                context.totalPowerWatts(),
                context.activeNodeCount(props.getActiveCpuPercent()),
                context.reclaimableIdleWatts(props.getActiveCpuPercent(), powerModel));
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

    /**
     * 냉각 비용과 경고 구간 감점을 함께 본 열 점수 — 입력은 <b>실효 온도</b>({@link ThermalModel#effectiveTemperature}).
     *
     * <p>같은 점수 안의 전력 3항(실효 사용률·실효 전력)과 자원 여유 2항은 예약분을 반영하는데 열 항만 실측 온도에 머물러 기저가 갈렸다 —
     * 한 실행에서 같은 노드에 워크로드를 잇달아 얹어도 열 항은 첫 배치 자리에 머문다. 온도 엔진({@code TemperatureAwareScheduler})과
     * 배치 후 예상 온도({@link ThermalModel#predictedTemperature})는 이미 실효 온도를 쓴다. 계측 게이트({@code hasThermal})는 의도된
     * 실측이라 그대로 두고, 열관성 계수는 엔진 노브라 판 생성식의 온도 상승분과 다르므로 이 배선은 기저 일치까지만 주장한다.
     * 예약이 0 이면 실효 온도 = 실측 온도라 값이 비트 단위로 같다.</p>
     */
    private double thermalScore(NodeResource node, SchedulingPolicy policy) {
        double maxTemp = policy.getMaxTemperatureCelsius();
        double warnTemp = policy.getWarningTemperatureCelsius() != null
                ? policy.getWarningTemperatureCelsius()
                : thermalModel.defaultWarningCelsius();

        double temperature = thermalModel.effectiveTemperature(node);
        double cooling = clamp01(thermalModel.coolingScore(temperature, maxTemp));
        double penalty = thermalModel.warningPenalty(temperature, warnTemp, maxTemp);

        return cooling * (1.0 - penalty);
    }

    private double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /**
     * 통합 판정의 여유 — 가동 중 후보의 최고 총점 − 빈 후보의 최고 총점(적격 후보만).
     *
     * <p>양수면 통합 쪽(가동 노드)을 골랐고, 값이 작으면 노드 조건이 조금만 달라져도 빈 노드를 깨우는 선택으로
     * 뒤집힌다는 뜻이다. 기준 시나리오에서 0.0002 였다. 점수·순위·선택은 바꾸지 않고 <b>보이게만</b> 한다 —
     * 어느 쪽으로 얼마나 밀어야 하는지는 노드 2대 이상 실환경의 이 값이 쌓인 뒤에 정한다.
     * 한쪽 집단이 없으면 키를 넣지 않는다.</p>
     */
    private static void annotateConsolidationMargin(List<NodeScore> scores) {
        double bestActive = Double.NaN, bestIdle = Double.NaN;
        for (NodeScore ns : scores) {
            if (!ns.eligible()) continue;
            boolean active = Double.valueOf(1.0).equals(ns.details().get("가동중"));
            if (active) bestActive = Double.isNaN(bestActive) ? ns.totalScore() : Math.max(bestActive, ns.totalScore());
            else bestIdle = Double.isNaN(bestIdle) ? ns.totalScore() : Math.max(bestIdle, ns.totalScore());
        }
        if (Double.isNaN(bestActive) || Double.isNaN(bestIdle)) return;
        double margin = Scores.round(bestActive - bestIdle);
        for (NodeScore ns : scores) ns.details().put("통합여유", margin);
    }
}
