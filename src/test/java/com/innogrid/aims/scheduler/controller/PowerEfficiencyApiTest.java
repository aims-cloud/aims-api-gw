package com.innogrid.aims.scheduler.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 응답 확인.
 *
 * <p>테스트 환경에는 메트릭 저장소가 없어 노드 수집이 빈 목록을 돌려준다. 그래서 이
 * 테스트가 검증하는 것은 <b>데이터가 없을 때 API가 올바르게 비어 있다고 답하는가</b>다.
 * 배포 VM도 IPMI가 없어 같은 경로를 타므로, 여기서 확인한 응답이 실환경 응답이기도 하다.</p>
 *
 * <p>지표·등급 값 검증은 {@code PowerEfficiencyServiceTest}가 합성 스냅샷으로 한다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser        // 모든 요청에 인증이 필요하다. 인증 자체는 마지막 테스트에서 확인한다
class PowerEfficiencyApiTest {

    @Autowired
    MockMvc mockMvc;

    @DisplayName("전력 효율 평가는 데이터가 없어도 200 이다")
    @Test
    void evaluation_200_without_data() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/nodes/power-efficiency"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.summary.totalNodes").value(0))
                .andExpect(jsonPath("$.data.summary.evaluableNodes").value(0))
                .andExpect(jsonPath("$.data.summary.gradeAvailable").value(false))
                .andExpect(jsonPath("$.data.summary.gradeUnavailableReason").isNotEmpty())
                .andExpect(jsonPath("$.data.nodes").isArray());
    }

    /**
     * {@code /nodes/power-efficiency}와 {@code /nodes/{nodeId}}는 둘 다 두 조각짜리 경로다.
     * 리터럴이 경로 변수보다 우선한다는 전제가 깨지면 평가 API가 노드 단건 조회로 잡힌다.
     */
    @DisplayName("리터럴 경로가 경로 변수보다 우선한다")
    @Test
    void literal_path_precedence() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/nodes/power-efficiency"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").exists());   // 노드 단건 응답에는 없는 필드
    }

    @DisplayName("추세는 데이터가 없으면 빈 배열과 사유를 돌려준다")
    @Test
    void trend_empty_array_with_reason() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/nodes/node-1/power-efficiency/trend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodeId").value("node-1"))
                .andExpect(jsonPath("$.data.step").value("5m"))
                .andExpect(jsonPath("$.data.points").isEmpty())
                .andExpect(jsonPath("$.data.message").isNotEmpty());
    }

    @DisplayName("콜론이 든 노드 ID 도 경로에 쓸 수 있다")
    @Test
    void node_id_with_colon_in_path() throws Exception {
        // 실제 노드 식별자가 host:port 형태다
        mockMvc.perform(get("/api/v1/scheduler/nodes/localhost:9100/power-efficiency/trend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodeId").value("localhost:9100"));
    }

    @DisplayName("잘못된 step 은 400 이다")
    @Test
    void invalid_step_400() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/nodes/node-1/power-efficiency/trend")
                        .param("step", "5분"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @DisplayName("from 이 to 보다 뒤면 400 이다")
    @Test
    void from_after_to_400() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/nodes/node-1/power-efficiency/trend")
                        .param("from", "2026-09-02T00:00:00Z")
                        .param("to", "2026-09-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @DisplayName("인증 없이는 접근할 수 없다")
    @Test
    @WithMockUser(username = "")
    void unauthenticated_403() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/nodes/power-efficiency")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.anonymous()))
                .andExpect(status().isForbidden());
    }
}
