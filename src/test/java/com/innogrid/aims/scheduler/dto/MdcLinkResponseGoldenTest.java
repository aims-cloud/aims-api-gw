package com.innogrid.aims.scheduler.dto;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.innogrid.aims.scheduler.entity.MdcLink;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MDC 링크 응답의 JSON 계약(필드 15개)과 매트릭스 형태를 고정한다. 프런트가 붙기 전에 못 박는 기준선.
 * 스프링이 구성한 Jackson 3 매퍼로 직렬화한다 — HTTP 응답을 만드는 것이 그 매퍼다.
 */
@SpringBootTest
public class MdcLinkResponseGoldenTest {

    /** 링크 응답 필드 15개. */
    public static final Set<String> LINK_FIELDS = new TreeSet<>(Set.of(
            "id", "mdcA", "mdcB", "latencyMs", "bandwidthMbps", "packetLossPercent",
            "status", "source", "description",
            "createdBy", "createdByName", "updatedBy", "updatedByName", "createdAt", "updatedAt"));

    @Autowired
    ObjectMapper objectMapper;

    @DisplayName("링크 응답은 15개 필드를 항상 낸다 null 도 키를 유지")
    @Test
    void link_response_15_fields() {
        MdcLink link = new MdcLink("mdc-02", "mdc-01", 20);
        Map<String, Object> json = objectMapper.readValue(
                objectMapper.writeValueAsString(MdcLinkResponse.from(link)), Map.class);

        assertEquals(LINK_FIELDS, new TreeSet<>(json.keySet()));
        assertEquals("mdc-01", json.get("mdcA"), "정렬된 쌍");
        assertEquals(20.0, json.get("latencyMs"));
        assertEquals("UP", json.get("status"));
        assertEquals("MANUAL", json.get("source"));
        assertTrue(json.containsKey("bandwidthMbps") && json.get("bandwidthMbps") == null, "모르는 값도 키는 남긴다");
    }

    @DisplayName("매트릭스는 링크에 나오는 MDC 만 사전순으로 모아 대칭 행렬을 만든다")
    @Test
    void matrix_linked_mdcs_symmetric() {
        List<MdcLink> links = List.of(
                new MdcLink("mdc-3", "mdc-1", 80),
                new MdcLink("mdc-1", "mdc-2", 10));

        MdcLinkMatrixResponse m = MdcLinkMatrixResponse.of(links);

        assertEquals(List.of("mdc-1", "mdc-2", "mdc-3"), m.mdcIds());
        assertEquals(3, m.links().size());
        for (int i = 0; i < 3; i++) {
            assertEquals(3, m.links().get(i).size());
            assertNull(m.links().get(i).get(i), "대각은 null");
        }
        assertEquals(10.0, m.links().get(0).get(1).latencyMs());
        assertSame(m.links().get(0).get(1), m.links().get(1).get(0), "대칭 — 같은 칸 객체");
        assertEquals(80.0, m.links().get(2).get(0).latencyMs());
        assertNull(m.links().get(1).get(2), "미등록 쌍은 null");
    }

    @DisplayName("링크가 없으면 빈 매트릭스")
    @Test
    void no_links_empty_matrix() {
        MdcLinkMatrixResponse m = MdcLinkMatrixResponse.of(List.of());
        assertTrue(m.mdcIds().isEmpty());
        assertTrue(m.links().isEmpty());
        assertEquals("{\"mdcIds\":[],\"links\":[]}", objectMapper.writeValueAsString(m));
    }
}
