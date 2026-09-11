package com.innogrid.aims.monitoring.controller;

import java.lang.reflect.Method;
import java.util.List;
import com.innogrid.aims.common.exception.GlobalExceptionHandler;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.common.log.PortalLog;
import com.innogrid.aims.monitoring.controller.resourceexplorer.node.ResourceExplorerNodeController;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogFileResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceRequest;
import com.innogrid.aims.monitoring.service.resourceexplorer.node.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NodeLogControllerTest {
    ResourceExplorerNodeLogService logs = mock(ResourceExplorerNodeLogService.class);
    MockMvc mvc;
    String base = "/api/v1/monitoring/resource-explorer/node/1/services";
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ResourceExplorerNodeController(mock(ResourceExplorerNodeService.class),
                logs, new ResourceExplorerNodeTraceService(logs))).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @Test void invalidTimestampIs400() throws Exception {
        mvc.perform(get(base + "/10/logs").param("from", "not-a-date")).andExpect(status().isBadRequest());
        verifyNoInteractions(logs);
    }
    @Test void fileResponseContainsFullPath() throws Exception {
        when(logs.getLogFiles(eq("1"), eq("10"), any(), any())).thenReturn(List.of(new ServiceLogFileResponse("app.log", "/var/app.log", 0)));
        mvc.perform(get(base + "/10/logs/files")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].path").value("/var/app.log"));
    }
    @Test void businessScopeFailureIs404() throws Exception {
        when(logs.getLogSummary(anyString(), anyString(), any(), any(), any()))
                .thenThrow(new BusinessException(HttpStatus.NOT_FOUND, "missing"));
        mvc.perform(get(base + "/10/logs/summary")).andExpect(status().isNotFound());
    }
    @Test void discoveryKeeps501() throws Exception {
        when(logs.getDiscoveredServices("1")).thenThrow(new BusinessException(HttpStatus.NOT_IMPLEMENTED, "unsupported"));
        mvc.perform(get(base + "/discovered")).andExpect(status().isNotImplemented());
    }
    @Test void allTraceRoutesValidateAccessBeforeUniform501() throws Exception {
        for (String route : List.of("/summary", "/trend", "/methods", "/endpoints", "/ports", "", "/trace-id", "/trace-id/logs")) {
            mvc.perform(get(base + "/10/traces" + route)).andExpect(status().isNotImplemented())
                    .andExpect(jsonPath("$.message").exists());
        }
        verify(logs, times(8)).requireAccessibleService("1", "10");
        doThrow(new BusinessException(HttpStatus.NOT_FOUND, "missing")).when(logs).requireAccessibleService("1", "10");
        for (String route : List.of("/summary", "/trend", "/methods", "/endpoints", "/ports", "", "/trace-id", "/trace-id/logs")) {
            mvc.perform(get(base + "/10/traces" + route)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("missing"));
        }
    }
    @Test void allWritesDeclarePortalAudit() throws Exception {
        Class<ResourceExplorerNodeController> type = ResourceExplorerNodeController.class;
        for (Method method : List.of(type.getMethod("createCollectService", String.class, CollectServiceRequest.class),
                type.getMethod("updateCollectService", String.class, String.class, CollectServiceRequest.class),
                type.getMethod("deleteCollectService", String.class, String.class))) {
            assertThat(method.getAnnotation(PortalLog.class).refType()).isEqualTo("COLLECT_SERVICE");
        }
    }
}
