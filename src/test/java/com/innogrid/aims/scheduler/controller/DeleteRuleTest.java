package com.innogrid.aims.scheduler.controller;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 삭제 규칙 — 실행 중 워크로드 409, 참조 중 정책 409, 없는 것 404.
 *
 * <p>예전에는 셋 다 조용히 200 이었다. 실행 중 워크로드를 지우면 예약 장부(=워크로드 행)가 사라져 노드가
 * 비어 보이고, 참조 중 정책을 지우면 스케줄의 다음 실행이 404 로 실패했다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class DeleteRuleTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WorkloadRepository workloadRepository;

    @Test
    void 없는_워크로드_삭제는_404_예전에는_조용히_200() throws Exception {
        mockMvc.perform(delete("/api/v1/scheduler/workloads/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("워크로드를 찾을 수 없습니다")));
    }

    @Test
    void 실행_중_워크로드_삭제는_409_종료_후에는_200() throws Exception {
        // 테스트 환경에는 노드가 없어 API 로는 배치할 수 없다 — 배치된 상태를 직접 만든다
        Workload w = new Workload("del-rule", 1, 512);
        w.assignTo("mdc-01", "node-1", 0.9, "test");            // SCHEDULED
        Long id = workloadRepository.save(w).getId();

        mockMvc.perform(delete("/api/v1/scheduler/workloads/" + id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("실행 중인 워크로드는 삭제할 수 없습니다")))
                .andExpect(jsonPath("$.message").value(containsString("node-1")));

        // SCHEDULED → RUNNING 도 실행 중
        mockMvc.perform(patch("/api/v1/scheduler/workloads/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RUNNING\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/scheduler/workloads/" + id)).andExpect(status().isConflict());

        // 종료하면 지울 수 있다
        mockMvc.perform(patch("/api/v1/scheduler/workloads/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/scheduler/workloads/" + id)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/scheduler/workloads/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void PENDING_워크로드는_바로_지울_수_있다() throws Exception {
        String body = mockMvc.perform(post("/api/v1/scheduler/workloads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"del-pending\",\"requiredCpu\":1,\"requiredMemoryMb\":512}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        mockMvc.perform(delete("/api/v1/scheduler/workloads/" + idOf(body))).andExpect(status().isOk());
    }

    @Test
    void 없는_정책_삭제는_404() throws Exception {
        mockMvc.perform(delete("/api/v1/scheduler/policies/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("정책을 찾을 수 없습니다")));
    }

    @Test
    void 스케줄이_참조하는_정책_삭제는_409_스케줄을_지우면_200() throws Exception {
        String policy = mockMvc.perform(post("/api/v1/scheduler/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"del-ref-" + System.nanoTime() + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long policyId = idOf(policy);

        String schedule = mockMvc.perform(post("/api/v1/scheduler/schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleId\":\"del-ref-" + System.nanoTime() + "\",\"name\":\"참조 스케줄\","
                                + "\"cronExpression\":\"0 0 2 * * ?\",\"resourceType\":\"VM\",\"policyId\":" + policyId + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long scheduleId = idOf(schedule);

        mockMvc.perform(delete("/api/v1/scheduler/policies/" + policyId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("스케줄이 참조하는 정책은 삭제할 수 없습니다")));

        mockMvc.perform(delete("/api/v1/scheduler/schedules?ids=" + scheduleId)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/scheduler/policies/" + policyId)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/scheduler/policies/" + policyId)).andExpect(status().isNotFound());
    }

    @SuppressWarnings("unchecked")
    private long idOf(String body) {
        Map<String, Object> data = (Map<String, Object>) objectMapper.readValue(body, Map.class).get("data");
        return ((Number) data.get("id")).longValue();
    }
}
