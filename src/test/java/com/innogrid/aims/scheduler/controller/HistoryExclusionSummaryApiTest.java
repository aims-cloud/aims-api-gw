package com.innogrid.aims.scheduler.controller;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
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
import org.springframework.transaction.support.TransactionTemplate;

import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 배치 이력 제외 사유 코드 합계({@code /history/exclusion-summary})와 페이징의 기간 창({@code from}·{@code to}).
 *
 * <p>합계와 페이징은 같은 조건식을 쓴다 — 그래서 "응답의 창을 그대로 넘긴 같은 필터의 {@code exclusionCode=CODE} 총건수 ==
 * {@code codes[CODE].decisions}"가 이 스위트의 오라클이다. 형식은 깨졌지만 코드 토큰은 든 행은 페이징(LIKE)에는 걸리고 합계에서는
 * {@code skipped}로 빠지므로 그 차이도 함께 고정한다. 시드는 이 테스트 전용 워크로드 id로만 심고, {@code createdAt}은
 * {@code @PrePersist}가 덮어쓰므로 창 테스트는 JPQL 일괄 갱신으로 과거로 옮긴다.</p>
 *
 * <p>시드는 심은 뒤 모두 1초 전으로 옮긴다 — 기본 창의 끝이 조회 시각이고 끝은 포함하지 않으므로, 심은 시각이 조회와 같은 시계
 * 눈금에 걸리면 마지막 행이 합계에서 빠진다(결정 수 7 대신 6). 1초보다 크게 옮기면 기본 창 끝을 몇십 초 당기는 결함을 기본 창
 * 테스트의 60초 허용 폭이 가리므로 1초만 옮긴다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class HistoryExclusionSummaryApiTest {

    private static final String SUMMARY = "/api/v1/scheduler/history/exclusion-summary";
    private static final String PAGE = "/api/v1/scheduler/history/page";
    private static final Set<String> SUMMARY_FIELDS = new TreeSet<>(Set.of("from", "to", "decisions", "withExclusions", "skipped", "codes"));
    private static final Set<String> CODE_FIELDS = new TreeSet<>(Set.of("decisions", "candidates"));
    /** 이 테스트 전용 — 다른 이력 테스트의 9_900_00x 대역과 겹치지 않는다. */
    private static final long WORKLOAD_F = 9_900_006L;
    private static final String W = String.valueOf(WORKLOAD_F);
    /** 심은 시드를 옮기는 폭 — 시계 눈금(Windows 최대 약 16ms)과 마이크로초 반올림보다 충분히 크다. */
    private static final Duration SEEDED_BEFORE = Duration.ofSeconds(1);
    /** 심은 시드가 심은 직후보다 적어도 이만큼 앞서야 기본 창 끝과 같은 눈금에 걸리지 않는다. */
    private static final Duration SEEDED_MARGIN = Duration.ofMillis(500);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SchedulingHistoryRepository repository;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    private final List<Long> seeded = new ArrayList<>();
    private long ir2Id, noneId;
    private OffsetDateTime seededAt;

    /** 성공 4(집계 2 · 없음 1 · 빈 객체 1) · 실패 3(집계 1 · 형식 깨짐 2 — 하나는 코드 토큰을 품는다). 심은 뒤 모두 1초 전으로 옮긴다. */
    @BeforeEach
    void seed() {
        ir2Id = save("hs-ir2", true, "{\"INSUFFICIENT_RESOURCE\":2}");
        save("hs-cd-ir", true, "{\"COOLING_DOWN\":1,\"INSUFFICIENT_RESOURCE\":1}");
        noneId = save("hs-none", true, null);
        save("hs-empty", true, "{}");                                    // 운영 경로는 만들지 않지만 열이 평문이라 심을 수 있다
        save("hs-te", false, "{\"TEMPERATURE_EXCEEDED\":1}");
        save("hs-broken", false, "not-json");                            // 토큰 없음 — 페이징에도 안 걸린다
        save("hs-broken-token", false, "{\"TEMPERATURE_EXCEEDED\":x}");   // 토큰 있음 — 페이징 LIKE에는 걸린다
        seededAt = OffsetDateTime.now();
        for (long id : seeded) backdate(id, seededAt.minus(SEEDED_BEFORE));
    }

    @AfterEach
    void clean() {
        repository.deleteAllById(seeded);
    }

    @DisplayName("합계는 코드별 결정 수·후보 수를 더하고, 형식이 어긋난 행은 건너뛰어 세며, 빈 객체는 어느 쪽에도 들지 않는다")
    @Test
    void summary_counts_decisions_and_candidates_per_code() throws Exception {
        JsonNode d = summary("workloadId", W);
        assertEquals(SUMMARY_FIELDS, keys(d), "응답 필드 6개");
        assertEquals(7, d.get("decisions").asLong(), "창 안 결정 전부 — 집계 없는 행도 센다");
        assertEquals(3, d.get("withExclusions").asLong(), "제외가 하나 이상 정상으로 읽힌 결정");
        assertEquals(2, d.get("skipped").asLong(), "형식이 어긋난 행 둘");
        JsonNode codes = d.get("codes");
        assertEquals(List.of("COOLING_DOWN", "INSUFFICIENT_RESOURCE", "TEMPERATURE_EXCEEDED"), names(codes), "등장한 코드만 · 이름 오름차순");
        for (String code : names(codes)) assertEquals(CODE_FIELDS, keys(codes.get(code)), code + " 값 필드 2개");
        assertCount(codes, "COOLING_DOWN", 1, 1);
        assertCount(codes, "INSUFFICIENT_RESOURCE", 2, 3);
        assertCount(codes, "TEMPERATURE_EXCEEDED", 1, 1);
    }

    @DisplayName("응답의 창을 넘긴 같은 필터의 페이징 exclusionCode 총건수는 codes 의 결정 수와 같다 — 토큰만 든 깨진 행만큼 어긋난다")
    @Test
    void summary_shares_population_with_page_filter() throws Exception {
        JsonNode d = summary("workloadId", W);
        String from = d.get("from").asString(), to = d.get("to").asString();
        JsonNode codes = d.get("codes");
        for (String code : List.of("COOLING_DOWN", "INSUFFICIENT_RESOURCE")) {
            assertEquals(codes.get(code).get("decisions").asLong(), pageTotal(code, from, to), code + " — 두 API 는 같은 조건식을 쓴다");
        }
        assertEquals(codes.get("TEMPERATURE_EXCEEDED").get("decisions").asLong() + 1, pageTotal("TEMPERATURE_EXCEEDED", from, to),
                "코드 토큰은 있지만 형식이 깨진 행은 페이징(LIKE)에는 걸리고 합계에서는 skipped 로 빠진다");

        JsonNode failed = summary("workloadId", W, "success", "false");
        assertEquals(List.of("TEMPERATURE_EXCEEDED"), names(failed.get("codes")), "실패 결정에는 온도 코드뿐");
        assertEquals(3, failed.get("decisions").asLong(), "실패 결정 셋 — 집계 1 · 형식 깨짐 2");
        assertEquals(1, failed.get("withExclusions").asLong(), "깨진 행은 withExclusions 에 들지 않는다");
        assertEquals(2, failed.get("skipped").asLong(), "깨진 행 둘은 실패 쪽");
        long failedSummaryTe = failed.get("codes").get("TEMPERATURE_EXCEEDED").get("decisions").asLong();
        long failedPageTe = pageTotal("TEMPERATURE_EXCEEDED", failed.get("from").asString(), failed.get("to").asString(), "success", "false");
        assertEquals(1, failedSummaryTe, "실패 중 온도 코드가 정상으로 읽힌 결정은 hs-te 하나");
        assertEquals(2, failedPageTe, "페이징은 hs-te 와 토큰만 든 깨진 행 hs-broken-token 을 함께 센다");
        assertEquals(failedSummaryTe + 1, failedPageTe, "success 필터와 함께 걸어도 같은 조건식 — 차이는 토큰만 든 깨진 행 1");

        // 나머지 필터도 합계까지 전달된다 — 걸리지 않는 값이면 0
        assertEquals(0, summary("workloadId", W, "engineName", "no-such-engine").get("decisions").asLong(), "engineName 도 합계에 전달된다");
        assertEquals(0, summary("workloadId", W, "scheduleRunId", "-1").get("decisions").asLong(), "scheduleRunId 도 합계에 전달된다");
    }

    @DisplayName("창을 둘 다 비우면 최근 30일이 실제로 거르고 응답이 그 창을 돌려준다 — 한쪽만 주면 나머지는 경계 없음")
    @Test
    void summary_default_window_filters_and_echoes_bounds() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        backdate(noneId, now.minusDays(31));   // hs-none을 31일 전으로 — 기본 창 밖

        JsonNode d = summary("workloadId", W);
        OffsetDateTime from = OffsetDateTime.parse(d.get("from").asString());
        OffsetDateTime to = OffsetDateTime.parse(d.get("to").asString());
        assertTrue(Math.abs(ChronoUnit.SECONDS.between(now.minusDays(30), from)) < 60, "from ≈ 30일 전: " + from);
        assertTrue(Math.abs(ChronoUnit.SECONDS.between(now, to)) < 60, "to ≈ 지금: " + to);
        assertEquals(6, d.get("decisions").asLong(), "31일 전 행은 기본 창에서 빠진다");
        assertEquals(7, summary("workloadId", W, "from", "2000-01-01T00:00:00Z").get("decisions").asLong(), "하한을 멀리 주면 전부");

        OffsetDateTime givenFrom = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        JsonNode onlyFrom = summary("workloadId", W, "from", givenFrom.toString());
        assertTrue(givenFrom.isEqual(OffsetDateTime.parse(onlyFrom.get("from").asString())), "준 from 을 그대로 돌려준다");
        assertTrue(onlyFrom.get("to").isNull(), "to 만 비우면 상한 없음 — 페이징과 같은 규칙");
        assertEquals(7, onlyFrom.get("decisions").asLong(), "31일 전 행도 올해 초부터의 창에는 든다");

        OffsetDateTime givenTo = now.plusHours(1);
        JsonNode onlyTo = summary("workloadId", W, "to", givenTo.toString());
        assertTrue(onlyTo.get("from").isNull(), "from 만 비우면 하한 없음");
        assertTrue(givenTo.isEqual(OffsetDateTime.parse(onlyTo.get("to").asString())), "준 to 를 그대로 돌려준다");
        assertEquals(7, onlyTo.get("decisions").asLong());
    }

    @DisplayName("창은 시작 포함 · 끝 미포함이고, 창 밖 결정은 합계에도 페이징에도 들어오지 않으며, 시작이 끝보다 뒤면 400")
    @Test
    void window_is_half_open_for_summary_and_page() throws Exception {
        OffsetDateTime t = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        backdate(ir2Id, t);   // hs-ir2를 초 단위 고정 시각으로
        String before = t.minusSeconds(1).toString(), at = t.toString(), after = t.plusSeconds(1).toString();

        assertEquals(1, summary("workloadId", W, "from", at, "to", after).get("decisions").asLong(), "from 과 같은 시각은 포함");
        assertEquals(0, summary("workloadId", W, "from", before, "to", at).get("decisions").asLong(), "to 와 같은 시각은 미포함");
        assertEquals(1, pageTotalInWindow(at, after), "페이징도 시작 포함");
        assertEquals(0, pageTotalInWindow(before, at), "페이징도 끝 미포함");

        OffsetDateTime now = OffsetDateTime.now();
        String future = now.plusHours(1).toString(), past = now.minusHours(1).toString();
        JsonNode outside = summary("workloadId", W, "from", future);
        assertEquals(0, outside.get("decisions").asLong(), "미래에서 시작하는 창은 비어 있다");
        assertEquals(0, outside.get("codes").size(), "등장한 코드가 없으면 빈 객체");
        assertEquals(6, pageTotalInWindow(past, null), "hs-ir2 를 뺀 여섯 행은 최근 1시간 안");
        assertEquals(0, pageTotalInWindow(future, null), "미래에서 시작하는 창은 페이징도 비어 있다");

        mockMvc.perform(get(SUMMARY).param("from", future).param("to", past))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(get(PAGE).param("from", future).param("to", past))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(get(SUMMARY).param("from", "yesterday")).andExpect(status().isBadRequest());
        mockMvc.perform(get(SUMMARY).param("from", "2026-09-01T00:00:00")).andExpect(status().isBadRequest());   // 오프셋 없음
        mockMvc.perform(get(PAGE).param("to", "2026-09-01T00:00:00")).andExpect(status().isBadRequest());
    }

    @DisplayName("심은 이력은 모두 심은 직후보다 0.5초 넘게 앞서 기본 창 끝과 같은 시계 눈금에 걸리지 않는다")
    @Test
    void seeded_rows_precede_default_window_end() {
        OffsetDateTime latest = repository.findAllById(seeded).stream()
                .map(SchedulingHistory::getCreatedAt).max(Comparator.naturalOrder()).orElseThrow();
        assertTrue(latest.isBefore(seededAt.minus(SEEDED_MARGIN)),
                () -> "가장 늦은 시드 " + latest + " · 심은 직후 " + seededAt + " — 기본 창 끝(조회 시각)과 같은 눈금이면 합계에서 빠진다");
    }

    @DisplayName("집계 문자열 역파싱은 쓰는 쪽 형식과 짝이고, 어긋나거나 음수·중복 키·공백 섞임이면 비어 있다")
    @Test
    void parse_matches_writer_format() {
        Optional<SortedMap<String, Integer>> parsed = SchedulingHistory.parseExclusionCodes(
                "{" + SchedulingHistory.exclusionCodeKey("B") + "2," + SchedulingHistory.exclusionCodeKey("A") + "1}");
        assertEquals(List.of("A", "B"), new ArrayList<>(parsed.orElseThrow().keySet()));
        assertEquals(3, parsed.orElseThrow().values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(SchedulingHistory.parseExclusionCodes(null).orElseThrow().isEmpty(), "null 은 빈 맵");
        assertTrue(SchedulingHistory.parseExclusionCodes("{}").orElseThrow().isEmpty(), "빈 객체는 빈 맵");
        for (String broken : List.of("not-json", "{\"A\":x}", "{A:1}", "[\"A\"]", "{\"A\":1,}", "{\"A\":-1}",
                                     "{\"\":1}", "{\"A\":1;\"B\":2}", "{\"A\" : 1}", "{\"A\":1,\"A\":2}")) {
            assertTrue(SchedulingHistory.parseExclusionCodes(broken).isEmpty(), broken + " 는 형식 오류");
        }
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private long save(String name, boolean success, String codes) {
        SchedulingHistory h = new SchedulingHistory(WORKLOAD_F, name, "mdc-01", "n1", 0.5, "r", "weighted", "default");
        h.linkToRun(null, success);
        h.setExclusionCodes(codes);
        long id = repository.save(h).getId();
        seeded.add(id);
        return id;
    }

    /** {@code @PrePersist}가 덮어쓰는 {@code createdAt}을 JPQL 일괄 갱신으로 옮긴다(갱신은 콜백을 거치지 않는다). */
    private void backdate(long id, OffsetDateTime at) {
        tx.executeWithoutResult(s -> em.createQuery("update SchedulingHistory h set h.createdAt = :t where h.id = :id")
                .setParameter("t", at).setParameter("id", id).executeUpdate());
    }

    private JsonNode summary(String... pairs) throws Exception {
        var req = get(SUMMARY);
        for (int i = 0; i + 1 < pairs.length; i += 2) req = req.param(pairs[i], pairs[i + 1]);
        return data(req);
    }

    /** 이 테스트 워크로드에 코드·창 필터(와 추가 파라미터 쌍)를 건 페이징 총건수 — null인 인자는 붙이지 않는다. */
    private long pageTotal(String code, String from, String to, String... extraPairs) throws Exception {
        var req = get(PAGE).param("size", "1").param("workloadId", W);
        if (code != null) req = req.param("exclusionCode", code);
        if (from != null) req = req.param("from", from);
        if (to != null) req = req.param("to", to);
        for (int i = 0; i + 1 < extraPairs.length; i += 2) req = req.param(extraPairs[i], extraPairs[i + 1]);
        return data(req).get("totalElements").asLong();
    }

    /** 코드 필터 없이 창만 건 페이징 총건수. */
    private long pageTotalInWindow(String from, String to) throws Exception {
        return pageTotal(null, from, to);
    }

    private JsonNode data(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) throws Exception {
        String body = mockMvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    private static void assertCount(JsonNode codes, String code, long decisions, long candidates) {
        assertEquals(decisions, codes.get(code).get("decisions").asLong(), code + " 결정 수");
        assertEquals(candidates, codes.get(code).get("candidates").asLong(), code + " 후보 수");
    }

    private static List<String> names(JsonNode codes) {
        List<String> out = new ArrayList<>();
        codes.propertyNames().forEach(out::add);
        return out;
    }

    private static Set<String> keys(JsonNode n) {
        Set<String> k = new TreeSet<>();
        n.propertyNames().forEach(k::add);
        return k;
    }
}
