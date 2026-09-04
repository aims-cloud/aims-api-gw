package com.innogrid.aims.scheduler.controller;

import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.innogrid.aims.scheduler.dto.MdcLinkResponseGoldenTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /scheduler/mdc-links} 6종의 HTTP 계약 — 필드 집합, 상태 코드와 사유, 매트릭스 대칭, 경로 우선, 인증.
 *
 * <p>테스트마다 고유 접두어의 MDC 식별자를 쓰고 끝에 지운다(H2 공유 컨텍스트). 감사 필드는 {@code @WithMockUser}
 * 의 "user" 가 사용자 원장에 없어 이름은 null, 계정은 "user" 로 남는다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class MdcLinkApiContractTest {

    private static final String BASE = "/api/v1/scheduler/mdc-links";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @DisplayName("생성 응답은 15필드이고 쌍은 정렬되며 감사 계정이 남는다")
    @Test
    void create_response_15_fields() throws Exception {
        String t = tag();
        MvcResult created = create("{\"mdcA\":\"" + t + "-b\",\"mdcB\":\"" + t + "-a\",\"latencyMs\":20,\"bandwidthMbps\":1000}")
                .andExpect(status().isOk()).andReturn();
        Map<String, Object> d = data(created);
        try {
            assertEquals(MdcLinkResponseGoldenTest.LINK_FIELDS, new TreeSet<>(d.keySet()));
            assertEquals(t + "-a", d.get("mdcA"));
            assertEquals(t + "-b", d.get("mdcB"));
            assertEquals("UP", d.get("status"));
            assertEquals("MANUAL", d.get("source"));
            assertEquals("user", d.get("createdBy"));
            assertNull(d.get("packetLossPercent"));

            // 생성 == 조회 — 시각은 제외한다. 생성 응답은 메모리의 나노초, 조회는 DB 에 저장된 마이크로초라
            // 같은 시각이 다른 문자열로 나온다(SchedulerApiContractTest 와 같은 사정).
            MvcResult fetched = mockMvc.perform(get(BASE + "/" + d.get("id"))).andExpect(status().isOk()).andReturn();
            assertEquals(withoutTimestamps(d), withoutTimestamps(data(fetched)));
        } finally {
            cleanup(d);
        }
    }

    @DisplayName("역방향 중복은 409 같은 MDC 는 400 음수 지연은 400")
    @Test
    void duplicate_409_same_mdc_400() throws Exception {
        String t = tag();
        Map<String, Object> d = data(create("{\"mdcA\":\"" + t + "-a\",\"mdcB\":\"" + t + "-b\",\"latencyMs\":1}")
                .andExpect(status().isOk()).andReturn());
        try {
            create("{\"mdcA\":\"" + t + "-b\",\"mdcB\":\"" + t + "-a\",\"latencyMs\":1}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(containsString("이미 등록된 MDC 쌍")));
            create("{\"mdcA\":\"" + t + "-a\",\"mdcB\":\"" + t + "-a\",\"latencyMs\":1}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(containsString("같은 MDC")));
            create("{\"mdcA\":\"" + t + "-a\",\"mdcB\":\"" + t + "-c\",\"latencyMs\":-1}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(containsString("0 이상")));
        } finally {
            cleanup(d);
        }
    }

    @DisplayName("부분 수정과 명시적 null 비움 그리고 삭제 후 404")
    @Test
    void patch_null_clears_delete_404() throws Exception {
        String t = tag();
        Map<String, Object> d = data(create("{\"mdcA\":\"" + t + "-a\",\"mdcB\":\"" + t + "-b\",\"latencyMs\":1,"
                + "\"description\":\"d\",\"packetLossPercent\":2}").andExpect(status().isOk()).andReturn());
        Object id = d.get("id");

        Map<String, Object> u = data(mockMvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"degraded\",\"description\":null}"))
                .andExpect(status().isOk()).andReturn());
        assertEquals("DEGRADED", u.get("status"));
        assertNull(u.get("description"), "명시적 null → 비움");
        assertEquals(2.0, u.get("packetLossPercent"), "키 없음 → 유지");
        assertEquals(1.0, u.get("latencyMs"));

        mockMvc.perform(delete(BASE + "/" + id)).andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("MDC 링크를 찾을 수 없습니다")));
        mockMvc.perform(delete(BASE + "/" + id)).andExpect(status().isNotFound());
    }

    @DisplayName("매트릭스는 리터럴 경로로 단건 조회에 잡히지 않고 대칭이다")
    @Test
    void matrix_literal_path_symmetric() throws Exception {
        String t = tag();
        Map<String, Object> d1 = data(create("{\"mdcA\":\"" + t + "-2\",\"mdcB\":\"" + t + "-1\",\"latencyMs\":10}")
                .andExpect(status().isOk()).andReturn());
        Map<String, Object> d2 = data(create("{\"mdcA\":\"" + t + "-1\",\"mdcB\":\"" + t + "-3\",\"latencyMs\":80}")
                .andExpect(status().isOk()).andReturn());
        try {
            JsonNode m = objectMapper.readTree(mockMvc.perform(get(BASE + "/matrix"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
            JsonNode ids = m.get("mdcIds");
            int i1 = indexOf(ids, t + "-1"), i2 = indexOf(ids, t + "-2"), i3 = indexOf(ids, t + "-3");
            assertTrue(i1 >= 0 && i2 >= 0 && i3 >= 0, "세 MDC 가 모두 들어 있다");
            assertEquals(ids.size(), m.get("links").size(), "정방 행렬");
            JsonNode links = m.get("links");
            assertTrue(links.get(i1).get(i1).isNull(), "대각 null");
            assertEquals(10.0, links.get(i1).get(i2).get("latencyMs").asDouble());
            assertEquals(links.get(i1).get(i2), links.get(i2).get(i1), "대칭");
            assertEquals(80.0, links.get(i3).get(i1).get("latencyMs").asDouble());
            assertTrue(links.get(i2).get(i3).isNull(), "미등록 쌍 null");
        } finally {
            cleanup(d1);
            cleanup(d2);
        }
    }

    @DisplayName("목록 필터는 어느 편이든 걸린 MDC 와 상태로 거른다")
    @Test
    void list_filter_by_mdc_and_status() throws Exception {
        String t = tag();
        Map<String, Object> d1 = data(create("{\"mdcA\":\"" + t + "-a\",\"mdcB\":\"" + t + "-b\",\"latencyMs\":1}")
                .andExpect(status().isOk()).andReturn());
        Map<String, Object> d2 = data(create("{\"mdcA\":\"" + t + "-c\",\"mdcB\":\"" + t + "-b\",\"latencyMs\":1,\"status\":\"down\"}")
                .andExpect(status().isOk()).andReturn());
        try {
            mockMvc.perform(get(BASE).param("mdcId", t + "-b")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2));
            mockMvc.perform(get(BASE).param("mdcId", t + "-b").param("status", "DOWN")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].mdcA").value(t + "-b"));
            mockMvc.perform(get(BASE).param("status", "broken")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(containsString("링크 상태")));
        } finally {
            cleanup(d1);
            cleanup(d2);
        }
    }

    @DisplayName("깨진 본문은 400 사유 미인증은 403")
    @Test
    void broken_body_400_unauthenticated_403() throws Exception {
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"mdcA\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("요청 본문을 읽을 수 없습니다")));
        mockMvc.perform(get(BASE).with(anonymous())).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/matrix").with(anonymous())).andExpect(status().isForbidden());
    }

    @DisplayName("없는 링크 조회는 404")
    @Test
    void get_missing_link_404() throws Exception {
        mockMvc.perform(get(BASE + "/987654321")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private static String tag() { return "k4t-" + Long.toHexString(System.nanoTime()); }

    private ResultActions create(String json) throws Exception {
        return mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(MvcResult r) throws Exception {
        Map<String, Object> body = objectMapper.readValue(r.getResponse().getContentAsString(), Map.class);
        return (Map<String, Object>) body.get("data");
    }

    private static Map<String, Object> withoutTimestamps(Map<String, Object> d) {
        Map<String, Object> copy = new java.util.HashMap<>(d);
        copy.remove("createdAt");
        copy.remove("updatedAt");
        return copy;
    }

    private void cleanup(Map<String, Object> d) throws Exception {
        if (d != null && d.get("id") != null) mockMvc.perform(delete(BASE + "/" + d.get("id")));
    }

    private static int indexOf(JsonNode array, String value) {
        for (int i = 0; i < array.size(); i++) if (value.equals(array.get(i).asString())) return i;
        return -1;
    }
}
