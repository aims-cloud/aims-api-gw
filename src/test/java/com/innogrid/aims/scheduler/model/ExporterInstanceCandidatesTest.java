package com.innogrid.aims.scheduler.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.NodeExporterMapping;
import com.innogrid.aims.scheduler.model.ExporterInstanceCandidates.Assignment;
import com.innogrid.aims.scheduler.model.ExporterInstanceCandidates.Candidate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 후보 분류 — 수집기 규칙({@link ExporterInstances})의 역방향이 여섯 갈래로 떨어지는지, 충돌 당사자·원장 행이 전부 보이는지,
 * 답하지 않은 질의가 뒷받침하는 칸이 비는지.
 */
class ExporterInstanceCandidatesTest {

    private static NodeExporterMapping row(String node, String dcgm, String ipmi) {
        NodeExporterMapping m = new NodeExporterMapping(node);
        m.setDcgmInstance(dcgm);
        m.setIpmiInstance(ipmi);
        return m;
    }

    private static Candidate one(List<Candidate> list, String instance) {
        return list.stream().filter(c -> c.instance().equals(instance)).findFirst().orElseThrow();
    }

    @DisplayName("다섯 갈래 — 원장 · 호스트명 · 그대로 · 둘이 가리켜 되돌림 · 어느 노드도 못 얻음, 충돌 당사자와 원장 노드는 전부 보인다")
    @Test
    void five_assignment_kinds() {
        List<String> nodes = List.of("a:9100", "b:9100", "c:9100", "d:9100", "e:9100");
        List<NodeExporterMapping> ledger = List.of(
                row("a:9100", "gpu-a:9400", null),          // 원장으로 잇는다
                row("d:9100", "c:9400", null));             // c의 호스트명 시리즈를 d가 원장으로 가리킨다 → c·d 충돌
        Map<String, Integer> dcgm = Map.of(
                "gpu-a:9400", 4,   // LEDGER (a)
                "b:9400", 2,       // HOSTNAME (b)
                "c:9400", 1,       // AMBIGUOUS (c 호스트명 · d 원장)
                "e:9100", 1,       // SAME_AS_NODE (e)
                "z:9400", 8);      // UNASSIGNED

        ExporterInstanceCandidates out = ExporterInstanceCandidates.of(ledger, dcgm, Set.of(), nodes, List.of());

        assertEquals(List.of("b:9400", "c:9400", "e:9100", "gpu-a:9400", "z:9400"),
                out.dcgm().stream().map(Candidate::instance).toList(), "instance 오름차순");

        Candidate a = one(out.dcgm(), "gpu-a:9400");
        assertEquals(Assignment.LEDGER, a.assignedBy());
        assertEquals("a:9100", a.assignedNodeId());
        assertEquals(List.of("a:9100"), a.ledgerNodeIds());
        assertEquals(List.of("a:9100"), a.claimedByNodeIds());
        assertEquals(4, a.gpuCount());
        assertEquals("gpu-a", a.host());

        Candidate b = one(out.dcgm(), "b:9400");
        assertEquals(Assignment.HOSTNAME, b.assignedBy());
        assertEquals("b:9100", b.assignedNodeId());
        assertTrue(b.ledgerNodeIds().isEmpty(), "원장 행이 없어도 호스트명으로 이어진다");

        Candidate c = one(out.dcgm(), "c:9400");
        assertEquals(Assignment.AMBIGUOUS, c.assignedBy());
        assertNull(c.assignedNodeId(), "둘이 가리키면 수집기는 둘을 자기 instance 로 되돌린다 — 이 시리즈에 붙는 노드가 없다");
        assertEquals(List.of("c:9100", "d:9100"), c.claimedByNodeIds(), "충돌 당사자 전부 — 호스트명 쪽 c 와 원장 쪽 d");
        assertEquals(List.of("d:9100"), c.ledgerNodeIds());

        Candidate e = one(out.dcgm(), "e:9100");
        assertEquals(Assignment.SAME_AS_NODE, e.assignedBy());
        assertEquals("e:9100", e.assignedNodeId());

        Candidate z = one(out.dcgm(), "z:9400");
        assertEquals(Assignment.UNASSIGNED, z.assignedBy());
        assertNull(z.assignedNodeId());
        assertTrue(z.claimedByNodeIds().isEmpty() && z.ledgerNodeIds().isEmpty());

        assertEquals(nodes, out.nodeInstances());
        assertTrue(out.unavailable().isEmpty());
    }

    @DisplayName("IPMI 후보는 gpuCount 가 null 이고, 중앙 익스포터 하나를 원장 두 행이 적으면 되돌림 충돌로 분류되며 두 원장 노드가 다 보인다")
    @Test
    void ipmi_candidates_have_no_gpu_count_and_shared_central_exporter_is_ambiguous() {
        List<String> nodes = List.of("n1:9100", "n2:9100", "n3:9100");
        List<NodeExporterMapping> ledger = List.of(row("n2:9100", null, "ipmi:9290"), row("n1:9100", null, "ipmi:9290"));

        ExporterInstanceCandidates out = ExporterInstanceCandidates.of(ledger, Map.of(), Set.of("ipmi:9290", "n3:9290"), nodes, List.of());

        Candidate central = one(out.ipmi(), "ipmi:9290");
        assertNull(central.gpuCount());
        assertEquals(Assignment.AMBIGUOUS, central.assignedBy());
        assertEquals("ipmi", central.host());
        assertEquals(List.of("n1:9100", "n2:9100"), central.ledgerNodeIds(), "원장 순서와 무관하게 오름차순으로 둘 다");
        assertEquals(List.of("n1:9100", "n2:9100"), central.claimedByNodeIds());
        Candidate n3 = one(out.ipmi(), "n3:9290");
        assertEquals(Assignment.HOSTNAME, n3.assignedBy());
        assertEquals("n3:9100", n3.assignedNodeId());
        assertTrue(out.dcgm().isEmpty());
    }

