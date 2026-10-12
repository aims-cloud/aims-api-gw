package com.innogrid.aims.dashboard.service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.dashboard.GpuDashboardProperties;
import com.innogrid.aims.dashboard.dto.GpuDashboardFilter;
import com.innogrid.aims.dashboard.dto.GpuDashboardResponse;
import com.innogrid.aims.dashboard.dto.GpuFilterOptionsResponse;
import com.innogrid.aims.dashboard.dto.GpuMetric;
import com.innogrid.aims.dashboard.dto.GpuNodeCoverageResponse;
import com.innogrid.aims.dashboard.dto.GpuRankingResponse;
import com.innogrid.aims.dashboard.dto.GpuSummaryResponse;
import com.innogrid.aims.dashboard.dto.GpuTrendResponse;
import com.innogrid.aims.dashboard.dto.GpuUnitPointResponse;
import com.innogrid.aims.dashboard.model.GpuDashboardThreshold;
import com.innogrid.aims.dashboard.service.GpuNodeScopeService.GpuNode;
import com.innogrid.aims.dashboard.service.GpuNodeScopeService.GpuNodeScope;
import com.innogrid.aims.mock.MockDataProvider;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 실데이터 경로(1·2단계) — 스코프(DB) 조인, 기간 평균 판정(활성 &gt;·유휴 &lt;·고활용 AND), 수집 끊긴 GPU 행,
 * GPU 단위 점·Top5·시계열, 노드 수집 현황(미등록 수는 ADMIN 만), 필터 옵션. Thanos 는 PromQL 문자열별 응답으로 모의한다.
 */
class GpuDashboardLiveTest {

    GpuDashboardProperties properties = new GpuDashboardProperties();
    VictoriaMetricsClient metrics = mock(VictoriaMetricsClient.class);
    GpuThresholdService thresholds = mock(GpuThresholdService.class);
    GpuNodeScopeService scope = mock(GpuNodeScopeService.class);
    DcgmSnapshotLoader loader = new DcgmSnapshotLoader(metrics, properties);
    GpuDashboardService service = new GpuDashboardService(properties, metrics, mock(MockDataProvider.class), thresholds,
            scope, loader);

    GpuNode node1 = new GpuNode(1L, "gpu-node-1", "mdc-01", "10.0.0.11:9400", Set.of("ADMIN", "USER"));
    GpuNode node2 = new GpuNode(2L, "gpu-node-2", "mdc-02", "10.0.0.12:9400", Set.of("ADMIN"));
    Instant now = Instant.parse("2026-10-06T03:00:00Z");
    GpuDashboardFilter all = GpuDashboardFilter.all();
    Map<String, List<Map<String, Object>>> byMetric = new HashMap<>();

    static Map<String, Object> series(String instance, String uuid, String gpu, double value) {
        return Map.of("metric", Map.of("instance", instance, "UUID", uuid, "gpu", gpu, "modelName", "NVIDIA A100",
                "Hostname", "host-" + instance.substring(0, instance.indexOf(':'))), "value", List.of(1, String.valueOf(value)));
    }

    void put(String metricName, Map<String, Object>... rows) {
        byMetric.put(metricName, List.of(rows));
    }

