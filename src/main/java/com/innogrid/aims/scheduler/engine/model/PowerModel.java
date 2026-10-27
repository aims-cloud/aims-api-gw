package com.innogrid.aims.scheduler.engine.model;

import java.util.List;

import org.springframework.stereotype.Component;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.model.NodeResource;

/**
 * 전력 모델 — 서버별 전력 효율성 지표 계산.
 *
 * 엔진과 평가 API 가 같은 수식을 쓰도록 계산만 담당하는 순수 컴포넌트로 분리했다.
 * 수식은 기존 PowerAwareScheduler 의 것을 그대로 옮겼고, 배치 후 예측 전력과
 * 한계 전력(ΔP)만 새로 추가했다.
 */
@Component
public class PowerModel {

    private final SchedulerProperties.Power props;

    /** 설정 {@code pei-normalizer} 가 0 이하일 때 쓰는 대체값 — 전력 효율 진단이 쓰는 값과 같다. */
    static final double FALLBACK_PEI_NORMALIZER = 0.5;

    public PowerModel(SchedulerProperties properties) {
        this.props = properties.getPower();
    }

    /**
     * 전력 효율 지표(PEI) — 단위 전력당 CPU 활용률을 0~1 로 정규화한다.
     * 전력을 수집하지 못한 노드는 판단 근거가 없으므로 중립값 0.5 를 준다.
     */
    public double normalizedPei(NodeResource node) {
        if (node.getPowerWatts() <= 0) return 0.5;
        double efficiency = node.getCpuUsagePercent() / node.getPowerWatts();
        return Math.min(1.0, efficiency / effectivePeiNormalizer());
    }

    /**
     * 유효 PEI 정규화 상수 — 설정값이 0 보다 크면 그대로, 아니면 대체값. 설정이 0 이면 {@code min(1, x/0)} 이 사용률 &gt; 0 노드에
     * 1.0(포화), 사용률 0 노드에 NaN 을 주고 NaN 은 엔진의 4자리 반올림에서 0.0 으로 삼켜져 그 노드의 전력점수·총점이 최하위로
     * 내려간다. 음수면 점수가 음수다. 진단 서비스도 이 값을 쓰고 경고 로그만 자기 자리에서 남긴다. 이름이 설정 스냅샷의 원시
     * 접근자({@code PowerKnobs.peiNormalizer})와 갈리도록 "유효" 를 붙였다 — 다이제스트는 원시값을 싣는다.
     */
    public double effectivePeiNormalizer() {
        double normalizer = props.getPeiNormalizer();
        return normalizer > 0 ? normalizer : FALLBACK_PEI_NORMALIZER;
    }

