package com.innogrid.aims.scheduler.engine;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 디스크 제약 시나리오 — 총량이 다른 노드 3대에 큰 디스크 워크로드를 연속 배치.
 *
 * <pre>
 * 입력   A 디스크 100 GB · B 500 GB · C 총량 모름(0)   자원(CPU 10%·8코어) 동일, 디스크 사용률 0
 *        200 GB × 2코어 워크로드 4건 (weighted)
 * 예상   w1 → B   (A 는 100 < 200 으로 제외, B·C 동점이면 앞 순서 B)
 *        w2 → C   (B 는 예약 2코어로 실효 CPU 35% → C 가 높음. C 는 총량 모름 → 디스크 검사 건너뜀)
 *        w3 → B   (B 가용 300 ≥ 200. B·C 실효 동일 → 앞 순서 B)
 *        w4 → C   (B 가용 100 < 200 제외 → C)
 *        A 는 한 번도 선택되지 않고, B 의 가용 디스크는 500 → 100 으로 내려간다
 * </pre>
 */
class DiskConstraintScenarioTest {

    @DisplayName("시나리오 디스크 총량이 다른 노드에 200GB 워크로드 4건")
    @Test
    void scenario_200gb_workloads_mixed_disks() {
        NodeResource a = node("A").withDiskCapacityGb(100);
        NodeResource b = node("B").withDiskCapacityGb(500);
        NodeResource c = node("C");                                    // 총량 모름
        PlacementContext ctx = PlacementContext.of(List.of(a, b, c), new SchedulingPolicy("t", "t"));
        WeightedScoreScheduler engine = new WeightedScoreScheduler();

        List<String> placed = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            Workload w = new Workload("w" + i, 2, 1024);
            w.setRequiredDiskGb(200);
            SchedulingResult r = engine.schedule(w, ctx);
            assertTrue(r.success(), "w" + i + " 실패: " + r.reason());
            ctx.reserve(r.selectedNodeId(), w);
            placed.add(r.selectedNodeId());
            System.out.printf("  w%d → %s   (A 제외사유: %s | B 가용 %dGB | C 총량 %dGB)%n", i, r.selectedNodeId(),
                    r.candidateScores().stream().filter(s -> s.nodeId().equals("A")).findFirst()
                            .map(s -> s.exclusionReason() == null ? "없음" : s.exclusionReason()).orElse("?"),
                    b.availableDiskGb(), c.diskCapacityGb());
        }

        System.out.println("\n[디스크 시나리오] 배치 순서 " + placed);
        assertEquals(List.of("B", "C", "B", "C"), placed);
        assertFalse(placed.contains("A"), "총량 100 GB 노드는 200 GB 요구를 받을 수 없다");
        assertEquals(100, b.availableDiskGb(), "B 의 가용 디스크는 두 번 예약으로 500 → 100");
        assertEquals(0, c.diskCapacityGb(), "C 는 여전히 총량 모름 — 검사 없이 받았다");
    }

    @DisplayName("같은 시나리오의 후보 상세 — A·B 는 디스크검사 1.0, 총량 모름 C 는 0.0")
    @Test
    void candidate_details_mark_which_nodes_were_checked() {
        NodeResource a = node("A").withDiskCapacityGb(100);
        NodeResource b = node("B").withDiskCapacityGb(500);
        NodeResource c = node("C");
        PlacementContext ctx = PlacementContext.of(List.of(a, b, c), new SchedulingPolicy("t", "t"));
        Workload w = new Workload("w", 2, 1024);
        w.setRequiredDiskGb(200);

        SchedulingResult r = new WeightedScoreScheduler().schedule(w, ctx);

        for (SchedulingResult.NodeScore s : r.candidateScores()) {
            double expected = s.nodeId().equals("C") ? 0.0 : 1.0;
            assertEquals(expected, s.details().get("디스크검사"), s.nodeId());
        }
        assertFalse(r.candidateScores().stream().filter(s -> s.nodeId().equals("A")).findFirst().orElseThrow().eligible(), "A 는 검사에서 걸렸다");
    }

    private static NodeResource node(String id) {
        return new NodeResource("mdc-01", id, 8, 16384, 0, 10, 20, 0, 200, 500, 45, 1000, 500);
    }
}