    @BeforeEach void setup() {
        properties.setMockEnabled(false);
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        service.setClock(clock);
        loader.setClock(clock);
        when(thresholds.current()).thenReturn(GpuDashboardThreshold.DEFAULT);       // 활성 >10, 유휴 <10, 고활용 60·60
        // node1: GPU-a(80%, VRAM 30/40GB) · GPU-b(10% 경계, 1/40GB) · GPU-c(하루 안에 보였으나 기간에 표본 없음)
        // 원장 밖 10.0.0.99: GPU-x. node2 는 시계열 없음
        put("DCGM_FI_DEV_GPU_UTIL", series("10.0.0.11:9400", "GPU-a", "0", 80), series("10.0.0.11:9400", "GPU-b", "1", 10),
                series("10.0.0.99:9400", "GPU-x", "0", 50));
        put("avg_over_time(DCGM_FI_DEV_FB_USED", series("10.0.0.11:9400", "GPU-a", "0", 30000),
                series("10.0.0.11:9400", "GPU-b", "1", 1000), series("10.0.0.99:9400", "GPU-x", "0", 1));
        put("DCGM_FI_DEV_FB_FREE[", series("10.0.0.11:9400", "GPU-a", "0", 10000), series("10.0.0.11:9400", "GPU-b", "1", 39000),
                series("10.0.0.99:9400", "GPU-x", "0", 1));
        put("max_over_time(DCGM_FI_DEV_FB_USED", series("10.0.0.11:9400", "GPU-a", "0", 36000),
                series("10.0.0.11:9400", "GPU-b", "1", 2000));
        put("DCGM_FI_DEV_GPU_TEMP", series("10.0.0.11:9400", "GPU-a", "0", 61));
        put("DCGM_FI_DEV_POWER_USAGE", series("10.0.0.11:9400", "GPU-a", "0", 250));
        put("[1d]", series("10.0.0.11:9400", "GPU-a", "0", 1), series("10.0.0.11:9400", "GPU-b", "1", 1),
                series("10.0.0.11:9400", "GPU-c", "2", 1), series("10.0.0.99:9400", "GPU-x", "0", 1));
        when(metrics.queryResultsAt(anyString(), any())).thenAnswer(inv -> {
            String promQl = inv.getArgument(0);
            if (promQl.startsWith("avg_over_time(DCGM_FI_DEV_GPU_UTIL")) return byMetric.get("DCGM_FI_DEV_GPU_UTIL");
            if (promQl.startsWith("avg_over_time(DCGM_FI_DEV_FB_USED")) return byMetric.get("avg_over_time(DCGM_FI_DEV_FB_USED");
            if (promQl.startsWith("avg_over_time(DCGM_FI_DEV_FB_FREE")) return byMetric.get("DCGM_FI_DEV_FB_FREE[");
            if (promQl.startsWith("max_over_time")) return byMetric.get("max_over_time(DCGM_FI_DEV_FB_USED");
            if (promQl.contains("GPU_TEMP")) return byMetric.get("DCGM_FI_DEV_GPU_TEMP");
            if (promQl.contains("POWER_USAGE")) return byMetric.get("DCGM_FI_DEV_POWER_USAGE");
            if (promQl.contains("[1d]")) return byMetric.get("[1d]");
            return List.of();
        });
        when(scope.registeredInstances()).thenReturn(Set.of("10.0.0.11:9400", "10.0.0.12:9400"));
        when(scope.currentScope(any(), any())).thenReturn(new GpuNodeScope(true, Set.of("ADMIN"), List.of(node1, node2)));
    }

    @Test void windowAveragesAreQueriedAtWindowEndWithDefaultOneHour() {
        service.getSummary(all, null, null);
        verify(metrics).queryResultsAt(startsWith("avg_over_time(DCGM_FI_DEV_GPU_UTIL[3600s]) or "), eq(now));

        OffsetDateTime from = OffsetDateTime.parse("2026-10-05T00:00:00Z");
        OffsetDateTime to = OffsetDateTime.parse("2026-10-06T00:00:00Z");
        service.getSummary(all, from, to);
        verify(metrics).queryResultsAt(eq("max_over_time(DCGM_FI_DEV_FB_USED[86400s])"), eq(to.toInstant()));
    }