    /**
     * <b>배치 점수용</b> PEI — 실효 사용률과 실효 전력({@link #effectivePowerWatts})으로 낸다.
     *
     * <p>{@link #normalizedPei} 는 실측 CPU·실측 W 다. 배치 점수 안의 다른 항({@link #isActive}·{@link #marginalPowerOnCurve}·
     * {@link #effectiveHeadroomPercent})은 이미 예약분을 반영하는데 PEI·비낭비 두 항만 실측에 머물러 <b>한 점수 안에서 기저가 갈렸다</b> —
     * 한 실행에서 같은 노드에 워크로드를 잇달아 얹어도 두 항은 첫 배치 자리에 머문다. 진단 API({@code PowerEfficiencyService}·
     * {@code PowerOptimizationService})는 "지금 이 노드가 어떤가" 를 보이는 자리라 실측 {@code normalizedPei} 를 그대로 쓴다 —
     * {@link #headroomScore} 와 {@link #effectiveHeadroomPercent} 의 분업과 같다.</p>
     *
     * <p><b>코어 비율을 분자에 곱한다.</b> 사용률은 코어 수를 보지 않아, 같은 사용률·같은 W 면 8코어와 64코어가 같은 PEI 를 받았다
     * (판정 회차 4 지목). 그래서 실효 사용률에 (그 노드 코어 수 ÷ 그 배치 호출 후보 중 코어 수가 0 보다 큰 노드의 평균 코어 수) 비율을
     * 먼저 계산해 곱한다. 평균을 내는 후보는 그 호출의 후보 전체다 — 제외 판정을 받은 노드와 전력을 계측하지 못한 노드도 센다.
     * 코어 수가 0 이하인 노드는 비율 1 이다. 새 노브는 없고 정규화 상수도 그대로다. 진단 {@link #normalizedPei} 에는 이 비율이 없어,
     * 코어 수가 섞인 후보에서는 같은 노드의 두 PEI 값이 갈린다.</p>
     *
     * <p>예약이 0 이고 코어 비율이 1(후보가 하나이거나 코어 수가 모두 같음)이면 {@code u_eff = u}, {@code P_eff = P},
     * {@code u × 1.0 = u} 라 {@link #normalizedPei} 와 값이 비트 단위로 같다. 예약 비율 r 이 있으면
     * {@code (u + 100r) / (P + D·r)} (D = 정격 − 유휴) 이고, 이 값이 실측 {@code u/P} 보다 큰 조건은 {@code 100·P > u·D} 다 —
     * 등록된 판정 자격 두 판의 노드는 전부 이 조건을 만족해 예약이 쌓일수록 PEI 가 오른다(판 파일 값으로 계산, 하락·포화 노드 없음 —
     * 두 판의 생성식처럼 P ≥ P_idle + D·u/100 이면 대수적으로 항상 성립). 조건이 깨지는 노드는 이상 곡선 아래로 쓰는 노드고 거기서는 내려간다.
     * 코어 비율은 호출마다 양의 상수라 오르내림의 방향을 바꾸지 않는다(실효 사용률이 100 에 닿기 전까지 — 그 뒤로는 실효 전력만 늘어 내려간다).
     * 다만 비율이 1 보다 큰 노드(코어가 평균보다 많은 노드)는 1.0 에 먼저 닿을 수 있어, 두 판에서 1.0 에 닿지 않는다는 것은 판 파일 값 계산이 아니라
     * {@code PlacementBenchmarkServiceTest} 의 포화 테스트가 확인한다.</p>
     */
    public double placementPei(NodeResource node, double meanCores) {
        double powerEff = effectivePowerWatts(node);
        // 정격 0 이면 실효 전력이 실측에 머물러(D = 0) 분자만 실효가 되는 기저 혼재라 중립값 — placementWasteScore 와 같은 가드.
        // 엔진은 hasPower(전력·정격 > 0)로 이 경로를 이미 막으므로 직접 호출자(테스트·앞으로의 호출부)를 위한 방어다
        if (node.getPowerCapacityWatts() <= 0 || powerEff <= 0) return 0.5;
        // 비율을 먼저 내 분자에만 곱한다 — 비율이 1.0 이면 (u × 1.0) / P = u / P 라 정정 전과 비트 단위로 같다
        double efficiency = node.effectiveCpuUsagePercent() * coreRatio(node, meanCores) / powerEff;
        return Math.min(1.0, efficiency / effectivePeiNormalizer());
    }

    /**
     * 후보가 이 노드 하나일 때의 배치 점수용 PEI — 평균이 자기 코어 수라 비율 1 이다. 배치 엔진은 두 인자 판을 쓴다.
     * 후보가 여럿인 배치 경로에서 이 판을 부르면 코어 비율이 빠진다.
     */
    public double placementPei(NodeResource node) {
        return placementPei(node, node.getTotalCpu());
    }

    /**
     * 배치 점수용 PEI 의 코어 기준 — 후보 중 코어 수가 0 보다 큰 노드의 평균 코어 수. 그런 노드가 없으면 0 이다.
     * 코어 수가 모두 c 이면 정수 합 ÷ 개수 = (n·c)/n 이라 정확히 c 다.
     */
    public static double meanPositiveCores(List<NodeResource> candidates) {
        long sum = 0;
        int count = 0;
        for (NodeResource candidate : candidates) {
            int cores = candidate.getTotalCpu();
            if (cores > 0) {
                sum += cores;
                count++;
            }
        }
        return count == 0 ? 0.0 : (double) sum / count;
    }

