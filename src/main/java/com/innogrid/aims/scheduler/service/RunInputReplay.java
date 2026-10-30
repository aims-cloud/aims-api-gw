package com.innogrid.aims.scheduler.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.ScheduleRunReplayResponse.Outcome;
import com.innogrid.aims.scheduler.dto.ScheduleRunReplayResponse.Verdict;
import com.innogrid.aims.scheduler.engine.PowerAwareScheduler;
import com.innogrid.aims.scheduler.engine.SchedulingEngine;
import com.innogrid.aims.scheduler.engine.TemperatureAwareScheduler;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.model.NetworkModel;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.MdcLink;
import com.innogrid.aims.scheduler.entity.PlacementDispatch;
import com.innogrid.aims.scheduler.entity.PlacementDispatch.DispatchStatus;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.GpuTelemetry;
import com.innogrid.aims.scheduler.model.LinkMatrix;
import com.innogrid.aims.scheduler.model.MdcPowerBudgets;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;

/**
 * 저장한 실행 입력만으로 그 실행의 배치를 다시 돌리고 배치 이력과 대조한다 — 실행 재생 API·엔진별 재계산 API 와 재생 테스트가
 * 함께 쓴다. 엔진별 재계산은 같은 루프를 엔진만 바꿔 돌린다.
 *
 * <p>엔진·평가기·모델은 스프링 빈을 쓰지 않고 공개 생성자로 다시 만든다. 운영 {@link ThermalModel} 빈의 냉각 대기를 건드리지
 * 않으려는 것이다. 노브는 payload 의 전력·열·네트워크 설정을 새 {@link SchedulerProperties} 에 덮어쓴다. 냉각 대기는 시각을
 * 맞춘 시계로 다시 건 뒤 저장 시각으로 돌려놓는다. 평가기는 대기를 거는 쪽이다 — 실제 실행처럼 루프 중 상한을 넘은 노드에
 * 대기가 걸려 뒤 워크로드가 그 노드를 보지 못한다.</p>
 *
 * <p>루프 안에서 생긴 일은 결과 쪽 기록에서 가져온다: 배치 이력의 후보 0 행은 건너뜀, 전달 기록의 거부·불가는 예약 되돌림
 * ({@link PlacementDispatchService#downgrade} 가 실패로 낮추는 상태와 같다).</p>
 */
final class RunInputReplay {

    /** 다시 만들 수 있는 엔진 id 와 엔진별 재계산 응답의 엔진 순서. 엔진 레지스트리의 id 와 같아야 한다 — 테스트가 맞대어 확인한다. */
    static final List<String> ENGINE_ORDER = List.of("weighted", "power", "temperature");

    static final Set<String> ENGINES = Set.copyOf(ENGINE_ORDER);

    /** 실행이 예약을 되돌린 전달 상태 — 실행 계층이 받지 않은 배치. */
    private static final Set<DispatchStatus> ROLLED_BACK = EnumSet.of(DispatchStatus.REJECTED, DispatchStatus.UNAVAILABLE);

    private RunInputReplay() {}

    /** 재생한 워크로드 한 건 — 건너뛴 건은 결과가 없다. */
    record Step(Long workloadId, boolean skipped, SchedulingResult result) {}

    /** 재생에 쓰는 엔진 묶음. 노브를 덮어쓴 설정이 정말 저장값과 같은지 테스트가 볼 수 있게 설정도 돌려준다. */
    record Rebuilt(SchedulerProperties properties, ThermalModel thermalModel, PlacementContext context, SchedulingEngine engine) {}

    /** 재생 한 건과 그 워크로드의 배치 이력 행(없으면 null)을 맞댄 결과. */
    record Compared(Step step, SchedulingHistory recorded, Outcome outcome) {}

    /** 대조 전체 — 판정과 워크로드별 결과, 성공한 배치인데 전달 기록이 없는 워크로드 수. */
    record Report(Verdict verdict, List<Compared> steps, int missingDispatchRecords) {}

    static Rebuilt rebuild(RunInputCapture.RunInputs in) {
        return rebuild(in, in.engineId());
    }

