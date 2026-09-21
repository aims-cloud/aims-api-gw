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
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.process.ProcessMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.process.ProcessMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.process.ProcessSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostProcessMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostProcessMetricsQueryService.ProcessSummary;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostProcessMetricsQueryService.StateCount;
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
 * 노드 Process 카테고리 패널 테스트 — 스코프 접근 검증(404), exporter 미등록 노드의 미수집 처리,
 * KPI·상태별 개수 전달, interval 시계열.
 *
 * 대역: node-01(assets.id 11, node_exporter 10.0.0.1:9100) — ADMIN 스코프
 *       node-02(assets.id 12, exporter 없음) — ADMIN 스코프
 *       assets.id 19 — 스코프 밖
 */
class ResourceExplorerNodeProcessServiceTest {

    ResourceScopeService scopeService = mock(ResourceScopeService.class);
    AssetRepository assetRepository = mock(AssetRepository.class);
    HostProcessMetricsQueryService metrics = mock(HostProcessMetricsQueryService.class);

    ResourceExplorerNodeProcessService service = new ResourceExplorerNodeProcessService(
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
        assertThatThrownBy(() -> service.getProcessSummary("19"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getProcessMetricTrend("19", ProcessMetric.PROCS_RUNNING, NodeMetricInterval.LAST_1H))
                .isInstanceOf(BusinessException.class);

        verify(metrics, never()).summary(any());
        verify(metrics, never()).trend(any(), any(), any(), any(), any());
    }

    @Test
    void exporter가_없는_노드는_Thanos를_조회하지_않고_미수집으로_내린다() {
        ProcessSummaryResponse summary = service.getProcessSummary("12");
        assertThat(summary.runningCount()).isNull();
        assertThat(summary.forksPerSec()).isNull();
        assertThat(summary.processesByState()).isEmpty();
        assertThat(service.getProcessMetricTrend("12", ProcessMetric.THREADS, NodeMetricInterval.LAST_1H).series()).isEmpty();

        verify(metrics, never()).summary(any());
        verify(metrics, never()).trend(any(), any(), any(), any(), any());
    }

    @Test
    void KPI는_조회_값과_상태별_개수를_응답으로_옮긴다() {
        when(metrics.summary(NODE_SEL)).thenReturn(new ProcessSummary(3, 0, 412, 1287, 2.35,
                List.of(new StateCount("I", 11), new StateCount("R", 3), new StateCount("S", 398))));

        ProcessSummaryResponse summary = service.getProcessSummary("11");

        assertThat(summary.runningCount()).isEqualTo(3);
        assertThat(summary.blockedCount()).isEqualTo(0);
        assertThat(summary.processCount()).isEqualTo(412);
        assertThat(summary.threadCount()).isEqualTo(1287);
        assertThat(summary.forksPerSec()).isEqualTo(2.35);
        assertThat(summary.processesByState()).extracting(ProcessSummaryResponse.StateCount::state).containsExactly("I", "R", "S");
        assertThat(summary.processesByState().get(2).count()).isEqualTo(398);

        when(metrics.summary(NODE_SEL)).thenReturn(new ProcessSummary(1, 0, null, null, 0.5, List.of()));   // processes 콜렉터 없음
        ProcessSummaryResponse partial = service.getProcessSummary("11");
        assertThat(partial.runningCount()).isEqualTo(1);
        assertThat(partial.processCount()).isNull();
        assertThat(partial.processesByState()).isEmpty();
    }

    @Test
    void 시계열은_interval_구간과_기본_step으로_조회하고_미수집이면_빈_시리즈다() {
        when(metrics.trend(eq(ProcessMetric.FORKS_RATE), eq(NODE_SEL), any(), any(), eq("15m")))
                .thenReturn(List.of(new DataPoint(1L, 2.35)));

        ProcessMetricTrendResponse trend = service.getProcessMetricTrend("11", ProcessMetric.FORKS_RATE, NodeMetricInterval.LAST_6H);

        assertThat(trend.metric()).isEqualTo(ProcessMetric.FORKS_RATE);
        assertThat(trend.unit()).isEqualTo("/s");
        assertThat(trend.step()).isEqualTo("15m");
        assertThat(trend.to() - trend.from()).isEqualTo(6 * 3600);
        assertThat(trend.series()).hasSize(1);
        assertThat(trend.series().get(0).name()).isEqualTo("process");
        assertThat(trend.series().get(0).dataPoints()).containsExactly(new DataPoint(1L, 2.35));

        when(metrics.trend(eq(ProcessMetric.PROCESSES_TOTAL), eq(NODE_SEL), any(), any(), eq("30s"))).thenReturn(List.of());
        ProcessMetricTrendResponse empty = service.getProcessMetricTrend("11", ProcessMetric.PROCESSES_TOTAL, NodeMetricInterval.LAST_10M);
        assertThat(empty.unit()).isEqualTo("count");
        assertThat(empty.series()).isEmpty();
    }
}