    @Test void summaryJudgesOnlySampledGpusWithStrictBoundaries() {
        GpuSummaryResponse summary = service.getSummary(all, null, null);
        assertThat(summary.totalGpuCount()).isEqualTo(3);                    // a, b, c(끊김) — 원장 밖 x 제외
        assertThat(summary.activeGpuCount()).isEqualTo(1);                   // 80 > 10. b 는 10 = 경계라 아님
        assertThat(summary.idleGpuCount()).isZero();                         // b 10 은 < 10 아님, c 는 표본 없음
        assertThat(summary.highUtilizationGpuCount()).isEqualTo(1);          // a: 80 ≥ 60 AND 75% ≥ 60
        assertThat(summary.vramUsedMb()).isEqualTo(31000.0);
        assertThat(summary.vramTotalMb()).isEqualTo(80000.0);
        assertThat(summary.peakVramMb()).isEqualTo(36000.0);
        assertThat(summary.peakVramGpuId()).isEqualTo("GPU-a");
        assertThat(summary.peakVramVmId()).isNull();                         // 3단계 매핑 전
        assertThat(summary.allocatedGpuCount()).isZero();
    }

    @Test void gpuPointsKeepSilentGpuWithNullValuesAndCarryTemperaturePower() {
        List<GpuUnitPointResponse> points = service.getGpuDistribution(all, null, null);
        assertThat(points).extracting(GpuUnitPointResponse::id).containsExactly("GPU-a", "GPU-b", "GPU-c");
        GpuUnitPointResponse a = points.get(0);
        assertThat(a.name()).isEqualTo("host-10.0.0.11 / gpu 0");
        assertThat(a.gpuUtilization()).isEqualTo(80.0);
        assertThat(a.vramUtilization()).isEqualTo(75.0);
        assertThat(a.temperatureC()).isEqualTo(61.0);
        assertThat(a.powerWatts()).isEqualTo(250.0);
        GpuUnitPointResponse c = points.get(2);
        assertThat(c.collecting()).isFalse();
        assertThat(c.gpuUtilization()).isNull();
        assertThat(c.vramUtilization()).isNull();
    }

    @Test void gpuRankingsSkipSilentGpusAndCarryRoleAndDeploymentMeta() {
        List<GpuRankingResponse> peak = service.getRankings(all, GpuMetric.PEAK_VRAM, 5, false, null, null);
        assertThat(peak).extracting(GpuRankingResponse::id, GpuRankingResponse::value)
                .containsExactly(Tuple.tuple("GPU-a", 36000.0), Tuple.tuple("GPU-b", 2000.0));
        assertThat(peak.get(0).unit()).isEqualTo("MB");
        assertThat(peak.get(0).roleName()).isEqualTo("ADMIN");
        assertThat(peak.get(0).deploymentId()).isEqualTo("mdc-01");
        assertThat(peak.get(0).allocationType()).isEqualTo("UNKNOWN");      // MIG 가 아니면 3단계 전까지 모름

        GpuDashboardResponse dashboard = service.getDashboard(all, null, null);
        assertThat(dashboard.gpuVramUsedTop()).extracting(GpuRankingResponse::id).containsExactly("GPU-a", "GPU-b");
        assertThat(dashboard.gpuPeakVramTop()).hasSize(2);
        assertThat(dashboard.gpuDistribution()).hasSize(3);
        assertThat(dashboard.dataSource()).isEqualTo("THANOS");
    }

    @Test void coverageKeepsSilentNodesAndUnregisteredCountIsAdminOnly() {
        GpuNodeCoverageResponse admin = service.getDashboard(all, null, null).nodeCoverage();
        assertThat(admin.registeredNodeCount()).isEqualTo(2);
        assertThat(admin.collectingNodeCount()).isEqualTo(1);
        assertThat(admin.unregisteredNodeCount()).isEqualTo(1);              // 10.0.0.99
        assertThat(admin.nodes()).extracting(GpuNodeCoverageResponse.Node::name, GpuNodeCoverageResponse.Node::collecting)
                .containsExactly(Tuple.tuple("gpu-node-1", true), Tuple.tuple("gpu-node-2", false));

        when(scope.currentScope(any(), any())).thenReturn(new GpuNodeScope(false, Set.of("USER"), List.of(node1)));
        GpuNodeCoverageResponse user = service.getDashboard(all, null, null).nodeCoverage();
        assertThat(user.unregisteredNodeCount()).isNull();
        assertThat(user.registeredNodeCount()).isEqualTo(1);
    }

