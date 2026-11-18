package com.innogrid.aims.scheduler.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 경로 변수·쿼리 파라미터를 해석하지 못했을 때 <b>사유가 있는 400</b>이 나가는가.
 *
 * <p><b>상태 코드만 봐서는 증명이 안 된다.</b> 핸들러가 없어도 스프링 기본 처리가 {@code sendError(400)}을
 * 부르므로 MockMvc에서는 똑같이 400으로 보인다. 차이는 <b>본문</b>이다 — 기본 처리는 본문을 비우고,
 * 배포 환경에서는 그 뒤 {@code /error} 재요청이 인증에 막혀 클라이언트가 <b>403(본문 없음)</b>을 받는다.
 * 실제로 배포본에서 {@code /workloads/abc/history}·{@code ?from=not-a-date}·{@code ?enabled=maybe}가
 * 모두 403이었다. 그래서 여기서는 {@code $.success}와 <b>사유 문구</b>까지 확인한다 —
 * {@code ApiResponse.error}만이 그 필드를 만들기 때문에, 그것이 보이면 핸들러를 탄 것이다.</p>
 *
 * <p>사유에는 <b>어느 파라미터가 어떤 값 때문에</b> 걸렸는지가 있어야 한다. 고정 문구만 돌려주는 구현도
 * "400 + success=false"는 만족시키므로, 그 두 조각을 함께 확인해야 실제로 쓸모 있는 사유임이 고정된다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class SchedulerParameterErrorTest {

    @Autowired MockMvc mockMvc;

    @DisplayName("경로 변수가 숫자가 아니면 400 이고 사유에 파라미터 이름과 받은 값이 있다")
    @Test
    void non_numeric_path_variable_400_with_name_and_value() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/workloads/abc/history"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("workloadId")))
                .andExpect(jsonPath("$.message").value(containsString("abc")));
    }

    @DisplayName("날짜 형식이 틀리면 400 이고 사유에 파라미터 이름과 받은 값이 있다")
    @Test
    void bad_date_parameter_400_with_name_and_value() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/optimization/power/trend")
                        .param("from", "not-a-date")
                        .param("to", "2026-09-07T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("from")))
                .andExpect(jsonPath("$.message").value(containsString("not-a-date")));
    }

    @DisplayName("참/거짓 자리에 다른 값이 오면 400 이고 사유에 파라미터 이름과 받은 값이 있다")
    @Test
    void bad_boolean_parameter_400_with_name_and_value() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/schedules").param("enabled", "maybe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("enabled")))
                .andExpect(jsonPath("$.message").value(containsString("maybe")));
    }

    @DisplayName("열거형 오타는 사유에 받을 수 있는 값을 함께 알려 준다")
    @Test
    void bad_enum_parameter_lists_allowed_values() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/schedules").param("resourceType", "nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("resourceType")))
                .andExpect(jsonPath("$.message").value(containsString("VM")))
                .andExpect(jsonPath("$.message").value(containsString("GPU")));
    }

    @DisplayName("필수 파라미터가 빠지면 400 이고 사유에 그 이름이 있다 — 아무것도 고르지 않고 삭제를 누른 경우")
    @Test
    void missing_required_parameter_400_with_name() throws Exception {
        mockMvc.perform(delete("/api/v1/scheduler/schedules"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("ids")));
    }

    @DisplayName("step 자릿수가 넘쳐도 400 이다 — 형식 오류와 같은 취급")
    @Test
    void step_overflow_400() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/optimization/power/trend")
                        .param("from", "2026-09-06T00:00:00Z")
                        .param("to", "2026-09-07T00:00:00Z")
                        .param("step", "99999999999999999999s"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("step")));
    }

    /**
     * 자릿수는 long에 들어가도 <b>단위 환산 곱셈</b>이 넘칠 수 있다. {@code 2^57 d}는 곱하면 정확히 0이 되어
     * 뒤의 나눗셈에서 예외가 나고(배포본 실측 403), {@code 2×10^14 d}는 음수로 감겨 점 수 상한이 통째로 무력화된다
     * (배포본 실측 200 — 무거운 질의가 그대로 나갔다). 위의 {@code step_overflow_400}은 파싱 단계만 보므로
     * 이 두 경로는 따로 확인한다.
     */
    @DisplayName("단위 환산이 넘치는 step 도 400 — 0 나눗셈과 음수 랩어라운드 둘 다")
    @Test
    void step_unit_multiplication_overflow_400() throws Exception {
        for (String step : new String[]{"144115188075855872d", "200000000000000d"}) {
            mockMvc.perform(get("/api/v1/scheduler/optimization/power/trend")
                            .param("from", "2026-09-06T00:00:00Z")
                            .param("to", "2026-09-07T00:00:00Z")
                            .param("step", step))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(containsString("step")));
        }
    }

    @DisplayName("받은 값이 길면 사유에서 잘린다 — 사용자 입력이 응답을 통째로 채우지 않게")
    @Test
    void long_value_is_shortened_in_reason() throws Exception {
        String huge = "x".repeat(500);
        String message = mockMvc.perform(get("/api/v1/scheduler/workloads/" + huge + "/history"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertTrue(message.contains("…"), "잘린 표시가 없다: " + message.substring(0, Math.min(120, message.length())));
        assertTrue(message.length() < 400, "사유가 그대로 실렸다 (" + message.length() + "자)");
    }

    /**
     * 이 처리기는 스케줄러 패키지에만 걸려야 한다({@code @RestControllerAdvice(basePackages = …)}).
     *
     * <p>범위가 전역으로 새면 다른 담당 영역의 응답이 조용히 바뀐다. 그런데 스케줄러 테스트만 돌리면
     * 전부 통과하므로 아무도 알아채지 못한다 — 그래서 <b>밖에서</b> 한 번 확인한다.</p>
     *
     * <p>본문이 완전히 비어 있어 {@code jsonPath}로는 확인할 수 없다(파싱 단계에서 실패한다).
     * 빈 문자열인지 직접 본다.</p>
     */
    @DisplayName("스케줄러 밖 엔드포인트는 그대로다 — 이 처리기가 전역으로 새지 않는다")
    @Test
    void advice_does_not_leak_outside_scheduler() throws Exception {
        mockMvc.perform(get("/api/v1/roles/abc"))
                .andExpect(status().isBadRequest())   // 매핑·인증은 통과하고 변환에서 걸린 400이어야 의미가 있다
                .andExpect(content().string(""));
    }
}
