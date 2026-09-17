package com.innogrid.aims.scheduler.engine.constraint;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.entity.MdcLink;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.LinkMatrix;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 제외 까닭 코드 — 문장과 짝이 맞는가, 후보 응답에 실리는가, 묶음 판정이 맞는가.
 */
class ExclusionCodeTest {

    private final ConstraintEvaluator evaluator = ConstraintEvaluator.withDefaults();

    @DisplayName("까닭과 문장이 짝으로 나오고 서로 어긋나지 않는다")
    @Test
    void code_and_message_come_as_a_pair() {
        Exclusion cpu = evaluator.exclusion(workload(1, 512), busyNode(95, 10), ctx(busyNode(95, 10)));
        assertNotNull(cpu);
        assertEquals(ExclusionReason.CPU_USAGE_EXCEEDED, cpu.reason());
        assertTrue(cpu.message().startsWith("CPU 사용률 초과"), cpu.message());

        Exclusion memory = evaluator.exclusion(workload(1, 512), busyNode(10, 95), ctx(busyNode(10, 95)));
        assertEquals(ExclusionReason.MEMORY_USAGE_EXCEEDED, memory.reason());
        assertTrue(memory.message().startsWith("메모리 사용률 초과"), memory.message());

        assertNull(evaluator.exclusion(workload(1, 512), idleNode(), ctx(idleNode())), "적격이면 제외가 없다");
    }

    @DisplayName("같은 검사를 두 번 부르지 않아도 문장과 까닭을 함께 얻는다")
    @Test
    void the_message_accessor_delegates_to_the_same_check() {
        NodeResource node = busyNode(95, 10);
        PlacementContext ctx = ctx(node);

        assertEquals(evaluator.exclusion(workload(1, 512), node, ctx).message(),
                evaluator.exclusionReason(workload(1, 512), node, ctx));
    }

    @DisplayName("후보 상세에 까닭 코드가 실리고 적격 후보는 비어 있다")
    @Test
    void candidate_carries_the_code() {
        NodeResource busy = busyNode(95, 10);
        NodeResource idle = idleNode();
        SchedulingResult r = new WeightedScoreScheduler().schedule(workload(1, 512),
                PlacementContext.of(List.of(busy, idle), new SchedulingPolicy("p", "d"),
                        ConstraintEvaluator.withDefaults()));

        NodeScore excluded = byNodeId(r, busy.getNodeId());
        assertFalse(excluded.eligible());
        assertEquals(ExclusionReason.CPU_USAGE_EXCEEDED, excluded.exclusionCode());
        assertTrue(excluded.exclusionReason().startsWith("CPU 사용률 초과"));

        NodeScore ok = byNodeId(r, idle.getNodeId());
        assertTrue(ok.eligible());
        assertNull(ok.exclusionCode(), "적격 후보는 까닭이 없다");
        assertNull(ok.exclusionReason());
    }

    @DisplayName("열·전력 묶음 판정이 까닭을 빠짐없이 가른다")
    @Test
    void thermal_and_power_groups_cover_their_members() {
        Set<ExclusionReason> thermal = EnumSet.of(
                ExclusionReason.COOLING_DOWN,
                ExclusionReason.TEMPERATURE_EXCEEDED,
                ExclusionReason.WORKLOAD_TEMPERATURE_EXCEEDED,
                ExclusionReason.PREDICTED_TEMPERATURE_EXCEEDED);
        Set<ExclusionReason> power = EnumSet.of(
                ExclusionReason.POWER_HEADROOM_EXCEEDED,
                ExclusionReason.WORKLOAD_POWER_EXCEEDED,
                ExclusionReason.RUN_POWER_BUDGET_EXCEEDED,
                ExclusionReason.PREDICTED_POWER_EXCEEDED,
                ExclusionReason.MDC_POWER_BUDGET_EXCEEDED);

        for (ExclusionReason r : ExclusionReason.values()) {
            assertEquals(thermal.contains(r), r.isThermal(), r + " 의 열 묶음 판정");
            assertEquals(power.contains(r), r.isPower(), r + " 의 전력 묶음 판정");
            assertFalse(r.isThermal() && r.isPower(), r + " 가 두 묶음에 동시에 들었다");
        }
        assertEquals(14, ExclusionReason.values().length, "까닭을 더하거나 빼면 이 수와 묶음 판정을 함께 본다");
    }

