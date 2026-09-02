package com.innogrid.aims.scheduler.controller;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.innogrid.aims.scheduler.dto.ResponseJsonGoldenTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 단계 13 이 바꾸는 응답 계약을 고정한다 — 요약 9필드(+2), 전환 응답 2필드, 정책 응답 20필드 불변, `/history` 배열 불변.
 * 테스트 환경은 메트릭 저장소가 없어 노드가 0대 — 요약의 최소·최대는 null 이지만 키는 있어야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class PolicyRulesContractTest {

    /** 전력 효율 요약 필드 9개. */
    static final Set<String> SUMMARY_FIELDS = new TreeSet<>(Set.of(
            "totalNodes", "evaluableNodes", "averagePeiRaw", "minPeiRaw", "maxPeiRaw",
            "totalPowerWatts", "totalCapacityWatts", "gradeAvailable", "gradeUnavailableReason"));

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void 전력_효율_요약은_9필드이고_노드가_없으면_최소_최대가_null_키는_유지() throws Exception {
        JsonNode summary = data(get("/api/v1/scheduler/nodes/power-efficiency")).get("summary");

        assertEquals(SUMMARY_FIELDS, keys(summary));
        assertTrue(summary.get("minPeiRaw").isNull());
        assertTrue(summary.get("maxPeiRaw").isNull());
    }

    @Test
    void 전환_응답은_activated_와_deactivated_두_필드이고_정책_응답은_20필드_그대로() throws Exception {
        String name = "contract-act-" + System.nanoTime();
        JsonNode created = data(post("/api/v1/scheduler/policies").contentType("application/json")
                .content("{\"name\":\"" + name + "\",\"resourceType\":\"NPU\",\"active\":false}"));
        try {
            assertEquals(ResponseJsonGoldenTest.POLICY_FIELDS, keys(created), "정책 응답 20필드 불변");

            JsonNode act = data(post("/api/v1/scheduler/policies/" + created.get("id").asLong() + "/activate"));
            assertEquals(new TreeSet<>(List.of("activated", "deactivated")), keys(act));
            assertEquals(ResponseJsonGoldenTest.POLICY_FIELDS, keys(act.get("activated")));
            assertTrue(act.get("deactivated").isArray());
        } finally {
            mockMvc.perform(delete("/api/v1/scheduler/policies/" + created.get("id").asLong()));
        }
    }

    @Test
    void 이력_응답은_배열이고_페이지_응답은_객체다() throws Exception {
        assertTrue(data(get("/api/v1/scheduler/history")).isArray());
        JsonNode page = data(get("/api/v1/scheduler/history/page"));
        assertTrue(page.isObject());
        assertEquals(new TreeSet<>(List.of("items", "page", "size", "totalElements", "totalPages")), keys(page));
        assertEquals(20, page.get("size").asInt(), "기본 size");
    }

    private JsonNode data(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) throws Exception {
        String body = mockMvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    private static TreeSet<String> keys(JsonNode n) {
        TreeSet<String> k = new TreeSet<>();
        n.propertyNames().forEach(k::add);
        return k;
    }
}
