package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.innogrid.aims.scheduler.dto.PowerEfficiencyResponse;
import com.innogrid.aims.scheduler.model.NodeResource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 요약의 절대 기준값 — 상대 등급(4분위)은 클러스터 전체가 비효율이어도 누군가는 A 를 받는다.
 * 화면이 "A 지만 최대가 0.05%/W" 를 보여 줄 수 있도록 평가 가능 노드의 최소·최대 PEI 원값을 함께 낸다(12장 T1 → 단계 13).
 */
@SpringBootTest
class PowerEfficiencySummaryRangeTest {

    private static final double EPS = 1e-4;

    @Autowired
    PowerEfficiencyService service;

    @DisplayName("최소 최대 PEI 원값은 평가 가능 노드에서만 계산한다")
    @Test
    void min_max_pei_from_evaluable_only() {
        PowerEfficiencyResponse.Summary s = service.evaluate(List.of(
                node("n1", 80, 200),      // 0.40
                node("n2", 10, 200),      // 0.05
                node("n3", 50, 200),      // 0.25
                node("n4", 90, 0)))       // IPMI 없음 — 평가 불가, 범위에서 제외
                .summary();

        assertEquals(3, s.evaluableNodes());
        assertEquals(0.05, s.minPeiRaw(), EPS);
        assertEquals(0.40, s.maxPeiRaw(), EPS);
        assertTrue(s.minPeiRaw() <= s.averagePeiRaw() && s.averagePeiRaw() <= s.maxPeiRaw());
    }

    @DisplayName("평가 가능 노드가 없으면 최소 최대는 null")
    @Test
    void no_evaluable_node_min_max_null() {
        PowerEfficiencyResponse.Summary s = service.evaluate(List.of(node("n1", 50, 0), node("n2", 60, 0))).summary();

        assertEquals(0, s.evaluableNodes());
        assertNull(s.minPeiRaw());
        assertNull(s.maxPeiRaw());
        assertNull(s.averagePeiRaw());
    }

    @DisplayName("노드가 하나면 최소와 최대가 같다")
    @Test
    void single_node_min_equals_max() {
        PowerEfficiencyResponse.Summary s = service.evaluate(List.of(node("n1", 40, 200))).summary();
        assertEquals(s.minPeiRaw(), s.maxPeiRaw());
        assertEquals(0.20, s.maxPeiRaw(), EPS);
    }

    private static NodeResource node(String id, double cpu, double watts) {
        return new NodeResource("mdc-01", id, 8, 16384, 0, cpu, 30, 0, watts, 500, 40, 0, 0);
    }
}
