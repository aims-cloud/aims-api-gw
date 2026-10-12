package com.innogrid.aims.monitoring.controller;

import java.util.List;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.GlobalExceptionHandler;
import com.innogrid.aims.monitoring.controller.resourceexplorer.vm.ResourceExplorerVmLogController;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogLevel;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogListResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.VmAgentInstallResponse;
import com.innogrid.aims.monitoring.entity.AgentInstallStatus;
import com.innogrid.aims.monitoring.service.resourceexplorer.vm.ResourceExplorerVmLogService;
import com.innogrid.aims.monitoring.service.resourceexplorer.vm.VmAgentInstallService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** VM 로그 탭 API — nsId·infraId·vmId 필수, 노드와 같은 시각 파싱(400), 서비스 위임 인자. */
class VmLogControllerTest {
    ResourceExplorerVmLogService vmLogs = mock(ResourceExplorerVmLogService.class);
    VmAgentInstallService agentInstall = mock(VmAgentInstallService.class);
    MockMvc mvc;
    String base = "/api/v1/monitoring/resource-explorer/vm/services";

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ResourceExplorerVmLogController(vmLogs, agentInstall))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void vmKeysAreRequired() throws Exception {
        mvc.perform(get(base).param("nsId", "ns-1").param("infraId", "infra-1")).andExpect(status().isBadRequest());
        verifyNoInteractions(vmLogs);
    }

    @Test void listPassesVmKeysAndPaging() throws Exception {
        when(vmLogs.getCollectServices(null, "ns-1", "infra-1", "vm-1", null, 1, 10, "serviceName,asc"))
                .thenReturn(new PageResponse<>(List.of(), 1, 10, 0, 0));
        mvc.perform(get(base).param("nsId", "ns-1").param("infraId", "infra-1").param("vmId", "vm-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test void searchParsesTimesAndLevels() throws Exception {
        when(vmLogs.searchLogs(eq("ADMIN"), eq("ns-1"), eq("infra-1"), eq("vm-1"), eq("10"), isNull(),
                eq(List.of(ServiceLogLevel.ERROR)), isNull(), any(), any(), eq(5)))
                .thenReturn(new ServiceLogListResponse(3, List.of()));
        mvc.perform(get(base + "/10/logs").param("roleName", "ADMIN").param("nsId", "ns-1").param("infraId", "infra-1")
                        .param("vmId", "vm-1").param("levels", "ERROR").param("limit", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(3));
        mvc.perform(get(base + "/10/logs").param("nsId", "ns-1").param("infraId", "infra-1").param("vmId", "vm-1")
                        .param("from", "yesterday"))
                .andExpect(status().isBadRequest());
    }

    @Test void createAndDeleteDelegateWithVmKeys() throws Exception {
        mvc.perform(post(base).param("nsId", "ns-1").param("infraId", "infra-1").param("vmId", "vm-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceName\":\"nginx\",\"logCollection\":{\"enabled\":true,\"syslogEnabled\":false,\"logPaths\":[\"/var/log/nginx/access.log\"]}}"))
                .andExpect(status().isOk());
        verify(vmLogs).createCollectService(isNull(), eq("ns-1"), eq("infra-1"), eq("vm-1"), any());
        mvc.perform(delete(base + "/12").param("nsId", "ns-1").param("infraId", "infra-1").param("vmId", "vm-1"))
                .andExpect(status().isOk());
        verify(vmLogs).deleteCollectService(null, "ns-1", "infra-1", "vm-1", "12");
    }

    @Test void agentInstallStartsAndReportsStatusWithVmKeys() throws Exception {
        when(agentInstall.install("ADMIN", "ns-1", "infra-1", "vm-1")).thenReturn(
                new VmAgentInstallResponse(AgentInstallStatus.INSTALLING, "SEND_COMMAND", null, null, null, null));
        mvc.perform(post(base + "/agent/install").param("roleName", "ADMIN").param("nsId", "ns-1").param("infraId", "infra-1")
                        .param("vmId", "vm-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.installStatus").value("INSTALLING"))
                .andExpect(jsonPath("$.data.installStep").value("SEND_COMMAND"));
        when(agentInstall.status(null, "ns-1", "infra-1", "vm-1")).thenReturn(VmAgentInstallResponse.notInstalled());
        mvc.perform(get(base + "/agent/install").param("nsId", "ns-1").param("infraId", "infra-1").param("vmId", "vm-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.installStatus").value("NOT_INSTALLED"));
        mvc.perform(post(base + "/agent/install").param("nsId", "ns-1").param("infraId", "infra-1"))
                .andExpect(status().isBadRequest());                             // vmId 누락
    }
}