    /** 코어 비율 — 코어 수가 0 이하이거나 평균이 0 이면 1. 코어 수가 평균과 같으면 c/c = 1.0 이 정확하다. */
    static double coreRatio(NodeResource node, double meanCores) {
        int cores = node.getTotalCpu();
        return cores > 0 && meanCores > 0 ? cores / meanCores : 1.0;
    }

    /** 정규화 전 PEI 원값(%/W). 평가 API 에서 그대로 보여준다. */
    public double rawPei(NodeResource node) {
        if (node.getPowerWatts() <= 0) return 0;
        return node.getCpuUsagePercent() / node.getPowerWatts();
    }

    /**
     * 전력 비례성 — 이상적인 전력 곡선과의 편차. 1 에 가까울수록 비례적이다.
     * P_ideal = P_idle + (P_max − P_idle) × utilization
     */
    public double proportionality(NodeResource node) {
        double pMax = node.getPowerCapacityWatts();
        if (pMax <= 0) return 0.5;

        double pIdle = idlePower(node);
        double utilization = node.getCpuUsagePercent() / 100.0;
        double pIdeal = pIdle + (pMax - pIdle) * utilization;
        double deviation = Math.abs(node.getPowerWatts() - pIdeal) / pMax;

        return Math.max(0, 1.0 - deviation);
    }

    /** 유휴 전력. P_idle = P_max × idleRatio */
    public double idlePower(NodeResource node) {
        return node.getPowerCapacityWatts() * props.getIdleRatio();
    }

    /**
     * 빈 노드를 깨울 때 새로 드는 전력(W) — 깨우기 비용·반납 가능 전력 계열, 그리고 한계 전력 식
     * {@link #realMarginalPower} 의 동적 범위 기준선. 진단 지표(비례성·비낭비)와 {@link #idlePower} 를 쓰는
     * 선형 식(제약·예상 전력·대조군)은 이 값을 쓰지 않는다 — 그쪽 기준선에 실측을 넣으면 지표가 항등이 된다.
     *
     * <p><b>왜 유휴 노드에서만 역산하는가</b> — 선형 전력식 {@code P = P_idle + (P_max − P_idle)·u}
     * 를 P_idle 에 대해 풀면 {@code P_idle = (P − P_max·u) / (1 − u)} 다. 가동 중인 노드는 이미
     * 다른 워크로드의 동적분이 실측치에 섞여 있어 이 식이 그 노드의 "유휴" 전력을 돌려주지
     * 않는다 — 그리고 조건수가 {@code 1/(1−u)} 라 u 가 1 에 가까울수록 계측 잡음이 그대로
     * 증폭된다. 그래서 가동 노드·활용률 100% 는 역산을 포기하고 대체값으로 돌아간다.</p>
     *
     * <p><b>왜 실측 CPU 인가</b> — 역산은 "같은 순간에 관측한 (P, u) 쌍"이 전제다. 예약분까지
     * 반영한 사용률을 쓰면 이 노드가 실제로 그 전력을 그리던 순간의 u 가 아니게 되어 역산식이
     * 성립하지 않는다.</p>
     *
     * <p><b>왜 {@link #idlePower} 와 다른 메서드인가</b> — {@code idlePower} 는 진단 지표와 선형 식의
     * 기준선이다. 거기에 실측 역산을 넣으면 {@code P_ideal} 이 실측 {@code P} 를 그대로 따라가는 항등식이
     * 되어 비례성·비낭비가 노드를 구별하지 못한다. 그래서 역산은 이 메서드에 두고, 한계 전력 식은 계측된
     * 유휴 노드에서 동적 범위와 깨우기항 두 자리에 이 값을 같이 쓴다({@link #realMarginalPower}).</p>
     *
     * <p><b>미계측 가드</b> — 정격을 모르거나(override 없음) IPMI 가 없어 전력을 못 읽는 노드는
     * 역산할 분자·분모가 없다. 전력 메트릭이 없는 환경(IPMI 미구성)이 이 경로다 — 지금의 배포 환경이
     * 여기 해당하며, IPMI 가 붙으면 그 순간부터 역산 경로를 탄다. 대체값으로 빠지는 것이 운영
     * 안전핀이고, {@link SchedulerProperties.Power#isIdleFromMeasurement()} 로 이 전체 경로를
     * 끄고 대체값 고정으로 되돌릴 수 있다.</p>
     *
     * <p><b>가드 둘은 짝이다</b> — 전력 0 가드({@code P <= 0})와 추정 하한 가드({@code estimate <= 0})는 서로를
     * 덮는다(P=0 이면 추정이 0 이하). 하나를 {@code <} 로 바꾸면 다른 하나가 막아 주지만 둘을 함께 바꾸면
     * 0W 유휴가 유효값으로 나간다. 등호를 둘 다 유지한다.</p>
     */
    public double wakeupPower(NodeResource node) {
        double pMax = node.getPowerCapacityWatts();
        double fallback = pMax * props.getIdleRatio();
        if (!props.isIdleFromMeasurement()) return fallback;
        if (pMax <= 0 || node.getPowerWatts() <= 0) return fallback;   // 정격 미상·미계측(전력 메트릭 없음)
        if (isActive(node)) return fallback;                            // 가동 노드는 역산 불가(동적분이 섞임)
        double u = Math.min(1.0, Math.max(0.0, node.getCpuUsagePercent() / 100.0));   // 실측 CPU — 역산은 같은 순간의 (P, u)
        if (u >= 1.0) return fallback;                                  // 기본 설정에선 isActive 가 먼저 막는다 — 가동 문턱 100% 설정 방어
        double estimate = (node.getPowerWatts() - pMax * u) / (1.0 - u);
        return (estimate <= 0 || estimate > pMax) ? fallback : estimate;   // 범위 밖은 계측 잡음으로 보고 대체값
    }

