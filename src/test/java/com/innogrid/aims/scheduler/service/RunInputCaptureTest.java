package com.innogrid.aims.scheduler.service;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dispatch.DispatcherRegistry;
import com.innogrid.aims.scheduler.dispatch.RecordingPlacementDispatcher;
import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.MdcLink;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.ScheduleRunInput;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.ExcludedNode;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.repository.MdcLinkRepository;
import com.innogrid.aims.scheduler.repository.PlacementDispatchRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunInputRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 스케줄 실행 입력 저장 — 실행 1회에 한 행, 담는 항목, 담지 않는 회차, 함께 지우는 두 자리, 형식의 빠짐 없음.
 * 재생이 되는지는 {@link RunInputReplayTest} 가 본다.
 */
@SpringBootTest
class RunInputCaptureTest {

    private static final String PREFIX = "prc-";
    private static final String NODE_B = "prc-b:9100";
    private static final String NODE_A = "prc-a:9100";

    @Autowired ScheduleService scheduleService;
    @Autowired ScheduleExecutionService executionService;
    @Autowired ScheduleRunner runner;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ScheduleRunRepository runRepository;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired SchedulingHistoryRepository historyRepository;
    @Autowired PlacementDispatchRepository dispatchRepository;
    @Autowired SchedulingPolicyRepository policyRepository;
    @Autowired MdcLinkRepository linkRepository;
    @Autowired ScheduleRunInputRepository inputRepository;
    @Autowired ThermalModel thermalModel;
    @Autowired SchedulerProperties properties;
    @MockitoBean NodeResourceCollector collector;
    @MockitoBean DispatcherRegistry registry;

