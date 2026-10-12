package com.innogrid.aims.scheduler.engine;

import java.util.LinkedHashMap;
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
public class WeightedScoreScheduler extends AbstractScoringEngine<AbstractScoringEngine.Run> {

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
        this(new PowerModel(props), new ThermalModel(props), new NetworkModel(props));
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
    protected Run newRun(Workload workload, PlacementContext context) {
        return new Run();
    }

    @Override
    protected NodeScore scoreNode(Workload workload, PlacementContext context, NodeResource node, Exclusion exclusion, Run run) {
        SchedulingPolicy policy = context.policy();

        // ── 소프트 스코어링 (5차원, 예약분 반영 실효값) ──
        double cpuScore = (100.0 - node.effectiveCpuUsagePercent()) / 100.0;
        double memScore = (100.0 - node.effectiveMemoryUsagePercent()) / 100.0;
        // 전력·온도를 수집하지 못한 노드(0W·0°C)는 판단 근거가 없으므로 중립 0.5.
        // 실측 0 을 그대로 계산하면 여유 100% 로 읽혀 미계측 노드가 만점을 받는다.
        double powerScore = Measured.weightedPowerNeutral(policy, node)
                ? 0.5
                : powerModel.effectiveHeadroomPercent(node) / 100.0;
        double tempScore = Measured.weightedThermalNeutral(policy, node)
                ? 0.5
                : thermalModel.headroomScore(thermalModel.effectiveTemperature(node),
                                             policy.getMaxTemperatureCelsius());
        double netScore = networkModel.localTrafficScore(node);   // 예약을 트래픽으로 환산할 근거 없음
        // 게이트(정책 affinityEnabled + 워크로드 dataMdcId)가 켜졌을 때만 데이터 MDC ↔ 노드 MDC 링크 품질을 섞는다.
        // 같은 MDC 는 1.0, 미등록 원격은 설정값(기본 0.5)이라 등록된 양호 링크 아래로 간다. DOWN 은 C5 가 이미 제외했다.
        Double linkScore = null;
        Double linkRegistered = null;
        if (context.linkAware(workload)) {
            String dataMdc = workload.getDataMdcId();
            linkScore = networkModel.linkScore(dataMdc, node.getMdcId(), context.linkMatrix(),
                                               workload.getRequiredBandwidthMbps());
            netScore = networkModel.blend(netScore, linkScore);
            // 링크 점수가 측정에서 왔는지 기본값인지를 응답에서 구분할 수 있게. 같은 MDC 는 링크를 타지
            // 않으므로 "등록됨" 과 같은 1.0 — 게이트가 켜진 후보는 모두 이 키를 가져 키 집합이 노드마다 다르지 않다.
            boolean sameMdc = dataMdc.equals(node.getMdcId());
            linkRegistered = sameMdc || networkModel.isRegistered(dataMdc, node.getMdcId(), context.linkMatrix())
                    ? 1.0 : 0.0;
        }

        // ── 가중합 ── 항을 더하는 순서를 바꾸지 않는다 — 반올림 경계에 걸린 값이 있어 순서만 바꿔도 넷째 자리가 움직인다
        double totalScore = cpuScore * policy.getCpuWeight()
                + memScore * policy.getMemoryWeight()
                + powerScore * policy.getPowerWeight()
                + tempScore * policy.getTemperatureWeight()
                + netScore * policy.getNetworkWeight();

        // ── 선호도 보너스 ── 반올림 전에 곱한다
        if (workload.getPreferredMdcId() != null
                && workload.getPreferredMdcId().equals(node.getMdcId())) {
            totalScore *= 1.10; // +10% 보너스
        }

        // 우선순위 보너스
        totalScore *= getPriorityMultiplier(workload.getPriority());

        totalScore = Scores.round(totalScore);

        Map<String, Double> details = new LinkedHashMap<>();
        details.put("cpu여유", Scores.round(cpuScore));
        details.put("메모리여유", Scores.round(memScore));
        details.put("전력여유", Scores.round(powerScore));
        details.put("온도여유", Scores.round(tempScore));
        details.put("네트워크한산도", Scores.round(netScore));
        putDiskCheck(details, workload, node);
        if (linkScore != null) {   // 게이트 on 일 때만 — 게이트 off 응답의 키 집합은 예전과 같다
            details.put("링크품질", Scores.round(linkScore));
            details.put("링크등록", linkRegistered);   // 1.0 등록됨·같은 MDC, 0.0 원장에 없음(링크품질이 기본값)
        }

        return nodeScore(node, Scores.round(cpuScore), Scores.round(memScore), Scores.round(powerScore),
                Scores.round(tempScore), Scores.round(netScore), totalScore, exclusion, details);
    }

    @Override
    protected String noEligibleReason() {
        return "모든 후보 노드가 제약 조건을 충족하지 못합니다";
    }

    @Override
    protected String reason(NodeScore best, PlacementContext context, int candidateCount, Run run) {
        return String.format(
                "최고 점수 %.4f (CPU:%.2f MEM:%.2f PWR:%.2f TEMP:%.2f NET:%.2f) — %d개 후보 중 선택",
                best.totalScore(), best.cpuScore(), best.memoryScore(),
                best.powerScore(), best.temperatureScore(), best.networkScore(),
                candidateCount);
    }

    private double getPriorityMultiplier(Workload.WorkloadPriority priority) {
        return switch (priority) {
            case CRITICAL -> 1.2;
            case HIGH -> 1.1;
            case NORMAL -> 1.0;
            case LOW -> 0.9;
        };
    }
}
