package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.DraftCapacityResponse;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.PowerAwareScheduler;
import com.innogrid.aims.scheduler.engine.SchedulingEngine;
import com.innogrid.aims.scheduler.engine.TemperatureAwareScheduler;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.MdcLink;
import com.innogrid.aims.scheduler.entity.MdcPowerBudget;
import com.innogrid.aims.scheduler.entity.NodePlacementHold;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.ExcludedNode;
import com.innogrid.aims.scheduler.model.ExclusionReason;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.MdcLinkRepository;
import com.innogrid.aims.scheduler.repository.MdcPowerBudgetRepository;
import com.innogrid.aims.scheduler.repository.NodePlacementHoldRepository;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static com.innogrid.aims.scheduler.entity.Workload.WorkloadStatus.SCHEDULED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 워크로드 수용 개수 모의 배치 — 노드마다 들어가지 못할 때까지 쌓기, 사용률 상한을 놓기 전 값으로 봄, 메모리 · GPU 로 멈춤, 기존 예약,
 * 배치 중지, 상한(같음 · 넘음), 수집 0, 엔진과 무관한 개수, MDC 전력 예산, 링크, 냉각 대기를 걸지 않음, 저장 0, 수집 노드 그대로,
 * 디스크 경고 한 번, 단건 평가({@code evaluateIn})와 같은 결정, 엔진 선택, 종류별 정책.
 *
 * <p>노드는 따로 적지 않으면 전력 · 온도를 모르는 값(0)이라 C1 자원 · C2 사용률만 걸린다. 개수는 테스트마다 식으로 적는다 — 가용 CPU =
 * 코어 × (1 − 사용률) − 예약, 실효 사용률 = 사용률 + 예약 ÷ 코어. 스프링 없이 돈다.</p>
 */
class DraftCapacityPreviewerTest {

    private static final String A = "a:9100";
    private static final String B = "b:9100";

    private final NodeResourceCollector collector = mock(NodeResourceCollector.class);
    private final WorkloadRepository workloads = mock(WorkloadRepository.class);
    private final SchedulingPolicyRepository policies = mock(SchedulingPolicyRepository.class);
    private final NodePlacementHoldRepository holds = mock(NodePlacementHoldRepository.class);
    private final MdcLinkRepository links = mock(MdcLinkRepository.class);
    private final MdcPowerBudgetRepository budgets = mock(MdcPowerBudgetRepository.class);
    private final SchedulerProperties properties = new SchedulerProperties();
    private final ConstraintEvaluator evaluator = ConstraintEvaluator.withDefaults();
    private EngineRegistry engines = new EngineRegistry(List.of(new WeightedScoreScheduler()));

    // ── 쌓기 · 멈춤 ────────────────────────────────────────────

    @DisplayName("같은 초안을 노드마다 들어가지 못할 때까지 쌓고, 행은 노드 id 순이며 멈춘 시도의 사유를 노드마다 싣는다")
    @Test
    void counts_each_node_until_nothing_fits() {
        // 수집 순서를 id 순과 다르게 둔다
        nodes(node(B, 4, 0), node(A, 8, 0));

        DraftCapacityResponse r = service().draftCapacity(draft(2, 512), null);

        // A: 8 − 2k ≥ 2 → k ≤ 3 · 25k ≤ 80 → 4개 / B: 4 − 2k ≥ 2 → k ≤ 1 · 50k ≤ 80 → 2개
        assertEquals(6, r.placeableCount());
        assertEquals(List.of(A + ":4", B + ":2"), rows(r));
        assertEquals(2, r.nodeCount());
        assertFalse(r.truncated());
        assertEquals("{\"INSUFFICIENT_RESOURCE\":2}", r.stopExclusionCodes());
        assertEquals("모든 후보 노드가 제약 조건을 충족하지 못합니다", r.stopReason());
        assertTrue(r.nodes().stream().allMatch(n -> n.stopCode() == ExclusionReason.INSUFFICIENT_RESOURCE
                && n.stopReason().startsWith("자원 부족")));
        assertEquals("mdc-a", r.nodes().get(0).mdcId());
        assertEquals(DraftCapacityPreviewer.LIMITATION, r.limitation());
        assertEquals(Workload.ResourceType.VM, r.resourceType(), "종류를 비우면 VM");

        DraftCapacityResponse control = service().draftCapacity(draft(3, 512), null);
        // 대조: A: 8 − 3k ≥ 3 → 2개 / B: 4 − 3k ≥ 3 → 1개
        assertEquals(List.of(A + ":2", B + ":1"), rows(control));
    }

