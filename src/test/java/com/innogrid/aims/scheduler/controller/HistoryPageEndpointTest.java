package com.innogrid.aims.scheduler.controller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
 * {@code GET /scheduler/history/page} — 페이지 경계·필터·정렬·상한, 그리고 기존 {@code /history}가 배열로 그대로인가.
 *
 * <p>이력 25건(엔진 weighted 15·power 10, 성공 20·실패 5)을 심고 끝에 지운다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class HistoryPageEndpointTest {

    private static final String PAGE = "/api/v1/scheduler/history/page";
    /** {@code SchedulerController.HISTORY_MAX}와 같다. {@link HistoryReturnLimitTest}가 그 값을 고정한다. */
    private static final int HISTORY_RETURN_MAX = 1000;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SchedulingHistoryRepository repository;

    private final List<Long> seeded = new ArrayList<>();
    private static final long WORKLOAD_A = 9_900_001L;
    private static final long WORKLOAD_B = 9_900_002L;
    /** 위 둘의 건수 확인을 건드리지 않으려고 따로 쓰는 id. */
    private static final long WORKLOAD_C = 9_900_003L;
    /** 실행 필터 테스트 전용 — 실행에 묶인 행은 이 id로만 심는다. */
    private static final long WORKLOAD_D = 9_900_004L;
    /** 제외 코드 키 경계 테스트 전용 — A·B의 건수 확인을 건드리지 않으려고 따로 쓰는 id. */
    private static final long WORKLOAD_E = 9_900_005L;
    /**
     * 실재 실행과 겹치지 않는 예약 대역의 실행 id — 실행 id는 1부터 자라 이 대역에 닿지 않고, FK가 없어 실행 행이
     * 없어도 저장된다. 테스트마다 다른 값을 써 서로의 정리에 기대지 않는다.
     */
    private static final long RUN_X = 9_900_777L;
    /** 위와 같은 용도, 다른 테스트용. */
    private static final long RUN_Y = 9_900_778L;
    /** 아무 행도 묶지 않는 실행 id — "없는 실행 id 는 빈 페이지"를 볼 때만 쓴다. */
    private static final long RUN_NONE = 9_900_999L;
    /** 동률 페이지 경계 테스트 전용 — 성공 12건만 심어 success가 전부 같은 값이 되게 한다. 다른 시드의 건수 확인과 겹치지 않는 id. */
    private static final long WORKLOAD_G = 9_900_011L;

    @BeforeEach
    void seed() {
        for (int i = 0; i < 25; i++) {
            SchedulingHistory h = new SchedulingHistory(i < 12 ? WORKLOAD_A : WORKLOAD_B, "hp-" + String.format("%02d", i),
                    "mdc-01", "n1", i / 100.0, "r", i < 15 ? "weighted" : "power", "default");
            h.linkToRun(null, i % 5 != 0);   // 0,5,10,15,20 실패 → 5건 (실행 연결 없이 결과만)
            // 집계 값이 있는 행 셋 — DB 왕복(hp-03)과 코드 필터(hp-03·hp-07은 성공, hp-20은 실패·power) 확인용
            if (i == 3) h.setExclusionCodes("{\"INSUFFICIENT_RESOURCE\":2}");
            if (i == 7) h.setExclusionCodes("{\"COOLING_DOWN\":1,\"INSUFFICIENT_RESOURCE\":1}");
            if (i == 20) h.setExclusionCodes("{\"TEMPERATURE_EXCEEDED\":1}");
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
            else if (item.get("workloadName").asString().equals("hp-01")) without = item;
        }
        assertNotNull(withCodes, "씨앗 hp-03 이 있어야 한다");
        assertEquals("{\"INSUFFICIENT_RESOURCE\":2}", withCodes.get("exclusionCodes").asString(), "TEXT 열 왕복 뒤에도 문자열 그대로");
        assertNotNull(without);
        assertTrue(without.get("exclusionCodes").isNull(), "집계가 없는 행은 null");
    }

    @DisplayName("제외 코드 필터는 그 코드 키가 든 행만 고르고, 집계가 없는 행은 어떤 코드로도 고르지 않는다")
    @Test
    void exclusion_code_filter_matches_only_rows_containing_the_code() throws Exception {
        JsonNode d = data(get(PAGE).param("size", "100").param("workloadId", String.valueOf(WORKLOAD_A))
                .param("exclusionCode", "INSUFFICIENT_RESOURCE"));
        assertEquals(2, d.get("totalElements").asInt(), "A 의 hp-03·hp-07");
        for (JsonNode row : d.get("items")) {
            assertTrue(row.get("exclusionCodes").asString().contains("\"INSUFFICIENT_RESOURCE\":"), "키 단위로 맞는다");
        }
        assertEquals(1, countByCode(WORKLOAD_A, "COOLING_DOWN"), "A 의 hp-07");
        assertEquals(1, countByCode(WORKLOAD_B, "TEMPERATURE_EXCEEDED"), "B 의 hp-20");
        assertEquals(0, countByCode(WORKLOAD_A, "MDC_LINK_DOWN"), "집계가 NULL 인 행은 어떤 코드에도 걸리지 않는다");
    }

    /**
     * 짧은 코드로 긴 이름의 행을 물을 때만 앞 따옴표가 일한다 — 반대 방향(긴 코드로 짧은 행)은 따옴표 없이도 0이라
     * 증거가 못 된다. 밑줄 자리만 다른 키는 실제 코드에 없지만 열이 평문 TEXT(CHECK 없음)라 합성 키로 심는다.
     */
    @DisplayName("제외 코드 필터는 키 이름 전체로 맞춘다 — 긴 이름의 꼬리나 밑줄 자리의 다른 글자에 걸리지 않는다")
    @Test
    void exclusion_code_filter_matches_whole_key_only() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (String codes : List.of("{\"WORKLOAD_TEMPERATURE_EXCEEDED\":1}",   // 앞 따옴표가 없으면 TEMPERATURE_EXCEEDED에 걸린다
                                    "{\"INSUFFICIENTxRESOURCE\":1}")) {        // 밑줄을 풀면 INSUFFICIENT_RESOURCE에 걸린다
            SchedulingHistory h = new SchedulingHistory(WORKLOAD_E, "hp-key", "mdc-01", "n1", 0.5, "r", "weighted", "default");
            h.linkToRun(null, true);
            h.setExclusionCodes(codes);
            ids.add(repository.save(h).getId());
        }
        try {
            assertEquals(0, countByCode(WORKLOAD_E, "TEMPERATURE_EXCEEDED"), "긴 이름의 꼬리에 걸렸다 — 키 앞 따옴표가 빠졌다");
            assertEquals(1, countByCode(WORKLOAD_E, "WORKLOAD_TEMPERATURE_EXCEEDED"), "긴 이름 자체는 잡혀야 한다");
            assertEquals(0, countByCode(WORKLOAD_E, "INSUFFICIENT_RESOURCE"), "밑줄이 LIKE 와일드카드로 풀렸다");
        } finally {
            repository.deleteAllById(ids);
        }
    }

    @DisplayName("제외 코드 필터는 다른 필터와 함께 걸어도 무너지지 않는다")
    @Test
    void exclusion_code_filter_combines_with_other_filters() throws Exception {
        assertEquals(2, countByCode(WORKLOAD_A, "INSUFFICIENT_RESOURCE", "success", "true"),
                "성공했지만 다른 후보가 빠진 결정 — hp-03·hp-07");
        assertEquals(0, countByCode(WORKLOAD_A, "INSUFFICIENT_RESOURCE", "success", "false"),
                "A 의 실패 행에는 그 코드가 없다 — 필터가 최상위 OR 이면 이 수가 부풀어 오른다");
        assertEquals(1, countByCode(WORKLOAD_B, "TEMPERATURE_EXCEEDED", "success", "false"),
                "실패한 결정에도 코드 필터가 걸린다 — 운영의 주 모집단");
        assertEquals(0, countByCode(WORKLOAD_B, "INSUFFICIENT_RESOURCE"), "B 에는 그 코드가 없다");
        assertEquals(1, countByCode(WORKLOAD_B, "TEMPERATURE_EXCEEDED", "engineName", "power"), "B 의 hp-20 은 power");
        assertEquals(0, countByCode(WORKLOAD_B, "TEMPERATURE_EXCEEDED", "engineName", "weighted"), "B 의 hp-20 은 weighted 가 아니다");
        assertEquals(12, countByCode(WORKLOAD_A, ""), "빈 값은 필터 없음 — A 12건 그대로");
    }

    @DisplayName("필터는 각각 그리고 함께 적용된다")
    @Test
    void filters_single_and_combined() throws Exception {
        assertEquals(10, data(get(PAGE).param("size", "100").param("engineName", "power")).get("totalElements").asInt());
        // 실패 5건 중 심은 것만 세기 위해 workloadId와 함께
        assertEquals(3, data(get(PAGE).param("size", "100").param("success", "false")
                .param("workloadId", String.valueOf(WORKLOAD_A))).get("totalElements").asInt(), "A 의 실패: 0,5,10");
        assertEquals(2, data(get(PAGE).param("size", "100").param("success", "false")
                .param("workloadId", String.valueOf(WORKLOAD_B))).get("totalElements").asInt(), "B 의 실패: 15,20");
        assertEquals(0, data(get(PAGE).param("size", "100").param("engineName", "power")
                .param("workloadId", String.valueOf(WORKLOAD_A))).get("totalElements").asInt(), "A 는 전부 weighted");
    }

    /**
     * {@code success}가 NULL인 행은 두 가지다 — 이 컬럼이 생기기 전에 쌓인 행, 그리고 단건 배치가 실행 연결 없이
     * 만든 옛 행(지금은 둘 다 값을 남긴다). 실패한 결정은 언제나 명시적 false로 저장되므로 <b>NULL은 전부 성공</b>
     * 인데, 조회 필터만 그 해석을 따르지 않아 성공한 결정이 통째로 빠졌다.
     *
     * <p>다른 시드(WORKLOAD_A·B, 엔진 weighted·power)를 건드리지 않도록 <b>제3의 워크로드 id와 다른 엔진명</b>
     * 으로 심는다 — 그러지 않으면 위의 건수 확인들이 함께 실패한다.</p>
     */
    @DisplayName("성공을 물으면 성공 여부가 비어 있는 행도 함께 나온다 — 실패를 물을 때는 아니다")
    @Test
    void success_filter_includes_null_rows() throws Exception {
        SchedulingHistory unlinked = new SchedulingHistory(WORKLOAD_C, "hp-null", "mdc-01", "n1",
                0.5, "r", "solo", "default");   // linkToRun을 부르지 않는다 → success가 NULL
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
     * 필터가 최상위 OR로 잘못 조립되면 다른 조건이 무력화된다 — 그러면 위 확인은 통과하면서
     * {@code success=true}에 {@code workloadId}를 함께 준 결과가 전체 행으로 부풀어 오른다.
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

    /**
     * 실행 필터의 모집단은 실행 상세 결정 목록과 같다 — 그 실행에 {@code linkToRun}으로 묶인 행 전부.
     * 기존 시드(A·B)는 실행 연결이 없으므로 제4의 워크로드 id로 심어 위 건수 확인과 겹치지 않게 한다.
     */
    @DisplayName("실행 필터는 그 실행이 만든 결정만 돌려주고 다른 필터와 함께 걸어도 무너지지 않는다")
    @Test
    void run_filter_returns_only_that_runs_rows() throws Exception {
        List<Long> ids = seedRun(RUN_X, 3);
        try {
            JsonNode d = data(get(PAGE).param("size", "100").param("scheduleRunId", String.valueOf(RUN_X)));
            assertEquals(3, d.get("totalElements").asInt(), "실행 X 에 묶인 세 행");
            for (JsonNode row : d.get("items")) {
                assertEquals(RUN_X, row.get("scheduleRunId").asLong(), "묶이지 않은 행이 섞였다");
            }
            assertEquals(1, data(get(PAGE).param("size", "100").param("scheduleRunId", String.valueOf(RUN_X))
                    .param("success", "false")).get("totalElements").asInt(), "실행 X 의 실패는 한 건");
            assertEquals(0, data(get(PAGE).param("size", "100").param("scheduleRunId", String.valueOf(RUN_X))
                    .param("workloadId", String.valueOf(WORKLOAD_A))).get("totalElements").asInt(),
                    "A 는 실행 X 에 없다 — 필터가 최상위 OR 이면 이 수가 부풀어 오른다");
        } finally {
            repository.deleteAllById(ids);
        }
    }

    /**
     * 실행 연결이 NULL인 행은 어떤 값으로도 되찾을 수 없다 — 묶인 행만 돌아오는지는
     * {@link #run_filter_returns_only_that_runs_rows}가 보므로, 여기서는 NULL인 모집단(A 12건) 쪽에서 본다.
     */
    @DisplayName("실행 연결이 없는 행은 어떤 실행 id 로도 걸리지 않고, 행이 없는 실행 id 는 빈 페이지다")
    @Test
    void run_filter_never_matches_unlinked_rows() throws Exception {
        List<Long> ids = seedRun(RUN_Y, 3);
        try {
            assertEquals(0, data(get(PAGE).param("size", "100").param("workloadId", String.valueOf(WORKLOAD_A))
                            .param("scheduleRunId", String.valueOf(RUN_Y))).get("totalElements").asInt(),
                    "실행 연결이 NULL 인 A 12건이 실행 Y 에 걸렸다 — NULL 은 '실행 없음' 이라 대신 넣을 값이 없다");
            assertEquals(0, data(get(PAGE).param("size", "100").param("scheduleRunId", String.valueOf(RUN_NONE)))
                    .get("totalElements").asInt(), "묶인 행이 없는 실행 id 는 0 건 — 오류가 아니라 빈 페이지");
            assertEquals(3, data(get(PAGE).param("size", "100").param("workloadId", String.valueOf(WORKLOAD_D)))
                    .get("totalElements").asInt(), "심은 행은 워크로드 필터만으로 세 건 그대로다");
        } finally {
            repository.deleteAllById(ids);
        }
    }

    @DisplayName("같은 값으로 정렬해도 페이지를 이어 붙이면 중복·누락 없이 id 가 첫 키 방향으로 이어진다")
    @Test
    void tied_sort_pages_are_stitched_by_id_in_first_key_direction() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            SchedulingHistory h = new SchedulingHistory(WORKLOAD_G, "hp-tie-" + i, "mdc-01", "n1", 0.5, "r", "weighted", "default");
            h.linkToRun(null, true);
            ids.add(repository.save(h).getId());
        }
        try {
            List<Long> stitched = new ArrayList<>();
            for (int p = 1; p <= 3; p++) {
                JsonNode d = data(get(PAGE).param("workloadId", String.valueOf(WORKLOAD_G))
                        .param("sort", "success,desc").param("size", "5").param("page", String.valueOf(p)));
                assertEquals(12, d.get("totalElements").asInt());
                for (JsonNode row : d.get("items")) stitched.add(row.get("id").asLong());
            }
            assertEquals(12, stitched.size(), "세 페이지(5·5·2)를 이어 붙이면 12건");
            assertEquals(12, new HashSet<>(stitched).size(), "페이지 경계에서 행이 겹쳤다 " + stitched);
            List<Long> expected = new ArrayList<>(ids);
            expected.sort(Comparator.reverseOrder());
            assertEquals(expected, stitched, "동률은 id 내림차순 — 첫 키 방향을 따른다");
        } finally {
            repository.deleteAllById(ids);
        }
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

    @DisplayName("size 상한 100 · page 최소 1 · 숫자가 아닌 실행 id · 모르는 제외 코드는 400")
    @Test
    void invalid_size_page_run_id_or_code_400() throws Exception {
        mockMvc.perform(get(PAGE).param("size", "101")).andExpect(status().isBadRequest());
        mockMvc.perform(get(PAGE).param("page", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(get(PAGE).param("scheduleRunId", "abc")).andExpect(status().isBadRequest());
        mockMvc.perform(get(PAGE).param("exclusionCode", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("exclusionCode")))
                .andExpect(jsonPath("$.message").value(containsString("INSUFFICIENT_RESOURCE")));   // 가능한 값 목록을 돌려준다
        mockMvc.perform(get(PAGE).param("exclusionCode", "insufficient_resource"))
                .andExpect(status().isBadRequest());   // 대문자 그대로 — 대소문자를 풀면 계약이 바뀐다
    }

    @DisplayName("기존 history 는 여전히 배열이다 — 다만 전량이 아니라 반환 상한까지다")
    @Test
    void existing_history_is_array_within_return_limit() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[?(@.workloadName == 'hp-24')]").exists());
        // 페이지 총합은 배열 길이와 같다.
        // 단, 배열에는 반환 상한이 있고 페이지 총건수에는 없다. 이 조건은 전체 이력이 상한 아래일 때만
        // 성립하므로 상한으로 한 번 감싼다 — 그러지 않으면 이 스위트가 커진 어느 날 원인 모를 실패가 된다.
        int arrayLength = objectMapper.readTree(mockMvc.perform(get("/api/v1/scheduler/history")).andReturn()
                .getResponse().getContentAsString()).get("data").size();
        int total = data(get(PAGE).param("size", "1")).get("totalElements").asInt();
        assertEquals(Math.min(total, HISTORY_RETURN_MAX), arrayLength);
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    /** 워크로드 하나에 제외 코드 필터(와 추가 파라미터 쌍)를 건 총건수. */
    private int countByCode(long workloadId, String code, String... extraPairs) throws Exception {
        var req = get(PAGE).param("size", "1").param("workloadId", String.valueOf(workloadId)).param("exclusionCode", code);
        for (int i = 0; i + 1 < extraPairs.length; i += 2) req = req.param(extraPairs[i], extraPairs[i + 1]);
        return data(req).get("totalElements").asInt();
    }

    /** 실행 {@code runId}에 묶인 행 {@code n} 건 — 마지막 한 건만 실패. {@code WORKLOAD_D}로만 심는다. */
    private List<Long> seedRun(long runId, int n) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            SchedulingHistory h = new SchedulingHistory(WORKLOAD_D, "hp-run-" + i, "mdc-01", "n1", 0.5, "r", "weighted", "default");
            h.linkToRun(runId, i != n - 1);
            ids.add(repository.save(h).getId());
        }
        return ids;
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
