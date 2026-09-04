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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * 운영자가 MDC 3곳의 링크를 등록·수정·삭제하는 순서를 그대로 보내고, 매트릭스가 매 단계 맞게 바뀌는지 표로 남긴다.
 *
 * <pre>
 * 입력                                            예상
 * ①  1-2 UP 10ms 등록                               200  mdcA=1 mdcB=2
 * ②  3-1 DEGRADED 80ms 등록(역순 입력)               200  mdcA=1 mdcB=3 (정렬)
 * ③  2-3 DOWN 등록                                  200
 * ④  매트릭스                                       200  mdcIds=[1,2,3] · 대각 null · [1][2]==[2][1] · [1][3]=80
 * ⑤  3-1 재등록(역방향)                              409  "이미 등록된 MDC 쌍"
 * ⑥  1-1 등록                                       400  "같은 MDC"
 * ⑦  latencyMs -1                                   400  "0 이상"
 * ⑧  packetLossPercent 101                          400  "손실률"
 * ⑨  status "broken"                                400  "링크 상태"
 * ⑩  ② 수정: status UP, description null            200  status=UP description=null latency 80 유지
 * ⑪  ③ 삭제                                         200
 * ⑫  매트릭스 재확인                                 200  [2][3]=null · 목록 2건
 * ⑬  ③ 재삭제                                       404
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class MdcLinkScenarioTest {

    private static final String BASE = "/api/v1/scheduler/mdc-links";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    record Step(String no, String input, int expected, String expectMessage, int actual, String message) {}

    @DisplayName("시나리오 MDC 3곳 링크 등록 수정 삭제가 매트릭스에 그대로 반영된다")
    @Test
    void scenario_link_lifecycle_matrix() throws Exception {
        List<Step> steps = new ArrayList<>();
        String t = "scn-" + Long.toHexString(System.nanoTime());
        String m1 = t + "-1", m2 = t + "-2", m3 = t + "-3";
        List<Object> created = new ArrayList<>();

        try {
            MvcResult r1 = step(steps, "①", "1-2 UP 10ms", 200, null,
                    create("{\"mdcA\":\"" + m1 + "\",\"mdcB\":\"" + m2 + "\",\"latencyMs\":10}"));
            created.add(idOf(r1));
            assertEquals(m1, data(r1).get("mdcA"));

            MvcResult r2 = step(steps, "②", "3-1 DEGRADED 80ms (역순)", 200, null,
                    create("{\"mdcA\":\"" + m3 + "\",\"mdcB\":\"" + m1 + "\",\"latencyMs\":80,\"status\":\"DEGRADED\",\"description\":\"old\"}"));
            created.add(idOf(r2));
            assertEquals(m1, data(r2).get("mdcA"), "정렬되어 저장");
            assertEquals(m3, data(r2).get("mdcB"));

            MvcResult r3 = step(steps, "③", "2-3 DOWN", 200, null,
                    create("{\"mdcA\":\"" + m2 + "\",\"mdcB\":\"" + m3 + "\",\"latencyMs\":5,\"status\":\"down\"}"));
            created.add(idOf(r3));

            MvcResult mx = step(steps, "④", "매트릭스", 200, null, get(BASE + "/matrix"));
            JsonNode m = objectMapper.readTree(mx.getResponse().getContentAsString()).get("data");
            int i1 = indexOf(m.get("mdcIds"), m1), i2 = indexOf(m.get("mdcIds"), m2), i3 = indexOf(m.get("mdcIds"), m3);
            assertTrue(i1 < i2 && i2 < i3, "사전순");
            JsonNode links = m.get("links");
            assertTrue(links.get(i1).get(i1).isNull());
            assertEquals(links.get(i1).get(i2), links.get(i2).get(i1), "대칭");
            assertEquals(80.0, links.get(i1).get(i3).get("latencyMs").asDouble());
            assertEquals("DOWN", links.get(i2).get(i3).get("status").asString());

            step(steps, "⑤", "3-1 재등록(역방향)", 409, "이미 등록된 MDC 쌍",
                    create("{\"mdcA\":\"" + m3 + "\",\"mdcB\":\"" + m1 + "\",\"latencyMs\":1}"));
            step(steps, "⑥", "1-1 등록", 400, "같은 MDC",
                    create("{\"mdcA\":\"" + m1 + "\",\"mdcB\":\"" + m1 + "\",\"latencyMs\":1}"));
            step(steps, "⑦", "latencyMs -1", 400, "0 이상",
                    create("{\"mdcA\":\"" + m1 + "\",\"mdcB\":\"" + t + "-9\",\"latencyMs\":-1}"));
            step(steps, "⑧", "packetLossPercent 101", 400, "손실률",
                    create("{\"mdcA\":\"" + m1 + "\",\"mdcB\":\"" + t + "-9\",\"latencyMs\":1,\"packetLossPercent\":101}"));
            step(steps, "⑨", "status broken", 400, "링크 상태",
                    create("{\"mdcA\":\"" + m1 + "\",\"mdcB\":\"" + t + "-9\",\"latencyMs\":1,\"status\":\"broken\"}"));

            MvcResult u = step(steps, "⑩", "② 수정 UP, description null", 200, null,
                    put(BASE + "/" + created.get(1)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"up\",\"description\":null}"));
            assertEquals("UP", data(u).get("status"));
            assertNull(data(u).get("description"));
            assertEquals(80.0, data(u).get("latencyMs"), "키 없음은 유지");

            step(steps, "⑪", "③ 삭제", 200, null, delete(BASE + "/" + created.get(2)));

            MvcResult mx2 = step(steps, "⑫", "매트릭스 재확인", 200, null, get(BASE + "/matrix"));
            JsonNode m2n = objectMapper.readTree(mx2.getResponse().getContentAsString()).get("data");
            int j2 = indexOf(m2n.get("mdcIds"), m2), j3 = indexOf(m2n.get("mdcIds"), m3);
            assertTrue(m2n.get("links").get(j2).get(j3).isNull(), "삭제된 쌍은 null");
            MvcResult list = mockMvc.perform(get(BASE).param("mdcId", m1)).andReturn();
            assertEquals(2, objectMapper.readTree(list.getResponse().getContentAsString()).get("data").size());

            step(steps, "⑬", "③ 재삭제", 404, "찾을 수 없습니다", delete(BASE + "/" + created.get(2)));
        } finally {
            for (Object id : created) mockMvc.perform(delete(BASE + "/" + id));
        }

        StringBuilder table = new StringBuilder("\n| # | 입력 | 예상 | 실제 | 사유 |\n|---|---|---|---|---|\n");
        for (Step s : steps) {
            table.append(String.format("| %s | %s | %d | %d | %s |%n", s.no(), s.input(), s.expected(), s.actual(),
                    s.message() == null ? "" : s.message()));
        }
        System.out.println(table);
        for (Step s : steps) {
            assertEquals(s.expected(), s.actual(), "단계 " + s.no() + " 상태코드: " + s.message());
            if (s.expectMessage() != null) {
                assertTrue(s.message() != null && s.message().contains(s.expectMessage()),
                        "단계 " + s.no() + " 사유에 '" + s.expectMessage() + "' 가 없다: " + s.message());
            }
        }
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private MockHttpServletRequestBuilder create(String json) {
        return post(BASE).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private MvcResult step(List<Step> steps, String no, String input, int expected, String expectMessage,
                           MockHttpServletRequestBuilder request) throws Exception {
        MvcResult r = mockMvc.perform(request).andReturn();
        String body = r.getResponse().getContentAsString();
        String message = null;
        if (!body.isBlank()) {
            JsonNode msg = objectMapper.readTree(body).get("message");
            if (msg != null && !msg.isNull()) message = msg.asString();
        }
        steps.add(new Step(no, input, expected, expectMessage, r.getResponse().getStatus(), message));
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(MvcResult r) throws Exception {
        Map<String, Object> body = objectMapper.readValue(r.getResponse().getContentAsString(), Map.class);
        return (Map<String, Object>) body.get("data");
    }

    private Object idOf(MvcResult r) throws Exception {
        return data(r).get("id");
    }

    private static int indexOf(JsonNode array, String value) {
        for (int i = 0; i < array.size(); i++) if (value.equals(array.get(i).asString())) return i;
        return -1;
    }
}
