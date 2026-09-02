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
 * 전력 최적화 진단 HTTP 계약.
 *
 * <p>테스트 환경에는 메트릭 저장소가 없어 노드 수집이 빈 목록을 돌려준다. 그래서 여기서
 * 검증하는 것은 <b>데이터가 없을 때 API 가 올바르게 비어 있다고 답하는가</b>다. 배포 VM 도
 * IPMI 가 없어 같은 경로를 타므로, 여기서 고정한 계약이 실환경 계약이기도 하다.</p>
 *
 * <p>판정 값 검증은 {@code PowerOptimizationServiceTest}·{@code ConsolidationServiceTest} 가
 * 합성 클러스터로 한다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class PowerOptimizationApiTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 전력_최적화_진단은_데이터가_없어도_200_이다() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/optimization/power"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.cluster.totalNodes").value(0))
                .andExpect(jsonPath("$.data.cluster.measured").value(false))
                .andExpect(jsonPath("$.data.cluster.unmeasuredReason").isNotEmpty())
                .andExpect(jsonPath("$.data.cluster.excludedNodes").value(0))
                .andExpect(jsonPath("$.data.cluster.excluded").isArray())
                .andExpect(jsonPath("$.data.findings").isArray())
                .andExpect(jsonPath("$.data.summary.findingCount").value(0));
    }

    @Test
    void 통합_진단은_데이터가_없어도_200_이다() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/optimization/consolidation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.feasible").value(false))
                .andExpect(jsonPath("$.data.candidates").isArray())
                .andExpect(jsonPath("$.data.summary.reclaimableNodes").value(0))
                .andExpect(jsonPath("$.data.summary.excludedNodes").value(0))
                .andExpect(jsonPath("$.data.summary.excluded").isArray())
                .andExpect(jsonPath("$.data.limitation").isNotEmpty());
    }

    /**
     * {@code /optimization/power} 는 두 조각짜리 경로다. {@code SchedulerController} 의
     * {@code /nodes/{nodeId}} 같은 경로 변수에 잡히지 않고 제 컨트롤러로 가야 한다.
     */
    @Test
    void 최적화_경로가_다른_컨트롤러에_잡히지_않는다() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/optimization/power"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cluster").exists());   // 노드 단건 응답에는 없는 필드
    }

    @Test
    void 미인증이면_403_이다() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/optimization/power")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous()))
                .andExpect(status().isForbidden());
    }
}
