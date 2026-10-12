package com.innogrid.aims.monitoring.controller;

import java.time.Instant;
import java.util.List;

import com.innogrid.aims.common.exception.GlobalExceptionHandler;
import com.innogrid.aims.monitoring.controller.resourceexplorer.node.ResourceExplorerNodeGpuController;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuMetricTrendResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.node.ResourceExplorerNodeGpuService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 노드 GPU 차트의 from/to 해석 — 형식 오류는 서비스 호출 없이 400(공통 응답), 정상 값은 그대로 전달. */
class NodeGpuControllerTest {

    ResourceExplorerNodeGpuService gpus = mock(ResourceExplorerNodeGpuService.class);
    MockMvc mvc;
    String base = "/api/v1/monitoring/resource-explorer/node/11/gpu";

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ResourceExplorerNodeGpuController(gpus))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void 차트의_잘못된_시각은_500이_아니라_400이고_서비스를_부르지_않는다() throws Exception {
        mvc.perform(get(base + "/metrics/trend").param("from", "not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("from 은(는) ISO-8601 시각이어야 합니다 (예: 2026-10-12T00:00:00Z): not-a-date"));
        mvc.perform(get(base + "/devices/GPU-a/metrics/trend").param("to", "2026-10-12 00:00"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(gpus);
    }

    @Test
    void 차트의_정상_시각은_Instant로_전달한다() throws Exception {
        Instant from = Instant.parse("2026-10-12T00:00:00Z");
        Instant to = Instant.parse("2026-10-12T01:00:00Z");
        when(gpus.getGroupMetricTrend(anyString(), any(), any(), any(), anyString())).thenReturn(
                new GpuMetricTrendResponse(GpuMetric.GPU_UTILIZATION, "%", from.getEpochSecond(), to.getEpochSecond(), "60s", List.of()));

        mvc.perform(get(base + "/metrics/trend")
                        .param("from", "2026-10-12T09:00:00+09:00").param("to", "2026-10-12T01:00:00Z").param("step", "60s"))
                .andExpect(status().isOk());
        verify(gpus).getGroupMetricTrend(eq("11"), eq(GpuMetric.GPU_UTILIZATION), eq(from), eq(to), eq("60s"));
    }
}
