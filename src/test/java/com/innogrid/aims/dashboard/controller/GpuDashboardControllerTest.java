package com.innogrid.aims.dashboard.controller;

import java.time.OffsetDateTime;
import java.util.List;
import com.innogrid.aims.common.exception.GlobalExceptionHandler;
import com.innogrid.aims.dashboard.dto.GpuDashboardFilter;
import com.innogrid.aims.dashboard.dto.GpuFilterOptionsResponse;
import com.innogrid.aims.dashboard.dto.GpuMetric;
import com.innogrid.aims.dashboard.dto.GpuUnitPointResponse;
import com.innogrid.aims.dashboard.service.GpuDashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.ResourceAccessException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 2단계 계약 — allocationTypes·기간 파라미터 전달, GPU 단위 분포 경로, 필터 목록 ?role=, 시계열 기간 생략 허용, Thanos 연결 실패 503. */
class GpuDashboardControllerTest {

    GpuDashboardService service = mock(GpuDashboardService.class);
    MockMvc mvc;
    String base = "/api/v1/dashboards/gpu";

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new GpuDashboardController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void gpuDistributionPassesAllocationTypesAndWindow() throws Exception {
        when(service.getGpuDistribution(any(), any(), any())).thenReturn(List.of(new GpuUnitPointResponse(
                "GPU-a", "h / gpu 0", "MIG", null, null, "h", "NVIDIA A100", 80.0, 75.0, 61.0, 250.0, true)));
        mvc.perform(get(base + "/distributions/gpus").param("allocationTypes", "MIG", "VGPU")
                        .param("from", "2026-10-06T00:00:00Z").param("to", "2026-10-06T01:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("GPU-a"))
                .andExpect(jsonPath("$.data[0].temperatureC").value(61.0));
        verify(service).getGpuDistribution(argThat((GpuDashboardFilter f) -> f.allocationTypes().equals(List.of("MIG", "VGPU"))),
                eq(OffsetDateTime.parse("2026-10-06T00:00:00Z")), eq(OffsetDateTime.parse("2026-10-06T01:00:00Z")));
    }

    @Test void filterOptionsTakeRoleAndTrendsAllowOmittedWindow() throws Exception {
        when(service.getFilterOptions(List.of("USER"))).thenReturn(new GpuFilterOptionsResponse(List.of("ADMIN", "USER"),
                List.of("mdc-01"), List.of(), List.of(), List.of("PASSTHROUGH", "MIG", "VGPU"),
                List.of(new GpuFilterOptionsResponse.DeploymentOption("mdc-01", "mdc-01"))));
        mvc.perform(get(base + "/filter-options").param("role", "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deployments[0].name").value("mdc-01"));

        mvc.perform(get(base + "/trends/gpus").param("metric", "GPU_UTILIZATION"))
                .andExpect(status().isOk());                                 // 기간 생략 = 최근 1시간
        verify(service).getTrends(any(), eq(GpuMetric.GPU_UTILIZATION), eq(5), isNull(), isNull(), eq(false));
    }

    @Test void thanosUnreachableIs503InCommonResponseWithoutInternalAddress() throws Exception {
        when(service.getSummary(any(), any(), any())).thenThrow(new ResourceAccessException(
                "I/O error on GET request for \"http://10.0.0.5:19192/api/v1/query\": Connection refused"));
        mvc.perform(get(base + "/summary"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("외부 연동 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."));
    }
}
