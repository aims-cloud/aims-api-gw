package com.innogrid.aims.monitoring.controller.agent;

import java.util.Optional;

import com.innogrid.aims.common.exception.GlobalExceptionHandler;
import com.innogrid.aims.monitoring.service.agent.AgentTargetsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** instance 파라미터 필수·404 처리와, 응답이 봉투 없는 file_sd JSON 배열 그대로인지 검증한다. */
class AgentTargetsPullControllerTest {
    AgentTargetsService targets = mock(AgentTargetsService.class);
    MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new AgentTargetsPullController(targets))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void returnsRawTargetsJsonForKnownInstance() throws Exception {
        String json = "[ {\n  \"targets\" : [ \"localhost\" ],\n  \"labels\" : { \"__path__\" : \"/var/log/nginx/access.log\" }\n} ]\n";
        when(targets.serve("10.0.0.21:9100")).thenReturn(Optional.of(json));
        mvc.perform(get("/api/v1/agent/targets").param("instance", "10.0.0.21:9100"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].targets[0]").value("localhost"))
                .andExpect(jsonPath("$[0].labels.__path__").value("/var/log/nginx/access.log"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test void missingInstanceIs400AndUnknownInstanceIs404() throws Exception {
        mvc.perform(get("/api/v1/agent/targets")).andExpect(status().isBadRequest());
        verifyNoInteractions(targets);
        when(targets.serve("10.0.0.99:9100")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/agent/targets").param("instance", "10.0.0.99:9100")).andExpect(status().isNotFound());
    }
}
