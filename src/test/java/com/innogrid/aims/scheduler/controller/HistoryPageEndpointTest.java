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
    /** {@code SchedulerController.HISTORY_MAX} 와 같다. {@link HistoryReturnLimitTest} 가 그 값을 고정한다. */
    private static final int HISTORY_RETURN_MAX = 1000;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SchedulingHistoryRepository repository;

    private final List<Long> seeded = new ArrayList<>();
    private static final long WORKLOAD_A = 9_900_001L;
    private static final long WORKLOAD_B = 9_900_002L;
    /** 위 둘의 건수 단언을 건드리지 않으려고 따로 쓰는 id. */
    private static final long WORKLOAD_C = 9_900_003L;

    @BeforeEach
    void seed() {
        for (int i = 0; i < 25; i++) {
            SchedulingHistory h = new SchedulingHistory(i < 12 ? WORKLOAD_A : WORKLOAD_B, "hp-" + String.format("%02d", i),
                    "mdc-01", "n1", i / 100.0, "r", i < 15 ? "weighted" : "power", "default");
            h.linkToRun(null, i % 5 != 0);   // 0,5,10,15,20 실패 → 5건 (실행 연결 없이 결과만)
            if (i == 3) h.setExclusionCodes("{\"INSUFFICIENT_RESOURCE\":2}");   // 한 행만 집계 값 — DB 왕복 확인용
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

    @DisplayName("항목은 이력 응답 23필드 계약을 그대로 쓴다")
    @Test
    void items_23_field_contract() throws Exception {
        JsonNode d = data(get(PAGE).param("size", "1").param("workloadId", String.valueOf(WORKLOAD_A)));
        assertEquals(ResponseJsonGoldenTest.HISTORY_FIELDS, keys(d.get("items").get(0)));
    }

    @DisplayName("제외 코드 집계는 DB 를 거쳐 문자열 그대로 돌아오고, 값이 없는 행은 null 이다")
    @Test
    void exclusion_codes_survive_the_database_round_trip() throws Exception {
        JsonNode d = data(get(PAGE).param("size", "25").param("workloadId", String.valueOf(WORKLOAD_A)));
        JsonNode withCodes = null, without = null;
        for (JsonNode item : d.get("items")) {
            if (item.get("workloadName").asString().equals("hp-03")) withCodes = item;
            else if (without == null) without = item;
        }
        assertNotNull(withCodes, "씨앗 hp-03 이 있어야 한다");
        assertEquals("{\"INSUFFICIENT_RESOURCE\":2}", withCodes.get("exclusionCodes").asString(), "TEXT 열 왕복 뒤에도 문자열 그대로");
        assertNotNull(without);
        assertTrue(without.get("exclusionCodes").isNull(), "집계가 없는 행은 null");
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

    /**
     * {@code success} 가 NULL 인 행은 두 가지다 — 이 컬럼이 생기기 전에 쌓인 행, 그리고 단건 배치가 실행 연결 없이
     * 만든 옛 행(지금은 둘 다 값을 남긴다). 실패한 결정은 언제나 명시적 false 로 저장되므로 <b>NULL 은 전부 성공</b>
     * 인데, 조회 필터만 그 해석을 따르지 않아 성공한 결정이 통째로 빠졌다.
     *
     * <p>다른 시드(WORKLOAD_A·B, 엔진 weighted·power)를 건드리지 않도록 <b>제3의 워크로드 id 와 다른 엔진명</b>
     * 으로 심는다 — 그러지 않으면 위의 건수 단언들이 함께 깨진다.</p>
     */
    @DisplayName("성공을 물으면 성공 여부가 비어 있는 행도 함께 나온다 — 실패를 물을 때는 아니다")
    @Test
    void success_filter_includes_null_rows() throws Exception {
        SchedulingHistory unlinked = new SchedulingHistory(WORKLOAD_C, "hp-null", "mdc-01", "n1",
                0.5, "r", "solo", "default");   // linkToRun 을 부르지 않는다 → success 가 NULL
        Long id = repository.save(unlinked).getId();
        try {
            assertEquals(1, data(get(PAGE).param("size", "100").param("success", "true")
                            .param("workloadId", String.valueOf(WORKLOAD_C))).get("totalElements").asInt(),
                    "성공 여부가 비어 있는 행이 '성공' 필터에서 빠졌다");
            assertEquals(0, data(get(PAGE).param("size", "100").param("success", "false")
                            .param("workloadId", String.valueOf(WORKLOAD_C))).get("totalElements").asInt(),
                    "비어 있는 행을 '실패' 로도 세면 없는 실패를 발명하는 것이다");
        } finally {
            repository.deleteById(id);
        }
    }

    /**
     * 필터가 최상위 OR 로 잘못 조립되면 다른 조건이 무력화된다 — 그러면 위 단언은 통과하면서
     * {@code success=true} 에 {@code workloadId} 를 함께 준 결과가 전체 행으로 부풀어 오른다.
     * 그래서 조합 필터의 건수와, 성공·실패 두 필터의 합이 전체와 같은지를 함께 본다.
     */
    @DisplayName("성공·실패 필터의 합이 전체와 같고, 다른 조건과 함께 걸어도 무너지지 않는다")
    @Test
    void success_filter_partitions_and_combines() throws Exception {
        int total = data(get(PAGE).param("size", "1")).get("totalElements").asInt();
        int ok = data(get(PAGE).param("size", "1").param("success", "true")).get("totalElements").asInt();
        int failed = data(get(PAGE).param("size", "1").param("success", "false")).get("totalElements").asInt();

        assertEquals(total, ok + failed, "성공(빈 값 포함)과 실패가 전체를 정확히 둘로 나눠야 한다");

        assertEquals(9, data(get(PAGE).param("size", "100").param("success", "true")
                        .param("workloadId", String.valueOf(WORKLOAD_A))).get("totalElements").asInt(),
                "A 12건 중 실패 3건(0,5,10)을 뺀 나머지. 필터가 최상위 OR 이면 이 수가 부풀어 오른다");
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

    @DisplayName("기존 history 는 여전히 배열이다 — 다만 전량이 아니라 반환 상한까지다")
    @Test
    void existing_history_is_array_within_return_limit() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[?(@.workloadName == 'hp-24')]").exists());
        // 페이지 총합은 배열 길이와 같다.
        // 단, 배열에는 반환 상한이 있고(단계 23) 페이지 총건수에는 없다. 이 단언은 전체 이력이 상한 아래일 때만
        // 성립하므로 상한으로 한 번 감싼다 — 그러지 않으면 이 스위트가 커진 어느 날 원인 모를 실패가 된다.
        int arrayLength = objectMapper.readTree(mockMvc.perform(get("/api/v1/scheduler/history")).andReturn()
                .getResponse().getContentAsString()).get("data").size();
        int total = data(get(PAGE).param("size", "1")).get("totalElements").asInt();
        assertEquals(Math.min(total, HISTORY_RETURN_MAX), arrayLength);
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
