package com.innogrid.aims.scheduler.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 워크로드 생성 요청 검증 — 예전에 500 이던 것이 400 과 사유가 되는가, 정상 요청은 그대로인가.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class WorkloadRequestValidationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 이름이_없으면_400() throws Exception {
        create("{\"requiredCpu\":1,\"requiredMemoryMb\":512}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("워크로드 이름")));
    }

    @Test
    void 요구_CPU_가_없으면_400_예전에는_NPE_500() throws Exception {
        create("{\"name\":\"w\",\"requiredMemoryMb\":512}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("요구 CPU")));
    }

    @Test
    void 요구_CPU_가_0_이면_400() throws Exception {
        create("{\"name\":\"w\",\"requiredCpu\":0,\"requiredMemoryMb\":512}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("1 이상")));
    }

    @Test
    void 요구_CPU_에_문자열을_넣으면_400_예전에는_ClassCastException_500() throws Exception {
        create("{\"name\":\"w\",\"requiredCpu\":\"abc\",\"requiredMemoryMb\":512}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("요청 본문을 읽을 수 없습니다")));
    }

    @Test
    void 숫자_문자열은_관대하게_받는다() throws Exception {
        // "2" 처럼 숫자 모양 문자열은 예전 맵 방식에서는 ClassCastException 이었고, 이제는 정상으로 받는다
        String body = create("{\"name\":\"w-str\",\"requiredCpu\":\"2\",\"requiredMemoryMb\":\"512\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiredCpu").value(2))
                .andReturn().getResponse().getContentAsString();
        cleanup(body);
    }

    @Test
    void 이름이_100자를_넘으면_400() throws Exception {
        create("{\"name\":\"" + "x".repeat(101) + "\",\"requiredCpu\":1,\"requiredMemoryMb\":512}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("100자")));
    }

    @Test
    void 요구_GPU_가_음수면_400() throws Exception {
        create("{\"name\":\"w\",\"requiredCpu\":1,\"requiredMemoryMb\":512,\"requiredGpu\":-1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("요구 GPU")));
    }

    @Test
    void 전력_상한이_0_이면_400() throws Exception {
        create("{\"name\":\"w\",\"requiredCpu\":1,\"requiredMemoryMb\":512,\"maxPowerBudgetWatts\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("전력 상한")));
    }

    @Test
    void 모르는_리소스_종류는_400_현행_동일() throws Exception {
        create("{\"name\":\"w\",\"requiredCpu\":1,\"requiredMemoryMb\":512,\"resourceType\":\"quantum\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("리소스 종류")));
    }

    @Test
    void 소문자_열거형과_모르는_키는_예전처럼_받는다() throws Exception {
        String body = create("{\"name\":\"w-ok\",\"requiredCpu\":1,\"requiredMemoryMb\":512,"
                + "\"resourceType\":\"gpu\",\"priority\":\"low\",\"someUnknownKey\":123}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resourceType").value("GPU"))
                .andExpect(jsonPath("$.data.priority").value("LOW"))
                .andReturn().getResponse().getContentAsString();
        cleanup(body);
    }

    @Test
    void 상태_전이_요청에_status_가_없으면_400() throws Exception {
        mockMvc.perform(patch("/api/v1/scheduler/workloads/1/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("status")));
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private ResultActions create(String json) throws Exception {
        return mockMvc.perform(post("/api/v1/scheduler/workloads")
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private void cleanup(String responseBody) throws Exception {
        String id = responseBody.replaceAll(".*\"id\":(\\d+).*", "$1");
        mockMvc.perform(delete("/api/v1/scheduler/workloads/" + id)).andExpect(status().isOk());
    }
}
