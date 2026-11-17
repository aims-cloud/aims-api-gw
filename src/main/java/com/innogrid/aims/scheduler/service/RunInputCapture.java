package com.innogrid.aims.scheduler.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.MdcLink;
import com.innogrid.aims.scheduler.entity.ScheduleRunInput;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.ExcludedNode;
import com.innogrid.aims.scheduler.model.LinkMatrix;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.repository.ScheduleRunInputRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 스케줄 실행 1회의 배치 입력을 남긴다 — 1세부 실데이터가 들어온 뒤 그 회차를 그대로 다시 돌려 보려고.
 *
 * <p><b>담는 것</b>: 배치 루프 직전의 노드 상태(예약 반영 뒤 · 선언 필드 전부, 수집 순서 그대로 — 엔진은 동점이면 앞 노드를
 * 고른다)·수집에서 빠진 노드·대상 워크로드(배치 순서 그대로)·정책 사본(스케줄의 온도 상한을 덮어쓴 뒤)·실행 전력 상한·MDC
 * 예산·링크 원장·냉각 대기·엔진 노브(전력·열·네트워크 설정 전부).</p>
 *
 * <p><b>모은 그 자리에서 문자열로 바꾼다</b>. 루프는 노드 예약을 제자리에서 바꾸고 냉각 대기를 새로 건다 — 참조만 들고 있다가
 * 나중에 바꾸면 이번 실행의 예약과 대기가 섞여 들어간다.</p>
 *
 * <p><b>담지 않는 것</b>: 루프 안에서 생기는 일 — 배치 직전 다시 읽어 건너뛴 워크로드(배치 이력의 후보 0 행)와 실행 계층이
 * 받지 않아 되돌린 예약(전달 기록). 둘 다 이미 남으므로 재생할 때 거기서 읽는다. 실행 도중 편집된 워크로드의 새 값은 담지
 * 않는다.</p>
 *
 * <p>빈이 아니다 — {@link ScheduleExecutionService}와 단건 배치({@link SchedulingService})가 만들어 쓴다. 직렬화기도 전역 빈을
 * 주입받지 않고 여기서 만든다. 전역 설정이 바뀌어도 저장 형식이 흔들리지 않게 하려는 것이다.</p>
 */
final class RunInputCapture {

    /** payload 형식 판. 담는 항목이 바뀌면 올린다. */
    static final int FORMAT_VERSION = 1;

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ScheduleRunInputRepository repository;
    private final ThermalModel thermalModel;
    private final SchedulerProperties properties;

    RunInputCapture(ScheduleRunInputRepository repository, ThermalModel thermalModel, SchedulerProperties properties) {
        this.repository = repository;
        this.thermalModel = thermalModel;
        this.properties = properties;
    }

    /** 저장소 없이 문자열만 만드는 형태 — 단건 배치({@link SchedulingService})는 자기 표에 저장한다. {@link #capture}를 쓰지 않는다. */
    RunInputCapture(ThermalModel thermalModel, SchedulerProperties properties) {
        this(null, thermalModel, properties);
    }

    /** 입력을 모아 문자열로 바꾸고 한 행으로 저장한다. 예외는 호출자가 잡는다 — 실행은 계속해야 한다. */
    void capture(Long runId, String engineId, PlacementContext context, List<Workload> targets, String knobsDigest) {
        Instant now = Instant.now();
        String payload = payloadOf(engineId, now, context, targets);
        repository.save(new ScheduleRunInput(runId, FORMAT_VERSION, OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                knobsDigest, digest(payload), payload));
    }

    /** 입력을 모아 그 자리에서 문자열로 바꾼다 — 뒤에 바뀌는 예약·냉각 대기·워크로드 상태가 섞이지 않게. */
    String payloadOf(String engineId, Instant capturedAt, PlacementContext context, List<Workload> targets) {
        return MAPPER.writeValueAsString(inputsOf(engineId, capturedAt, context, targets));
    }

    RunInputs inputsOf(String engineId, Instant capturedAt, PlacementContext context, List<Workload> targets) {
        return new RunInputs(FORMAT_VERSION, engineId, capturedAt, context.powerBudgetWatts(),
                context.nodes().stream().map(NodeRow::of).toList(),
                List.copyOf(context.excludedNodes()),
                targets.stream().map(WorkloadRow::of).toList(),
                PolicyRow.of(context.policy()),
                new TreeMap<>(context.mdcBudgets().budgetByMdc()),
                linksOf(context.linkMatrix()),
                cooldownsOf(thermalModel.cooldownMarks()),
                new Knobs(MAPPER.valueToTree(properties.getPower()), MAPPER.valueToTree(properties.getThermal()),
                        MAPPER.valueToTree(properties.getNetwork())));
    }

    static RunInputs read(String payload) {
        return MAPPER.readValue(payload, RunInputs.class);
    }

