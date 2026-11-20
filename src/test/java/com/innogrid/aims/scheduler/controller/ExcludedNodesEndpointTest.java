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
 * {@code GET /scheduler/nodes/excluded} — 노드 목록의 배열 형태를 지키면서 제외 노드를 보여주는 별도 엔드포인트.
 *
 * <p>{@code /nodes/{nodeId}}와 경로가 겹치는 듯 보이지만 스프링은 리터럴 경로를 우선 매칭한다. 이 테스트가
 * 200을 받는 것 자체가 "excluded라는 노드를 찾다 404"가 아님을 증명한다. 테스트 환경은 메트릭 저장소가
 * 없어 수집 자체가 비어 있으므로 제외 목록도 빈 배열이다 — 제외 노드가 담기는지는 {@code SchedulingServiceExcludedTest}·
 * 시나리오 4가 서비스 계층에서 확인한다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class ExcludedNodesEndpointTest {

    @Autowired
    MockMvc mockMvc;

    @DisplayName("제외 노드 목록은 배열이고 노드 단건 경로에 잡히지 않는다")
    @Test
    void excluded_nodes_literal_path_array() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/nodes/excluded"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @DisplayName("노드 목록 계약은 그대로 배열이다")
    @Test
    void node_list_stays_array() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/nodes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }
}