    @DisplayName("CPU 사용률 상한은 놓기 전 값으로 봐서, 마지막으로 놓은 개수에서는 상한을 넘는다")
    @Test
    void usage_limit_is_checked_before_placing() {
        nodes(node(A, 10, 50));

        DraftCapacityResponse r = service().draftCapacity(draft(1, 512), null);

        // 가용 5 − k ≥ 1 → k ≤ 4 이지만 실효 50 + 10k ≤ 80 → k ≤ 3 → 4개, 다섯째 시도는 실효 90%
        assertEquals(4, r.placeableCount());
        assertEquals(ExclusionReason.CPU_USAGE_EXCEEDED, r.nodes().get(0).stopCode());
        assertEquals("{\"CPU_USAGE_EXCEEDED\":1}", r.stopExclusionCodes());
        assertEquals("CPU 사용률 초과 (90.0% > 80.0%)", r.nodes().get(0).stopReason(),
                "다섯째 시도가 본 실효 사용률 — 넷을 놓은 뒤 상한 80% 를 넘었다");
    }

    @DisplayName("메모리는 가용량이 남아도 메모리 사용률 상한에서 멈춘다")
    @Test
    void memory_usage_limit_stops() {
        nodes(new NodeResource("mdc-a", A, 64, 65536, 0, 0, 50, 15, 0, 0, 0, 0, 0));

        DraftCapacityResponse r = service().draftCapacity(draft(1, 8192), null);

        // 가용 32768 − 8192k ≥ 8192 → k ≤ 3 이지만 실효 50 + 12.5k ≤ 85 → k ≤ 2 → 3개
        assertEquals(3, r.placeableCount());
        assertEquals(ExclusionReason.MEMORY_USAGE_EXCEEDED, r.nodes().get(0).stopCode());
    }

    @DisplayName("GPU 를 요구하면 GPU 없는 노드는 0개이고, GPU 두 장 노드는 두 개에서 멈춘다")
    @Test
    void gpu_draft_counts_only_gpu_nodes() {
        nodes(node(A, 8, 0), node(B, 4, 0));
        Workload gpu = draft(1, 512);
        gpu.setRequiredGpu(1);

        DraftCapacityResponse r = service().draftCapacity(gpu, null);

        assertEquals(0, r.placeableCount());
        assertEquals(List.of(A + ":0", B + ":0"), rows(r), "0개인 노드도 행으로 싣는다");
        assertEquals("{\"INSUFFICIENT_RESOURCE\":2}", r.stopExclusionCodes());

        nodes(new NodeResource("mdc-a", A, 8, 65536, 2, 0, 20, 15, 0, 0, 0, 0, 0));
        assertEquals(2, service().draftCapacity(gpu, null).placeableCount(), "대조: GPU 2 − k ≥ 1 → 2개");
    }

    // ── 기존 예약 · 배치 중지 ────────────────────────────────────

    @DisplayName("기존 배치 대기의 예약만큼 덜 들어가고, 보존 기한이 지난 배치 대기는 예약으로 세지 않는다")
    @Test
    void existing_reservations_reduce_capacity() {
        nodes(node(A, 8, 0), node(B, 4, 0));
        held(placed(1, A, 2));

        DraftCapacityResponse r = service().draftCapacity(draft(2, 512), null);

        // A 예약 2코어: 8 − 2 − 2k ≥ 2 → k ≤ 2 · 25(1 + k) ≤ 80 → 3개
        assertEquals(List.of(A + ":3", B + ":2"), rows(r));

        Workload expired = placed(1, A, 2);
        ReflectionTestUtils.setField(expired, "scheduledAt", OffsetDateTime.now().minusDays(3));
        held(expired);
        assertEquals(List.of(A + ":4", B + ":2"), rows(service().draftCapacity(draft(2, 512), null)),
                "대조: 보존 기한이 지난 배치 대기는 자원을 묶지 않는다");
    }

