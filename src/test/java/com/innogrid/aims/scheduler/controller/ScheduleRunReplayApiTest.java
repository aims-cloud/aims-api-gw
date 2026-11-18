package com.innogrid.aims.scheduler.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.ScheduleRunInput;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.repository.ScheduleRunInputRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실행 재생 API와 엔진별 재계산 API의 응답 형식과 오류 — 재생 응답 14키·단계 8키, 엔진별 재계산 응답 12키·
 * 엔진 5키·단계 5키·선택 3키의 순서, 404 두 가지, 409 네 가지(엔진별 재계산은 같은 검사를 거쳐 세 가지만 다시 본다).
 *
 * <p>이 컨텍스트는 실 게이트웨이라 노드가 없어 즉시 실행으로는 입력이 남지 않는다. 그래서 형식 1 고정 픽스처
 * ({@code scheduler/replay/run-input-format-1.json})를 입력 행으로 심고 실행·결정 이력도 저장소에 직접 심는다. 재생 판정이
 * 실제 실행과 맞는지는 {@code RunInputReplayTest}가 본다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class ScheduleRunReplayApiTest {

    private static final String BASE = "/api/v1/scheduler/schedules";
    private static final String FIXTURE = "/scheduler/replay/run-input-format-1.json";
    /** 픽스처의 워크로드 id — 실재 행과 겹치지 않는 예약 대역(다른 이력 테스트의 9_9xx_xxx 관례). */
    private static final long WORKLOAD_ID = 9_930_101L;
    private static final List<String> RESPONSE_KEYS = List.of("runId", "formatVersion", "capturedAt", "engineId", "inputDigest",
            "knobsDigest", "verdict", "total", "matched", "mismatched", "skipped", "notRecorded", "missingDispatchRecords", "steps");
    private static final List<String> STEP_KEYS = List.of("workloadId", "outcome", "recordedNodeId", "recordedScore", "recordedSuccess",
            "replayedNodeId", "replayedScore", "replayedSuccess");
    private static final List<String> ENGINE_RESPONSE_KEYS = List.of("runId", "formatVersion", "capturedAt", "recordedEngineId", "inputDigest",
            "knobsDigest", "replayVerdict", "total", "skipped", "rolledBack", "engines", "steps");
    private static final List<String> ENGINE_KEYS = List.of("engineId", "recorded", "placed", "failed", "sameAsRecorded");
    private static final List<String> STEP_KEYS_BY_ENGINE = List.of("workloadId", "skipped", "recordedNodeId", "recordedSuccess", "choices");
    private static final List<String> CHOICE_KEYS = List.of("engineId", "nodeId", "success");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ScheduleRunRepository runRepository;
    @Autowired SchedulingHistoryRepository historyRepository;
    @Autowired ScheduleRunInputRepository inputRepository;

    @DisplayName("응답은 14키, 단계는 8키가 선언 순서대로 실리고 실패로 남은 결정을 다시 계산해 성공하면 MISMATCH 다")
    @Test
    void replay_response_keys_and_mismatch() throws Exception {
        String payload = fixture();
        long scheduleId = createSchedule();
        List<Long> historyIds = new ArrayList<>();
        try {
            long runId = finishedRun(scheduleId);
            inputRepository.save(input(runId, 1, digest(payload), payload));
            SchedulingHistory failed = new SchedulingHistory(WORKLOAD_ID, "rpl-w1", null, null, 0, "모든 후보 노드가 제약 조건을 충족하지 못합니다",
                    "weighted", "default");
            failed.setCandidateCount(2);
            failed.linkToRun(runId, false);
            historyIds.add(historyRepository.save(failed).getId());

            JsonNode body = data(get(BASE + "/" + scheduleId + "/runs/" + runId + "/replay"));

            assertEquals(RESPONSE_KEYS, keys(body));
            assertEquals(1, body.get("steps").size());
            JsonNode step = body.get("steps").get(0);
            assertEquals(STEP_KEYS, keys(step));
            assertEquals("MISMATCH", body.get("verdict").asString());
            assertEquals(digest(payload), body.get("inputDigest").asString());
            assertEquals(WORKLOAD_ID, step.get("workloadId").asLong());
            assertEquals("MISMATCH", step.get("outcome").asString());
            assertFalse(step.get("recordedSuccess").asBoolean());
            assertTrue(step.get("replayedSuccess").asBoolean());
            assertFalse(step.get("replayedNodeId").isNull());
            assertFalse(body.has("payload"), "저장한 입력 원문은 싣지 않는다");
        } finally {
            historyRepository.deleteAllById(historyIds);
            deleteSchedule(scheduleId);
        }
    }

    @DisplayName("저장한 입력이 없는 실행과 다른 스케줄의 실행은 404 다")
    @Test
    void replay_without_input_or_of_another_schedule_is_404() throws Exception {
        long scheduleId = createSchedule();
        long otherId = createSchedule();
        try {
            long runId = finishedRun(scheduleId);
            mockMvc.perform(get(BASE + "/" + scheduleId + "/runs/" + runId + "/replay")).andExpect(status().isNotFound());

            String payload = fixture();
            inputRepository.save(input(runId, 1, digest(payload), payload));
            mockMvc.perform(get(BASE + "/" + otherId + "/runs/" + runId + "/replay")).andExpect(status().isNotFound());
        } finally {
            deleteSchedule(scheduleId);
            deleteSchedule(otherId);
        }
    }

    @DisplayName("실행 중인 실행·형식 판 2·지문이 다른 입력·지금 코드로 읽을 수 없는 입력은 409 다")
    @Test
    void replay_of_running_run_unknown_format_or_altered_payload_is_409() throws Exception {
        String payload = fixture();
        long scheduleId = createSchedule();
        try {
            long running = runRepository.save(new ScheduleRun(scheduleId, "rpl", ScheduleRun.TriggerType.MANUAL, "tester")).getId();
            inputRepository.save(input(running, 1, digest(payload), payload));
            long version2 = finishedRun(scheduleId);
            inputRepository.save(input(version2, 2, digest(payload), payload));
            long altered = finishedRun(scheduleId);
            inputRepository.save(input(altered, 1, digest(payload), payload.replace("rpl-w1", "rpl-w2")));
            long unreadable = finishedRun(scheduleId);   // 지문은 맞지만 지금 코드에 없는 열거값 — 처리하지 않으면 본문 없는 403이 된다
            String unknownState = payload.replace("\"thermalState\": \"NORMAL\"", "\"thermalState\": \"MELTING\"");
            assertNotEquals(payload, unknownState);
            inputRepository.save(input(unreadable, 1, digest(unknownState), unknownState));

            for (long runId : List.of(running, version2, altered, unreadable)) {
                mockMvc.perform(get(BASE + "/" + scheduleId + "/runs/" + runId + "/replay")).andExpect(status().isConflict());
            }
        } finally {
            deleteSchedule(scheduleId);
        }
    }

    @DisplayName("엔진별 재계산 응답은 12키, 엔진 5키, 단계 5키, 선택 3키가 선언 순서대로 실리고 엔진 순서가 고정이다")
    @Test
    void engine_replay_response_keys_and_engine_order() throws Exception {
        String payload = fixture();
        long scheduleId = createSchedule();
        List<Long> historyIds = new ArrayList<>();
        try {
            long runId = finishedRun(scheduleId);
            inputRepository.save(input(runId, 1, digest(payload), payload));
            SchedulingHistory failed = new SchedulingHistory(WORKLOAD_ID, "rpl-w1", null, null, 0, "모든 후보 노드가 제약 조건을 충족하지 못합니다",
                    "weighted", "default");
            failed.setCandidateCount(2);
            failed.linkToRun(runId, false);
            historyIds.add(historyRepository.save(failed).getId());

            JsonNode body = data(get(BASE + "/" + scheduleId + "/runs/" + runId + "/replay/engines"));

            assertEquals(ENGINE_RESPONSE_KEYS, keys(body));
            assertEquals("weighted", body.get("recordedEngineId").asString());
            assertEquals("MISMATCH", body.get("replayVerdict").asString(), "실패로 남은 결정을 다시 계산해 성공하면 재생 판정은 MISMATCH");
            List<String> order = new ArrayList<>();
            for (JsonNode e : body.get("engines")) {
                assertEquals(ENGINE_KEYS, keys(e));
                order.add(e.get("engineId").asString());
            }
            assertEquals(List.of("weighted", "power", "temperature"), order);
            JsonNode step = body.get("steps").get(0);
            assertEquals(STEP_KEYS_BY_ENGINE, keys(step));
            assertEquals(3, step.get("choices").size());
            assertEquals(CHOICE_KEYS, keys(step.get("choices").get(0)));
            assertFalse(body.has("payload"), "저장한 입력 원문은 싣지 않는다");
        } finally {
            historyRepository.deleteAllById(historyIds);
            deleteSchedule(scheduleId);
        }
    }

    @DisplayName("엔진별 재계산도 입력 없는 실행·다른 스케줄의 실행은 404, 실행 중·형식 판 2·지문이 다른 입력은 409 다")
    @Test
    void engine_replay_shares_replay_errors() throws Exception {
        String payload = fixture();
        long scheduleId = createSchedule();
        long otherId = createSchedule();
        try {
            long noInput = finishedRun(scheduleId);
            mockMvc.perform(get(BASE + "/" + scheduleId + "/runs/" + noInput + "/replay/engines")).andExpect(status().isNotFound());
            long done = finishedRun(scheduleId);
            inputRepository.save(input(done, 1, digest(payload), payload));
            mockMvc.perform(get(BASE + "/" + otherId + "/runs/" + done + "/replay/engines")).andExpect(status().isNotFound());

            long running = runRepository.save(new ScheduleRun(scheduleId, "rpl", ScheduleRun.TriggerType.MANUAL, "tester")).getId();
            inputRepository.save(input(running, 1, digest(payload), payload));
            long version2 = finishedRun(scheduleId);
            inputRepository.save(input(version2, 2, digest(payload), payload));
            long altered = finishedRun(scheduleId);
            inputRepository.save(input(altered, 1, digest(payload), payload.replace("rpl-w1", "rpl-w2")));
            for (long runId : List.of(running, version2, altered)) {
                mockMvc.perform(get(BASE + "/" + scheduleId + "/runs/" + runId + "/replay/engines")).andExpect(status().isConflict());
            }
        } finally {
            deleteSchedule(scheduleId);
            deleteSchedule(otherId);
        }
    }

    @DisplayName("저장한 입력 원문이 없으면 두 경로 모두 409 다 — 지문 계산에서 본문 없는 403 으로 새지 않는다")
    @Test
    void missing_payload_is_409_on_both_paths() throws Exception {
        long scheduleId = createSchedule();
        try {
            long runId = finishedRun(scheduleId);
            inputRepository.save(input(runId, 1, "000000000000", null));

            expectReason(runId, scheduleId, status().isConflict(), "저장한 입력 원문이 없습니다");
        } finally {
            deleteSchedule(scheduleId);
        }
    }

    @DisplayName("저장한 설정 노브의 형식이 지금 코드와 다르면 두 경로 모두 409 다")
    @Test
    void knob_of_another_shape_is_409_on_both_paths() throws Exception {
        String payload = fixture();
        String knobs = payload.replace("\"knobs\": {\"power\": {}", "\"knobs\": {\"power\": {\"idleRatio\": {\"v\": 1}}");
        assertNotEquals(payload, knobs);
        long scheduleId = createSchedule();
        try {
            long runId = finishedRun(scheduleId);
            inputRepository.save(input(runId, 1, digest(knobs), knobs));

            expectReason(runId, scheduleId, status().isConflict(), "저장한 설정 노브를 지금 코드에 적용할 수 없습니다");
        } finally {
            deleteSchedule(scheduleId);
        }
    }

    @DisplayName("원문이 null 한 단어이거나 엔진이 빠진 입력은 두 경로 모두 409 다 — 지문은 맞아도 본문 없는 403 으로 새지 않는다")
    @Test
    void null_literal_or_engineless_input_is_409_on_both_paths() throws Exception {
        ObjectNode tree = (ObjectNode) objectMapper.readTree(fixture());
        tree.remove("engineId");
        String engineless = objectMapper.writeValueAsString(tree);
        long scheduleId = createSchedule();
        try {
            long nullLiteral = finishedRun(scheduleId);
            inputRepository.save(input(nullLiteral, 1, digest("null"), "null"));
            long noEngine = finishedRun(scheduleId);
            inputRepository.save(input(noEngine, 1, digest(engineless), engineless));

            expectReason(nullLiteral, scheduleId, status().isConflict(), "저장한 입력에 엔진이 없습니다");
            expectReason(noEngine, scheduleId, status().isConflict(), "저장한 입력에 엔진이 없습니다");
        } finally {
            deleteSchedule(scheduleId);
        }
    }

    @DisplayName("설정 노브 묶음 자체가 비면 두 경로 모두 사유를 실은 500 이다 — 본문 없는 403 으로 되돌아가지 않는다")
    @Test
    void missing_knobs_is_500_with_reason_on_both_paths() throws Exception {
        ObjectNode tree = (ObjectNode) objectMapper.readTree(fixture());
        tree.putNull("knobs");
        String noKnobs = objectMapper.writeValueAsString(tree);
        long scheduleId = createSchedule();
        try {
            long runId = finishedRun(scheduleId);
            inputRepository.save(input(runId, 1, digest(noKnobs), noKnobs));

            expectReason(runId, scheduleId, status().isInternalServerError(), "저장한 입력으로 다시 계산할 수 없습니다");
        } finally {
            deleteSchedule(scheduleId);
        }
    }

    @DisplayName("다시 계산하다 예외가 나면 실행 재생은 사유를 실은 500, 엔진별 재계산은 엔진 id 를 실은 500 이다")
    @Test
    void recompute_failure_is_500_with_engine_in_reason() throws Exception {
        ObjectNode tree = (ObjectNode) objectMapper.readTree(fixture());
        tree.putNull("policy");
        String noPolicy = objectMapper.writeValueAsString(tree);
        long scheduleId = createSchedule();
        try {
            long runId = finishedRun(scheduleId);
            inputRepository.save(input(runId, 1, digest(noPolicy), noPolicy));

            mockMvc.perform(get(BASE + "/" + scheduleId + "/runs/" + runId + "/replay"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value(containsString("실행 재생에 실패했습니다")));
            mockMvc.perform(get(BASE + "/" + scheduleId + "/runs/" + runId + "/replay/engines"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value(containsString("엔진별 재계산에 실패했습니다: 엔진 weighted")));
        } finally {
            deleteSchedule(scheduleId);
        }
    }

    // ── 시딩 ───────────────────────────────────────────────────

    /** 실행 재생과 엔진별 재계산이 같은 상태 코드와 사유를 돌려주는지 — 두 경로가 같은 검사를 거친다. */
    private void expectReason(long runId, long scheduleId, ResultMatcher expected, String reason) throws Exception {
        for (String path : List.of("/replay", "/replay/engines")) {
            mockMvc.perform(get(BASE + "/" + scheduleId + "/runs/" + runId + path))
                    .andExpect(expected)
                    .andExpect(jsonPath("$.message").value(containsString(reason)));
        }
    }

    private long createSchedule() throws Exception {
        String key = "rplapi-" + Long.toHexString(System.nanoTime());
        JsonNode schedule = objectMapper.readTree(mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleId\":\"" + key + "\",\"name\":\"" + key + "\",\"cronExpression\":\"0 0 2 * * ?\",\"resourceType\":\"GPU\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        return schedule.get("id").asLong();
    }

    private long finishedRun(long scheduleId) {
        ScheduleRun run = new ScheduleRun(scheduleId, "rpl", ScheduleRun.TriggerType.MANUAL, "tester");
        run.recordCounts(1, 0, 1, false);
        run.finish(ScheduleRun.RunStatus.FAILED, "테스트용");
        return runRepository.save(run).getId();
    }

    private static ScheduleRunInput input(long runId, int formatVersion, String inputDigest, String payload) {
        return new ScheduleRunInput(runId, formatVersion, OffsetDateTime.of(2026, 10, 29, 0, 0, 0, 0, ZoneOffset.UTC),
                "3f2a91c0d4e7", inputDigest, payload);
    }

    /** 스케줄 삭제가 실행 이력과 실행 입력을 함께 지운다. 정리는 확인하지 않아 본문의 원인을 가리지 않는다. */
    private void deleteSchedule(long scheduleId) throws Exception {
        mockMvc.perform(delete(BASE).param("ids", String.valueOf(scheduleId)));
    }

    private String fixture() throws Exception {
        try (var in = getClass().getResourceAsStream(FIXTURE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 저장 경로와 같은 지문 — 원문 UTF-8의 SHA-256 앞 12자. */
    private static String digest(String payload) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash).substring(0, 12);
    }

    private JsonNode data(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) throws Exception {
        String body = mockMvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    private static List<String> keys(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.propertyNames().forEach(keys::add);
        return keys;
    }
}
