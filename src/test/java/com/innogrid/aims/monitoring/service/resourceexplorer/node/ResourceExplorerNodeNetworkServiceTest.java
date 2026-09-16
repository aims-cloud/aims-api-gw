package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.infra.repository.AssetRepository;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.network.NetworkMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.network.NetworkMetricTrendResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostCategoryMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 노드 Network 카테고리 패널 테스트 — 스코프 접근 검증(404), exporter 미등록 노드의 빈 시리즈,
 * instance 선택자·interval 구간·step 전달, 미수집 빈 시리즈.
 *
 * 대역: node-01(assets.id 11, node_exporter 10.0.0.1:9100) — ADMIN 스코프
 *       node-02(assets.id 12, exporter 없음) — ADMIN 스코프
 *       assets.id 19 — 스코프 밖
 */
class ResourceExplorerNodeNetworkServiceTest {

    ResourceScopeService scopeService = mock(ResourceScopeService.class);
    AssetRepository assetRepository = mock(AssetRepository.class);
    HostCategoryMetricsQueryService metrics = mock(HostCategoryMetricsQueryService.class);

    ResourceExplorerNodeNetworkService service = new ResourceExplorerNodeNetworkService(
            new NodeAccessService(scopeService, assetRepository), metrics);

    static final MetricSelector NODE_SEL = MetricSelector.instance("10.0.0.1:9100");

    @BeforeEach
    void setUp() {
        Asset node01 = new Asset("mdc-01", Asset.AssetType.SERVER, "node-01");
        ReflectionTestUtils.setField(node01, "id", 11L);
        Asset node02 = new Asset("mdc-01", Asset.AssetType.SERVER, "node-02");
        ReflectionTestUtils.setField(node02, "id", 12L);

        ScopedHost withExporter = new ScopedHost("ADMIN", 11L, "node-01", null, "mdc-01", ResourceStatus.RUNNING,
                "10.0.0.1:9100", List.of("10.0.0.1"));
        ScopedHost withoutExporter = new ScopedHost("ADMIN", 12L, "node-02", null, "mdc-01", ResourceStatus.PENDING,
                null, List.of());
        when(scopeService.currentUserScope()).thenReturn(
                new Scope(Set.of("ADMIN"), List.of(withExporter, withoutExporter), List.of(), List.of()));
        when(assetRepository.findById(11L)).thenReturn(Optional.of(node01));
        when(assetRepository.findById(12L)).thenReturn(Optional.of(node02));
    }

    @Test
    void 스코프_밖_노드는_404이고_Thanos를_조회하지_않는다() {
        assertThatThrownBy(() -> service.getNetworkMetricTrend("19", NetworkMetric.NETWORK_TOTAL, NodeMetricInterval.LAST_1H))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(metrics, never()).networkTrend(any(NetworkMetric.class), any(), any(), any(), any());
    }

    @Test
    void exporter가_없는_노드는_Thanos를_조회하지_않고_빈_시리즈를_내린다() {
        NetworkMetricTrendResponse trend = service.getNetworkMetricTrend("12", NetworkMetric.NETWORK_RECV, NodeMetricInterval.LAST_30D);

        assertThat(trend.series()).isEmpty();
        assertThat(trend.step()).isEqualTo("1d");
        verify(metrics, never()).networkTrend(any(NetworkMetric.class), any(), any(), any(), any());
    }

    @Test
    void 시계열은_instance_선택자와_interval_구간_기본_step으로_조회하고_미수집이면_빈_시리즈다() {
        when(metrics.networkTrend(eq(NetworkMetric.NETWORK_TOTAL), eq(NODE_SEL), any(), any(), eq("5m")))
                .thenReturn(List.of(new DataPoint(1L, 1_048_576.0)));

        NetworkMetricTrendResponse trend = service.getNetworkMetricTrend("11", NetworkMetric.NETWORK_TOTAL, NodeMetricInterval.LAST_1H);

        assertThat(trend.metric()).isEqualTo(NetworkMetric.NETWORK_TOTAL);
        assertThat(trend.unit()).isEqualTo("B/s");
        assertThat(trend.step()).isEqualTo("5m");
        assertThat(trend.to() - trend.from()).isEqualTo(3600);
        assertThat(trend.series()).hasSize(1);
        assertThat(trend.series().get(0).name()).isEqualTo("network");
        assertThat(trend.series().get(0).dataPoints()).containsExactly(new DataPoint(1L, 1_048_576.0));

        when(metrics.networkTrend(eq(NetworkMetric.NETWORK_DROP_IN), eq(NODE_SEL), any(), any(), eq("30s"))).thenReturn(List.of());
        NetworkMetricTrendResponse empty = service.getNetworkMetricTrend("11", NetworkMetric.NETWORK_DROP_IN, NodeMetricInterval.LAST_10M);
        assertThat(empty.unit()).isEqualTo("/s");
        assertThat(empty.series()).isEmpty();
    }
}