    /** 전력 여유율 점수 (0~1). */
    public double headroomScore(NodeResource node) {
        return node.powerHeadroomPercent() / 100.0;
    }

    /**
     * 예약분까지 반영한 소비 전력(W).
     *
     * <p>실측 전력은 이미 실행 중인 부하만 담는다. 한 실행에서 여러 워크로드를 배치하면
     * 예약만 쌓이고 실측값은 그대로라, "배치 후 예상 전력" 검사가 매번 <b>워크로드 하나만
     * 얹은 값</b>을 돌려준다. 그 상태로는 용량을 넘겨도 걸러내지 못한다.</p>
     *
     * <p>전력을 수집하지 못한 노드(0W)는 더할 기준이 없으므로 0 을 유지한다 — 예약이 있다고
     * 없던 전력을 만들어내면 판단 근거가 없는 숫자가 생긴다.</p>
     */
    public double effectivePowerWatts(NodeResource node) {
        if (node.getPowerWatts() <= 0) return node.getPowerWatts();

        double pMax = node.getPowerCapacityWatts();
        if (pMax <= 0) return node.getPowerWatts();

        return node.getPowerWatts() + (pMax - idlePower(node)) * node.reservedCpuRatio();
    }

    /**
     * 예약분까지 반영한 전력 여유율 (%).
     *
     * <p>제약 검사와 배치 점수가 함께 쓴다. 실측 여유율({@link NodeResource#powerHeadroomPercent})만
     * 보면 예약이 아무리 쌓여도 여유가 그대로라, 전력 상한이 배치 누적에 대해 적용되지 않고
     * 점수도 매번 같아 한 노드로 몰린다.</p>
     *
     * <p>{@link #headroomScore} 는 실측 그대로다 — 진단·조회처럼 "지금 이 노드에 얼마나 여유가
     * 있나"를 보여주는 자리에서 쓴다. 배치 엔진은 이 메서드를 쓴다.</p>
     */
    public double effectiveHeadroomPercent(NodeResource node) {
        double pMax = node.getPowerCapacityWatts();
        if (pMax <= 0) return 100;
        return (1.0 - effectivePowerWatts(node) / pMax) * 100;
    }