    @DisplayName("배치 중지 노드는 0개이고 그 사유를 싣고, 중지가 없으면 같은 노드에 들어간다")
    @Test
    void held_node_gets_none() {
        nodes(node(A, 8, 0), node(B, 4, 0));
        NodePlacementHold hold = new NodePlacementHold(A);
        hold.setReason("점검");
        when(holds.findAll()).thenReturn(List.of(hold));

        DraftCapacityResponse r = service().draftCapacity(draft(2, 512), null);

        assertEquals(List.of(A + ":0", B + ":2"), rows(r));
        assertEquals(ExclusionReason.NODE_PLACEMENT_HOLD, r.nodes().get(0).stopCode());
        assertEquals("운영자 배치 중지 (사유: 점검)", r.nodes().get(0).stopReason());
        assertEquals("{\"INSUFFICIENT_RESOURCE\":1,\"NODE_PLACEMENT_HOLD\":1}", r.stopExclusionCodes());

        when(holds.findAll()).thenReturn(List.of());
        assertEquals(List.of(A + ":4", B + ":2"), rows(service().draftCapacity(draft(2, 512), null)), "대조: 중지 없음");
    }

    // ── 상한 · 수집 0 ──────────────────────────────────────────

    @DisplayName("상한과 들어갈 개수가 같으면 상한에서 멈춰 truncated 이고, 상한이 하나 크면 한 번 더 시도해 실패로 멈춘다")
    @Test
    void limit_stops_at_or_above_capacity() {
        nodes(node(A, 8, 0), node(B, 4, 0));
        AtomicInteger calls = new AtomicInteger();
        engines = new EngineRegistry(List.of(counting(new WeightedScoreScheduler(), calls)));
        properties.setMaxWorkloadsPerRun(6);

        DraftCapacityResponse r = service().draftCapacity(draft(2, 512), null);

        assertEquals(6, r.placeableCount());
        assertTrue(r.truncated());
        assertEquals(6, r.limit());
        assertEquals(String.format(DraftCapacityPreviewer.LIMIT_REACHED, 6), r.stopReason());
        assertNull(r.stopExclusionCodes());
        assertTrue(r.nodes().stream().allMatch(n -> n.stopReason() == null && n.stopCode() == null));
        assertEquals(6, calls.get(), "상한에 닿으면 더 시도하지 않는다");

        calls.set(0);
        properties.setMaxWorkloadsPerRun(7);
        DraftCapacityResponse control = service().draftCapacity(draft(2, 512), null);
        assertEquals(6, control.placeableCount());
        assertFalse(control.truncated());
        assertEquals("{\"INSUFFICIENT_RESOURCE\":2}", control.stopExclusionCodes());
        assertEquals(7, calls.get(), "처음 실패한 시도에서 멈춘다 — 성공 6번 + 실패 1번");
    }

