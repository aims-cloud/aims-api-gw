package com.innogrid.aims.scheduler.controller;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.service.SchedulingService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * 운영자가 정책을 만들고 바꾸는 순서를 그대로 보내고, 활성 규칙·전환·`default` 처리·이력 페이징이 설계대로 답하는지 표로 남긴다.
 *
 * <pre>
 * 입력                                              예상
 * ①  빈 상태에서 공통 A 생성                          200  active=true  (첫 정책은 바로 쓰인다)
 * ②  공통 B 생성                                      200  active=false (활성이 있어 비활성으로)
 * ③  B PUT active:true                                409  "이미 활성 정책이 있습니다: A"
 * ④  B activate                                       200  activated=B, deactivated=[A]
 * ⑤  B PUT active:false                               200  공통 활성 0
 * ⑥  활성 0 상태에서 정책 해석(dryRun 경로)            정책명 default, 정책 행 수 +1 (default 없으므로 생성)
 * ⑦  default PUT active:false → 다시 해석             정책명 default, 행 수 불변 (비활성 default 를 저장 없이 사용)
 * ⑧  GPU 정책 G 생성                                  200  active=true  (종류 분리)
 * ⑨  G PUT resourceType:null (공통으로)                200  공통 활성이 0 이라 허용
 * ⑨' A activate 후 G PUT resourceType:null 재시도      409  공통 활성 A 와 충돌 → G 는 GPU 그대로
 * ⑩  이력 30건 page=2 size=10                          200  items 10, totalPages 3
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class PolicyRulesScenarioTest {

    private static final String P = "/api/v1/scheduler/policies";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SchedulingPolicyRepository policyRepository;
    @Autowired SchedulingHistoryRepository historyRepository;
    @Autowired SchedulingService schedulingService;

    record Step(String no, String input, String expected, String actual) {}

    private List<SchedulingPolicy> snapshot;
    private final List<Long> seededHistory = new ArrayList<>();

    @BeforeEach
    void isolate() {
        snapshot = policyRepository.findAll();
        policyRepository.deleteAll();
    }

    @AfterEach
    void restore() {
        historyRepository.deleteAllById(seededHistory);
        policyRepository.deleteAll();
        for (SchedulingPolicy p : snapshot) {
            SchedulingPolicy copy = new SchedulingPolicy(p.getName(), p.getDescription());
            copy.setResourceType(p.getResourceType());
            copy.setActive(p.isActive());
            policyRepository.save(copy);
        }
    }

    @DisplayName("시나리오 정책 생성 전환 비활성 default 페이징이 설계대로 동작한다")
    @Test
    void scenario_policy_lifecycle() throws Exception {
        List<Step> steps = new ArrayList<>();

        JsonNode a = body(post(P).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"scn-a\"}"));
        step(steps, "①", "빈 상태 공통 A 생성", "200 active=true", code(a) + " active=" + a.path("data").path("active"));
        assertTrue(a.path("data").path("active").asBoolean());

        JsonNode b = body(post(P).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"scn-b\"}"));
        step(steps, "②", "공통 B 생성", "200 active=false", code(b) + " active=" + b.path("data").path("active"));
        assertFalse(b.path("data").path("active").asBoolean());
        long aId = a.path("data").path("id").asLong(), bId = b.path("data").path("id").asLong();

        JsonNode r3 = body(put(P + "/" + bId).contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"));
        step(steps, "③", "B PUT active:true", "409 이미 활성 정책: A", code(r3) + " " + r3.path("message").asString());
        assertEquals(409, status(r3));
        assertTrue(r3.path("message").asString().contains("scn-a"));

        JsonNode r4 = body(post(P + "/" + bId + "/activate"));
        step(steps, "④", "B activate", "200 activated=B deactivated=[A]",
                code(r4) + " activated=" + r4.path("data").path("activated").path("name").asString()
                        + " deactivated=" + r4.path("data").path("deactivated").size());
        assertEquals("scn-b", r4.path("data").path("activated").path("name").asString());
        assertEquals("scn-a", r4.path("data").path("deactivated").get(0).path("name").asString());

        JsonNode r5 = body(put(P + "/" + bId).contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"));
        step(steps, "⑤", "B PUT active:false", "200, 공통 활성 0", code(r5) + " 활성=" + policyRepository.findByActiveTrueAndResourceTypeIsNullOrderByNameAsc().size());
        assertEquals(0, policyRepository.findByActiveTrueAndResourceTypeIsNullOrderByNameAsc().size());

        long before = policyRepository.count();
        SchedulingPolicy resolved = schedulingService.resolvePolicy();
        step(steps, "⑥", "활성 0 에서 정책 해석", "default 생성(+1)", resolved.getName() + " 행 " + before + "→" + policyRepository.count());
        assertEquals("default", resolved.getName());
        assertEquals(before + 1, policyRepository.count());

        long defaultId = policyRepository.findByName("default").orElseThrow().getId();
        body(put(P + "/" + defaultId).contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"));
        long before7 = policyRepository.count();
        SchedulingPolicy resolved7 = assertDoesNotThrow(() -> schedulingService.resolvePolicy());
        step(steps, "⑦", "default 비활성 후 해석", "default 사용, 행 불변(예전 500)", resolved7.getName() + " 행 " + before7 + "→" + policyRepository.count());
        assertEquals(before7, policyRepository.count());
        assertFalse(policyRepository.findByName("default").orElseThrow().isActive(), "재활성화하지 않는다");

        JsonNode g = body(post(P).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"scn-g\",\"resourceType\":\"GPU\"}"));
        step(steps, "⑧", "GPU 정책 G 생성", "200 active=true(종류 분리)", code(g) + " active=" + g.path("data").path("active"));
        assertTrue(g.path("data").path("active").asBoolean());
        long gId = g.path("data").path("id").asLong();

        JsonNode r9 = body(put(P + "/" + gId).contentType(MediaType.APPLICATION_JSON).content("{\"resourceType\":null}"));
        step(steps, "⑨", "G 를 공통으로(공통 활성 0)", "200", code(r9) + " type=" + r9.path("data").path("resourceType"));
        assertEquals(200, status(r9));
        body(put(P + "/" + gId).contentType(MediaType.APPLICATION_JSON).content("{\"resourceType\":\"GPU\"}"));   // 되돌림

        body(post(P + "/" + aId + "/activate"));
        JsonNode r9b = body(put(P + "/" + gId).contentType(MediaType.APPLICATION_JSON).content("{\"resourceType\":null}"));
        step(steps, "⑨'", "A 활성 후 G 를 공통으로", "409 공통 활성 A 충돌", code(r9b) + " " + r9b.path("message").asString());
        assertEquals(409, status(r9b));
        assertEquals("GPU", policyRepository.findById(gId).orElseThrow().getResourceType().name());

        for (int i = 0; i < 30; i++) {
            seededHistory.add(historyRepository.save(new SchedulingHistory(9_800_000L, "scn-h-" + i, "mdc-01", "n1",
                    i / 100.0, "r", "weighted", "default")).getId());
        }
        JsonNode r10 = body(get("/api/v1/scheduler/history/page").param("workloadId", "9800000").param("page", "2").param("size", "10"));
        step(steps, "⑩", "이력 30건 page=2 size=10", "items 10 totalPages 3",
                code(r10) + " items=" + r10.path("data").path("items").size() + " totalPages=" + r10.path("data").path("totalPages"));
        assertEquals(10, r10.path("data").path("items").size());
        assertEquals(3, r10.path("data").path("totalPages").asInt());

        StringBuilder t = new StringBuilder("\n| # | 입력 | 예상 | 실제 |\n|---|---|---|---|\n");
        for (Step s : steps) t.append(String.format("| %s | %s | %s | %s |%n", s.no(), s.input(), s.expected(), s.actual()));
        System.out.println(t);
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private JsonNode body(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult r = mockMvc.perform(req).andReturn();
        String txt = r.getResponse().getContentAsString();
        JsonNode n = txt.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(txt);
        ((tools.jackson.databind.node.ObjectNode) n).put("__status", r.getResponse().getStatus());
        return n;
    }

    private static int status(JsonNode n) { return n.path("__status").asInt(); }
    private static String code(JsonNode n) { return String.valueOf(status(n)); }

    private static void step(List<Step> steps, String no, String input, String expected, String actual) {
        steps.add(new Step(no, input, expected, actual));
    }
}