    /**
     * 워크로드를 배치했을 때의 예상 전력(W).
     * P_after = P_effective + (P_max − P_idle) × ΔU,  ΔU = 요구 CPU / 전체 CPU
     *
     * <p>기준은 실측이 아니라 <b>예약분까지 반영한 실효 전력</b>이다. 한 실행에서 여러
     * 워크로드를 배치할 때 실측만 보면 상승분이 누적되지 않아, 몇 개를 얹든 "하나 얹은 값"이
     * 나오고 용량 초과를 걸러내지 못한다.</p>
     *
     * <p>지금 평가 중인 워크로드는 <b>아직 예약되지 않은 상태</b>다 — 예약은 배치가 확정된
     * 뒤에 잡는다. 그래서 실효 전력에 이 워크로드가 이중으로 들어가지 않는다.</p>
     */
    public double predictedPower(NodeResource node, int requiredCpu) {
        return effectivePowerWatts(node) + marginalPower(node, requiredCpu);
    }

    // ── 배치 점수용 지표 ────────────────────────────────────────

    /**
     * 비낭비 점수 (0~1) — 이상 전력 곡선을 <b>넘어선 만큼만</b> 감점한다.
     *
     * <p>{@link #proportionality} 와 다르다. 그쪽은 이상 곡선과의 거리를 양방향 대칭으로 재는
     * <b>진단</b> 지표라, 곡선보다 전력을 적게 쓰는 효율적인 노드도 함께 감점한다. 용량 500W ·
     * CPU 60%(이상 360W) 기준으로 실측 200W 는 0.68, 500W 는 0.72 가 나와 <b>가장 효율적인
     * 노드가 가장 낭비하는 노드보다 낮게</b> 평가된다.</p>
     *
     * <p>진단으로는 그게 맞다 — 모델에서 벗어난 정도를 보는 것이니까. 하지만 <b>배치 점수로는
     * 틀렸다.</b> 전력을 덜 쓰는 노드를 벌할 이유가 없다. 그래서 배치에는 초과분만 보는 이
     * 단방향 지표를 쓰고, {@code proportionality} 는 전력 효율 평가 API 전용으로 남겨 둔다.</p>
     */
    public double wasteScore(NodeResource node) {
        double pMax = node.getPowerCapacityWatts();
        if (pMax <= 0 || node.getPowerWatts() <= 0) return 0.5;

        double pIdle = idlePower(node);
        double pIdeal = pIdle + (pMax - pIdle) * (node.getCpuUsagePercent() / 100.0);
        double overdraw = Math.max(0, node.getPowerWatts() - pIdeal);

        return 1.0 - Math.min(1.0, overdraw / pMax);
    }

    /**
     * <b>배치 점수용</b> 비낭비 — 실효 사용률·실효 전력으로 {@link #wasteScore} 와 같은 식을 낸다.
     *
     * <p>적격 노드에서는 <b>실수 항등</b>이다: {@code (P + D·r) − (P_idle + D·(u/100 + r)) = P − P_ideal} 이라 예약분이 분자에서
     * 상쇄된다. 갈리는 것은 {@code u/100 + r} 이 1 을 넘어 실효 사용률이 100 에서 잘리는 노드뿐인데, 그런 노드는 제약 C1
     * (가용 코어 = 총코어 × (1 − u/100) − 예약 ≥ 요구)에서 이미 부적격이다 — 부적격 후보도 점수는 계산·표시되므로 그 표시값은 갈린다.
     * 그래도 같은 기저로 두는 이유는 점수 한 식 안에서 항마다 입력이 갈리지 않게 하기 위함이고, binary64 마지막 비트는 실수 항등과 다를 수 있다.</p>
     */
    public double placementWasteScore(NodeResource node) {
        double pMax = node.getPowerCapacityWatts();
        double powerEff = effectivePowerWatts(node);
        if (pMax <= 0 || powerEff <= 0) return 0.5;

        double pIdle = idlePower(node);
        double pIdeal = pIdle + (pMax - pIdle) * (node.effectiveCpuUsagePercent() / 100.0);
        double overdraw = Math.max(0, powerEff - pIdeal);

        return 1.0 - Math.min(1.0, overdraw / pMax);
    }