    @DisplayName("원장에만 있는 instance 는 따로 실리고, 답하지 않은 질의 이름은 그대로 전달된다")
    @Test
    void ledger_only_and_unavailable_pass_through() {
        List<NodeExporterMapping> ledger = List.of(row("n1:9100", "typo:9400", "gone:9290"), row("n2:9100", "n2:9400", null));

        ExporterInstanceCandidates out = ExporterInstanceCandidates.of(ledger, Map.of("n2:9400", 2), Set.of(), List.of("n1:9100", "n2:9100"),
                List.of());

        assertEquals(List.of("typo:9400"), out.ledgerOnlyDcgm(), "저장소에 없는 DCGM 원장값");
        assertEquals(List.of("gone:9290"), out.ledgerOnlyIpmi(), "IPMI 질의가 답했고 그 값이 없다 — 오타이거나 익스포터 다운");
        assertTrue(out.unavailable().isEmpty());
        assertEquals(Assignment.LEDGER, one(out.dcgm(), "n2:9400").assignedBy());
    }

    @DisplayName("DCGM 질의가 답하지 않으면 그쪽 ledgerOnly 는 비운다 — 못 받은 것을 '저장소에 없음' 으로 보이게 하지 않는다")
    @Test
    void unavailable_exporter_query_empties_its_ledger_only_list() {
        List<NodeExporterMapping> ledger = List.of(row("n1:9100", "n1:9400", "gone:9290"));

        ExporterInstanceCandidates out = ExporterInstanceCandidates.of(ledger, Map.of(), Set.of("n1:9290"), List.of("n1:9100"),
                List.of(ExporterInstanceCandidates.QUERY_DCGM));

        assertEquals(List.of(ExporterInstanceCandidates.QUERY_DCGM), out.unavailable());
        assertTrue(out.ledgerOnlyDcgm().isEmpty(), "DCGM 목록을 못 받았으니 n1:9400 을 오타로 몰 수 없다");
        assertEquals(List.of("gone:9290"), out.ledgerOnlyIpmi(), "IPMI 는 답했으니 대조한다");
        assertEquals(Assignment.UNASSIGNED, one(out.ipmi(), "n1:9290").assignedBy(),
                "원장이 gone:9290 을 가리켜 n1 은 원장으로 해석된다 — 호스트명 시리즈 n1:9290 은 아무도 안 쓴다");
    }

    @DisplayName("노드 목록 질의가 답하지 않으면 모든 후보의 배정은 UNKNOWN 이다 — UNASSIGNED 로 보여 원장 기입을 유도하지 않는다")
    @Test
    void unavailable_nodes_query_makes_every_assignment_unknown() {
        List<NodeExporterMapping> ledger = List.of(row("n1:9100", "n1:9400", null));

        ExporterInstanceCandidates out = ExporterInstanceCandidates.of(ledger, Map.of("n1:9400", 2, "n2:9400", 1), Set.of("n2:9290"),
                List.of(), List.of(ExporterInstanceCandidates.QUERY_NODES));

        assertEquals(3, out.dcgm().size() + out.ipmi().size());
        for (Candidate c : out.dcgm()) {
            assertEquals(Assignment.UNKNOWN, c.assignedBy(), c.toString());
            assertNull(c.assignedNodeId());
            assertTrue(c.claimedByNodeIds().isEmpty());
        }
        assertEquals(Assignment.UNKNOWN, out.ipmi().get(0).assignedBy());
        assertEquals(List.of("n1:9100"), one(out.dcgm(), "n1:9400").ledgerNodeIds(), "원장 행 자체는 노드 목록과 무관하니 보인다");
        assertTrue(out.ledgerOnlyDcgm().isEmpty(), "n1:9400 은 저장소에 있다");
        assertTrue(out.nodeInstances().isEmpty());
    }

    @DisplayName("IPv6 대괄호 instance 의 host 는 대괄호 안이고, null 원소·null 목록은 빈 값으로 정리된다")
    @Test
    void ipv6_host_and_null_tolerance() {
        Map<String, Integer> counts = new java.util.HashMap<>();
        counts.put("[::1]:9400", 1);
        counts.put(null, 9);
        ExporterInstanceCandidates out = ExporterInstanceCandidates.of(null, counts, java.util.Arrays.asList("x:9290", null),
                java.util.Arrays.asList("[::1]:9100", null), null);

        Candidate v6 = one(out.dcgm(), "[::1]:9400");
        assertEquals("::1", v6.host());
        assertEquals(Assignment.HOSTNAME, v6.assignedBy());
        assertEquals(1, out.dcgm().size(), "null 키는 버린다");
        assertEquals(List.of("[::1]:9100"), out.nodeInstances());
        assertEquals(1, out.ipmi().size());
        assertTrue(out.ledgerOnlyDcgm().isEmpty() && out.unavailable().isEmpty());
    }
}
