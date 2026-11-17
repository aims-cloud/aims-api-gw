package com.innogrid.aims.scheduler.controller;

import java.util.ArrayList;
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
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * 운영자가 저지를 법한 실수를 순서대로 보내는 시나리오 — 상태 코드와 사유 문구를 표로 남긴다.
 *
 * <pre>
 * 입력                                      예상
 * ① 깨진 JSON                                400  "요청 본문을 읽을 수 없습니다"
 * ② 요구 CPU 누락                            400  "요구 CPU"                    (예전 500)
 * ③ 요구 CPU 에 문자열 "abc"                  400  "요청 본문"                    (예전 500)
 * ④ 모르는 리소스 종류 "quantum"              400  "리소스 종류"
 * ⑤ 정상 생성 ("vm"·"high" 소문자)            200  resourceType=VM priority=HIGH
 * ⑥ 같은 워크로드 PENDING→RUNNING             400  "허용되지 않는 상태 전이"
 * ⑦ 정책 생성, 가중치 음수                    400  "가중치는 0 이상"
 * ⑧ 정책 정상 생성                            200
 * ⑨ 같은 이름으로 다시 생성                    409  "이미 있는 정책 이름"          (예전 500)
 * ⑩ 그 정책을 참조하는 스케줄 생성 후 정책 삭제  409  "스케줄이 참조하는 정책"       (예전 200)
 * ⑪ 없는 워크로드 삭제                         404  "워크로드를 찾을 수 없습니다"  (예전 200)
 * ⑫ 제외 노드 목록                            200  []
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class RequestValidationScenarioTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    record Step(String no, String input, int expected, String expectMessage, int actual, String message) {}

    @DisplayName("시나리오 운영자 실수 시퀀스가 설계된 상태코드와 사유로 거부된다")
    @Test
    void scenario_operator_mistakes() throws Exception {
        List<Step> steps = new ArrayList<>();
        String tag = String.valueOf(System.nanoTime());

        step(steps, "①", "깨진 JSON", 400, "요청 본문을 읽을 수 없습니다",
                post("/api/v1/scheduler/workloads").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"));
        step(steps, "②", "요구 CPU 누락", 400, "요구 CPU",
                post("/api/v1/scheduler/workloads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"s\",\"requiredMemoryMb\":512}"));
        step(steps, "③", "요구 CPU 문자열", 400, "요청 본문",
                post("/api/v1/scheduler/workloads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"s\",\"requiredCpu\":\"abc\",\"requiredMemoryMb\":512}"));
        step(steps, "④", "모르는 리소스 종류", 400, "리소스 종류",
                post("/api/v1/scheduler/workloads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"s\",\"requiredCpu\":1,\"requiredMemoryMb\":512,\"resourceType\":\"quantum\"}"));

        MvcResult created = step(steps, "⑤", "정상 생성(소문자 열거형)", 200, null,
                post("/api/v1/scheduler/workloads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"scn-" + tag + "\",\"requiredCpu\":1,\"requiredMemoryMb\":512,"
                                + "\"resourceType\":\"vm\",\"priority\":\"high\"}"));
        long workloadId = idOf(created);
        assertEquals("VM", data(created).get("resourceType"));
        assertEquals("HIGH", data(created).get("priority"));

        step(steps, "⑥", "PENDING→RUNNING", 400, "허용되지 않는 상태 전이",
                patch("/api/v1/scheduler/workloads/" + workloadId + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"running\"}"));

        String policyName = "scn-policy-" + tag;
        step(steps, "⑦", "정책 가중치 음수", 400, "가중치는 0 이상",
                post("/api/v1/scheduler/policies").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + policyName + "\",\"cpuWeight\":-1}"));
        MvcResult policy = step(steps, "⑧", "정책 정상 생성", 200, null,
                post("/api/v1/scheduler/policies").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + policyName + "\",\"defaultEngine\":\"power\"}"));
        long policyId = idOf(policy);
        step(steps, "⑨", "같은 이름 정책 재생성", 409, "이미 있는 정책 이름",
                post("/api/v1/scheduler/policies").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + policyName + "\"}"));

        MvcResult schedule = mockMvc.perform(post("/api/v1/scheduler/schedules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scheduleId\":\"scn-" + tag + "\",\"name\":\"s\",\"cronExpression\":\"0 0 3 * * ?\","
                        + "\"resourceType\":\"VM\",\"policyId\":" + policyId + "}")).andReturn();
        assertEquals(200, schedule.getResponse().getStatus());
        step(steps, "⑩", "참조 중 정책 삭제", 409, "스케줄이 참조하는 정책",
                delete("/api/v1/scheduler/policies/" + policyId));

        step(steps, "⑪", "없는 워크로드 삭제", 404, "워크로드를 찾을 수 없습니다",
                delete("/api/v1/scheduler/workloads/999999"));
        MvcResult excluded = step(steps, "⑫", "제외 노드 목록", 200, null, get("/api/v1/scheduler/nodes/excluded"));
        assertTrue(excluded.getResponse().getContentAsString().contains("\"data\":[]"));

        // 정리
        mockMvc.perform(delete("/api/v1/scheduler/schedules?ids=" + idOf(schedule)));
        mockMvc.perform(delete("/api/v1/scheduler/policies/" + policyId));
        mockMvc.perform(delete("/api/v1/scheduler/workloads/" + workloadId));

        print(steps);
        for (Step s : steps) {
            assertEquals(s.expected(), s.actual(), s.no() + " " + s.input() + " 상태 코드");
            if (s.expectMessage() != null) {
                assertTrue(s.message() != null && s.message().contains(s.expectMessage()),
                        s.no() + " 사유에 '" + s.expectMessage() + "' 이 있어야 한다: " + s.message());
            }
        }
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private MvcResult step(List<Step> steps, String no, String input, int expected, String expectMessage,
                           org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) throws Exception {
        MvcResult r = mockMvc.perform(req).andReturn();
        String message = null;
        try {
            Object m = objectMapper.readValue(r.getResponse().getContentAsString(), Map.class).get("message");
            message = m == null ? null : m.toString();
        } catch (Exception ignored) {
            // 본문이 JSON이 아니면 사유 없음
        }
        steps.add(new Step(no, input, expected, expectMessage, r.getResponse().getStatus(), message));
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(MvcResult r) throws Exception {
        return (Map<String, Object>) objectMapper.readValue(r.getResponse().getContentAsString(), Map.class).get("data");
    }

    private long idOf(MvcResult r) throws Exception {
        return ((Number) data(r).get("id")).longValue();
    }

    private static void print(List<Step> steps) {
        StringBuilder sb = new StringBuilder("\n[요청 검증 시나리오 — 운영자 실수 시퀀스]\n");
        sb.append(String.format("  %-3s %-24s %-6s %-6s %s%n", "#", "입력", "예상", "실제", "사유"));
        for (Step s : steps) {
            String msg = s.message() == null ? "" : (s.message().length() > 60 ? s.message().substring(0, 60) + "…" : s.message());
            sb.append(String.format("  %-3s %-24s %-6d %-6d %s%n", s.no(), s.input(), s.expected(), s.actual(), msg));
        }
        System.out.println(sb);
    }
}
