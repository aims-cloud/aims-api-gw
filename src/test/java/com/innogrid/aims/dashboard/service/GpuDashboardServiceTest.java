package com.innogrid.aims.dashboard.service;

import java.util.List;

import com.innogrid.aims.dashboard.dto.GpuDashboardFilter;
import com.innogrid.aims.dashboard.dto.GpuFilterOptionsResponse;
import com.innogrid.aims.dashboard.dto.GpuMetric;
import com.innogrid.aims.dashboard.dto.GpuRankingResponse;
import com.innogrid.aims.dashboard.dto.GpuSummaryResponse;
import com.innogrid.aims.dashboard.dto.UpdateGpuThresholdRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
// TODO:: 실제 Thanos 연동 시 MockDataProvider 기반 검증을 Thanos Client 대역 기반 검증으로 교체
class GpuDashboardServiceTest {

    @Autowired
    GpuDashboardService service;

    @BeforeEach
    void resetThresholds() {
        service.updateThresholds(new UpdateGpuThresholdRequest(10.0, 60.0, 60.0, 10.0));
    }

    @Test
    void MockDataProvider로_요약과_필터를_조회한다() {
        GpuSummaryResponse summary = service.getSummary(new GpuDashboardFilter(null, null, null, null), null, null);
        GpuFilterOptionsResponse options = service.getFilterOptions(null);

        assertThat(summary.allocatedGpuCount()).isEqualTo(7L);
        assertThat(summary.idleGpuCount()).isEqualTo(2L);
        assertThat(options.gpuTypes())
                .containsExactly("NVIDIA A100", "NVIDIA H100", "NVIDIA L40S", "NVIDIA T4");
    }

    @Test
    void 복수_VM_필터와_TopN을_적용한다() {
        GpuDashboardFilter filter = new GpuDashboardFilter(null, null, List.of("vm-01", "vm-02"), null);

        List<GpuRankingResponse> result = service.getRankings(filter, GpuMetric.GPU_UTILIZATION, 1, true, null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("vm-01");
        assertThat(result.get(0).value()).isEqualTo(89.0);
    }

    @Test
    void 요약에_활성_전체_Peak_GPU가_있고_할당_유형으로_거른다() {
        GpuSummaryResponse summary = service.getSummary(GpuDashboardFilter.all(), null, null);

        assertThat(summary.totalGpuCount()).isEqualTo(8L);
        assertThat(summary.activeGpuCount()).isEqualTo(6L);            // 사용률 > 10: 92·86·78·64·55·38
        assertThat(summary.peakVramGpuId()).isEqualTo("gpu-03");
        assertThat(summary.peakVramVmId()).isEqualTo("vm-02");

        GpuDashboardFilter mig = new GpuDashboardFilter(null, null, null, null, List.of("MIG"));
        assertThat(service.getGpuDistribution(mig, null, null))
                .extracting(com.innogrid.aims.dashboard.dto.GpuUnitPointResponse::id).containsExactly("gpu-02", "gpu-06");
        assertThat(service.getThresholds().activeGpuUtilization()).isEqualTo(10.0);
    }

    @Test
    void 임계치_변경은_요약_판정에_반영된다() {
        service.updateThresholds(new UpdateGpuThresholdRequest(5.0, 80.0, 80.0, null));

        GpuSummaryResponse summary = service.getSummary(new GpuDashboardFilter(null, null, null, null), null, null);

        assertThat(summary.idleGpuCount()).isEqualTo(1L);
        assertThat(summary.highUtilizationGpuCount()).isEqualTo(2L);
    }
}
