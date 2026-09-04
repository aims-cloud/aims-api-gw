package com.innogrid.aims.scheduler.controller;

import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 기본(플래그 꺼짐) 컨텍스트 — 소스는 victoria, 모의 정보는 null. 배포 VM 의 기대 응답과 같다. */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class MetricsSourceApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @DisplayName("플래그가 꺼져 있으면 source=victoria·mockEnabled=false 이고 클러스터 정보는 null, 6필드. 미인증은 403")
    @Test
    void default_is_victoria() throws Exception {
        JsonNode d = objectMapper.readTree(mockMvc.perform(get("/api/v1/scheduler/metrics-source")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
        assertEquals(new TreeSet<>(List.of("source", "mockEnabled", "clusterFile", "clusterName", "mdcCount", "nodeCount")), new TreeSet<>(d.propertyNames()));
        assertEquals("victoria", d.get("source").asString());
        assertFalse(d.get("mockEnabled").asBoolean());
        assertTrue(d.get("clusterFile").isNull());
        assertTrue(d.get("nodeCount").isNull());
        mockMvc.perform(get("/api/v1/scheduler/metrics-source").with(anonymous())).andExpect(status().isForbidden());
    }
}
