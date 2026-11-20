package com.innogrid.aims.scheduler.controller;

import java.util.List;
import java.util.Map;

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

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;

import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 활성 정책은 종류(공통 포함)당 하나 — 조건부 기본 활성, 엄격한 PUT, 명시적 전환.
 *
 * <p>공유 H2에 다른 테스트가 남긴 정책이 있을 수 있어, 시작 전에 정책 표를 비우고 끝나면 만든 것을 지운다.
 * (다른 테스트는 각자 정책을 만들어 쓰므로 비어 있는 상태를 전제하지 않는다.)</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class PolicyActivationRuleTest {

    private static final String BASE = "/api/v1/scheduler/policies";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SchedulingPolicyRepository repository;

    private List<SchedulingPolicy> snapshot;

    @BeforeEach
    void isolate() {
        snapshot = repository.findAll();
        repository.deleteAll();
    }

    @AfterEach
    void restore() {
        repository.deleteAll();
        for (SchedulingPolicy p : snapshot) {
            SchedulingPolicy copy = new SchedulingPolicy(p.getName(), p.getDescription());
            copy.setResourceType(p.getResourceType());
            copy.setActive(p.isActive());
            repository.save(copy);
        }
    }

    @DisplayName("빈 상태의 첫 정책은 활성 그 다음은 비활성으로 만들어진다")
    @Test
    void first_policy_active_rest_inactive() throws Exception {
        Map<String, Object> first = create("{\"name\":\"act-first\"}");
        Map<String, Object> second = create("{\"name\":\"act-second\"}");

        assertEquals(true, first.get("active"), "빈 상태의 첫 공통 정책은 바로 쓰인다");
        assertEquals(false, second.get("active"), "활성이 있으면 비활성으로 만들어지고 응답에 드러난다");
        assertEquals(true, repository.findByName("act-first").orElseThrow().isActive(), "첫 정책은 그대로");
    }

    @DisplayName("명시적 active true 가 충돌하면 409 이고 아무것도 바뀌지 않는다")
    @Test
    void create_active_true_409() throws Exception {
        create("{\"name\":\"act-a\"}");

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"act-b\",\"active\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("이미 활성 정책이 있습니다: act-a")))
                .andExpect(jsonPath("$.message").value(containsString("/activate")));
        assertTrue(repository.findByName("act-b").isEmpty(), "저장되지 않는다");
    }

    @DisplayName("PUT active true 는 충돌 시 409 active false 는 항상 허용")
    @Test
    void put_active_true_409_false_ok() throws Exception {
        create("{\"name\":\"act-a\"}");
        Map<String, Object> b = create("{\"name\":\"act-b\"}");

        mockMvc.perform(put(BASE + "/" + b.get("id")).contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isConflict());
        assertEquals(false, repository.findByName("act-b").orElseThrow().isActive());

        Map<String, Object> a = repositoryAsMap("act-a");
        mockMvc.perform(put(BASE + "/" + a.get("id")).contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(false));
        mockMvc.perform(put(BASE + "/" + b.get("id")).contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(true));   // 충돌이 없어졌으니 허용
    }

    @DisplayName("전환은 같은 종류의 활성을 끄고 대상을 켜며 꺼진 것을 응답에 담는다")
    @Test
    void activate_deactivates_same_type() throws Exception {
        create("{\"name\":\"act-a\"}");
        Map<String, Object> b = create("{\"name\":\"act-b\"}");
        Map<String, Object> gpu = create("{\"name\":\"act-gpu\",\"resourceType\":\"GPU\"}");
        assertEquals(true, gpu.get("active"), "GPU 종류에는 활성이 없어 켜진다 — 공통과 분리");

        MvcResult r = mockMvc.perform(post(BASE + "/" + b.get("id") + "/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activated.name").value("act-b"))
                .andExpect(jsonPath("$.data.activated.active").value(true))
                .andExpect(jsonPath("$.data.deactivated.length()").value(1))
                .andExpect(jsonPath("$.data.deactivated[0].name").value("act-a"))
                .andReturn();

        assertEquals(false, repository.findByName("act-a").orElseThrow().isActive());
        assertEquals(true, repository.findByName("act-b").orElseThrow().isActive());
        assertEquals(true, repository.findByName("act-gpu").orElseThrow().isActive(), "다른 종류는 건드리지 않는다");

        // 멱등 — 이미 활성인 정책을 전환하면 꺼지는 것이 없다
        mockMvc.perform(post(BASE + "/" + b.get("id") + "/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deactivated.length()").value(0));
        assertNotNull(r);
    }

    @DisplayName("종류를 바꾸는 부분 수정도 활성 충돌에 걸린다")
    @Test
    void patch_type_change_409() throws Exception {
        create("{\"name\":\"act-common\"}");
        Map<String, Object> gpu = create("{\"name\":\"act-gpu\",\"resourceType\":\"GPU\"}");
        assertEquals(true, gpu.get("active"));

        // 활성인 GPU 정책을 공통으로 바꾸면 공통 활성(act-common)과 충돌
        mockMvc.perform(put(BASE + "/" + gpu.get("id")).contentType(MediaType.APPLICATION_JSON).content("{\"resourceType\":null}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("공통")));
        assertEquals("GPU", repository.findByName("act-gpu").orElseThrow().getResourceType().name(), "바뀌지 않는다");
    }

    @DisplayName("자기 자신을 다시 저장하는 것은 충돌이 아니다")
    @Test
    void resaving_same_policy_no_conflict() throws Exception {
        Map<String, Object> a = create("{\"name\":\"act-a\"}");
        mockMvc.perform(put(BASE + "/" + a.get("id")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true,\"description\":\"same\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(true));
    }

    @DisplayName("없는 정책 전환은 404")
    @Test
    void activate_missing_policy_404() throws Exception {
        mockMvc.perform(post(BASE + "/987654321/activate")).andExpect(status().isNotFound());
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> create(String json) throws Exception {
        MvcResult r = mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andReturn();
        Map<String, Object> body = objectMapper.readValue(r.getResponse().getContentAsString(), Map.class);
        return (Map<String, Object>) body.get("data");
    }

    private Map<String, Object> repositoryAsMap(String name) {
        SchedulingPolicy p = repository.findByName(name).orElseThrow();
        return Map.of("id", p.getId(), "active", p.isActive());
    }
}