    /**
     * 저장한 설정 노브를 새 설정에 덮어쓴다. 지금 코드에 없는 노브는 무시하고, 저장 뒤 새로 생긴 노브는 지금 기본값으로 둔다.
     * 형식이 지금 코드와 다르면 {@link tools.jackson.core.JacksonException} 을 던진다 — 실행 재생은 이것을 409 로 돌려준다.
     */
    static SchedulerProperties properties(RunInputCapture.RunInputs in) {
        SchedulerProperties props = new SchedulerProperties();
        apply(in.knobs().power(), props.getPower());
        apply(in.knobs().thermal(), props.getThermal());
        apply(in.knobs().network(), props.getNetwork());
        return props;
    }

    /** 저장한 입력을 그대로 두고 엔진만 바꿔 조립한다 — 엔진마다 설정·모델·컨텍스트를 새로 만들어 서로의 예약·냉각 대기가 섞이지 않는다. */
    static Rebuilt rebuild(RunInputCapture.RunInputs in, String engineId) {
        SchedulerProperties props = properties(in);

        MovableClock clock = new MovableClock();
        ThermalModel thermal = new ThermalModel(props, clock);
        for (RunInputCapture.CooldownRow c : in.cooldowns()) {
            clock.now = c.since();
            thermal.markCritical(c.nodeId(), c.limitCelsius());
        }
        clock.now = in.capturedAt();

        PowerModel power = new PowerModel(props);
        NetworkModel network = new NetworkModel(props);
        List<NodeResource> nodes = in.nodes().stream().map(RunInputReplay::node).toList();
        List<MdcLink> links = in.links().stream().map(RunInputReplay::link).toList();
        PlacementContext context = PlacementContext.of(nodes, policy(in.policy()), new ConstraintEvaluator(power, thermal))
                .withPowerBudget(in.powerBudgetWatts())
                .withExcluded(in.excludedNodes())
                .withLinkMatrix(LinkMatrix.of(links))
                .withMdcBudgets(new MdcPowerBudgets(in.mdcBudgets()));
        SchedulingEngine engine = switch (engineId) {
            case "weighted" -> new WeightedScoreScheduler(power, thermal, network);
            case "power" -> new PowerAwareScheduler(power, thermal, network, props);
            case "temperature" -> new TemperatureAwareScheduler(thermal);
            default -> throw new IllegalArgumentException("재생할 수 없는 엔진: " + engineId);
        };
        return new Rebuilt(props, thermal, context, engine);
    }

    /**
     * 대상 순서대로 다시 배치한다.
     *
     * @param history    그 실행의 배치 이력(워크로드마다 한 행) — 후보 0 행이면 건너뛴 것이다
     * @param rolledBack 실행 계층이 받지 않아 실행이 예약을 되돌린 워크로드 id — 성공한 배치의 예약을 되돌린다
     */
    static List<Step> replay(RunInputCapture.RunInputs in, List<SchedulingHistory> history, Set<Long> rolledBack) {
        return replay(in, in.engineId(), history, rolledBack);
    }

    /**
     * 엔진만 바꿔 대상 순서대로 다시 배치한다. 건너뜀과 예약 되돌림은 기록 엔진과 같게 따른다 — 건너뜀은 배치 직전 다시 읽은
     * 워크로드 상태로 정해져 엔진과 무관하고, 되돌림은 워크로드 단위로 적용한다(실행 계층이 노드마다 다르게 받을 수 있다면 가정이다).
     */
    static List<Step> replay(RunInputCapture.RunInputs in, String engineId, List<SchedulingHistory> history, Set<Long> rolledBack) {
        Rebuilt rebuilt = rebuild(in, engineId);
        Map<Long, SchedulingHistory> byWorkload = byWorkload(history);
        List<Step> steps = new ArrayList<>();
        for (RunInputCapture.WorkloadRow row : in.workloads()) {
            if (isSkip(byWorkload.get(row.id()))) {
                steps.add(new Step(row.id(), true, null));
                continue;
            }
            Workload w = workload(row);
            SchedulingResult result;
            try {
                result = rebuilt.engine().schedule(w, rebuilt.context());
            } catch (RuntimeException e) {
                // 어느 워크로드에서 났는지 붙여 다시 던진다 — 호출자가 오류 응답에 싣는다
                throw new IllegalStateException("워크로드 " + row.id() + " 재계산 중 오류: " + e, e);
            }
            if (result.success()) {
                rebuilt.context().reserve(result.selectedNodeId(), w);
                if (rolledBack.contains(row.id())) {
                    rebuilt.context().unreserve(result.selectedNodeId(), w);
                }
            }
            steps.add(new Step(row.id(), false, result));
        }
        return steps;
    }

