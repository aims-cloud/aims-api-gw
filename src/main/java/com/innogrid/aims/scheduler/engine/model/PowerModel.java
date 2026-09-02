package com.innogrid.aims.scheduler.engine.model;

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
        return Math.min(1.0, efficiency / props.getPeiNormalizer());
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
     * 전력이 통째로 새로 든다.</b> 500W 용량 · 유휴 비율 0.3 인 노드에 2코어(전체 8코어)를
     * 얹을 때 가동 중이면 87.5W, 비어 있으면 237.5W 로 <b>2.7배</b> 차이가 난다.</p>
     *
     * <p>이 한 줄이 전력 통합을 "CPU 가 10% 넘으면 보너스" 같은 임의 규칙이 아니라 물리량으로
     * 만든다. 별도의 통합 항이 필요 없어진다.</p>
     */
    public double realMarginalPower(NodeResource node, int requiredCpu) {
        double dynamic = marginalPowerOnCurve(node, requiredCpu);
        return isActive(node) ? dynamic : dynamic + idlePower(node);
    }

    /**
     * 실질 한계 전력 점수 (0~1). 배치로 전력이 적게 느는 노드일수록 높다.
     * 용량 대비로 정규화한다 — 깨우는 비용까지 더하면 동적 범위를 넘을 수 있기 때문이다.
     */
    public double marginalScore(NodeResource node, int requiredCpu) {
        double pMax = node.getPowerCapacityWatts();
        if (pMax <= 0) return 0.5;
        return 1.0 - Math.min(1.0, realMarginalPower(node, requiredCpu) / pMax);
    }

    /**
     * 전력 곡선 위에서 계산한 동적 증가분(W).
     * ΔP = (P_max − P_idle) × (u₂^α − u^α),  u₂ = min(1, u + 요구 CPU / 전체 CPU)
     *
     * <p>α=1 이면 {@link #marginalPower} 와 같다(사용률 100% 를 넘지 않도록 자르는 것만 다르다).
     * α&gt;1 로 올리면 이미 부하가 높은 노드일수록 같은 워크로드의 증가분이 커진다 — 실제 서버의
     * 전력 곡선이 볼록하기 때문이다. 기존 {@code marginalPower} 는 현재 부하를 아예 보지 않아
     * 같은 사양이면 모든 노드가 같은 값이었다.</p>
     */
    public double marginalPowerOnCurve(NodeResource node, int requiredCpu) {
        double pMax = node.getPowerCapacityWatts();
        if (pMax <= 0) return 0;

        double alpha = props.getCurveAlpha();
        double u = Math.min(1.0, Math.max(0, node.getCpuUsagePercent() / 100.0));
        double u2 = Math.min(1.0, u + (double) requiredCpu / Math.max(node.getTotalCpu(), 1));

        return (pMax - idlePower(node)) * (Math.pow(u2, alpha) - Math.pow(u, alpha));
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
