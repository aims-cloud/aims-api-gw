package com.innogrid.aims.scheduler.engine.constraint;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import org.slf4j.Logger;
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
import com.innogrid.aims.scheduler.model.SchedulingResult;

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
 * C6 MDC 전력 예산 (그 MDC 의 전력 합계가 예산을 넘으면 제외 — 예산 원장에 등록된 MDC 일 때만)
 * </pre>
 *
 * <p>C6 을 <b>맨 뒤에</b> 둔 이유: C1~C5 는 노드 한 대만 보지만 C6 은 같은 MDC 의 형제 노드를 전부 훑는
 * <b>유일한 교차 노드 검사</b>다. 순서는 동작이 아니라 "먼저 걸린 사유"만 정하므로, 싼 검사부터라는
 * 기존 순서를 지키는 편이 낫다.</p>
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
        return Exclusion.messageOf(exclusion(workload, node, ctx));
    }

    /**
     * 노드가 워크로드를 받을 수 없는 까닭과 문장을 함께 돌려준다.
     *
     * <p>{@link #exclusionReason} 이 돌려주던 문장에 기계가 읽는 까닭을 얹은 것이다. 검사 순서와 문장은
     * 그대로다 — 먼저 걸린 제약 하나만 돌려주고, 그 문장은 예전과 한 글자도 다르지 않다.</p>
     *
     * @return 배치 가능하면 {@code null}, 아니면 제외 한 건
     */
    public Exclusion exclusion(Workload workload, NodeResource node, PlacementContext ctx) {
        SchedulingPolicy policy = ctx.policy();

        Exclusion e = checkResources(workload, node);
        if (e != null) return e;

        e = checkUsageThreshold(node, policy);
        if (e != null) return e;

        e = checkThermal(workload, node, policy);
        if (e != null) return e;

        e = checkPower(workload, node, policy, ctx.powerBudgetWatts());
        if (e != null) return e;

        e = checkLink(workload, node, ctx);
        if (e != null) return e;

        return checkMdcBudget(workload, node, ctx);
    }

    /** 까닭·문장 한 쌍을 만든다 — 검사 메서드가 쓰는 짧은 이름. */
    private static Exclusion of(ExclusionReason reason, String format, Object... args) {
        return new Exclusion(reason, String.format(format, args));
    }

    // ── C6. MDC 전력 예산 ──────────────────────────────────────

    /**
     * 이 노드가 속한 <b>MDC 전체의 전력 합계</b>가 예산을 넘으면 제외한다.
     *
     * <p>C4 는 노드 한 대의 정격만 본다. 그런데 MDC 는 컨테이너·랙 단위 수전 용량이 곧 상한이라,
     * 8대가 각자 정격 아래여도 합계가 계약 용량을 넘으면 실제로는 올릴 수 없다.</p>
     *
     * <p><b>합계는 실측이 아니라 예약분을 반영한 실효 전력으로 낸다.</b> 실측 전력은 이미 실행 중인 부하만
     * 담아서, 한 실행에서 워크로드를 몇 개 얹든 합계가 그대로다 — 그러면 예산이 누적되지 않아 상한이
     * 사실상 적용되지 않는다({@code WeightedScoreScheduler} 가 고친 실효 점수 결함과 같은 부류). 후보 자신은 아직 예약 전이므로
     * "배치 후 예상 전력"을 쓴다. 두 값 모두 {@link PowerModel} 에 이미 있는 것으로 새 수식은 없다.</p>
     *
     * <p>건너뛰는 경우 셋: 예산 원장이 비었을 때, 이 MDC 에 예산이 등록되지 않았을 때, <b>후보 노드의
     * 전력을 수집하지 못했을 때</b>. 마지막은 "전력을 모르는 노드는 전력으로 막지 않는다"는 기존 원칙이다.
     * 형제 노드 중 미계측은 합계에서 빼고 <b>사유에 대수를 적어</b> 합계가 과소 평가임을 드러낸다.</p>
     */
    private Exclusion checkMdcBudget(Workload workload, NodeResource node, PlacementContext ctx) {
        if (ctx.mdcBudgets().isEmpty() || node.getPowerWatts() <= 0) return null;

        String mdcId = node.getMdcId();
        OptionalDouble budget = ctx.mdcBudgets().budgetOf(mdcId);
        if (budget.isEmpty()) return null;

        double sum = powerModel.predictedPower(node, workload.getRequiredCpu());
        int measured = 1;
        int unmeasured = 0;
        for (NodeResource sibling : ctx.nodes()) {
            if (sibling.getNodeId().equals(node.getNodeId()) || !mdcId.equals(sibling.getMdcId())) continue;
            if (sibling.getPowerWatts() <= 0) {
                unmeasured++;
            } else {
                sum += powerModel.effectivePowerWatts(sibling);
                measured++;
            }
        }

        double limit = budget.getAsDouble();
        if (sum <= limit) return null;
        return of(ExclusionReason.MDC_POWER_BUDGET_EXCEEDED, "MDC %s 전력 합계 예상 %,.0fW > 예산 %,.0fW (계측 %d대%s)",
                mdcId, sum, limit, measured,
                unmeasured > 0 ? String.format(", 미계측 %d대 제외", unmeasured) : "");
    }

    // ── C5. MDC 링크 ───────────────────────────────────────────

    /**
     * 데이터가 있는 MDC 와 노드가 있는 MDC 사이 링크가 <b>끊겼으면</b> 제외한다.
     *
     * <p>게이트({@link PlacementContext#linkAware})가 꺼져 있으면 아무것도 하지 않는다 — 기존 정책은 전부
     * 꺼져 있어 배치 결과가 바뀌지 않는다. 같은 MDC·미등록 쌍·라벨 없는 노드는 통과시킨다(모르는 값으로
     * 배치를 막지 않는다). UP·DEGRADED 는 제약이 아니라 점수(weighted)에서 다룬다.</p>
     */
    private Exclusion checkLink(Workload workload, NodeResource node, PlacementContext ctx) {
        if (!ctx.linkAware(workload)) return null;
        String dataMdc = workload.getDataMdcId();
        String nodeMdc = node.getMdcId();
        if (nodeMdc == null || nodeMdc.equals(dataMdc)) return null;

        return ctx.linkMatrix().between(dataMdc, nodeMdc)
                .filter(LinkMatrix.Quality::isDown)
                .map(q -> of(ExclusionReason.MDC_LINK_DOWN, "MDC 링크 단절 (%s ↔ %s DOWN)", dataMdc, nodeMdc))
                .orElse(null);
    }

    // ── C1 디스크 검사의 표기 — 판정을 바꾸지 않고 "검사가 적용됐는가" 만 밖으로 낸다 ────────────────

    /** 후보 상세 키. 워크로드가 디스크를 요구할 때만 붙는다 — 요구가 없는 배치의 키 집합은 그대로다. */
    public static final String DISK_CHECK_DETAIL = "디스크검사";

    /** 디스크 요구가 있어 표기가 뜻을 갖는 워크로드인가. */
    public static boolean diskCheckRelevant(Workload workload) {
        return workload.getRequiredDiskGb() > 0;
    }

    /**
     * 이 노드에 C1 디스크 검사가 <b>적용되는가</b> — 총량을 알아야 한다. {@link #checkResources} 와 같은 식이다.
     * "검사했다" 가 아니라 "검사 대상이다" 다: 자원 검사는 단락 평가라 CPU 부족 노드는 디스크 줄에 닿지 않고,
     * 다른 제약에서 걸린 노드도 마찬가지다. 그래서 이 값은 적격·부적격과 무관하다.
     */
    public static boolean diskCheckApplied(Workload workload, NodeResource node) {
        return diskCheckRelevant(workload) && node.diskCapacityGb() > 0;
    }

    /**
     * 디스크 요구가 있는 배치에서 <b>총량을 몰라 검사 없이 적격이 된</b> 노드 id. 비어 있으면 표기할 것이 없다.
     * 후보 상세가 아니라 노드 총량으로 센다 — 디스패치 강등은 결과를 새로 만들어 상세를 비우므로 상세로 세면 샌다.
     */
    public static List<String> uncheckedDiskEligible(Workload workload, SchedulingResult result, PlacementContext ctx) {
        if (!diskCheckRelevant(workload) || result == null || result.candidateScores() == null) return List.of();
        List<String> out = new ArrayList<>();
        for (SchedulingResult.NodeScore s : result.candidateScores()) {
            if (!s.eligible()) continue;
            ctx.node(s.nodeId()).filter(n -> n.diskCapacityGb() <= 0).ifPresent(n -> out.add(n.getNodeId()));
        }
        return out;
    }

    /**
     * 위 목록이 비어 있지 않으면 WARN 한 줄 — 배치 1회당. 응답의 0.0 은 규약이지만 로그까지 조용하면 운영자가
     * 어디를 봐야 할지 모른다. 원인은 셋(그 노드의 루트 마운트가 설정과 다름 · 익스포터에 filesystem 시리즈가 없음
     * 또는 overlay 라 걸러짐 · instance 오타)이라 적용 중인 마운트 설정값과 노드 예시를 함께 찍는다.
     */
    public static void warnUncheckedDisk(Logger log, Workload workload, SchedulingResult result, PlacementContext ctx, String mountPoint) {
        List<String> nodes = uncheckedDiskEligible(workload, result, ctx);
        if (nodes.isEmpty()) return;
        log.warn("디스크 {}GB 요구 워크로드 {} — 총량을 모르는 노드 {}대({})가 디스크 검사 없이 적격이 됐다. "
                        + "마운트 설정 aims.scheduler.disk-mount-point='{}' 이 그 노드의 루트 마운트와 같은지, "
                        + "익스포터에 filesystem 시리즈가 있는지, 익스포터 대응 원장의 instance 가 맞는지 확인하십시오",
                workload.getRequiredDiskGb(), workload.getName(), nodes.size(),
                String.join(", ", nodes.subList(0, Math.min(3, nodes.size()))) + (nodes.size() > 3 ? ", …" : ""), mountPoint);
    }

    // ── C1. 자원 가용성 ─────────────────────────────────────────

    private Exclusion checkResources(Workload workload, NodeResource node) {
        if (!node.canAccommodate(workload.getRequiredCpu(), workload.getRequiredMemoryMb(), workload.getRequiredGpu())) {
            return of(ExclusionReason.INSUFFICIENT_RESOURCE,
                    "자원 부족 (필요 CPU:%d MEM:%dMB GPU:%d, 가용 CPU:%.1f MEM:%dMB GPU:%d)",
                    workload.getRequiredCpu(), workload.getRequiredMemoryMb(), workload.getRequiredGpu(),
                    node.availableCpu(), node.availableMemoryMb(), node.availableGpu());
        }
        // 디스크는 총량을 아는 노드에서만 본다. 총량 0 은 "모름"이고, 모르는 값으로 배치를 막지 않는다 —
        // 없는 값에 숫자를 채우던 결함의 반대 방향 실수를 피한다. 요구량 0 이면 검사할 것이 없다.
        if (workload.getRequiredDiskGb() > 0 && node.diskCapacityGb() > 0
                && node.availableDiskGb() < workload.getRequiredDiskGb()) {
            return of(ExclusionReason.INSUFFICIENT_DISK, "디스크 부족 (필요 %dGB, 가용 %dGB / 총량 %dGB)",
                    workload.getRequiredDiskGb(), node.availableDiskGb(), node.diskCapacityGb());
        }
        return null;
    }

    // ── C2. 사용률 임계 ─────────────────────────────────────────

    private Exclusion checkUsageThreshold(NodeResource node, SchedulingPolicy policy) {
        double cpu = node.effectiveCpuUsagePercent();
        if (cpu > policy.getMaxCpuUsagePercent()) {
            return of(ExclusionReason.CPU_USAGE_EXCEEDED, "CPU 사용률 초과 (%.1f%% > %.1f%%)",
                    cpu, policy.getMaxCpuUsagePercent());
        }
        double memory = node.effectiveMemoryUsagePercent();
        if (memory > policy.getMaxMemoryUsagePercent()) {
            return of(ExclusionReason.MEMORY_USAGE_EXCEEDED, "메모리 사용률 초과 (%.1f%% > %.1f%%)",
                    memory, policy.getMaxMemoryUsagePercent());
        }
        return null;
    }

    // ── C3. 열 상태 ────────────────────────────────────────────

    private Exclusion checkThermal(Workload workload, NodeResource node, SchedulingPolicy policy) {
        double maxTemp = policy.getMaxTemperatureCelsius();

        if (policy.isTemperatureAwareEnabled()) {
            if (thermalModel.isCoolingDown(node.getNodeId())) {
                return of(ExclusionReason.COOLING_DOWN, "냉각 대기 중 (%d분 남음)",
                        thermalModel.remainingCooldownMinutes(node.getNodeId()));
            }
            // 냉각 대기는 실제로 뜨거웠던 노드에만 건다. 여기서 실효 온도(예약분 포함)를 쓰면
            // 배치 예정 부하만으로 10분간 배제되는 노드가 생긴다. 예약까지 반영한 판단은
            // 아래 "배치 후 예상 온도"가 맡으며, 그쪽은 이번 배치만 거르고 기록을 남기지 않는다.
            if (node.getTemperatureCelsius() > maxTemp) {
                thermalModel.markCritical(node.getNodeId());
                return of(ExclusionReason.TEMPERATURE_EXCEEDED, "온도 초과 (%.1f°C > %.1f°C)",
                        node.getTemperatureCelsius(), maxTemp);
            }
        }

        // 워크로드가 지정한 온도 제약은 정책 설정과 무관하게 적용한다.
        if (workload.getMaxTemperatureCelsius() != null
                && node.getTemperatureCelsius() > workload.getMaxTemperatureCelsius()) {
            return of(ExclusionReason.WORKLOAD_TEMPERATURE_EXCEEDED, "워크로드 온도 제약 초과 (%.1f°C > %.1f°C)",
                    node.getTemperatureCelsius(), workload.getMaxTemperatureCelsius());
        }

        if (policy.isTemperatureAwareEnabled()) {
            double predicted = thermalModel.predictedTemperature(node, workload.getRequiredCpu());
            if (predicted > maxTemp) {
                return of(ExclusionReason.PREDICTED_TEMPERATURE_EXCEEDED,
                        "배치 후 예상 온도 %.1f°C > 임계치 %.1f°C", predicted, maxTemp);
            }
        }
        return null;
    }

    // ── C4. 전력 ───────────────────────────────────────────────

    private Exclusion checkPower(Workload workload, NodeResource node, SchedulingPolicy policy,
                                 Double runPowerBudgetWatts) {
        if (policy.isPowerAwareEnabled()) {
            double requiredHeadroom = 100 - policy.getMaxPowerUsagePercent();
            // 예약분을 반영한 여유율을 본다. 실측만 보면 배치를 아무리 쌓아도 여유가 그대로라
            // 전력 상한이 적용되지 않는다. 특히 요구 CPU 가 0 인 워크로드는 아래 예상 전력
            // 검사도 그냥 통과하므로, 여기서 걸러야 상한이 실제로 지켜진다.
            double headroom = powerModel.effectiveHeadroomPercent(node);
            if (headroom < requiredHeadroom) {
                return of(ExclusionReason.POWER_HEADROOM_EXCEEDED, "전력 여유 부족 (여유 %.1f%%)", headroom);
            }
        }

        // 워크로드가 지정한 전력 예산은 정책 설정과 무관하게 적용한다.
        if (workload.getMaxPowerBudgetWatts() != null
                && node.getPowerWatts() > workload.getMaxPowerBudgetWatts()) {
            return of(ExclusionReason.WORKLOAD_POWER_EXCEEDED, "워크로드 전력 제약 초과 (%.1fW > %.1fW)",
                    node.getPowerWatts(), workload.getMaxPowerBudgetWatts());
        }

        // 스케줄이 실행 단위로 지정한 예산. 워크로드 엔티티를 바꾸지 않고 적용한다.
        if (runPowerBudgetWatts != null && node.getPowerWatts() > runPowerBudgetWatts) {
            return of(ExclusionReason.RUN_POWER_BUDGET_EXCEEDED, "스케줄 전력 예산 초과 (%.1fW > %.1fW)",
                    node.getPowerWatts(), runPowerBudgetWatts);
        }

        if (policy.isPowerAwareEnabled() && node.getPowerCapacityWatts() > 0) {
            double predicted = powerModel.predictedPower(node, workload.getRequiredCpu());
            if (predicted > node.getPowerCapacityWatts()) {
                return of(ExclusionReason.PREDICTED_POWER_EXCEEDED, "배치 후 예상 전력 %.0fW > 용량 %.0fW",
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

    /** 냉각 대기 중인 노드가 하나라도 있는지. 확인하면서 만료된 기록을 지운다. */
    public boolean anyCoolingDown() {
        return thermalModel.anyCoolingDown();
    }
}