    @BeforeEach
    void acceptAll() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
    }

    @AfterEach
    void clean() {
        List.of(NODE_A, NODE_B).forEach(thermalModel::clearCritical);
        historyRepository.findAll().stream().filter(h -> h.getWorkloadName() != null && h.getWorkloadName().startsWith(PREFIX)).forEach(historyRepository::delete);
        dispatchRepository.findAll().stream().filter(d -> d.getWorkloadName() != null && d.getWorkloadName().startsWith(PREFIX)).forEach(dispatchRepository::delete);
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith(PREFIX)).forEach(workloadRepository::delete);
        scheduleRepository.findAll().stream().filter(s -> s.getScheduleId().startsWith(PREFIX)).forEach(s -> scheduleService.delete(List.of(s.getId())));
        policyRepository.findAll().stream().filter(p -> p.getName().startsWith(PREFIX)).forEach(policyRepository::delete);
        linkRepository.findAll().stream().filter(l -> l.getMdcA().startsWith(PREFIX)).forEach(linkRepository::delete);
    }

    @DisplayName("실행 1회에 입력 한 행 — 노드는 수집 순서와 예약 반영 값 그대로, 정책 사본·대상 순서·수집 제외·노브가 담기고 노브 지문이 실행 이력과 같다")
    @Test
    void captures_inputs_once_per_run() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                List.of(node(NODE_B), node(NODE_A)), List.of(new ExcludedNode("mdc-01", "prc-gone:9100", List.of("cpu")))));
        Workload high = save("high", Workload.WorkloadPriority.HIGH);
        Workload low = save("low", Workload.WorkloadPriority.LOW);

        ScheduleRun run = executionService.runNow(schedule("one", null, 72.0), "tester");

        ScheduleRunInput row = inputRepository.findByScheduleRunId(run.getId()).orElseThrow();
        assertEquals(RunInputCapture.FORMAT_VERSION, row.getFormatVersion());
        assertEquals(run.getSnapshotKnobsDigest(), row.getKnobsDigest());
        assertEquals(RunInputCapture.digest(row.getPayload()), row.getInputDigest());
        RunInputCapture.RunInputs in = RunInputCapture.read(row.getPayload());
        assertEquals("weighted", in.engineId());
        assertEquals(List.of(NODE_B, NODE_A), in.nodes().stream().map(RunInputCapture.NodeRow::nodeId).toList(), "수집 순서 그대로");
        assertEquals(0, in.nodes().get(0).reservedCpu(), "배치 루프 전 값 — 이번 실행의 예약이 섞이지 않는다");
        assertEquals(List.of(high.getId(), low.getId()), in.workloads().stream().map(RunInputCapture.WorkloadRow::id).toList(), "배치 순서 그대로");
        assertEquals(72.0, in.policy().maxTemperatureCelsius(), "스케줄의 온도 상한을 덮어쓴 정책 사본");
        assertEquals(List.of("prc-gone:9100"), in.excludedNodes().stream().map(ExcludedNode::nodeId).toList());
        assertEquals(properties.getThermal().getInertiaK(), in.knobs().thermal().get("inertiaK").asDouble());
        assertEquals(properties.getPower().getIdleRatio(), in.knobs().power().get("idleRatio").asDouble());
        assertEquals(1, inputRepository.findAll().stream().filter(r -> r.getScheduleRunId().equals(run.getId())).count());
    }

    @DisplayName("실행 전에 걸린 냉각 대기가 상한·시작 시각과 함께 담긴다")
    @Test
    void captures_cooldowns_marked_before_run() {
        twoNodes();
        save("cd", Workload.WorkloadPriority.NORMAL);
        thermalModel.markCritical(NODE_A, 70);

        ScheduleRun run = executionService.runNow(schedule("cd", null, null), "tester");

        RunInputCapture.RunInputs in = RunInputCapture.read(inputRepository.findByScheduleRunId(run.getId()).orElseThrow().getPayload());
        RunInputCapture.CooldownRow mark = in.cooldowns().stream().filter(c -> c.nodeId().equals(NODE_A)).findFirst().orElseThrow();
        assertEquals(70.0, mark.limitCelsius());
        assertNotNull(mark.since());
    }

    @DisplayName("냉각 대기 기록 읽기는 만료된 기록을 지우지 않는다 — 판정 쪽 읽기만 정리한다")
    @Test
    void reading_cooldown_marks_does_not_purge() {
        RunInputReplay.MovableClock clock = new RunInputReplay.MovableClock();
        clock.now = Instant.parse("2026-10-29T00:00:00Z");
        ThermalModel model = new ThermalModel(new SchedulerProperties(), clock);
        model.markCritical("n", 75);
        clock.now = clock.now.plusSeconds(3600);   // 기본 10분이 지났다

        assertTrue(model.cooldownMarks().containsKey("n"), "읽기만 하고 지우지 않는다");
        assertTrue(model.cooldownMarks().containsKey("n"));
        assertFalse(model.isCoolingDown("n"));
        assertFalse(model.cooldownMarks().containsKey("n"), "판정 쪽 읽기는 만료 기록을 지운다");
    }

    @DisplayName("링크 원장은 정책의 링크 게이트가 켜진 실행에만 담기고 꺼진 실행은 빈 목록이다")
    @Test
    void captures_link_matrix_only_when_affinity_enabled() {
        twoNodes();
        save("ln", Workload.WorkloadPriority.NORMAL);
        MdcLink link = new MdcLink(PREFIX + "x", PREFIX + "y", 7.5);
        link.setBandwidthMbps(1000.0);
        linkRepository.saveAndFlush(link);
        SchedulingPolicy gated = new SchedulingPolicy(PREFIX + "gated", "링크 게이트");
        gated.setAffinityEnabled(true);
        gated.setActive(false);   // 지정한 스케줄만 쓴다 — 활성 공통 정책이면 지정 없는 스케줄도 이 정책을 고른다
        long gatedId = policyRepository.saveAndFlush(gated).getId();

        ScheduleRun on = executionService.runNow(schedule("on", gatedId, null), "tester");
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith(PREFIX)).forEach(workloadRepository::delete);
        save("ln2", Workload.WorkloadPriority.NORMAL);
        ScheduleRun off = executionService.runNow(schedule("off", null, null), "tester");

        RunInputCapture.RunInputs withGate = RunInputCapture.read(inputRepository.findByScheduleRunId(on.getId()).orElseThrow().getPayload());
        RunInputCapture.RunInputs withoutGate = RunInputCapture.read(inputRepository.findByScheduleRunId(off.getId()).orElseThrow().getPayload());
        RunInputCapture.LinkRow row = withGate.links().stream().filter(l -> l.mdcA().startsWith(PREFIX)).findFirst().orElseThrow();
        assertEquals(7.5, row.latencyMs());
        assertEquals(1000.0, row.bandwidthMbps());
        assertTrue(withoutGate.links().isEmpty());
    }

    @DisplayName("대상이 없거나 노드가 없어 배치 루프에 들어가지 않은 회차는 입력을 남기지 않는다")
    @Test
    void no_capture_without_placement_loop() {
        twoNodes();
        ScheduleRun noTarget = executionService.runNow(schedule("empty", null, null), "tester");
        save("nn", Workload.WorkloadPriority.NORMAL);
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(List.of(), List.of()));
        ScheduleRun noNodes = executionService.runNow(schedule("nonode", null, null), "tester");

        assertEquals(ScheduleRun.SnapshotStatus.NO_TARGET, noTarget.getSnapshotStatus());
        assertEquals(ScheduleRun.SnapshotStatus.NO_NODES, noNodes.getSnapshotStatus());
        assertTrue(inputRepository.findByScheduleRunId(noTarget.getId()).isEmpty());
        assertTrue(inputRepository.findByScheduleRunId(noNodes.getId()).isEmpty());
    }

    @DisplayName("보존 기간이 지난 실행 이력을 지울 때 그 실행 입력도 함께 지운다")
    @Test
    void retention_cleanup_deletes_inputs_with_runs() {
        twoNodes();
        save("rt", Workload.WorkloadPriority.NORMAL);
        ScheduleRun run = executionService.runNow(schedule("rt", null, null), "tester");
        assertTrue(inputRepository.findByScheduleRunId(run.getId()).isPresent());

        runner.cleanupRunsStartedBefore(OffsetDateTime.now().plusMinutes(1));

        assertTrue(runRepository.findById(run.getId()).isEmpty());
        assertTrue(inputRepository.findByScheduleRunId(run.getId()).isEmpty());
    }

    @DisplayName("스케줄을 지우면 그 실행들의 입력도 함께 지운다")
    @Test
    void schedule_delete_deletes_inputs() {
        twoNodes();
        save("del", Workload.WorkloadPriority.NORMAL);
        long scheduleId = schedule("del", null, null);
        ScheduleRun run = executionService.runNow(scheduleId, "tester");
        assertTrue(inputRepository.findByScheduleRunId(run.getId()).isPresent());

        scheduleService.delete(List.of(scheduleId));

        assertTrue(inputRepository.findByScheduleRunId(run.getId()).isEmpty());
    }

    @DisplayName("모의 실행 두 경로는 입력을 남기지 않는다")
    @Test
    void dry_runs_capture_nothing() {
        twoNodes();
        save("dr", Workload.WorkloadPriority.NORMAL);
        long scheduleId = schedule("dr", null, null);
        long before = inputRepository.count();

        executionService.dryRun(scheduleId);
        executionService.dryRunDraft(new ScheduleRequest(PREFIX + "dr2", PREFIX + "dr2", Workload.ResourceType.GPU, null,
                false, true, PREFIX, "weighted", null, null, null, null));

        assertEquals(before, inputRepository.count());
    }

    @DisplayName("실수 경계값이 저장했다 읽어도 같은 값이다 — NaN·무한대·아주 작은 값")
    @Test
    void doubles_round_trip_exactly() {
        double[] values = {0.1, 1e-300, Double.MAX_VALUE, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double v : values) {
            RunInputCapture.NodeRow row = new RunInputCapture.NodeRow("m", "n", 8, 1024, 0, v, v, v, v, v, v, v, v, v, v, v,
                    0, 0, 0, 0, 0, null, NodeResource.PowerSource.NONE);
            RunInputCapture.NodeRow back = RunInputCapture.MAPPER.readValue(RunInputCapture.MAPPER.writeValueAsString(row),
                    RunInputCapture.NodeRow.class);
            assertEquals(Double.doubleToRawLongBits(v), Double.doubleToRawLongBits(back.cpuUsagePercent()), "값 " + v);
            assertEquals(row, back);
        }
    }

    @DisplayName("노드 행은 NodeResource 의 선언 필드를 빠짐없이 같은 이름으로 담는다 — 필드가 늘면 형식을 함께 고친다")
    @Test
    void node_row_covers_every_node_field() {
        assertEquals(declaredFields(NodeResource.class, Set.of()), componentNames(RunInputCapture.NodeRow.class));
    }

    @DisplayName("정책 행은 SchedulingPolicy 의 판단 필드를 빠짐없이 같은 이름으로 담는다(id·생성 시각 제외)")
    @Test
    void policy_row_covers_every_policy_field() {
        assertEquals(declaredFields(SchedulingPolicy.class, Set.of("id", "createdAt")), componentNames(RunInputCapture.PolicyRow.class));
    }

    @DisplayName("형식 1 고정 픽스처를 읽어 다시 쓰면 같은 JSON 이다 — 형식 1 의 필드 이름·종류·순서가 바뀌면 저장해 둔 실행을 재생할 수 없다")
    @Test
    void format_1_fixture_round_trips() throws Exception {
        String fixture;
        try (var in = getClass().getResourceAsStream("/scheduler/replay/run-input-format-1.json")) {
            fixture = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        RunInputCapture.RunInputs read = RunInputCapture.read(fixture);

        assertEquals(RunInputCapture.FORMAT_VERSION, read.formatVersion());
        assertEquals(RunInputCapture.MAPPER.writeValueAsString(RunInputCapture.MAPPER.readTree(fixture)),
                RunInputCapture.MAPPER.writeValueAsString(read), "픽스처에 없는 필드·이름이 다른 필드·순서가 다른 필드가 있다");
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private static Set<String> declaredFields(Class<?> type, Set<String> excluded) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()) && !f.isSynthetic())
                .map(Field::getName).filter(n -> !excluded.contains(n)).collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    private static Set<String> componentNames(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    private void twoNodes() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(List.of(node(NODE_B), node(NODE_A)), List.of()));
    }

    private static NodeResource node(String nodeId) {
        return new NodeResource("mdc-01", nodeId, 16, 65_536, 0, 20, 20, 0, 200, 500, 50, 0, 0);
    }

    private long schedule(String key, Long policyId, Double maxTemperature) {
        return scheduleService.create(new ScheduleRequest(PREFIX + key, PREFIX + key, Workload.ResourceType.GPU, "0 0 2 * * ?",
                false, true, PREFIX, "weighted", policyId, null, maxTemperature, null)).id();
    }

    private Workload save(String name, Workload.WorkloadPriority priority) {
        Workload w = new Workload(PREFIX + name, 2, 1024);
        w.setResourceType(Workload.ResourceType.GPU);
        w.setPriority(priority);
        return workloadRepository.saveAndFlush(w);
    }
}
