package com.innogrid.aims.dashboard.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.innogrid.aims.dashboard.GpuDashboardProperties;
import com.innogrid.aims.dashboard.dto.GpuDashboardFilter;
import com.innogrid.aims.dashboard.dto.GpuDashboardResponse;
import com.innogrid.aims.dashboard.dto.GpuFilterOptionsResponse;
import com.innogrid.aims.dashboard.dto.GpuMetric;
import com.innogrid.aims.dashboard.dto.GpuNodeCoverageResponse;
import com.innogrid.aims.dashboard.dto.GpuRankingResponse;
import com.innogrid.aims.dashboard.model.GpuDashboardThreshold;
import com.innogrid.aims.dashboard.service.GpuNodeScopeService.GpuNode;
import com.innogrid.aims.dashboard.service.GpuNodeScopeService.GpuNodeScope;
import com.innogrid.aims.mock.MockDataProvider;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 실데이터 경로(1단계 기반) — DCGM 시계열은 스코프(DB) 노드의 instance 만 쓰고 역할·MDC 는 DB 에서 붙는다,
 * 수집 현황(등록·수집 중·끊긴 노드), 미등록 노드 수는 ADMIN 만, Thanos 즉시 조회는 캐시 수명 동안 재사용.
 */
class GpuDashboardLiveTest {

    GpuDashboardProperties properties = new GpuDashboardProperties();
    VictoriaMetricsClient metrics = mock(VictoriaMetricsClient.class);
    GpuThresholdService thresholds = mock(GpuThresholdService.class);
    GpuNodeScopeService scope = mock(GpuNodeScopeService.class);
    GpuDashboardService service = new GpuDashboardService(properties, metrics, mock(MockDataProvider.class), thresholds, scope);

    GpuNode node1 = new GpuNode(1L, "gpu-node-1", "mdc-01", "10.0.0.11:9400", Set.of("ADMIN", "USER"));
    GpuNode node2 = new GpuNode(2L, "gpu-node-2", "mdc-02", "10.0.0.12:9400", Set.of("ADMIN"));
    Instant now = Instant.parse("2026-10-06T03:00:00Z");

    static Map<String, Object> series(String instance, String uuid, String gpu, double value) {
        return Map.of("metric", Map.of("instance", instance, "UUID", uuid, "gpu", gpu, "modelName", "NVIDIA A100",
                "Hostname", "host-" + instance), "value", List.of(1, String.valueOf(value)));
    }

    @BeforeEach void setup() {
        properties.setMockEnabled(false);
        service.setClock(Clock.fixed(now, ZoneOffset.UTC));
        when(thresholds.current()).thenReturn(GpuDashboardThreshold.DEFAULT);
        // node1 의 GPU 2장 + 원장에 없는 노드(10.0.0.99) 1장. node2 는 시계열 없음(수집 끊김)
        when(metrics.queryResults("DCGM_FI_DEV_GPU_UTIL")).thenReturn(List.of(
                series("10.0.0.11:9400", "GPU-a", "0", 80), series("10.0.0.11:9400", "GPU-b", "1", 5),
                series("10.0.0.99:9400", "GPU-x", "0", 50)));
        when(metrics.queryResults("DCGM_FI_DEV_FB_USED")).thenReturn(List.of(
                series("10.0.0.11:9400", "GPU-a", "0", 30000), series("10.0.0.11:9400", "GPU-b", "1", 1000),
                series("10.0.0.99:9400", "GPU-x", "0", 1)));
        when(metrics.queryResults("DCGM_FI_DEV_FB_FREE")).thenReturn(List.of(
                series("10.0.0.11:9400", "GPU-a", "0", 10000), series("10.0.0.11:9400", "GPU-b", "1", 39000),
                series("10.0.0.99:9400", "GPU-x", "0", 1)));
        when(scope.registeredInstances()).thenReturn(Set.of("10.0.0.11:9400", "10.0.0.12:9400"));
    }