    static String digest(String payload) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", e);   // 표준 JRE에는 늘 있다
        }
    }

    private static List<LinkRow> linksOf(LinkMatrix matrix) {
        List<LinkRow> rows = new ArrayList<>();
        matrix.entries().forEach((pair, q) -> rows.add(new LinkRow(pair.a(), pair.b(),
                q.latencyMs(), q.bandwidthMbps(), q.packetLossPercent(), q.status())));
        rows.sort(Comparator.comparing(LinkRow::mdcA).thenComparing(LinkRow::mdcB));
        return rows;
    }

    private static List<CooldownRow> cooldownsOf(Map<String, Map<Double, Instant>> marks) {
        List<CooldownRow> rows = new ArrayList<>();
        marks.forEach((nodeId, byLimit) -> byLimit.forEach((limit, since) -> rows.add(new CooldownRow(nodeId, limit, since))));
        rows.sort(Comparator.comparing(CooldownRow::nodeId).thenComparingDouble(CooldownRow::limitCelsius));
        return rows;
    }

    // ── 형식 1 ──────────────────────────────────────────────────

    /** 한 회차의 배치 입력. 목록은 모두 순서가 의미를 갖거나 정렬해 둔 것이다. */
    record RunInputs(int formatVersion, String engineId, Instant capturedAt, Double powerBudgetWatts,
                     List<NodeRow> nodes, List<ExcludedNode> excludedNodes, List<WorkloadRow> workloads,
                     PolicyRow policy, Map<String, Double> mdcBudgets, List<LinkRow> links,
                     List<CooldownRow> cooldowns, Knobs knobs) {}

    /** {@link NodeResource}의 선언 필드 전부. 이름을 필드와 같게 둔다 — 빠진 필드를 테스트가 반사로 잡는다. */
    record NodeRow(String mdcId, String nodeId, int totalCpu, long totalMemoryMb, int totalGpu,
                   double cpuUsagePercent, double memoryUsagePercent, double diskUsagePercent,
                   double powerWatts, double powerCapacityWatts, double temperatureCelsius,
                   double networkRxBps, double networkTxBps,
                   double gpuUsagePercent, double gpuTemperatureCelsius, double gpuPowerWatts,
                   int reservedCpu, long reservedMemoryMb, int reservedGpu, long reservedDiskGb,
                   long diskCapacityGb, NodeResource.ThermalState thermalState, NodeResource.PowerSource powerSource) {
        static NodeRow of(NodeResource n) {
            return new NodeRow(n.getMdcId(), n.getNodeId(), n.getTotalCpu(), n.getTotalMemoryMb(), n.getTotalGpu(),
                    n.getCpuUsagePercent(), n.getMemoryUsagePercent(), n.getDiskUsagePercent(),
                    n.getPowerWatts(), n.getPowerCapacityWatts(), n.getTemperatureCelsius(),
                    n.getNetworkRxBps(), n.getNetworkTxBps(),
                    n.getGpuUsagePercent(), n.getGpuTemperatureCelsius(), n.getGpuPowerWatts(),
                    n.getReservedCpu(), n.getReservedMemoryMb(), n.getReservedGpu(), n.reservedDiskGb(),
                    n.diskCapacityGb(), n.getThermalState(), n.getPowerSource());
        }
    }

    /** 대상 워크로드 — 배치 판정과 예약에 쓰는 필드, 그리고 건너뜀 판정에 쓰는 상태·재시도 횟수. */
    record WorkloadRow(Long id, String name, Workload.ResourceType resourceType,
                       int requiredCpu, int requiredMemoryMb, int requiredGpu, int requiredDiskGb,
                       String preferredMdcId, String dataMdcId, Double maxPowerBudgetWatts, Double maxTemperatureCelsius,
                       Double requiredBandwidthMbps, Workload.WorkloadPriority priority,
                       Workload.WorkloadStatus status, Integer retryCount) {
        static WorkloadRow of(Workload w) {
            return new WorkloadRow(w.getId(), w.getName(), w.getResourceType(),
                    w.getRequiredCpu(), w.getRequiredMemoryMb(), w.getRequiredGpu(), w.getRequiredDiskGb(),
                    w.getPreferredMdcId(), w.getDataMdcId(), w.getMaxPowerBudgetWatts(), w.getMaxTemperatureCelsius(),
                    w.getRequiredBandwidthMbps(), w.getPriority(), w.getStatus(), w.getRetryCount());
        }
    }

    /** 정책 사본의 판단 필드 전부({@code id}·{@code createdAt} 제외). 이름을 필드와 같게 둔다 — 테스트가 반사로 대조한다. */
    record PolicyRow(String name, String description, Workload.ResourceType resourceType, String defaultEngine,
                     Double warningTemperatureCelsius,
                     double cpuWeight, double memoryWeight, double powerWeight, double temperatureWeight, double networkWeight,
                     double maxCpuUsagePercent, double maxMemoryUsagePercent, double maxTemperatureCelsius,
                     double maxPowerUsagePercent,
                     boolean powerAwareEnabled, boolean temperatureAwareEnabled, boolean affinityEnabled, boolean active) {
        static PolicyRow of(SchedulingPolicy p) {
            return new PolicyRow(p.getName(), p.getDescription(), p.getResourceType(), p.getDefaultEngine(),
                    p.getWarningTemperatureCelsius(),
                    p.getCpuWeight(), p.getMemoryWeight(), p.getPowerWeight(), p.getTemperatureWeight(), p.getNetworkWeight(),
                    p.getMaxCpuUsagePercent(), p.getMaxMemoryUsagePercent(), p.getMaxTemperatureCelsius(),
                    p.getMaxPowerUsagePercent(),
                    p.isPowerAwareEnabled(), p.isTemperatureAwareEnabled(), p.isAffinityEnabled(), p.isActive());
        }
    }

    /** 링크 원장 한 쌍. 정책의 링크 게이트가 꺼진 실행은 원장을 읽지 않아 비어 있다. */
    record LinkRow(String mdcA, String mdcB, double latencyMs, Double bandwidthMbps, Double packetLossPercent,
                   MdcLink.LinkStatus status) {}

    /** 냉각 대기 한 건 — 그 대기를 건 온도 상한과 시작 시각. 만료 정리 전 그대로다. */
    record CooldownRow(String nodeId, double limitCelsius, Instant since) {}

    /** 엔진 노브 — 전력·열·네트워크 설정을 필드 이름 그대로. */
    record Knobs(JsonNode power, JsonNode thermal, JsonNode network) {}
}
