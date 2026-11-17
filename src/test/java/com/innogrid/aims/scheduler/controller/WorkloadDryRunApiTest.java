package com.innogrid.aims.scheduler.controller;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 저장 전 워크로드 모의 배치의 HTTP 계약 — 일곱 키와 순서, 쓰기 없음, 오류 래퍼, 403, Swagger. 트랜잭션을 걸지 않는다(쓰지 않으니 정리할 것도 없다). */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class WorkloadDryRunApiTest {

    private static final String BASE = "/api/v1/scheduler/workloads";
    private static final List<String> KEYS = List.of("success", "selectedMdcId", "selectedNodeId", "finalScore", "reason", "candidateScores", "policyName");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired SchedulingHistoryRepository historyRepository;

    @DisplayName("폼을 보내면 모의 배치의 일곱 키를 그 순서로 돌려주고 아무것도 쓰지 않는다")
    @Test
    void draft_returns_7_keys_and_writes_nothing() throws Exception {
        long workloads = workloadRepository.count();
        long history = historyRepository.count();

        String body = mockMvc.perform(post(BASE + "/dry-run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"api-draft\",\"requiredCpu\":1,\"requiredMemoryMb\":256}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(body).get("data");
        List<String> keys = new ArrayList<>();
        data.propertyNames().forEach(keys::add);
        assertEquals(KEYS, keys, "단건 배치 dryRun 과 같은 일곱 키·순서");
        assertTrue(data.get("policyName").isString(), "정책 이름은 문자열");
        assertEquals(workloads, workloadRepository.count(), "워크로드 행 없음");
        assertEquals(history, historyRepository.count(), "이력 없음");
    }

    @DisplayName("잘못된 폼·엔진은 400 과 사유를 공통 래퍼로 돌려준다")
    @Test
    void invalid_form_400_in_wrapper() throws Exception {
        mockMvc.perform(post(BASE + "/dry-run").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("워크로드 이름은(는) 필수입니다"));
        mockMvc.perform(post(BASE + "/dry-run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"requiredCpu\":1,\"requiredMemoryMb\":256,\"resourceType\":\"XYZ\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(post(BASE + "/dry-run?engine=nope").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"requiredCpu\":1,\"requiredMemoryMb\":256}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("알 수 없는 스케줄링 엔진입니다: nope"));
        mockMvc.perform(post(BASE + "/dry-run"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(post(BASE + "/dry-run?engine=nope").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("워크로드 이름은(는) 필수입니다"));   // 칸 400이 엔진 400보다 먼저
    }

    @DisplayName("빈 엔진 값은 비운 것과 같아 400 이 아니다")
    @Test
    void empty_engine_is_same_as_omitted() throws Exception {
        for (String engine : List.of("", " ")) {
            mockMvc.perform(post(BASE + "/dry-run").param("engine", engine).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"api-draft\",\"requiredCpu\":1,\"requiredMemoryMb\":256}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        }
    }

    @DisplayName("Swagger 의 두 engine 파라미터는 기본값 없이 선택이다 — 비우면 정책 기본 엔진을 쓰므로")
    @Test
    void swagger_engine_params_have_no_default() throws Exception {
        JsonNode paths = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("paths");
        for (String path : List.of(BASE + "/dry-run", BASE + "/{workloadId}/schedule")) {
            JsonNode engine = null;
            for (JsonNode p : paths.get(path).get("post").get("parameters")) {
                if ("engine".equals(p.get("name").asString())) engine = p;
            }
            assertNotNull(engine, path + " 에 engine 파라미터");
            assertFalse(engine.path("required").asBoolean(false), path + " engine 은 선택");
            assertTrue(engine.get("schema").path("default").isMissingNode(), path + " engine 기본값 없음: " + engine);
        }
    }

    @DisplayName("미인증이면 403 이다")
    @Test
    void anonymous_is_403() throws Exception {
        mockMvc.perform(post(BASE + "/dry-run").with(anonymous()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"requiredCpu\":1,\"requiredMemoryMb\":256}"))
                .andExpect(status().isForbidden());
    }

    @DisplayName("Swagger 에 경로가 있고 요청은 생성 스키마, 응답은 모의 배치 결과 스키마를 가리킨다")
    @Test
    void swagger_path_and_schemas() throws Exception {
        JsonNode doc = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode op = doc.get("paths").get(BASE + "/dry-run").get("post");
        assertEquals("저장 전 워크로드 모의 배치", op.get("summary").asString());
        assertTrue(op.get("requestBody").get("content").get("application/json").get("schema").get("$ref").asString().endsWith("/WorkloadRequest"));
        String resp = op.get("responses").get("200").get("content").properties().iterator().next().getValue().get("schema").get("$ref").asString();
        assertTrue(resp.endsWith("/ApiResponseSchedulingResult"), resp);
        assertNotNull(doc.get("paths").get(BASE + "/{workloadId}/schedule"), "기존 단건 배치 경로 유지");
    }
}