    @Test void allocationTypeFilterAndFilterOptions() {
        assertThat(service.getGpuDistribution(new GpuDashboardFilter(null, null, null, null, List.of("MIG")), null, null))
                .isEmpty();                                                  // 이 데이터에는 MIG 가 없다

        when(scope.currentScope(isNull(), isNull())).thenReturn(new GpuNodeScope(true, Set.of("USER", "ADMIN"), List.of(node1, node2)));
        when(scope.currentScope(eq(List.of("USER")), isNull())).thenReturn(new GpuNodeScope(true, Set.of("USER"), List.of(node1)));
        GpuFilterOptionsResponse options = service.getFilterOptions(List.of("USER"));
        assertThat(options.roles()).containsExactly("ADMIN", "USER");       // 역할 목록은 토큰 역할 전체
        assertThat(options.deploymentIds()).containsExactly("mdc-01");     // ?role=USER 로 좁힘
        assertThat(options.deployments()).extracting(GpuFilterOptionsResponse.DeploymentOption::name).containsExactly("mdc-01");
        assertThat(options.allocationTypes()).containsExactly("PASSTHROUGH", "MIG", "VGPU");
        assertThat(options.gpuTypes()).containsExactly("NVIDIA A100");
    }

    @Test void gpuTrendsUseSameIdsAsRankingAndWindowStep() {
        Map<String, Object> rangeA = Map.of("metric", Map.of("instance", "10.0.0.11:9400", "UUID", "GPU-a", "gpu", "0",
                "Hostname", "host-10.0.0.11"), "values", List.of(List.of(1759719600, "70"), List.of(1759719660, "90")));
        Map<String, Object> rangeX = Map.of("metric", Map.of("instance", "10.0.0.99:9400", "UUID", "GPU-x", "gpu", "0"),
                "values", List.of(List.of(1759719600, "50")));
        when(metrics.queryRangeResults(anyString(), any(), any(), anyString())).thenReturn(List.of(rangeA, rangeX));

        List<GpuTrendResponse> trends = service.getTrends(all, GpuMetric.GPU_UTILIZATION, 1, null, null, false);
        assertThat(trends).extracting(GpuTrendResponse::id).containsExactly("GPU-a");   // 1위만, 원장 밖 x 는 순위에 없음
        assertThat(trends.get(0).name()).isEqualTo("host-10.0.0.11 / gpu 0");
        assertThat(trends.get(0).dataPoints()).hasSize(2);
        verify(metrics).queryRangeResults(eq(GpuDashboardService.rangeMetric(GpuMetric.GPU_UTILIZATION)),
                eq(now.minusSeconds(3600)), eq(now), eq("60s"));             // 1시간/60
    }

    @Test void invalidWindowIs400() {
        OffsetDateTime t = OffsetDateTime.parse("2026-10-06T00:00:00Z");
        assertThatThrownBy(() -> service.getSummary(all, t, t)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getSummary(all, t.minusDays(31), t)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(metrics);
    }

    @Test void roleAndDeploymentFiltersArePassedToScopeNotMatchedOnLabels() {
        GpuNode userView = new GpuNode(1L, "gpu-node-1", "mdc-01", "10.0.0.11:9400", Set.of("USER"));   // 스코프가 역할을 좁힌 결과
        when(scope.currentScope(eq(List.of("USER")), eq(List.of("mdc-01"))))
                .thenReturn(new GpuNodeScope(false, Set.of("USER"), List.of(userView)));
        List<GpuRankingResponse> top = service.getRankings(new GpuDashboardFilter(List.of("USER"), List.of("mdc-01"), null, null),
                GpuMetric.GPU_UTILIZATION, 5, false, null, null);
        assertThat(top).hasSize(2);                                          // DCGM 에 role 라벨이 없어도 걸러지지 않는다
        assertThat(new ArrayList<>(top)).allMatch(r -> "USER".equals(r.roleName()));
    }
}