    /**
     * 노드가 가동 중인지. 이 아래면 사실상 빈 노드로 보고 깨우는 비용을 따로 센다.
     *
     * <p><b>예약분을 반영한 사용률로 판단한다.</b> 실측만 보면 한 번의 실행에서 같은 빈 노드에
     * 워크로드를 잇달아 배치할 때 <b>유휴 전력이 매번 새로 드는 것처럼</b> 계산된다. 첫 배치로
     * 이미 깨운 노드인데도 두 번째, 세 번째 배치가 또 깨우는 비용을 무는 셈이다.</p>
     */
    public boolean isActive(NodeResource node) {
        return node.effectiveCpuUsagePercent() > props.getActiveCpuPercent();
    }

    /**
     * 배치로 실제로 늘어나는 전력(W) — <b>동적 증가분 + 빈 노드를 깨우는 비용</b>.
     *
     * <p>클러스터 총 전력은 "가동 중인 노드의 유휴 전력 + 동적 전력"의 합이다. 이미 도는
     * 노드에 얹으면 동적 증가분만 들지만, 비어 있는 노드에 처음 얹으면 그 노드의 <b>유휴
     * 전력이 통째로 새로 든다.</b> 그 값은 {@link #wakeupPower} — 계측된 유휴 노드는 실측에서 역산한
     * 값이고, 미계측이면 대체값(정격 × 0.3)이다. 500W 용량 노드에 2코어(전체 8코어)를 얹을 때 가동 중이면
     * 87.5W, 비어 있고 미계측이면 87.5 + 150 = 237.5W(<b>2.7배</b>), 비어 있고 108W 로 계측되면
     * 역산 유휴 100 이 절편과 깨우기항에 함께 들어 (500 − 100) × 0.25 + 100 = 200W 다.</p>
     *
     * <p>이 한 줄이 전력 통합을 "CPU 가 10% 넘으면 보너스" 같은 임의 규칙이 아니라 모델의 전력
     * 단위(W)로 계산되는 항으로 만든다. 별도의 통합 항이 필요 없어진다. 동적항은 모델식, 깨우기항은 계측
     * 노드면 실측 역산·미계측이면 대체값이며, 총량이 실측 서버의 전력과 같다는 주장은 아니다.</p>
     *
     * <p><b>한 식 안의 유휴 기준선은 하나다.</b> 동적 범위 {@code (P_max − 기준선)} 의 기준선과 깨우기항이 같은 값
     * {@link #wakeupPower} 를 쓴다 — 계측된 유휴 노드는 실측 역산, 그 밖(가동·미계측·정격 미상·역산 범위 밖·역산 끔)은
     * 대체값이라 {@link #idlePower} 와 비트까지 같다. 정정 전에는 동적항이 선언 유휴({@code idlePower}), 깨우기항만
     * 역산이라 계측된 유휴 노드의 유휴가 한 식의 두 자리에서 다른 값으로 쓰였다(판정 회차 3 지목). 진단 지표(비례성·비낭비)와
     * 공개 {@link #marginalPowerOnCurve}(진단·테스트 표면)와 선형 {@link #marginalPower}(제약·예상 전력·대조군)의 절편은 선언 유휴 그대로다 —
     * 지목 밖이며, 그래서 계측 유휴 노드에서는 한계 전력 점수의 절편과 제약·예상 전력의 절편이 갈린다.</p>
     */
    public double realMarginalPower(NodeResource node, int requiredCpu) {
        double base = wakeupPower(node);   // 가동·미계측 노드는 이 안에서 선언 유휴로 떨어진다 — 삼항이 필요 없다
        double dynamic = marginalPowerOnCurve(node, requiredCpu, base);
        return isActive(node) ? dynamic : dynamic + base;
    }

