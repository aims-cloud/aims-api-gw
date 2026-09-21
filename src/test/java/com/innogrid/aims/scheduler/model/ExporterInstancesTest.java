package com.innogrid.aims.scheduler.model;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.NodeExporterMapping;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 익스포터 instance 해석 — 세 단계가 어느 순서로 떨어지고, 애매하면 무엇을 고르지 않는지.
 *
 * <p>배포 서버 실측(2026-09-21)을 그대로 판으로 쓴다: 노드 {@code localhost:9100}, DCGM {@code mdc1-gpu-node1:9400},
 * IPMI {@code ipmi-exporter:9290}. 이 세 값이 원장 없이 이어지지 않는다는 것이 이 브랜치의 출발점이다.</p>
 */
class ExporterInstancesTest {

    private static NodeExporterMapping mapping(String node, String dcgm, String ipmi) {
        NodeExporterMapping m = new NodeExporterMapping(node);
        m.setDcgmInstance(dcgm);
        m.setIpmiInstance(ipmi);
        return m;
    }

    @DisplayName("배포 서버 형태는 원장 없이 이어지지 않는다 — 이것이 실장비에서 GPU 가 0 이던 까닭이다")
    @Test
    void the_real_server_shape_does_not_join_without_a_ledger() {
        ExporterInstances r = ExporterInstances.of(List.of(),
                Set.of("mdc1-gpu-node1:9400"), Set.of("ipmi-exporter:9290"));

        assertEquals("localhost:9100", r.dcgmOf("localhost:9100"), "호스트가 달라 이을 수 없으면 그대로 둔다");
        assertEquals("localhost:9100", r.ipmiOf("localhost:9100"));
        assertFalse(r.resolvedDcgm("localhost:9100"));
        assertFalse(r.resolvedIpmi("localhost:9100"));
    }

    @DisplayName("원장이 있으면 원장이 이긴다 — 중앙 IPMI 는 이 길밖에 없다")
    @Test
    void the_ledger_wins() {
        ExporterInstances r = ExporterInstances.of(
                List.of(mapping("localhost:9100", "mdc1-gpu-node1:9400", "ipmi-exporter:9290")),
                Set.of("mdc1-gpu-node1:9400"), Set.of("ipmi-exporter:9290"));

        assertEquals("mdc1-gpu-node1:9400", r.dcgmOf("localhost:9100"));
        assertEquals("ipmi-exporter:9290", r.ipmiOf("localhost:9100"));
        assertTrue(r.resolvedDcgm("localhost:9100"));
        assertTrue(r.resolvedIpmi("localhost:9100"));
    }

    @DisplayName("원장이 없어도 호스트가 같고 포트만 다르면 잇는다 — DCGM 이 GPU 노드에서 도는 흔한 배치")
    @Test
    void a_same_host_different_port_is_joined_without_a_ledger() {
        ExporterInstances r = ExporterInstances.of(List.of(),
                Set.of("seoul-02:9400", "busan-03:9400"), Set.of("seoul-02:9290"));

        assertEquals("seoul-02:9400", r.dcgmOf("seoul-02:9100"));
        assertEquals("seoul-02:9290", r.ipmiOf("seoul-02:9100"));
        assertEquals("seoul-01:9100", r.dcgmOf("seoul-01:9100"), "그 호스트의 DCGM 이 없으면 그대로");
    }

    @DisplayName("같은 호스트에 후보가 둘이면 고르지 않는다 — 틀린 노드 값을 끌어오는 것보다 미계측이 낫다")
    @Test
    void two_candidates_on_one_host_are_not_chosen() {
        ExporterInstances r = ExporterInstances.of(List.of(),
                Set.of("seoul-02:9400", "seoul-02:9401"), Set.of());

        assertEquals("seoul-02:9100", r.dcgmOf("seoul-02:9100"));
        assertFalse(r.resolvedDcgm("seoul-02:9100"));
    }

    @DisplayName("저장소가 노드 instance 그대로 시리즈를 내면 그것을 쓴다 — 옛 모의 형태와 같은 포트 배치")
    @Test
    void an_identical_instance_in_the_store_is_used_as_is() {
        ExporterInstances r = ExporterInstances.of(List.of(),
                Set.of("seoul-02:9100"), Set.of("seoul-02:9100"));

        assertEquals("seoul-02:9100", r.dcgmOf("seoul-02:9100"));
        assertTrue(r.resolvedDcgm("seoul-02:9100"), "같은 문자열로 시리즈가 있으면 이어진 것이다");
    }

    @DisplayName("원장의 빈 칸은 없는 것으로 보고 다음 단계로 떨어진다")
    @Test
    void blank_ledger_cells_fall_through() {
        ExporterInstances r = ExporterInstances.of(
                List.of(mapping("seoul-02:9100", "  ", null)),
                Set.of("seoul-02:9400"), Set.of());

        assertEquals("seoul-02:9400", r.dcgmOf("seoul-02:9100"), "빈 원장 칸은 호스트명 일치로 넘어간다");
        assertEquals("seoul-02:9100", r.ipmiOf("seoul-02:9100"), "null 칸도 같다 — 저장소에 IPMI 가 없어 그대로");
    }

    @DisplayName("호스트 추출 — 포트 유무, IPv6 대괄호, 콜론 여러 개")
    @Test
    void host_extraction() {
        assertEquals("seoul-02", ExporterInstances.hostOf("seoul-02:9100"));
        assertEquals("seoul-02", ExporterInstances.hostOf("seoul-02"));
        assertEquals("10.0.0.5", ExporterInstances.hostOf("10.0.0.5:9400"));
        assertEquals("::1", ExporterInstances.hostOf("[::1]:9100"));
        assertEquals("fe80::1", ExporterInstances.hostOf("fe80::1"), "콜론이 여럿이고 대괄호가 없으면 포트 없는 IPv6");
        assertEquals("", ExporterInstances.hostOf(null));
    }

    @DisplayName("비어 있으면 모두 그대로다 — 이 브랜치 이전과 같은 동작")
    @Test
    void empty_resolves_everything_to_itself() {
        assertEquals("n1:9100", ExporterInstances.EMPTY.dcgmOf("n1:9100"));
        assertEquals("n1:9100", ExporterInstances.EMPTY.ipmiOf("n1:9100"));
        assertNull(ExporterInstances.EMPTY.dcgmOf(null));
    }
}