    @DisplayName("수집 후보가 없으면 엔진을 부르지 않고 활성 노드가 없다는 사유를 싣고, 수집 제외 노드는 수와 사유에 나온다")
    @Test
    void no_collected_node_skips_engine() {
        nodes();
        AtomicInteger calls = new AtomicInteger();
        engines = new EngineRegistry(List.of(counting(new WeightedScoreScheduler(), calls)));

        DraftCapacityResponse r = service().draftCapacity(draft(1, 512), null);

        assertEquals(0, calls.get());
        assertEquals(0, r.nodeCount());
        assertEquals(0, r.placeableCount());
        assertTrue(r.nodes().isEmpty());
        assertFalse(r.truncated());
        assertNull(r.stopExclusionCodes());
        assertEquals("활성 노드가 없습니다", r.stopReason());
        assertEquals("default", r.policyName());
        assertEquals("weighted", r.engine());

        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                List.of(), List.of(new ExcludedNode("mdc-a", "gone:9100", List.of("cpu")))));
        DraftCapacityResponse excluded = service().draftCapacity(draft(1, 512), null);
        assertEquals(1, excluded.excludedNodeCount());
        assertTrue(excluded.stopReason().contains("gone:9100"), excluded.stopReason());
        assertEquals(0, calls.get());
    }

    // ── 엔진 · MDC 예산 ────────────────────────────────────────

    @DisplayName("MDC 전력 예산이 없으면 엔진마다 먼저 고르는 노드가 달라도 노드별 개수와 노드별 멈춘 사유가 같다")
    @Test
    void engines_agree_without_mdc_budget() {
        nodes(new NodeResource("mdc-a", A, 16, 65536, 0, 10, 20, 15, 300, 1000, 40, 0, 0),
                new NodeResource("mdc-a", B, 16, 65536, 0, 60, 30, 15, 700, 1000, 55, 0, 0),
                new NodeResource("mdc-a", "c:9100", 8, 32768, 0, 30, 20, 15, 200, 500, 35, 0, 0));
        engines = new EngineRegistry(List.of(new WeightedScoreScheduler(), new PowerAwareScheduler(),
                new TemperatureAwareScheduler()));
        Workload d = draft(2, 1024);

        DraftCapacityResponse weighted = service().draftCapacity(d, "weighted");
        DraftCapacityResponse power = service().draftCapacity(d, "power");
        DraftCapacityResponse temperature = service().draftCapacity(d, "temperature");

        assertTrue(weighted.nodes().stream().filter(n -> n.placedCount() > 0).count() >= 2, rows(weighted).toString());
        assertEquals(detail(weighted), detail(power));
        assertEquals(detail(weighted), detail(temperature));
        assertEquals(weighted.placeableCount(), power.placeableCount());
        assertEquals("power", power.engine());
        List<String> firstChoices = List.of("weighted", "power", "temperature").stream()
                .map(e -> service().dryRun(d, e).selectedNodeId()).distinct().toList();
        assertTrue(firstChoices.size() >= 2, "대조: 엔진마다 처음 고르는 노드가 다르다 " + firstChoices);
    }

    @DisplayName("MDC 전력 예산이 있으면 그 예산에서 멈춰 예산이 없을 때보다 덜 들어간다")
    @Test
    void mdc_budget_stops_earlier() {
        nodes(new NodeResource("mdc-a", A, 16, 65536, 0, 5, 20, 15, 400, 1000, 0, 0, 0),
                new NodeResource("mdc-a", B, 16, 65536, 0, 5, 20, 15, 400, 1000, 0, 0, 0));
        when(budgets.findAll()).thenReturn(List.of(new MdcPowerBudget("mdc-a", 1000)));

        DraftCapacityResponse budgeted = service().draftCapacity(draft(1, 512), null);

        when(budgets.findAll()).thenReturn(List.of());
        DraftCapacityResponse control = service().draftCapacity(draft(1, 512), null);

        assertTrue(budgeted.placeableCount() > 0, rows(budgeted).toString());
        assertTrue(budgeted.placeableCount() < control.placeableCount(), rows(budgeted) + " vs " + rows(control));
        assertTrue(budgeted.stopExclusionCodes().contains("MDC_POWER_BUDGET_EXCEEDED"), budgeted.stopExclusionCodes());
        assertFalse(control.stopExclusionCodes().contains("MDC_POWER_BUDGET_EXCEEDED"), control.stopExclusionCodes());
    }

    @DisplayName("요청한 엔진을 쓰고, 비우면 정책 기본 엔진(등록된 것만), 모르는 엔진은 400 이다")
    @Test
    void engine_is_requested_or_policy_default() {
        nodes(node(A, 8, 0));
        SchedulingPolicy p = policy("p");
        p.setDefaultEngine("power");
        when(policies.findByActiveTrueAndResourceTypeIsNullOrderByNameAsc()).thenReturn(List.of(p));

        assertEquals("weighted", service().draftCapacity(draft(1, 512), null).engine(), "등록되지 않은 정책 기본 엔진은 서버 기본값");
        BusinessException e = assertThrows(BusinessException.class,
                () -> service().draftCapacity(draft(1, 512), "nope"));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());

        engines = new EngineRegistry(List.of(new WeightedScoreScheduler(), new PowerAwareScheduler()));
        assertEquals("power", service().draftCapacity(draft(1, 512), null).engine(), "등록된 정책 기본 엔진");
        assertEquals("weighted", service().draftCapacity(draft(1, 512), "weighted").engine());
    }

    @DisplayName("초안 종류의 정책으로 세고, 종류 정책이 없으면 공통 정책으로 센다")
    @Test
    void resource_type_picks_its_policy() {
        nodes(node(A, 8, 0), node(B, 4, 0));
        SchedulingPolicy strict = policy("gpu-strict");
        strict.setMaxCpuUsagePercent(20);
        when(policies.findByActiveTrueAndResourceTypeOrderByNameAsc(Workload.ResourceType.GPU))
                .thenReturn(List.of(strict));
        when(policies.findByActiveTrueAndResourceTypeIsNullOrderByNameAsc()).thenReturn(List.of(policy("common")));
        Workload gpuType = draft(2, 512);
        gpuType.setResourceType(Workload.ResourceType.GPU);

        DraftCapacityResponse r = service().draftCapacity(gpuType, null);

        // 상한 20%: 놓기 전 실효가 0% 인 첫 시도만 들어간다 → 노드마다 1개
        assertEquals("gpu-strict", r.policyName());
        assertEquals(Workload.ResourceType.GPU, r.resourceType());
        assertEquals(List.of(A + ":1", B + ":1"), rows(r));

        when(policies.findByActiveTrueAndResourceTypeOrderByNameAsc(Workload.ResourceType.GPU)).thenReturn(List.of());
        DraftCapacityResponse control = service().draftCapacity(gpuType, null);
        assertEquals("common", control.policyName());
        assertEquals(List.of(A + ":4", B + ":2"), rows(control), "대조: 공통 정책(상한 80%)");
    }

    // ── 링크 · 냉각 대기 · 저장 · 원본 ──────────────────────────

    @DisplayName("링크 반영을 켠 정책이면 끊긴 링크 건너 노드는 0개이고, 끈 정책이면 같은 노드에 들어간다")
    @Test
    void down_link_blocks_only_with_affinity() {
        nodes(node(A, 8, 0), new NodeResource("mdc-b", "far:9100", 8, 65536, 0, 0, 20, 15, 0, 0, 0, 0, 0));
        MdcLink down = new MdcLink("mdc-a", "mdc-b", 5);
        down.setStatus(MdcLink.LinkStatus.DOWN);
        when(links.findAll()).thenReturn(List.of(down));
        SchedulingPolicy affinity = policy("affinity");
        affinity.setAffinityEnabled(true);
        when(policies.findByActiveTrueAndResourceTypeIsNullOrderByNameAsc()).thenReturn(List.of(affinity));
        Workload d = draft(2, 512);
        d.setDataMdcId("mdc-a");

        DraftCapacityResponse r = service().draftCapacity(d, null);

        assertEquals(List.of(A + ":4", "far:9100:0"), rows(r));
        assertEquals(ExclusionReason.MDC_LINK_DOWN, r.nodes().get(1).stopCode());

        when(policies.findByActiveTrueAndResourceTypeIsNullOrderByNameAsc()).thenReturn(List.of(policy("plain")));
        assertEquals(List.of(A + ":4", "far:9100:4"), rows(service().draftCapacity(d, null)), "대조: 링크 반영을 끈 정책");
    }

    @DisplayName("냉각 대기를 새로 걸지 않고, 저장 전 모의 배치가 같은 노드에 건 냉각 대기는 그대로 본다")
    @Test
    void does_not_mark_cooldown() {
        nodes(node(A, 8, 0), new NodeResource("mdc-a", "hot:9100", 64, 65536, 0, 5, 20, 15, 0, 0, 70, 0, 0));
        SchedulingPolicy thermal = policy("thermal");
        thermal.setTemperatureAwareEnabled(true);
        thermal.setMaxTemperatureCelsius(60);
        when(policies.findByActiveTrueAndResourceTypeIsNullOrderByNameAsc()).thenReturn(List.of(thermal));

        DraftCapacityResponse r = service().draftCapacity(draft(2, 512), null);

        assertEquals(List.of(A + ":4", "hot:9100:0"), rows(r));
        assertEquals(ExclusionReason.TEMPERATURE_EXCEEDED, r.nodes().get(1).stopCode());
        assertFalse(evaluator.anyCoolingDown());
        service().dryRun(draft(2, 512), null);
        assertTrue(evaluator.anyCoolingDown(), "대조: 저장 전 모의 배치는 같은 노드에 냉각 대기를 건다");

        DraftCapacityResponse again = service().draftCapacity(draft(2, 512), null);
        assertEquals(List.of(A + ":4", "hot:9100:0"), rows(again));
        assertEquals(ExclusionReason.COOLING_DOWN, again.nodes().get(1).stopCode(), "걸려 있는 냉각 대기는 그대로 본다");
    }

    @DisplayName("아무것도 저장하지 않는다 — 정책이 하나도 없어도 default 를 저장하지 않고 워크로드 · 원장도 쓰지 않는다")
    @Test
    void saves_nothing() {
        nodes(node(A, 8, 0));
        held(placed(1, A, 2));

        DraftCapacityResponse r = service().draftCapacity(draft(2, 512), null);

        assertEquals("default", r.policyName());
        verify(policies, never()).save(any());
        verify(workloads, never()).save(any());
        verify(workloads, never()).saveAll(anyList());
        verify(holds, never()).save(any());
        verify(budgets, never()).save(any());
        verify(links, never()).save(any());
    }

    @DisplayName("수집한 노드 객체에는 예약을 쌓지 않는다 — 사본에만 쌓는다")
    @Test
    void collected_nodes_stay_untouched() {
        NodeResource a = node(A, 8, 0);
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(List.of(a), List.of()));

        DraftCapacityResponse r = service().draftCapacity(draft(2, 512), null);

        assertEquals(4, r.placeableCount());
        assertEquals(0, a.getReservedCpu());
        assertEquals(0, a.getReservedMemoryMb());
    }

    // ── 디스크 경고 · 단건 평가와 같은 결정 ───────────────────────

    @DisplayName("디스크 총량을 모르는 노드에 디스크 요구 초안을 여러 개 놓아도 경고는 한 줄이고, 총량을 알면 없다")
    @Test
    void unchecked_disk_warns_once() {
        nodes(node(A, 8, 0), node(B, 4, 0));
        Workload d = draft(2, 512);
        d.setRequiredDiskGb(10);
        ch.qos.logback.classic.Logger log =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DraftCapacityPreviewer.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> events =
                new ch.qos.logback.core.read.ListAppender<>();
        events.start();
        log.addAppender(events);
        try {
            DraftCapacityResponse r = service().draftCapacity(d, null);
            assertEquals(6, r.placeableCount(), "총량을 모르면 디스크로 막지 않는다");
            assertEquals(1, events.list.size(), "여섯 번 놓아도 한 줄");
            assertEquals(ch.qos.logback.classic.Level.WARN, events.list.get(0).getLevel());

            events.list.clear();
            nodes(node(A, 8, 0).withDiskCapacityGb(1000), node(B, 4, 0).withDiskCapacityGb(1000));
            service().draftCapacity(d, null);
            assertEquals(0, events.list.size(), "대조: 총량을 아는 노드뿐이면 조용하다");
        } finally {
            log.detachAppender(events);
        }
    }

    @DisplayName("같은 컨텍스트 사본에서 단건 평가(evaluateIn)를 되풀이한 결과와 노드별 개수 · 멈춘 사유 · 정책 이름이 같다")
    @Test
    void matches_repeated_evaluate_in() {
        nodes(node(B, 4, 0), node(A, 8, 30), node("c:9100", 6, 10));
        held(placed(1, A, 1));
        SchedulingService service = service();
        Workload d = draft(2, 2048);

        DraftCapacityResponse r = service.draftCapacity(d, null);

        PlacementContext collected = service.createContext(service.choosePolicy(Workload.ResourceType.VM).policy());
        PlacementContext copy = collected.sandbox(collected.nodes());
        Map<String, Integer> counts = new TreeMap<>();
        collected.nodes().forEach(n -> counts.put(n.getNodeId(), 0));
        SchedulingResult last;
        while ((last = service.evaluateIn(d, "weighted", copy)).success()) {
            copy.reserve(last.selectedNodeId(), d);
            counts.merge(last.selectedNodeId(), 1, Integer::sum);
        }
        List<String> expected = new ArrayList<>();
        counts.forEach((id, c) -> expected.add(id + ":" + c));

        assertEquals(expected, rows(r));
        assertTrue(r.placeableCount() > 0);
        assertEquals(last.reason(), r.stopReason());
        assertEquals(PlacementService.exclusionCodesOf(last), r.stopExclusionCodes());
        assertEquals(last.policyName(), r.policyName());
        assertNotEquals(null, r.policyName());
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    /** 정책 저장소는 받은 객체를 그대로 돌려준다 — 저장 여부만 다르고 배치 결과는 같게 해 「저장 0」만 갈리게 한다. */
    private SchedulingService service() {
        when(policies.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return SchedulingServiceBuilder.aSchedulingService()
                .collector(collector).workloads(workloads).policies(policies).holds(holds).links(links).budgets(budgets)
                .evaluator(evaluator).properties(properties).engines(engines).build();
    }

    /** 호출마다 새 노드 객체 — 예약은 노드 객체에 쌓이므로 같은 객체를 돌려주면 앞 호출의 예약이 남는다. */
    private void nodes(NodeResource... nodes) {
        List<NodeResource> template = List.of(nodes);
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                template.stream().map(NodeResource::snapshot).toList(), List.of()));
    }

    private void held(Workload... held) {
        List<Workload> rows = List.of(held);
        when(workloads.findByStatusIn(anyList())).thenAnswer(inv -> new ArrayList<>(rows));
    }

    /** 전력 · 온도를 모르는 노드(0) — C1 자원 · C2 사용률만 걸린다. 메모리 65536MB · 사용률 20%. */
    private static NodeResource node(String id, int cpu, double cpuUsage) {
        return new NodeResource("mdc-a", id, cpu, 65536, 0, cpuUsage, 20, 15, 0, 0, 0, 0, 0);
    }

    private static Workload draft(int cpu, int memoryMb) {
        return new Workload("draft", cpu, memoryMb);
    }

    private static SchedulingPolicy policy(String name) {
        SchedulingPolicy p = new SchedulingPolicy(name, "d");
        p.setActive(true);
        return p;
    }

    /** 그 노드에 배정된 배치 대기(메모리 512MB · 방금 배정). */
    private static Workload placed(long id, String node, int cpu) {
        Workload w = new Workload("held-" + id, cpu, 512);
        w.assignTo("mdc-a", node, 0.5, "test");
        ReflectionTestUtils.setField(w, "id", id);
        ReflectionTestUtils.setField(w, "createdAt", OffsetDateTime.parse("2026-11-01T00:00:00Z"));
        assertEquals(SCHEDULED, w.getStatus());
        return w;
    }

    /** 엔진 호출 수를 세는 감싸개. */
    private static SchedulingEngine counting(SchedulingEngine inner, AtomicInteger calls) {
        return new SchedulingEngine() {
            @Override
            public SchedulingResult schedule(Workload workload, PlacementContext context) {
                calls.incrementAndGet();
                return inner.schedule(workload, context);
            }

            @Override
            public String getName() { return inner.getName(); }

            @Override
            public String getId() { return inner.getId(); }
        };
    }

    private static List<String> rows(DraftCapacityResponse r) {
        return r.nodes().stream().map(n -> n.nodeId() + ":" + n.placedCount()).toList();
    }

    private static List<String> detail(DraftCapacityResponse r) {
        List<String> out = new ArrayList<>(rows(r));
        r.nodes().forEach(n -> out.add(n.nodeId() + "=" + n.stopCode() + " " + n.stopReason()));
        out.add(r.stopExclusionCodes());
        return out;
    }
}
