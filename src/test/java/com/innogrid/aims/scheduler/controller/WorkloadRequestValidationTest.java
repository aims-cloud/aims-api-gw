package com.innogrid.aims.scheduler.controller;

import org.junit.jupiter.api.DisplayName;
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

    @DisplayName("이름이 없으면 400")
    @Test
    void missing_name_400() throws Exception {
        create("{\"requiredCpu\":1,\"requiredMemoryMb\":512}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("워크로드 이름")));
    }

    @DisplayName("요구 CPU 가 없으면 400 예전에는 NPE 500")
    @Test
    void missing_required_cpu_400() throws Exception {
        create("{\"name\":\"w\",\"requiredMemoryMb\":512}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("요구 CPU")));
    }

    @DisplayName("요구 CPU 가 0 이면 400")
    @Test
    void required_cpu_0_400() throws Exception {
        create("{\"name\":\"w\",\"requiredCpu\":0,\"requiredMemoryMb\":512}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("1 이상")));
    }

    @DisplayName("요구 CPU 에 문자열을 넣으면 400 예전에는 ClassCastException 500")
    @Test
    void required_cpu_string_400() throws Exception {
        create("{\"name\":\"w\",\"requiredCpu\":\"abc\",\"requiredMemoryMb\":512}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("요청 본문을 읽을 수 없습니다")));
    }

    @DisplayName("숫자 문자열은 관대하게 받는다")
    @Test
    void numeric_string_accepted() throws Exception {
        // "2" 처럼 숫자 모양 문자열은 예전 맵 방식에서는 ClassCastException 이었고, 이제는 정상으로 받는다
        String body = create("{\"name\":\"w-str\",\"requiredCpu\":\"2\",\"requiredMemoryMb\":\"512\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiredCpu").value(2))
                .andReturn().getResponse().getContentAsString();
        cleanup(body);
    }

    @DisplayName("이름이 100자를 넘으면 400")
    @Test
    void name_over_100_chars_400() throws Exception {
        create("{\"name\":\"" + "x".repeat(101) + "\",\"requiredCpu\":1,\"requiredMemoryMb\":512}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("100자")));
    }

    @DisplayName("요구 GPU 가 음수면 400")
    @Test
    void negative_required_gpu_400() throws Exception {
        create("{\"name\":\"w\",\"requiredCpu\":1,\"requiredMemoryMb\":512,\"requiredGpu\":-1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("요구 GPU")));
    }

    @DisplayName("전력 상한이 0 이면 400")
    @Test
    void power_limit_0_400() throws Exception {
        create("{\"name\":\"w\",\"requiredCpu\":1,\"requiredMemoryMb\":512,\"maxPowerBudgetWatts\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("전력 상한")));
    }

    @DisplayName("모르는 리소스 종류는 400 현행 동일")
    @Test
    void unknown_resource_type_400() throws Exception {
        create("{\"name\":\"w\",\"requiredCpu\":1,\"requiredMemoryMb\":512,\"resourceType\":\"quantum\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("리소스 종류")));
    }

    @DisplayName("소문자 열거형과 모르는 키는 예전처럼 받는다")
    @Test
    void lowercase_enum_and_unknown_keys_ok() throws Exception {
        String body = create("{\"name\":\"w-ok\",\"requiredCpu\":1,\"requiredMemoryMb\":512,"
                + "\"resourceType\":\"gpu\",\"priority\":\"low\",\"someUnknownKey\":123}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resourceType").value("GPU"))
                .andExpect(jsonPath("$.data.priority").value("LOW"))
                .andReturn().getResponse().getContentAsString();
        cleanup(body);
    }

    @DisplayName("데이터 MDC 와 선호 MDC 는 앞뒤 공백을 지우고 저장한다 — 링크 판정이 정확 비교라 공백이 미등록으로 새지 않게")
    @Test
    void mdc_ids_are_trimmed() throws Exception {
        String body = create("{\"name\":\"w-trim\",\"requiredCpu\":1,\"requiredMemoryMb\":512,"
                + "\"dataMdcId\":\"  mdc-01 \",\"preferredMdcId\":\" mdc-02\\t\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataMdcId").value("mdc-01"))
                .andExpect(jsonPath("$.data.preferredMdcId").value("mdc-02"))
                .andReturn().getResponse().getContentAsString();
        cleanup(body);
    }

    @DisplayName("데이터 MDC 가 공백만이면 지정하지 않은 것으로 본다 — 링크 게이트가 열리지 않는다")
    @Test
    void blank_data_mdc_becomes_null() throws Exception {
        String body = create("{\"name\":\"w-blank\",\"requiredCpu\":1,\"requiredMemoryMb\":512,\"dataMdcId\":\"   \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataMdcId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        cleanup(body);
    }

    @DisplayName("상태 전이 요청에 status 가 없으면 400")
    @Test
    void status_transition_without_status_400() throws Exception {
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
