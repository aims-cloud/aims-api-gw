package com.innogrid.aims.scheduler.engine.constraint;

import org.springframework.stereotype.Component;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.NodeResource.ThermalState;
import com.innogrid.aims.scheduler.model.PlacementContext;

/**
 * 제약 조건 평가 — 모든 스케줄링 엔진이 공유하는 하드 제약 파이프라인.
 *
 * <p>엔진마다 검사 항목이 달랐던 것을 한 곳으로 모아, 어떤 엔진을 쓰든 같은 정책이
 * 같은 방식으로 적용되게 한다. 엔진은 이 평가를 통과한 노드만 점수화한다.</p>
 *
 * <pre>
 * C1 자원 가용성 (예약분 반영)
 * C2 사용률 임계 (CPU / 메모리)
 * C3 열 상태     (쿨다운, 현재 온도, 워크로드 제약, 배치 후 예상 온도)
 * C4 전력        (여유율, 워크로드 예산, 배치 후 예상 전력)
 * </pre>
 */
@Component
public class ConstraintEvaluator {

    private final PowerModel powerModel;
    private final ThermalModel thermalModel;

    public ConstraintEvaluator(PowerModel powerModel, ThermalModel thermalModel) {
        this.powerModel = powerModel;
        this.thermalModel = thermalModel;
    }

    /**
     * 기본 설정으로 만든 평가기.
     * 스프링 컨텍스트 없이 호출하는 경로(단발 호출, 단위 테스트)에서 쓴다.
     * 냉각 대기 상태를 공유하지 않도록 호출할 때마다 새로 만든다.
     */
    public static ConstraintEvaluator withDefaults() {
        SchedulerProperties defaults = new SchedulerProperties();
        return new ConstraintEvaluator(new PowerModel(defaults), new ThermalModel(defaults));
    }

    /**
     * 노드가 워크로드를 받을 수 없는 이유를 돌려준다.
     *
     * @return 배치 가능하면 {@code null}, 아니면 제외 사유
     */
    public String exclusionReason(Workload workload, NodeResource node, PlacementContext ctx) {
        SchedulingPolicy policy = ctx.policy();

        String reason = checkResources(workload, node);
        if (reason != null) return reason;

        reason = checkUsageThreshold(node, policy);
        if (reason != null) return reason;

        reason = checkThermal(workload, node, policy);
        if (reason != null) return reason;

        return checkPower(workload, node, policy);
    }

    /** 배치 가능 여부. */
    public boolean isEligible(Workload workload, NodeResource node, PlacementContext ctx) {
        return exclusionReason(workload, node, ctx) == null;
    }

    // ── C1. 자원 가용성 ─────────────────────────────────────────

    private String checkResources(Workload workload, NodeResource node) {
        if (node.canAccommodate(workload.getRequiredCpu(), workload.getRequiredMemoryMb(), workload.getRequiredGpu())) {
            return null;
        }
        return String.format("자원 부족 (필요 CPU:%d MEM:%dMB GPU:%d, 가용 CPU:%.1f MEM:%dMB GPU:%d)",
                workload.getRequiredCpu(), workload.getRequiredMemoryMb(), workload.getRequiredGpu(),
                node.availableCpu(), node.availableMemoryMb(), node.availableGpu());
    }

    // ── C2. 사용률 임계 ─────────────────────────────────────────

    private String checkUsageThreshold(NodeResource node, SchedulingPolicy policy) {
        if (node.getCpuUsagePercent() > policy.getMaxCpuUsagePercent()) {
            return String.format("CPU 사용률 초과 (%.1f%% > %.1f%%)",
                    node.getCpuUsagePercent(), policy.getMaxCpuUsagePercent());
        }
        if (node.getMemoryUsagePercent() > policy.getMaxMemoryUsagePercent()) {
            return String.format("메모리 사용률 초과 (%.1f%% > %.1f%%)",
                    node.getMemoryUsagePercent(), policy.getMaxMemoryUsagePercent());
        }
        return null;
    }

    // ── C3. 열 상태 ────────────────────────────────────────────

    private String checkThermal(Workload workload, NodeResource node, SchedulingPolicy policy) {
        double maxTemp = policy.getMaxTemperatureCelsius();

        if (policy.isTemperatureAwareEnabled()) {
            if (thermalModel.isCoolingDown(node.getNodeId())) {
                return String.format("냉각 대기 중 (%d분 남음)",
                        thermalModel.remainingCooldownMinutes(node.getNodeId()));
            }
            if (node.getTemperatureCelsius() > maxTemp) {
                thermalModel.markCritical(node.getNodeId());
                return String.format("온도 초과 (%.1f°C > %.1f°C)",
                        node.getTemperatureCelsius(), maxTemp);
            }
        }

        // 워크로드가 지정한 온도 제약은 정책 설정과 무관하게 적용한다.
        if (workload.getMaxTemperatureCelsius() != null
                && node.getTemperatureCelsius() > workload.getMaxTemperatureCelsius()) {
            return String.format("워크로드 온도 제약 초과 (%.1f°C > %.1f°C)",
                    node.getTemperatureCelsius(), workload.getMaxTemperatureCelsius());
        }

        if (policy.isTemperatureAwareEnabled()) {
            double predicted = thermalModel.predictedTemperature(node, workload.getRequiredCpu());
            if (predicted > maxTemp) {
                return String.format("배치 후 예상 온도 %.1f°C > 임계치 %.1f°C", predicted, maxTemp);
            }
        }
        return null;
    }

    // ── C4. 전력 ───────────────────────────────────────────────

    private String checkPower(Workload workload, NodeResource node, SchedulingPolicy policy) {
        if (policy.isPowerAwareEnabled()) {
            double requiredHeadroom = 100 - policy.getMaxPowerUsagePercent();
            if (node.powerHeadroomPercent() < requiredHeadroom) {
                return String.format("전력 여유 부족 (여유 %.1f%%)", node.powerHeadroomPercent());
            }
        }

        // 워크로드가 지정한 전력 예산은 정책 설정과 무관하게 적용한다.
        if (workload.getMaxPowerBudgetWatts() != null
                && node.getPowerWatts() > workload.getMaxPowerBudgetWatts()) {
            return String.format("워크로드 전력 제약 초과 (%.1fW > %.1fW)",
                    node.getPowerWatts(), workload.getMaxPowerBudgetWatts());
        }

        if (policy.isPowerAwareEnabled() && node.getPowerCapacityWatts() > 0) {
            double predicted = powerModel.predictedPower(node, workload.getRequiredCpu());
            if (predicted > node.getPowerCapacityWatts()) {
                return String.format("배치 후 예상 전력 %.0fW > 용량 %.0fW",
                        predicted, node.getPowerCapacityWatts());
            }
        }
        return null;
    }

    // ── 조회용 부가 정보 ────────────────────────────────────────

    /**
     * 정책 임계치를 적용한 노드의 온도 상태.
     * 경고 온도는 아직 정책에 필드가 없어 설정 기본값을 쓴다.
     */
    public ThermalState thermalState(NodeResource node, SchedulingPolicy policy) {
        return thermalModel.state(node.getTemperatureCelsius(),
                thermalModel.defaultWarningCelsius(),
                policy.getMaxTemperatureCelsius());
    }
}