    /**
     * 실질 한계 전력 점수 (0~1). 배치로 전력이 적게 느는 노드일수록 높다.
     * <b>W 눈금</b>으로 정규화한다 — 깨우기 벌점이 유휴비가 아니라 절대 W 로 총점에 들어간다.
     *
     * <p><b>고친 결함 — 분모가 상대화라 깨우기 벌점이 유휴비로 고정되던 것.</b> 빈 노드의 벌점은
     * {@code wakeupPower / pMax} 인데, 깨우기 비용이 대체값(정격 × 0.3)일 때 이 값은 전 노드 0.3 으로 같아
     * <b>일률적 통합 편향</b>으로 작동했다(아래 눈금 전환 이전의 이야기다). 실측 역산으로 바뀐 뒤에는 선언 유휴비가 낮은 노드(0.06~0.25)의 벌점이
     * 그만큼 줄어 엔진이 빈 노드를 더 자주 깨운다 — 비교 판에서 PABFD 대비 총전력 격차가 <b>벌어진</b> 주원인이다.
     * 또 역산 유휴가 큰 노드는 {@code realMarginalPower > pMax} 가 되어 점수가 0 으로 포화해 노드를 구별하지
     * 못한다. 둘 다 같은 분모의 두 구간이며, 절대 W 목적화가 이 결함의 수정이며, 생성식이 볼록한 비교판(convex·convex-misrated)에서 검정한다.</p>
     *
     * <p><b>지금은 W 눈금으로 정규화한다.</b> 분모는 노드 정격이 아니라 설정값 S
     * ({@code aims.scheduler.power.marginal-scale-watts}, 기본 500 W)다. 같은 절대 W 의 깨우기 비용은
     * 정격이 얼마든 같은 벌점을 받고, 정격이 큰 노드의 큰 깨우기 비용은 그만큼 크게 보인다.
     * {@code S ≤ 0} 이면 정격 분모(구 방식)로 돌아간다 — 되돌리기는 이 값 하나다.</p>
     *
     * <p>남은 한계: {@code realMarginalPower > S} 이면 점수가 0 으로 포화해 노드를 구별하지 못한다.
     * 비교 판에서는 실측 최댓값이 {@code BenchmarkRules.MAX_REAL_MARGINAL_WATTS} 라 기본값에서
     * 발현하지 않지만, 정격이 큰 미계측 노드의 대체값은 S 를 넘을 수 있다 — 그때는 노브로 올린다.</p>
     */
    public double marginalScore(NodeResource node, int requiredCpu) {
        double pMax = node.getPowerCapacityWatts();
        if (pMax <= 0) return 0.5;
        double scale = props.getMarginalScaleWatts() > 0 ? props.getMarginalScaleWatts() : pMax;
        return 1.0 - Math.min(1.0, realMarginalPower(node, requiredCpu) / scale);
    }