    @DisplayName("검사마다 제 까닭이 붙는다")
    @Test
    void every_check_reports_its_own_code() {
        // 디스크 — 자원 까닭과 갈라져야 한다
        Workload disk = workload(1, 512);
        disk.setRequiredDiskGb(5000);
        NodeResource small = idleNode().withDiskCapacityGb(2000);
        assertEquals(ExclusionReason.INSUFFICIENT_DISK,
                evaluator.exclusion(disk, small, ctx(small)).reason());

        // 자원 — 디스크와 갈라져야 한다
        assertEquals(ExclusionReason.INSUFFICIENT_RESOURCE,
                evaluator.exclusion(workload(999, 512), idleNode(), ctx(idleNode())).reason());

        // 링크 단절 — 열·전력 어느 묶음에도 들지 않는다
        NodeResource remote = new NodeResource("mdc-b", "n-remote", 64, 65536, 0, 0, 0, 15, 400, 1000, 40, 0, 0);
        SchedulingPolicy affinity = new SchedulingPolicy("p", "d");
        affinity.setAffinityEnabled(true);
        Workload dataBound = workload(1, 512);
        dataBound.setDataMdcId("mdc-a");
        MdcLink down = new MdcLink("mdc-a", "mdc-b", 5);
        down.setStatus(MdcLink.LinkStatus.DOWN);
        Exclusion link = evaluator.exclusion(dataBound, remote,
                PlacementContext.of(List.of(remote), affinity, ConstraintEvaluator.withDefaults())
                        .withLinkMatrix(LinkMatrix.of(List.of(down))));
        assertEquals(ExclusionReason.MDC_LINK_DOWN, link.reason());
        assertFalse(link.reason().isThermal(), "링크 단절은 열 막힘이 아니다");
        assertFalse(link.reason().isPower(), "링크 단절은 전력 막힘이 아니다");

        // 워크로드 온도 상한 — 정책 온도 초과와 갈라져야 한다
        Workload tempLimited = workload(1, 512);
        tempLimited.setMaxTemperatureCelsius(50.0);
        NodeResource warm = new NodeResource("mdc-a", "n-warm", 64, 65536, 0, 0, 0, 15, 400, 1000, 70, 0, 0);
        assertEquals(ExclusionReason.WORKLOAD_TEMPERATURE_EXCEEDED,
                evaluator.exclusion(tempLimited, warm, ctx(warm)).reason());
    }

    @DisplayName("까닭 없는 제외나 빈 문장은 만들 수 없다")
    @Test
    void an_exclusion_needs_both_parts() {
        assertThrows(IllegalArgumentException.class, () -> new Exclusion(null, "사유"));
        assertThrows(IllegalArgumentException.class, () -> new Exclusion(ExclusionReason.COOLING_DOWN, null));
        assertThrows(IllegalArgumentException.class, () -> new Exclusion(ExclusionReason.COOLING_DOWN, " "));

        assertNull(Exclusion.messageOf(null), "제외가 아니면 문장도 없다");
        assertNull(Exclusion.reasonOf(null));
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private static NodeScore byNodeId(SchedulingResult r, String nodeId) {
        return r.candidateScores().stream().filter(s -> s.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    private static PlacementContext ctx(NodeResource node) {
        return PlacementContext.of(List.of(node), new SchedulingPolicy("p", "d"), ConstraintEvaluator.withDefaults());
    }

    private static Workload workload(int cpu, int memoryMb) {
        return new Workload("w", cpu, memoryMb);
    }

    private static NodeResource idleNode() {
        return new NodeResource("mdc-a", "n-idle", 64, 65536, 0, 0, 0, 15, 400, 1000, 40, 0, 0);
    }

    private static NodeResource busyNode(double cpu, double memory) {
        return new NodeResource("mdc-a", "n-busy", 64, 65536, 0, cpu, memory, 15, 400, 1000, 40, 0, 0);
    }
}