    @Test void onlyScopedInstancesAreShownWithRoleAndMdcFromDb() {
        when(scope.currentScope(any(), any())).thenReturn(new GpuNodeScope(true, Set.of("ADMIN"), List.of(node1, node2)));

        List<GpuRankingResponse> top = service.getRankings(new GpuDashboardFilter(null, null, null, null),
                GpuMetric.GPU_UTILIZATION, 5, false);

        assertThat(top).extracting(GpuRankingResponse::id).containsExactly("GPU-a", "GPU-b");   // 미등록 GPU-x 는 없음
        assertThat(top.get(0).value()).isEqualTo(80.0);
        GpuDashboardResponse dashboard = service.getDashboard(new GpuDashboardFilter(null, null, null, null), null, null);
        assertThat(dashboard.summary().vramTotalMb()).isEqualTo(80000.0);
        assertThat(dashboard.dataSource()).isEqualTo("THANOS");
    }

    @Test void coverageKeepsSilentNodesAndUnregisteredCountIsAdminOnly() {
        when(scope.currentScope(any(), any())).thenReturn(new GpuNodeScope(true, Set.of("ADMIN"), List.of(node1, node2)));
        GpuNodeCoverageResponse admin = service.getDashboard(new GpuDashboardFilter(null, null, null, null), null, null)
                .nodeCoverage();
        assertThat(admin.registeredNodeCount()).isEqualTo(2);
        assertThat(admin.collectingNodeCount()).isEqualTo(1);
        assertThat(admin.unregisteredNodeCount()).isEqualTo(1);                                   // 10.0.0.99
        assertThat(admin.nodes()).extracting(GpuNodeCoverageResponse.Node::name, GpuNodeCoverageResponse.Node::collecting)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("gpu-node-1", true),
                        org.assertj.core.groups.Tuple.tuple("gpu-node-2", false));             // 끊긴 노드도 행 유지

        when(scope.currentScope(any(), any())).thenReturn(new GpuNodeScope(false, Set.of("USER"), List.of(node1)));
        GpuNodeCoverageResponse user = service.getDashboard(new GpuDashboardFilter(null, null, null, null), null, null)
                .nodeCoverage();
        assertThat(user.unregisteredNodeCount()).isNull();
        assertThat(user.registeredNodeCount()).isEqualTo(1);
    }

    @Test void roleAndDeploymentFiltersArePassedToScopeNotMatchedOnLabels() {
        when(scope.currentScope(eq(List.of("USER")), eq(List.of("mdc-01"))))
                .thenReturn(new GpuNodeScope(false, Set.of("USER"), List.of(node1)));
        List<GpuRankingResponse> top = service.getRankings(
                new GpuDashboardFilter(List.of("USER"), List.of("mdc-01"), null, null), GpuMetric.GPU_UTILIZATION, 5, false);
        assertThat(top).hasSize(2);                                                               // DCGM 에 role 라벨이 없어도 걸러지지 않는다
    }

    @Test void filterOptionsComeFromScopeEvenWithoutSeries() {
        when(scope.currentScope(any(), any())).thenReturn(new GpuNodeScope(true, Set.of("USER", "ADMIN"), List.of(node1, node2)));
        GpuFilterOptionsResponse options = service.getFilterOptions();
        assertThat(options.roles()).containsExactly("ADMIN", "USER");
        assertThat(options.deploymentIds()).containsExactly("mdc-01", "mdc-02");               // mdc-02 는 시계열 없음
        assertThat(options.gpuTypes()).containsExactly("NVIDIA A100");
    }

    @Test void thanosResultIsCachedForTtlThenReloaded() {
        when(scope.currentScope(any(), any())).thenReturn(new GpuNodeScope(true, Set.of("ADMIN"), List.of(node1)));
        GpuDashboardFilter all = new GpuDashboardFilter(null, null, null, null);
        service.getSummary(all);
        service.getRankings(all, GpuMetric.GPU_UTILIZATION, 5, true);
        verify(metrics, times(1)).queryResults("DCGM_FI_DEV_GPU_UTIL");                         // 같은 20초 안 — 재조회 없음

        service.setClock(Clock.fixed(now.plus(Duration.ofSeconds(21)), ZoneOffset.UTC));
        service.getSummary(all);
        verify(metrics, times(2)).queryResults("DCGM_FI_DEV_GPU_UTIL");

        properties.setCacheTtlSeconds(0);                                                        // 0 이면 캐시 안 함
        service.getSummary(all);
        verify(metrics, times(3)).queryResults("DCGM_FI_DEV_GPU_UTIL");
    }
}