    /**
     * 다시 계산한 결정 중 배치 이력과 선택 노드·성공 여부가 같은 수. 건너뛴 워크로드와 이력 행이 없는 워크로드는 세지 않고, 실패와
     * 실패(선택 노드 없음)는 같은 것으로 센다. 노드 배치 스코어는 엔진마다 뜻이 달라 보지 않는다.
     */
    static int sameAsRecorded(List<Step> steps, List<SchedulingHistory> history) {
        Map<Long, SchedulingHistory> byWorkload = byWorkload(history);
        int same = 0;
        for (Step step : steps) {
            SchedulingHistory h = byWorkload.get(step.workloadId());
            if (step.skipped() || h == null) continue;
            SchedulingResult r = step.result();
            if (Objects.equals(h.getSelectedNodeId(), r.selectedNodeId()) && Boolean.valueOf(r.success()).equals(h.getSuccess())) {
                same++;
            }
        }
        return same;
    }

    /** 이 전달 상태면 실행이 예약을 되돌렸다. */
    static boolean rollsBack(DispatchStatus status) {
        return ROLLED_BACK.contains(status);
    }

    /** 그 실행의 전달 기록 중 예약을 되돌린 워크로드 id. */
    static Set<Long> rolledBackOf(List<PlacementDispatch> dispatches) {
        return dispatches.stream().filter(d -> rollsBack(d.getStatus()))
                .map(PlacementDispatch::getWorkloadId).collect(Collectors.toSet());
    }

    /**
     * 배치 이력은 성공인데 그 실행의 전달 기록이 없는 워크로드 id. 워크로드 삭제가 전달 기록만 지웠거나, 멱등키가 같은 기록이
     * 다른 실행에 이미 있어 새로 만들지 않은 경우다 — 실행이 예약을 되돌렸는지 알 수 없다.
     */
    static Set<Long> missingDispatch(List<SchedulingHistory> history, List<PlacementDispatch> dispatches) {
        Set<Long> dispatched = dispatches.stream().map(PlacementDispatch::getWorkloadId).collect(Collectors.toSet());
        Set<Long> missing = new HashSet<>();
        for (SchedulingHistory h : history) {
            if (Boolean.TRUE.equals(h.getSuccess()) && !dispatched.contains(h.getWorkloadId())) {
                missing.add(h.getWorkloadId());
            }
        }
        return missing;
    }

    /**
     * 재생 결과를 워크로드 id 로 배치 이력과 맞댄다. 선택 노드·노드 배치 스코어({@code finalScore})·성공 여부가 모두 같아야
     * MATCH 다 — 스코어는 오차 없이 비교한다.
     *
     * <p>판정은 앞에서부터 본다. 이력 행이 없는 워크로드부터, 그리고 전달 기록이 없는 성공 배치의 다음 워크로드부터는 실행의
     * 예약 상태를 알 수 없다. 그 지점 앞에서 어긋났으면 MISMATCH, 앞이 모두 같고 그런 지점이 있으면 INCOMPLETE, 끝까지
     * 확인했으면 MATCH 다.</p>
     */
    static Report compare(List<Step> steps, List<SchedulingHistory> history, Set<Long> missingDispatch) {
        Map<Long, SchedulingHistory> byWorkload = byWorkload(history);
        List<Compared> compared = new ArrayList<>();
        Verdict verdict = null;
        boolean unknown = false;
        for (Step step : steps) {
            SchedulingHistory h = byWorkload.get(step.workloadId());
            Outcome outcome = outcomeOf(step, h);
            compared.add(new Compared(step, h, outcome));
            if (outcome == Outcome.NOT_RECORDED) unknown = true;
            if (outcome == Outcome.MISMATCH && !unknown && verdict == null) verdict = Verdict.MISMATCH;
            if (missingDispatch.contains(step.workloadId())) unknown = true;
        }
        if (verdict == null) verdict = unknown ? Verdict.INCOMPLETE : Verdict.MATCH;
        return new Report(verdict, compared, missingDispatch.size());
    }

    private static Outcome outcomeOf(Step step, SchedulingHistory h) {
        if (h == null) return Outcome.NOT_RECORDED;
        if (step.skipped()) return Outcome.SKIPPED;
        SchedulingResult r = step.result();
        boolean same = Objects.equals(h.getSelectedNodeId(), r.selectedNodeId())
                && Double.compare(h.getFinalScore(), r.finalScore()) == 0
                && Boolean.valueOf(r.success()).equals(h.getSuccess());
        return same ? Outcome.MATCH : Outcome.MISMATCH;
    }