    /**
     * 전력 곡선 위에서 계산한 동적 증가분(W).
     * ΔP = (P_max − P_idle) × (u₂^α − u^α),  u₂ = min(1, u + 요구 CPU / 전체 CPU)
     *
     * <p>α=1 이면 {@link #marginalPower} 와 같다(사용률 100% 를 넘지 않도록 자르는 것만 다르다).
     * α&gt;1 로 올리면 이미 부하가 높은 노드일수록 같은 워크로드의 증가분이 커진다 — 모델이 전력
     * 곡선을 볼록으로 <b>가정</b>한 결과이며, 실측 서버가 그렇다는 단정은 아니다(판정 규칙의 한계 문구가
     * 밝히듯 문헌에는 반대 방향의 보고도 있다). 기존 {@code marginalPower} 는 현재 부하를 아예 보지 않아
     * 같은 사양이면 모든 노드가 같은 값이었다.</p>
     *
     * <p><b>곡선 위의 평가점 u 는 실효 사용률(예약분 반영)이다.</b> {@link #realMarginalPower} 한 식 안에서 "깨우기를 물릴지"
     * ({@link #isActive})는 실효로 정하면서 "얼마나 늘지" 만 실측으로 재면, 한 실행에서 같은 노드에 워크로드를 잇달아 얹을 때
     * 곡선 위 평가점이 첫 배치 자리에 머문다 — {@link #effectivePowerWatts}·{@link #effectiveHeadroomPercent} 가 이미 고친 것과 같은
     * 부류의 결함이다. 깨우기항 {@link #wakeupPower} 의 역산은 여전히 실측 u 를 쓴다: 그쪽은 "같은 순간에 관측한 (P, u)" 가
     * 전제라 실효를 넣으면 식이 성립하지 않는다(다른 근거, 같은 노드).</p>
     *
     * <p><b>기본 α=1 에서는 값이 바뀌지 않는다.</b> 두 기저가 갈리는 것은 {@code u + ΔU} 가 1 에서 잘리는 노드뿐이고,
     * 제약 C1(가용 코어 = 총코어 × (1 − 실측) − 예약 ≥ 요구 코어)이 적격 노드에 {@code u_실효 + ΔU ≤ 1} 을 보장하므로 그런 노드는
     * 전부 부적격이다. 배치는 적격 노드에서만 일어나므로 배치 결정·비교 판의 값은 그대로이고, 부적격 후보의 표시값(점수·정렬 순서)만
     * 예약이 쌓인 실행에서 달라질 수 있다(예약으로 실효가 100% 에 닿은 부적격 노드는 동적항이 0 이 되어 전력 점수가 최고로 표시된다 — 선택 대상은
     * 아니다). 항등은 실수에서 성립하고 binary64 에서는 마지막 비트까지 성립하지 않는다 — {@code (u+ΔU)−u} 가 평가점 u 에 따라 1 ULP 달라지므로,
     * 지점값을 소수 4자리로 먼저 반올림하는 집계에서 4자리 동점에 앉은 칸(median 은 반올림된 두 지점값의 평균)은 표시값이 0.0001 뒤집힌다(잡음이 아니라
     * 재현되는 동점 처리다). 그 마지막 비트는 통합 권고의 반납 경계와 전력 인지 선택의 동점 처리도 지나므로 "선택 불변" 은 무조건문이 아니라
     * 관측 범위의 진술이다 — 회차별 관측은 배치 비교 계약 §10 의 행에 적는다. α&gt;1 이면 예약이 있는 적격 노드에서도 값이 갈린다 — 그것이 이 정정의 관측 가능한 효과다.</p>
     */
    public double marginalPowerOnCurve(NodeResource node, int requiredCpu) {
        return marginalPowerOnCurve(node, requiredCpu, idlePower(node));
    }

    /** 같은 곡선을 주어진 유휴 기준선으로 — {@link #realMarginalPower} 가 깨우기항과 같은 기준선을 넣는다. */
    private double marginalPowerOnCurve(NodeResource node, int requiredCpu, double idleBase) {
        double pMax = node.getPowerCapacityWatts();
        if (pMax <= 0) return 0;

        double alpha = props.getCurveAlpha();
        double u = Math.min(1.0, Math.max(0, node.effectiveCpuUsagePercent() / 100.0));   // 실효 — isActive 와 같은 기저
        double u2 = Math.min(1.0, u + (double) requiredCpu / Math.max(node.getTotalCpu(), 1));

        return (pMax - idleBase) * (Math.pow(u2, alpha) - Math.pow(u, alpha));
    }

    /** 배치로 늘어나는 전력(W) — 한계 전력 ΔP. 작을수록 전력 효율적인 배치다. */
    public double marginalPower(NodeResource node, int requiredCpu) {
        double pMax = node.getPowerCapacityWatts();
        if (pMax <= 0) return 0;

        double deltaUtilization = (double) requiredCpu / Math.max(node.getTotalCpu(), 1);
        return (pMax - idlePower(node)) * deltaUtilization;
    }

    /**
     * 노드의 전력 용량(W).
     * IPMI 로는 정격 전력을 알 수 없어 설정값을 쓰되, 노드별 override 를 우선한다.
     */
    public double capacityOf(String nodeId) {
        Double override = props.getCapacityByNode().get(nodeId);
        return override != null ? override : props.getDefaultCapacityWatts();
    }
}
