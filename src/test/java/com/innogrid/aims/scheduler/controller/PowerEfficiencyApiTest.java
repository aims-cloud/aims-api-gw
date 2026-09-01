package com.innogrid.aims.scheduler.controller;

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
 * HTTP 계약 확인.
 *
 * <p>테스트 환경에는 메트릭 저장소가 없어 노드 수집이 빈 목록을 돌려준다. 그래서 이
 * 테스트가 검증하는 것은 <b>데이터가 없을 때 API 가 올바르게 비어 있다고 답하는가</b>다.
 * 배포 VM 도 IPMI 가 없어 같은 경로를 타므로, 여기서 고정한 계약이 실환경 계약이기도 하다.</p>
 *
 * <p>지표·등급 값 검증은 {@code PowerEfficiencyServiceTest} 가 합성 스냅샷으로 한다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser        // 모든 요청에 인증이 필요하다. 인증 자체는 마지막 테스트에서 확인한다
class PowerEfficiencyApiTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 전력_효율_평가는_데이터가_없어도_200_이다() throws Exception {
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
     * {@code /nodes/power-efficiency} 와 {@code /nodes/{nodeId}} 는 둘 다 두 조각짜리 경로다.
     * 리터럴이 경로 변수보다 우선한다는 전제가 깨지면 평가 API 가 노드 단건 조회로 잡힌다.
     */
    @Test
    void 리터럴_경로가_경로_변수보다_우선한다() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/nodes/power-efficiency"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").exists());   // 노드 단건 응답에는 없는 필드
    }

    @Test
    void 추세는_데이터가_없으면_빈_배열과_사유를_돌려준다() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/nodes/node-1/power-efficiency/trend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodeId").value("node-1"))
                .andExpect(jsonPath("$.data.step").value("5m"))
                .andExpect(jsonPath("$.data.points").isEmpty())
                .andExpect(jsonPath("$.data.message").isNotEmpty());
    }

    @Test
    void 콜론이_든_노드_ID_도_경로에_쓸_수_있다() throws Exception {
        // 실제 노드 식별자가 host:port 형태다
        mockMvc.perform(get("/api/v1/scheduler/nodes/localhost:9100/power-efficiency/trend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodeId").value("localhost:9100"));
    }

    @Test
    void 잘못된_step_은_400_이다() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/nodes/node-1/power-efficiency/trend")
                        .param("step", "5분"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void from_이_to_보다_뒤면_400_이다() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/nodes/node-1/power-efficiency/trend")
                        .param("from", "2026-09-02T00:00:00Z")
                        .param("to", "2026-09-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "")
    void 인증_없이는_접근할_수_없다() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/nodes/power-efficiency")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.anonymous()))
                .andExpect(status().isForbidden());
    }
}
