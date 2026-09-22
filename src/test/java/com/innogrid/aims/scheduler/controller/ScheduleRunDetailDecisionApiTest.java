package com.innogrid.aims.scheduler.controller;

import java.util.ArrayList;
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

import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실행 상세 결정 목록의 응답 계약 — 결정 한 줄은 13키이고 {@code exclusionCodes} 가 맨 끝에 문자열 그대로 실린다.
 * 배치 이력 페이징의 실행 필터({@code scheduleRunId})는 이 결정 목록과 모집단이 같다.
 *
 * <p>이 컨텍스트는 실 게이트웨이라 노드가 없어 즉시 실행으로는 결정이 생기지 않는다. 그래서 실행 이력과 결정 이력 행을
 * 저장소에 직접 심고 HTTP 로 읽는다 — 컴포넌트 수는 {@code ScheduleSchemaContractTest} 가 세고, 여기서는 JSON 키 집합·순서를 본다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class ScheduleRunDetailDecisionApiTest {

    private static final String BASE = "/api/v1/scheduler/schedules";
    private static final String HISTORY_PAGE = "/api/v1/scheduler/history/page";
    private static final List<String> DECISION_KEYS = List.of("historyId", "workloadId", "workloadName", "success", "selectedMdcId",
            "selectedNodeId", "finalScore", "reason", "candidateCount", "engineName", "policyName", "decidedAt", "exclusionCodes");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ScheduleRunRepository runRepository;
    @Autowired SchedulingHistoryRepository historyRepository;

    @DisplayName("결정 한 줄은 13키이고 exclusionCodes 가 맨 끝에 문자열 그대로, 집계가 없는 결정은 null 로 실린다")
    @Test
    void decision_row_has_13_keys_with_exclusion_codes_last() throws Exception {
        Seeded s = seed();
        try {
            JsonNode decisions = runDetail(s).get("decisions");
            assertEquals(2, decisions.size());
            for (JsonNode row : decisions) {
                List<String> keys = new ArrayList<>();
                row.propertyNames().forEach(keys::add);
                assertEquals(DECISION_KEYS, keys, "결정 한 줄의 키 13개와 순서 — exclusionCodes 는 맨 끝");
            }
            JsonNode failedRow = decisions.get(0).get("workloadName").asString().endsWith("-fail") ? decisions.get(0) : decisions.get(1);
            JsonNode okRow = failedRow == decisions.get(0) ? decisions.get(1) : decisions.get(0);
            assertEquals("{\"INSUFFICIENT_RESOURCE\":1}", failedRow.get("exclusionCodes").asString(), "문자열 그대로 — 객체로 풀지 않는다");
            assertTrue(okRow.get("exclusionCodes").isNull(), "집계가 없는 결정은 null");

            JsonNode runs = data(get(BASE + "/" + s.scheduleId() + "/runs"));
            JsonNode first = runs.get("items").get(0);   // 실행 이력 목록은 페이지 응답이다
            assertFalse(first.has("exclusionCodes"), "실행 이력 목록 1행에는 실리지 않는다");
        } finally {
            clean(s);
        }
    }

    /** 실행 필터가 실행 상세와 다른 모집단을 보면 두 화면의 건수가 어긋난다 — 같은 시딩으로 두 응답을 맞대어 고정한다. */
    @DisplayName("배치 이력의 실행 필터는 실행 상세 결정 목록과 같은 행을 같은 값으로 돌려준다")
    @Test
    void history_page_run_filter_matches_run_detail_decisions() throws Exception {
        Seeded s = seed();
        try {
            Map<Long, String> fromDetail = new HashMap<>();
            for (JsonNode row : runDetail(s).get("decisions")) {
                fromDetail.put(row.get("historyId").asLong(), codes(row));
            }
            assertEquals(2, fromDetail.size(), "실행 상세의 결정도 두 건 — 아래 이력 쪽 총건수와 맞대는 기준");
            JsonNode page = data(get(HISTORY_PAGE).param("size", "100").param("scheduleRunId", String.valueOf(s.runId())));
            assertEquals(2, page.get("totalElements").asInt(), "실행 하나가 만든 결정 두 건");
            Map<Long, String> fromPage = new HashMap<>();
            for (JsonNode row : page.get("items")) {
                assertEquals(s.runId(), row.get("scheduleRunId").asLong());
                fromPage.put(row.get("id").asLong(), codes(row));
            }
            assertEquals(fromDetail, fromPage, "행 id 와 제외 코드 집계가 두 응답에서 같다");
        } finally {
            clean(s);
        }
    }

    // ── 시딩 ───────────────────────────────────────────────────

    private record Seeded(long scheduleId, long runId, List<Long> historyIds) { }

    /** 스케줄 1 · 실행 1(대상 2 · 성공 1 · 실패 1) · 결정 2(실패 한 건은 집계 있음, 성공 한 건은 없음). */
    private Seeded seed() throws Exception {
        String key = "rdapi-" + Long.toHexString(System.nanoTime());
        JsonNode schedule = objectMapper.readTree(mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleId\":\"" + key + "\",\"name\":\"" + key + "\",\"cronExpression\":\"0 0 2 * * ?\",\"resourceType\":\"GPU\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        long scheduleId = schedule.get("id").asLong();
        List<Long> historyIds = new ArrayList<>();
        try {
            ScheduleRun run = new ScheduleRun(scheduleId, key, ScheduleRun.TriggerType.MANUAL, "tester");
            run.recordCounts(2, 1, 1, false);
            run.finish(ScheduleRun.RunStatus.FAILED, "시험용");
            run = runRepository.save(run);
            // 워크로드 id 는 실재 행과 겹치지 않는 예약 대역(다른 이력 시험의 9_9xx_xxx 관례)
            SchedulingHistory failed = new SchedulingHistory(9_930_001L, key + "-fail", null, null, 0, "모든 후보 노드가 제약 조건을 충족하지 못합니다", "weighted", "default");
            failed.setCandidateCount(1);
            failed.setExclusionCodes("{\"INSUFFICIENT_RESOURCE\":1}");
            failed.linkToRun(run.getId(), false);
            historyIds.add(historyRepository.save(failed).getId());
            SchedulingHistory ok = new SchedulingHistory(9_930_002L, key + "-ok", "mdc-01", "n1", 0.7, "최고 점수", "weighted", "default");
            ok.setCandidateCount(1);
            ok.linkToRun(run.getId(), true);
            historyIds.add(historyRepository.save(ok).getId());
            return new Seeded(scheduleId, run.getId(), historyIds);
        } catch (Throwable e) {
            try {
                clean(scheduleId, historyIds);
            } catch (Exception cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    private JsonNode runDetail(Seeded s) throws Exception {
        return data(get(BASE + "/" + s.scheduleId() + "/runs/" + s.runId()));
    }

    private JsonNode data(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) throws Exception {
        String body = mockMvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    private static String codes(JsonNode row) {
        JsonNode c = row.get("exclusionCodes");
        return c.isNull() ? null : c.asString();
    }

    private void clean(Seeded s) throws Exception {
        clean(s.scheduleId(), s.historyIds());
    }

    /** 결정 행을 지운 뒤 스케줄을 지운다 — 스케줄 삭제가 실행 이력 행까지 지운다. 정리는 단언하지 않아 본문의 원인을 가리지 않는다. */
    private void clean(long scheduleId, List<Long> historyIds) throws Exception {
        historyRepository.deleteAllById(historyIds);
        mockMvc.perform(delete(BASE).param("ids", String.valueOf(scheduleId)));
    }
}
