package com.innogrid.aims.scheduler.controller;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실행 이력 목록({@code GET /schedules/{id}/runs})의 필터 규칙 — {@code status}·{@code triggerType}·{@code from}·{@code to}·{@code keyword}
 * 와 화면 열 정렬({@code message}·{@code scheduleKey}).
 *
 * <p>필터는 전부 선택이고 AND로 겹치며, 창은 시작 시각({@code startedAt})의 반개구간 {@code [from, to)}에 기본 창이 없다.
 * 이 컨텍스트는 실 게이트웨이라 즉시 실행으로는 실행 이력이 생기지 않으므로 행을 저장소에 직접 심는다.
 * {@code startedAt}은 생성자가 지금 시각으로 채우므로 JPQL 일괄 갱신으로 고정 시각에 흩어 놓는다(배치 이력 테스트의 선례).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class ScheduleRunListFilterApiTest {

    private static final String BASE = "/api/v1/scheduler/schedules";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ScheduleRunRepository runRepository;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    @DisplayName("상태·주체 필터는 단독으로도 조합으로도 총건수를 줄이고, 비우면 전체다")
    @Test
    void status_and_trigger_filters_narrow_total() throws Exception {
        Seeded s = seed();
        try {
            assertEquals(6, total(s));
            assertEquals(3, total(s, "status", "COMPLETED"));
            assertEquals(2, total(s, "status", "FAILED"));
            assertEquals(1, total(s, "status", "RUNNING"));
            assertEquals(4, total(s, "triggerType", "MANUAL"));
            assertEquals(2, total(s, "triggerType", "AUTO"));
            assertEquals(2, total(s, "status", "COMPLETED", "triggerType", "MANUAL"), "AND 조합");
            assertEquals(1, total(s, "status", "FAILED", "triggerType", "AUTO"));
            assertEquals(6, total(s, "status", "", "triggerType", "", "from", "", "to", ""), "빈 문자열은 필터 없음(넷 다)");
            for (JsonNode row : items(s, "status", "FAILED", "triggerType", "AUTO")) {
                assertEquals("FAILED", row.get("status").asString());
                assertEquals("AUTO", row.get("triggerType").asString());
            }
        } finally {
            clean(s);
        }
    }

    @DisplayName("창은 시작 시각 기준 시작 포함·끝 미포함이고 한쪽만 주면 그쪽만 경계다")
    @Test
    void window_is_half_open_on_started_at() throws Exception {
        Seeded s = seed();
        try {
            String t = s.base().toString();                      // 마지막 행의 startedAt (초 단위 고정)
            String t2h = s.base().minusHours(2).toString();
            String t4h = s.base().minusHours(4).toString();

            assertEquals(1, total(s, "from", t), "from 과 같은 시각은 포함");
            assertEquals(5, total(s, "to", t), "to 와 같은 시각은 미포함");
            assertEquals(2, total(s, "from", t2h, "to", t), "[T−2h, T) 에는 T−2h·T−1h 두 행");
            assertEquals(5, total(s, "from", t4h), "한쪽만 주면 나머지는 경계 없음");
            assertEquals(0, total(s, "from", "2030-01-01T00:00:00Z"), "창 밖");
            assertEquals(0, total(s, "to", "2000-01-01T00:00:00Z"));
            assertEquals(1, total(s, "from", t2h, "to", t, "status", "RUNNING"), "창과 상태 조합");
        } finally {
            clean(s);
        }
    }

    @DisplayName("모르는 상태·소문자·오프셋 없는 시각·역전된 창은 400 이고 메시지에 가능한 값이 실린다")
    @Test
    void invalid_filters_400() throws Exception {
        Seeded s = seed();
        try {
            String prefix = BASE + "/" + s.scheduleId() + "/runs";
            mockMvc.perform(get(prefix).param("status", "nope")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("RUNNING, COMPLETED, FAILED")));
            mockMvc.perform(get(prefix).param("status", "completed")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("RUNNING, COMPLETED, FAILED")));
            mockMvc.perform(get(prefix).param("triggerType", "CRON")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("AUTO, MANUAL")));
            mockMvc.perform(get(prefix).param("from", "2026-09-01T00:00:00")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("OffsetDateTime")));
            mockMvc.perform(get(prefix).param("from", "yesterday")).andExpect(status().isBadRequest());
            mockMvc.perform(get(prefix).param("from", "2026-09-02T00:00:00Z").param("to", "2026-09-01T00:00:00Z"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("시작이 끝보다 뒤")));
            // 스케줄 확인이 창 검증보다 먼저 — 없는 스케줄은 창이 역전돼도 404. 형식 오류는 바인딩 단계라 스케줄과 무관하게 400
            mockMvc.perform(get(BASE + "/999999999/runs").param("status", "FAILED")).andExpect(status().isNotFound());
            mockMvc.perform(get(BASE + "/999999999/runs").param("from", "2026-09-02T00:00:00Z").param("to", "2026-09-01T00:00:00Z"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get(BASE + "/999999999/runs").param("status", "nope")).andExpect(status().isBadRequest());
        } finally {
            clean(s);
        }
    }

    @DisplayName("필터는 정렬·페이징과 겹치고 페이지를 이어 붙이면 총건수와 같다")
    @Test
    void filter_combines_with_sort_and_paging() throws Exception {
        Seeded s = seed();
        try {
            JsonNode p1 = page(s, "status", "COMPLETED", "sort", "startedAt,asc", "size", "2", "page", "1");
            JsonNode p2 = page(s, "status", "COMPLETED", "sort", "startedAt,asc", "size", "2", "page", "2");
            assertEquals(3, p1.get("totalElements").asLong());
            assertEquals(2, p1.get("totalPages").asLong());
            assertEquals(2, p1.get("items").size());
            assertEquals(1, p2.get("items").size());
            List<String> started = new ArrayList<>();
            for (JsonNode row : p1.get("items")) started.add(row.get("startedAt").asString());
            for (JsonNode row : p2.get("items")) started.add(row.get("startedAt").asString());
            for (int i = 1; i < started.size(); i++) {
                assertTrue(OffsetDateTime.parse(started.get(i - 1)).isBefore(OffsetDateTime.parse(started.get(i))), "오름차순 " + started);
            }
            for (JsonNode row : p1.get("items")) assertEquals("COMPLETED", row.get("status").asString());
            for (JsonNode row : p2.get("items")) assertEquals("COMPLETED", row.get("status").asString());
        } finally {
            clean(s);
        }
    }

    @DisplayName("동률이 있는 상태로 정렬해도 두 건씩 이어 붙이면 상태 내림차순·id 내림차순이고 겹침·빠짐이 없다")
    @Test
    void tied_status_sort_pages_follow_id_in_first_key_direction() throws Exception {
        Seeded s = seed();
        try {
            Map<Long, String> statusOf = new HashMap<>();
            for (JsonNode row : items(s)) statusOf.put(row.get("runId").asLong(), row.get("status").asString());

            List<Long> stitched = new ArrayList<>();
            for (int p = 1; p <= 3; p++) {
                JsonNode d = page(s, "sort", "status,desc", "size", "2", "page", String.valueOf(p));
                assertEquals(6, d.get("totalElements").asLong());
                for (JsonNode row : d.get("items")) stitched.add(row.get("runId").asLong());
            }
            List<Long> expected = new ArrayList<>(s.runIds());
            expected.sort(Comparator.comparing((Long id) -> statusOf.get(id)).reversed().thenComparing(Comparator.<Long>reverseOrder()));
            assertEquals(expected, stitched, "상태는 문자열 내림차순(RUNNING·FAILED·COMPLETED), 같은 상태는 id 내림차순");
        } finally {
            clean(s);
        }
    }

    @DisplayName("검색어는 실행 메시지와 스케줄 ID 의 부분 일치이고 앞뒤 공백을 지우며 대소문자를 가리지 않는다")
    @Test
    void keyword_matches_message_and_schedule_key_ignoring_case() throws Exception {
        Seeded s = seedMessages();
        try {
            assertEquals(2, total(s, "keyword", "alpha"), "alpha done · ALPHA retry");
            assertEquals(2, total(s, "keyword", "  ALPHA "), "앞뒤 공백 제거 · 대소문자 무시");
            assertEquals(1, total(s, "keyword", "노드 없음"));
            assertEquals(6, total(s, "keyword", s.key()), "스케줄 ID 로 찾으면 메시지가 없는 실행 중 행까지 전부");
            assertEquals(6, total(s, "keyword", ""), "빈 값은 필터 없음");
            assertEquals(6, total(s, "keyword", "   "), "공백뿐인 값도 필터 없음");
            assertEquals(0, total(s, "keyword", "zzz"));
            for (JsonNode row : items(s, "keyword", "alpha")) {
                assertTrue(row.get("message").asString().toLowerCase().contains("alpha"), row.toString());
            }
        } finally {
            clean(s);
        }
    }

    @DisplayName("검색어의 % 와 _ 는 와일드카드가 아니라 문자 그대로 찾는다")
    @Test
    void keyword_treats_percent_and_underscore_literally() throws Exception {
        Seeded s = seedMessages();
        try {
            assertEquals(1, total(s, "keyword", "%"), "c 100% placed 만");
            assertEquals(1, total(s, "keyword", "_"), "d under_score 만");
            assertEquals(1, total(s, "keyword", "\\"), "beta 노드 없음 a\\b 만 — 백슬래시도 문자 그대로");
        } finally {
            clean(s);
        }
    }

    @DisplayName("검색어는 상태 · 창 필터와 AND 로 겹치고 페이지를 이어 붙이면 총건수와 같다")
    @Test
    void keyword_combines_with_filters_and_paging() throws Exception {
        Seeded s = seedMessages();
        try {
            assertEquals(1, total(s, "keyword", "alpha", "status", "COMPLETED"), "alpha done");
            assertEquals(1, total(s, "keyword", "alpha", "status", "FAILED"), "ALPHA retry");
            assertEquals(0, total(s, "keyword", "alpha", "status", "RUNNING"));
            assertEquals(1, total(s, "keyword", "alpha", "from", s.base().minusHours(4).toString()), "T−4h 이후의 ALPHA retry 만");
            // 크기 1이면 두 쪽 모두 꽉 차서 건수 질의가 실제로 돈다(첫 쪽이 덜 차면 Spring Data가 건수 질의를 건너뛴다)
            List<Long> stitched = new ArrayList<>();
            for (int p = 1; p <= 2; p++) {
                JsonNode d = page(s, "keyword", "alpha", "sort", "startedAt,asc", "size", "1", "page", String.valueOf(p));
                assertEquals(2, d.get("totalElements").asLong(), "건수 질의도 검색어를 반영");
                for (JsonNode row : d.get("items")) stitched.add(row.get("runId").asLong());
            }
            assertEquals(List.of(s.runIds().get(0), s.runIds().get(1)), stitched, "alpha done → ALPHA retry 순으로 겹침 · 빠짐 없이 이어짐");
        } finally {
            clean(s);
        }
    }

    @DisplayName("화면의 메시지 · 스케줄 ID 열 정렬을 받는다 — 메시지는 값 순서, 스케줄 ID 는 한 스케줄 안에서 같은 값이라 id 순서")
    @Test
    void message_and_schedule_key_sorts_are_accepted() throws Exception {
        Seeded s = seedMessages();
        try {
            assertEquals(List.of("alpha done", "c 100% placed", "d under_score"),
                    messages(items(s, "status", "COMPLETED", "sort", "message,asc")));
            assertEquals(List.of("d under_score", "c 100% placed", "alpha done"),
                    messages(items(s, "status", "COMPLETED", "sort", "message,desc")));
            List<Long> desc = new ArrayList<>(s.runIds());
            desc.sort(Comparator.reverseOrder());
            assertEquals(desc, runIds(items(s, "sort", "scheduleKey,desc")), "같은 값은 첫 키 방향의 id 순서");
            assertEquals(s.runIds(), runIds(items(s, "sort", "scheduleKey,asc")));
        } finally {
            clean(s);
        }
    }

    @DisplayName("허용하지 않는 정렬 필드는 400 이고 메시지의 가능한 값에 message · scheduleKey 가 있다")
    @Test
    void unknown_run_sort_field_400_lists_allowed_fields() throws Exception {
        Seeded s = seedMessages();
        try {
            mockMvc.perform(get(BASE + "/" + s.scheduleId() + "/runs").param("sort", "engineId,asc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("정렬할 수 없는 필드입니다: engineId")))
                    .andExpect(jsonPath("$.message", containsString("message")))
                    .andExpect(jsonPath("$.message", containsString("scheduleKey")));
        } finally {
            clean(s);
        }
    }

    // ── 시딩 ───────────────────────────────────────────────────

    private record Seeded(long scheduleId, String key, List<Long> runIds, OffsetDateTime base) { }

    /**
     * 스케줄 1 · 실행 6 — 시작 시각은 T−5h … T로 한 시간 간격(T는 초 단위 고정).
     * <pre>
     *   T−5h COMPLETED MANUAL   T−4h COMPLETED AUTO   T−3h FAILED MANUAL
     *   T−2h FAILED AUTO        T−1h RUNNING MANUAL   T    COMPLETED MANUAL
     * </pre>
     */
    private Seeded seed() throws Exception {
        ScheduleRun.RunStatus[] statuses = {
                ScheduleRun.RunStatus.COMPLETED, ScheduleRun.RunStatus.COMPLETED, ScheduleRun.RunStatus.FAILED,
                ScheduleRun.RunStatus.FAILED, ScheduleRun.RunStatus.RUNNING, ScheduleRun.RunStatus.COMPLETED };
        ScheduleRun.TriggerType[] triggers = {
                ScheduleRun.TriggerType.MANUAL, ScheduleRun.TriggerType.AUTO, ScheduleRun.TriggerType.MANUAL,
                ScheduleRun.TriggerType.AUTO, ScheduleRun.TriggerType.MANUAL, ScheduleRun.TriggerType.MANUAL };
        String[] messages = { "필터 확인용", "필터 확인용", "필터 확인용", "필터 확인용", null, "필터 확인용" };
        return seed("rlf-", statuses, triggers, messages);
    }

    /**
     * 검색어 · 메시지 정렬용 — 스케줄 1 · 실행 6, 시작 시각은 {@link #seed()}와 같이 T−5h … T.
     * 업무 키는 {@code rkw-<16진수>}라 아래 메시지의 검색어(alpha · 노드 없음 · % · _ · 백슬래시 · zzz)와 겹치지 않는다.
     * 완료 행 셋의 메시지 순서(alpha · c · d)는 id 순서(alpha · d · c)와 일부러 다르게 둔다 — 메시지 정렬이 id 순서로 바뀌면 드러나게.
     * <pre>
     *   T−5h COMPLETED "alpha done"       T−4h FAILED "ALPHA retry"      T−3h FAILED "beta 노드 없음 a\b"
     *   T−2h COMPLETED "d under_score"    T−1h COMPLETED "c 100% placed"  T RUNNING (메시지 없음)
     * </pre>
     */
    private Seeded seedMessages() throws Exception {
        ScheduleRun.RunStatus[] statuses = {
                ScheduleRun.RunStatus.COMPLETED, ScheduleRun.RunStatus.FAILED, ScheduleRun.RunStatus.FAILED,
                ScheduleRun.RunStatus.COMPLETED, ScheduleRun.RunStatus.COMPLETED, ScheduleRun.RunStatus.RUNNING };
        ScheduleRun.TriggerType[] triggers = {
                ScheduleRun.TriggerType.MANUAL, ScheduleRun.TriggerType.AUTO, ScheduleRun.TriggerType.MANUAL,
                ScheduleRun.TriggerType.AUTO, ScheduleRun.TriggerType.MANUAL, ScheduleRun.TriggerType.MANUAL };
        String[] messages = { "alpha done", "ALPHA retry", "beta 노드 없음 a\\b", "d under_score", "c 100% placed", null };
        return seed("rkw-", statuses, triggers, messages);
    }

    private Seeded seed(String prefix, ScheduleRun.RunStatus[] statuses, ScheduleRun.TriggerType[] triggers,
                        String[] messages) throws Exception {
        String key = prefix + Long.toHexString(System.nanoTime());
        JsonNode schedule = objectMapper.readTree(mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleId\":\"" + key + "\",\"name\":\"" + key + "\",\"cronExpression\":\"0 0 2 * * ?\",\"resourceType\":\"GPU\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        long scheduleId = schedule.get("id").asLong();
        List<Long> runIds = new ArrayList<>();
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10).truncatedTo(ChronoUnit.SECONDS);
        try {
            for (int i = 0; i < 6; i++) {
                ScheduleRun run = new ScheduleRun(scheduleId, key, triggers[i], "tester");
                if (statuses[i] != ScheduleRun.RunStatus.RUNNING) run.finish(statuses[i], messages[i]);
                long id = runRepository.save(run).getId();
                runIds.add(id);
                backdate(id, base.minusHours(5 - i));
            }
            return new Seeded(scheduleId, key, runIds, base);
        } catch (Throwable e) {
            try {
                clean(scheduleId, runIds);
            } catch (Exception cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    /** 생성자가 채운 시작 시각을 덮어쓴다 — 저장 경로로는 만들 수 없는 과거 시각. */
    private void backdate(long runId, OffsetDateTime at) {
        tx.executeWithoutResult(s -> em.createQuery("update ScheduleRun r set r.startedAt = :t where r.id = :id")
                .setParameter("t", at).setParameter("id", runId).executeUpdate());
    }

    private long total(Seeded s, String... pairs) throws Exception {
        return page(s, pairs).get("totalElements").asLong();
    }

    private JsonNode items(Seeded s, String... pairs) throws Exception {
        return page(s, pairs).get("items");
    }

    private static List<String> messages(JsonNode items) {
        List<String> out = new ArrayList<>();
        for (JsonNode row : items) out.add(row.get("message").asString());
        return out;
    }

    private static List<Long> runIds(JsonNode items) {
        List<Long> out = new ArrayList<>();
        for (JsonNode row : items) out.add(row.get("runId").asLong());
        return out;
    }

    private JsonNode page(Seeded s, String... pairs) throws Exception {
        MockHttpServletRequestBuilder req = get(BASE + "/" + s.scheduleId() + "/runs");
        boolean sized = false;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            req = req.param(pairs[i], pairs[i + 1]);
            sized |= pairs[i].equals("size");
        }
        if (!sized) req = req.param("size", "100");
        String body = mockMvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    private void clean(Seeded s) throws Exception {
        clean(s.scheduleId(), s.runIds());
    }

    /** 심은 실행 행을 지우고 스케줄을 지운다(스케줄 삭제도 실행 행을 지우지만 시드 실패 경로와 대칭으로 명시). 정리는 확인하지 않는다. */
    private void clean(long scheduleId, List<Long> runIds) throws Exception {
        runRepository.deleteAllById(runIds);
        mockMvc.perform(delete(BASE).param("ids", String.valueOf(scheduleId)));
    }
}