    /** 실행이 배치 직전에 다시 읽어 건너뛴 워크로드의 이력 행 — 후보 0 · 선택 노드 없음. */
    private static boolean isSkip(SchedulingHistory h) {
        return h != null && h.getCandidateCount() == 0 && h.getSelectedNodeId() == null;
    }

    private static Map<Long, SchedulingHistory> byWorkload(List<SchedulingHistory> history) {
        Map<Long, SchedulingHistory> byWorkload = new HashMap<>();
        history.forEach(h -> byWorkload.put(h.getWorkloadId(), h));
        return byWorkload;
    }

    private static void apply(JsonNode values, Object target) {
        RunInputCapture.MAPPER.readerForUpdating(target)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(RunInputCapture.MAPPER.writeValueAsString(values));
    }

    static NodeResource node(RunInputCapture.NodeRow r) {
        NodeResource n = new NodeResource(r.mdcId(), r.nodeId(), r.totalCpu(), r.totalMemoryMb(), r.totalGpu(),
                r.cpuUsagePercent(), r.memoryUsagePercent(), r.diskUsagePercent(),
                r.powerWatts(), r.powerCapacityWatts(), r.temperatureCelsius(), r.networkRxBps(), r.networkTxBps())
                .withDiskCapacityGb(r.diskCapacityGb())
                .withGpuTelemetry(new GpuTelemetry(r.gpuUsagePercent(), r.gpuTemperatureCelsius(), r.gpuPowerWatts()));
        n.reserve(r.reservedCpu(), r.reservedMemoryMb(), r.reservedGpu(), r.reservedDiskGb());
        n.setThermalState(r.thermalState());
        n.setPowerSource(r.powerSource());
        return n;
    }

    static SchedulingPolicy policy(RunInputCapture.PolicyRow r) {
        SchedulingPolicy p = new SchedulingPolicy(r.name(), r.description());
        p.setResourceType(r.resourceType());
        p.setDefaultEngine(r.defaultEngine());
        p.setWarningTemperatureCelsius(r.warningTemperatureCelsius());
        p.setCpuWeight(r.cpuWeight());
        p.setMemoryWeight(r.memoryWeight());
        p.setPowerWeight(r.powerWeight());
        p.setTemperatureWeight(r.temperatureWeight());
        p.setNetworkWeight(r.networkWeight());
        p.setMaxCpuUsagePercent(r.maxCpuUsagePercent());
        p.setMaxMemoryUsagePercent(r.maxMemoryUsagePercent());
        p.setMaxTemperatureCelsius(r.maxTemperatureCelsius());
        p.setMaxPowerUsagePercent(r.maxPowerUsagePercent());
        p.setPowerAwareEnabled(r.powerAwareEnabled());
        p.setTemperatureAwareEnabled(r.temperatureAwareEnabled());
        p.setAffinityEnabled(r.affinityEnabled());
        p.setActive(r.active());
        return p;
    }

    /** 엔진과 예약은 워크로드 id 를 쓰지 않아 id 는 채우지 않는다 — 대조는 {@link Step#workloadId()} 로 한다. */
    static Workload workload(RunInputCapture.WorkloadRow r) {
        Workload w = new Workload(r.name(), r.requiredCpu(), r.requiredMemoryMb());
        w.setRequiredGpu(r.requiredGpu());
        w.setRequiredDiskGb(r.requiredDiskGb());
        w.setResourceType(r.resourceType());
        w.setPreferredMdcId(r.preferredMdcId());
        w.setDataMdcId(r.dataMdcId());
        w.setMaxPowerBudgetWatts(r.maxPowerBudgetWatts());
        w.setMaxTemperatureCelsius(r.maxTemperatureCelsius());
        w.setRequiredBandwidthMbps(r.requiredBandwidthMbps());
        w.setPriority(r.priority());
        return w;
    }

    private static MdcLink link(RunInputCapture.LinkRow r) {
        MdcLink l = new MdcLink(r.mdcA(), r.mdcB(), r.latencyMs());
        l.setBandwidthMbps(r.bandwidthMbps());
        l.setPacketLossPercent(r.packetLossPercent());
        l.setStatus(r.status());
        return l;
    }

    /** 냉각 대기를 저장된 시작 시각으로 다시 걸려고 시각을 옮길 수 있는 시계. */
    static final class MovableClock extends Clock {
        Instant now = Instant.EPOCH;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
