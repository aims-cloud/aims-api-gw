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
 * {@code GET /scheduler/nodes/excluded} — 노드 목록의 배열 계약을 지키면서 제외 노드를 보여주는 별도 엔드포인트.
 *
 * <p>{@code /nodes/{nodeId}} 와 경로가 겹치는 듯 보이지만 스프링은 리터럴 경로를 우선 매칭한다. 이 테스트가
 * 200 을 받는 것 자체가 "excluded 라는 노드를 찾다 404" 가 아님을 증명한다. 테스트 환경은 메트릭 저장소가
 * 없어 수집 자체가 비어 있으므로 제외 목록도 빈 배열이다 — 제외 노드가 담기는지는 {@code SchedulingServiceExcludedTest}·
 * 시나리오 4 가 서비스 계층에서 고정한다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class ExcludedNodesEndpointTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 제외_노드_목록은_배열이고_노드_단건_경로에_잡히지_않는다() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/nodes/excluded"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void 노드_목록_계약은_그대로_배열이다() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/nodes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }
}
