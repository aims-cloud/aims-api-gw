package com.innogrid.aims.scheduler.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.test.util.ReflectionTestUtils;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.PowerAwareScheduler;
import com.innogrid.aims.scheduler.engine.SchedulingEngine;
import com.innogrid.aims.scheduler.engine.TemperatureAwareScheduler;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.model.NetworkModel;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.MdcLink;
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
 * 저장한 실행 입력만으로 그 회차의 배치를 다시 돌린다 — 저장한 묶음이 재생에 충분한지 확인하는 테스트 도구.
 *
 * <p>엔진·평가기·모델은 스프링 빈을 쓰지 않고 공개 생성자로 다시 만든다. 노브는 payload 의 전력·열·네트워크 설정을 새
 * {@link SchedulerProperties} 에 덮어쓴다. 냉각 대기는 시각을 맞춘 시계로 다시 건 뒤 저장 시각으로 돌려놓는다. 평가기는
 * 대기를 거는 쪽이다 — 실제 실행처럼 루프 중 상한을 넘은 노드에 대기가 걸려 뒤 워크로드가 그 노드를 보지 못한다.</p>
 *
 * <p>루프 안에서 생긴 일은 결과 쪽 기록에서 가져온다: 배치 이력의 후보 0 행은 건너뜀, 전달 기록의 거부는 예약 되돌림.</p>
 */
final class RunInputReplay {

    private RunInputReplay() {}

    /** 재생한 워크로드 한 건 — 건너뛴 건은 결과가 없다. */
    record Step(Long workloadId, boolean skipped, SchedulingResult result) {}

    /** 재생에 쓰는 엔진 묶음. 노브를 덮어쓴 설정이 정말 저장값과 같은지 테스트가 볼 수 있게 설정도 돌려준다. */
    record Rebuilt(SchedulerProperties properties, ThermalModel thermalModel, PlacementContext context, SchedulingEngine engine) {}

    static Rebuilt rebuild(RunInputCapture.RunInputs in) {
        SchedulerProperties props = new SchedulerProperties();
        apply(in.knobs().power(), props.getPower());
        apply(in.knobs().thermal(), props.getThermal());
        apply(in.knobs().network(), props.getNetwork());

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
        SchedulingEngine engine = switch (in.engineId()) {
            case "weighted" -> new WeightedScoreScheduler(power, thermal, network);
            case "power" -> new PowerAwareScheduler(power, thermal, network, props);
            case "temperature" -> new TemperatureAwareScheduler(thermal);
            default -> throw new IllegalArgumentException("재생할 수 없는 엔진: " + in.engineId());
        };
        return new Rebuilt(props, thermal, context, engine);
    }

    /**
     * 대상 순서대로 다시 배치한다.
     *
     * @param history  그 실행의 배치 이력(워크로드마다 한 행) — 후보 0 행이면 건너뛴 것이다
     * @param rejected 실행 계층이 받지 않은 워크로드 id — 성공한 배치의 예약을 되돌린다
     */
    static List<Step> replay(RunInputCapture.RunInputs in, List<SchedulingHistory> history, Set<Long> rejected) {
        Rebuilt rebuilt = rebuild(in);
        Map<Long, SchedulingHistory> byWorkload = new java.util.HashMap<>();
        history.forEach(h -> byWorkload.put(h.getWorkloadId(), h));
        List<Step> steps = new ArrayList<>();
        for (RunInputCapture.WorkloadRow row : in.workloads()) {
            SchedulingHistory h = byWorkload.get(row.id());
            if (h != null && h.getCandidateCount() == 0 && h.getSelectedNodeId() == null) {
                steps.add(new Step(row.id(), true, null));
                continue;
            }
            Workload w = workload(row);
            SchedulingResult result = rebuilt.engine().schedule(w, rebuilt.context());
            if (result.success()) {
                rebuilt.context().reserve(result.selectedNodeId(), w);
                if (rejected.contains(row.id())) {
                    rebuilt.context().unreserve(result.selectedNodeId(), w);
                }
            }
            steps.add(new Step(row.id(), false, result));
        }
        return steps;
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

    static Workload workload(RunInputCapture.WorkloadRow r) {
        Workload w = new Workload(r.name(), r.requiredCpu(), r.requiredMemoryMb());
        ReflectionTestUtils.setField(w, "id", r.id());
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
