package com.innogrid.aims.scheduler.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.innogrid.aims.scheduler.dto.ResponseJsonGoldenTest;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /scheduler/history/page} — 페이지 경계·필터·정렬·상한, 그리고 기존 {@code /history} 가 배열로 그대로인가.
 *
 * <p>이력 25건(엔진 weighted 15·power 10, 성공 20·실패 5)을 심고 끝에 지운다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class HistoryPageEndpointTest {

    private static final String PAGE = "/api/v1/scheduler/history/page";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SchedulingHistoryRepository repository;

    private final List<Long> seeded = new ArrayList<>();
    private static final long WORKLOAD_A = 9_900_001L;
    private static final long WORKLOAD_B = 9_900_002L;

    @BeforeEach
    void seed() {
        for (int i = 0; i < 25; i++) {
            SchedulingHistory h = new SchedulingHistory(i < 12 ? WORKLOAD_A : WORKLOAD_B, "hp-" + String.format("%02d", i),
                    "mdc-01", "n1", i / 100.0, "r", i < 15 ? "weighted" : "power", "default");
            h.linkToRun(null, i % 5 != 0);   // 0,5,10,15,20 실패 → 5건 (실행 연결 없이 결과만)
            seeded.add(repository.save(h).getId());
        }
    }

    @AfterEach
    void clean() {
        repository.deleteAllById(seeded);
    }

    @DisplayName("페이지 응답은 5필드이고 경계가 맞다")
    @Test
    void page_response_5_fields() throws Exception {
        JsonNode d = data(get(PAGE).param("size", "10").param("workloadId", String.valueOf(WORKLOAD_A)));

        assertEquals(new TreeSet<>(List.of("items", "page", "size", "totalElements", "totalPages")), keys(d));
        assertEquals(12, d.get("totalElements").asInt());
        assertEquals(2, d.get("totalPages").asInt());
        assertEquals(10, d.get("items").size());
        assertEquals(1, d.get("page").asInt());

        JsonNode last = data(get(PAGE).param("size", "10").param("page", "2").param("workloadId", String.valueOf(WORKLOAD_A)));
        assertEquals(2, last.get("items").size(), "마지막 페이지는 나머지 2건");

        JsonNode beyond = data(get(PAGE).param("size", "10").param("page", "3").param("workloadId", String.valueOf(WORKLOAD_A)));
        assertEquals(0, beyond.get("items").size(), "범위 밖 페이지는 빈 목록, 오류 아님");
    }

    @DisplayName("항목은 이력 응답 22필드 계약을 그대로 쓴다")
    @Test
    void items_22_field_contract() throws Exception {
        JsonNode d = data(get(PAGE).param("size", "1").param("workloadId", String.valueOf(WORKLOAD_A)));
        assertEquals(ResponseJsonGoldenTest.HISTORY_FIELDS, keys(d.get("items").get(0)));
    }

    @DisplayName("필터는 각각 그리고 함께 적용된다")
    @Test
    void filters_single_and_combined() throws Exception {
        assertEquals(10, data(get(PAGE).param("size", "100").param("engineName", "power")).get("totalElements").asInt());
        // 실패 5건 중 심은 것만 세기 위해 workloadId 와 함께
        assertEquals(3, data(get(PAGE).param("size", "100").param("success", "false")
                .param("workloadId", String.valueOf(WORKLOAD_A))).get("totalElements").asInt(), "A 의 실패: 0,5,10");
        assertEquals(2, data(get(PAGE).param("size", "100").param("success", "false")
                .param("workloadId", String.valueOf(WORKLOAD_B))).get("totalElements").asInt(), "B 의 실패: 15,20");
        assertEquals(0, data(get(PAGE).param("size", "100").param("engineName", "power")
                .param("workloadId", String.valueOf(WORKLOAD_A))).get("totalElements").asInt(), "A 는 전부 weighted");
    }

    @DisplayName("정렬은 허용 필드만 되고 나머지는 400")
    @Test
    void unpermitted_sort_field_400() throws Exception {
        JsonNode asc = data(get(PAGE).param("size", "3").param("sort", "finalScore,asc").param("workloadId", String.valueOf(WORKLOAD_A)));
        assertEquals("hp-00", asc.get("items").get(0).get("workloadName").asString());
        JsonNode desc = data(get(PAGE).param("size", "3").param("sort", "finalScore,desc").param("workloadId", String.valueOf(WORKLOAD_A)));
        assertEquals("hp-11", desc.get("items").get(0).get("workloadName").asString());

        mockMvc.perform(get(PAGE).param("sort", "nope,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @DisplayName("size 상한 100 과 page 최소 1 을 넘으면 400")
    @Test
    void size_over_100_or_page_0_400() throws Exception {
        mockMvc.perform(get(PAGE).param("size", "101")).andExpect(status().isBadRequest());
        mockMvc.perform(get(PAGE).param("page", "0")).andExpect(status().isBadRequest());
    }

    @DisplayName("기존 history 는 여전히 배열이고 전량이다")
    @Test
    void existing_history_full_array() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[?(@.workloadName == 'hp-24')]").exists());
        // 페이지 총합은 배열 길이와 같다
        int arrayLength = objectMapper.readTree(mockMvc.perform(get("/api/v1/scheduler/history")).andReturn()
                .getResponse().getContentAsString()).get("data").size();
        assertEquals(arrayLength, data(get(PAGE).param("size", "1")).get("totalElements").asInt());
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

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
