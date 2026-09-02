package com.innogrid.aims.scheduler.engine.constraint;

import org.springframework.stereotype.Component;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.LinkMatrix;
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
 * C1 자원 가용성 (예약분 반영 — CPU·메모리·GPU, 디스크는 총량을 아는 노드에서만)
 * C2 사용률 임계 (CPU / 메모리)
 * C3 열 상태     (쿨다운, 현재 온도, 워크로드 제약, 배치 후 예상 온도)
 * C4 전력        (여유율, 워크로드 예산, 배치 후 예상 전력)
 * C5 MDC 링크    (데이터 MDC ↔ 노드 MDC 링크가 DOWN 이면 제외 — 정책 affinityEnabled + 워크로드 dataMdcId 일 때만)
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

        reason = checkPower(workload, node, policy, ctx.powerBudgetWatts());
        if (reason != null) return reason;

        return checkLink(workload, node, ctx);
    }

    // ── C5. MDC 링크 ───────────────────────────────────────────

    /**
     * 데이터가 있는 MDC 와 노드가 있는 MDC 사이 링크가 <b>끊겼으면</b> 제외한다.
     *
     * <p>게이트({@link PlacementContext#linkAware})가 꺼져 있으면 아무것도 하지 않는다 — 기존 정책은 전부
     * 꺼져 있어 배치 결과가 바뀌지 않는다. 같은 MDC·미등록 쌍·라벨 없는 노드는 통과시킨다(모르는 값으로
     * 배치를 막지 않는다). UP·DEGRADED 는 제약이 아니라 점수(weighted)에서 다룬다.</p>
     */
    private String checkLink(Workload workload, NodeResource node, PlacementContext ctx) {
        if (!ctx.linkAware(workload)) return null;
        String dataMdc = workload.getDataMdcId();
        String nodeMdc = node.getMdcId();
        if (nodeMdc == null || nodeMdc.equals(dataMdc)) return null;

        return ctx.linkMatrix().between(dataMdc, nodeMdc)
                .filter(LinkMatrix.Quality::isDown)
                .map(q -> String.format("MDC 링크 단절 (%s ↔ %s DOWN)", dataMdc, nodeMdc))
                .orElse(null);
    }

    /** 배치 가능 여부. */
    public boolean isEligible(Workload workload, NodeResource node, PlacementContext ctx) {
        return exclusionReason(workload, node, ctx) == null;
    }

    // ── C1. 자원 가용성 ─────────────────────────────────────────

    private String checkResources(Workload workload, NodeResource node) {
        if (!node.canAccommodate(workload.getRequiredCpu(), workload.getRequiredMemoryMb(), workload.getRequiredGpu())) {
            return String.format("자원 부족 (필요 CPU:%d MEM:%dMB GPU:%d, 가용 CPU:%.1f MEM:%dMB GPU:%d)",
                    workload.getRequiredCpu(), workload.getRequiredMemoryMb(), workload.getRequiredGpu(),
                    node.availableCpu(), node.availableMemoryMb(), node.availableGpu());
        }
        // 디스크는 총량을 아는 노드에서만 본다. 총량 0 은 "모름"이고, 모르는 값으로 배치를 막지 않는다 —
        // 없는 값에 숫자를 채우던 결함(단계 9)의 반대 방향 실수를 피한다. 요구량 0 이면 검사할 것이 없다.
        if (workload.getRequiredDiskGb() > 0 && node.diskCapacityGb() > 0
                && node.availableDiskGb() < workload.getRequiredDiskGb()) {
            return String.format("디스크 부족 (필요 %dGB, 가용 %dGB / 총량 %dGB)",
                    workload.getRequiredDiskGb(), node.availableDiskGb(), node.diskCapacityGb());
        }
        return null;
    }

    // ── C2. 사용률 임계 ─────────────────────────────────────────

    private String checkUsageThreshold(NodeResource node, SchedulingPolicy policy) {
        double cpu = node.effectiveCpuUsagePercent();
        if (cpu > policy.getMaxCpuUsagePercent()) {
            return String.format("CPU 사용률 초과 (%.1f%% > %.1f%%)",
                    cpu, policy.getMaxCpuUsagePercent());
        }
        double memory = node.effectiveMemoryUsagePercent();
        if (memory > policy.getMaxMemoryUsagePercent()) {
            return String.format("메모리 사용률 초과 (%.1f%% > %.1f%%)",
                    memory, policy.getMaxMemoryUsagePercent());
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
            // 냉각 대기는 실제로 뜨거웠던 노드에만 건다. 여기서 실효 온도(예약분 포함)를 쓰면
            // 배치 예정 부하만으로 10분간 배제되는 노드가 생긴다. 예약까지 반영한 판단은
            // 아래 "배치 후 예상 온도"가 맡으며, 그쪽은 이번 배치만 거르고 기록을 남기지 않는다.
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

    private String checkPower(Workload workload, NodeResource node, SchedulingPolicy policy,
                             Double runPowerBudgetWatts) {
        if (policy.isPowerAwareEnabled()) {
            double requiredHeadroom = 100 - policy.getMaxPowerUsagePercent();
            // 예약분을 반영한 여유율을 본다. 실측만 보면 배치를 아무리 쌓아도 여유가 그대로라
            // 전력 상한이 적용되지 않는다. 특히 요구 CPU 가 0 인 워크로드는 아래 예상 전력
            // 검사도 그냥 통과하므로, 여기서 걸러야 상한이 실제로 지켜진다.
            double headroom = powerModel.effectiveHeadroomPercent(node);
            if (headroom < requiredHeadroom) {
                return String.format("전력 여유 부족 (여유 %.1f%%)", headroom);
            }
        }

        // 워크로드가 지정한 전력 예산은 정책 설정과 무관하게 적용한다.
        if (workload.getMaxPowerBudgetWatts() != null
                && node.getPowerWatts() > workload.getMaxPowerBudgetWatts()) {
            return String.format("워크로드 전력 제약 초과 (%.1fW > %.1fW)",
                    node.getPowerWatts(), workload.getMaxPowerBudgetWatts());
        }

        // 스케줄이 실행 단위로 지정한 예산. 워크로드 엔티티를 바꾸지 않고 적용한다.
        if (runPowerBudgetWatts != null && node.getPowerWatts() > runPowerBudgetWatts) {
            return String.format("스케줄 전력 예산 초과 (%.1fW > %.1fW)",
                    node.getPowerWatts(), runPowerBudgetWatts);
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
     * 경고 온도는 정책 값을 우선하고, 없으면 설정 기본값을 쓴다.
     */
    public ThermalState thermalState(NodeResource node, SchedulingPolicy policy) {
        return thermalModel.state(node.getTemperatureCelsius(),
                warningTemperatureOf(policy),
                policy.getMaxTemperatureCelsius());
    }

    /** 정책의 경고 온도. 지정되지 않았으면 설정 기본값. */
    public double warningTemperatureOf(SchedulingPolicy policy) {
        Double configured = policy.getWarningTemperatureCelsius();
        return configured != null ? configured : thermalModel.defaultWarningCelsius();
    }

    /** 임계 온도를 넘겨 냉각 대기 중인 노드인지. */
    public boolean isCoolingDown(String nodeId) {
        return thermalModel.isCoolingDown(nodeId);
    }

    /** 냉각 대기가 풀릴 때까지 남은 시간(분). 대기 중이 아니면 0. */
    public long remainingCooldownMinutes(String nodeId) {
        return thermalModel.remainingCooldownMinutes(nodeId);
    }
}
