package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.infra.repository.AssetRepository;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;
import com.innogrid.aims.monitoring.repository.MonitoringTargetRepository;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostSystemMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostSystemMetricsQueryService.SystemInfo;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostSystemMetricsQueryService.SystemSummary;
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
 * 노드 System 카테고리 패널 테스트 — 스코프 접근 검증(404), exporter 미등록 노드의 미수집·NOT_CONFIGURED,
 * KPI 가동 시간 계산, 시스템 정보 매핑·UNAVAILABLE, interval 시계열.
 *
 * 대역: node-01(assets.id 11, node_exporter 10.0.0.1:9100) — ADMIN 스코프
 *       node-02(assets.id 12, exporter 없음) — ADMIN 스코프
 *       assets.id 19 — 스코프 밖
 */
class ResourceExplorerNodeSystemServiceTest {

    ResourceScopeService scopeService = mock(ResourceScopeService.class);
    AssetRepository assetRepository = mock(AssetRepository.class);
    HostSystemMetricsQueryService metrics = mock(HostSystemMetricsQueryService.class);

    ResourceExplorerNodeSystemService service = new ResourceExplorerNodeSystemService(
            new NodeAccessService(scopeService, assetRepository, mock(MonitoringTargetRepository.class)), metrics);

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
        assertThatThrownBy(() -> service.getSystemSummary("19"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getSystemInfo("19")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getSystemMetricTrend("19", SystemMetric.LOAD_1, NodeMetricInterval.LAST_1H))
                .isInstanceOf(BusinessException.class);

        verify(metrics, never()).summary(any());
        verify(metrics, never()).info(any());
        verify(metrics, never()).trend(any(), any(), any(), any(), any());
    }

    @Test
    void exporter가_없는_노드는_Thanos를_조회하지_않고_미수집_NOT_CONFIGURED로_내린다() {
        SystemSummaryResponse summary = service.getSystemSummary("12");
        assertThat(summary.uptimeSeconds()).isNull();
        assertThat(summary.load1()).isNull();
        assertThat(summary.coreCount()).isNull();

        SystemInfoResponse info = service.getSystemInfo("12");
        assertThat(info.collectStatus()).isEqualTo(MonitoringStatus.NOT_CONFIGURED);
        assertThat(info.hostname()).isNull();

        assertThat(service.getSystemMetricTrend("12", SystemMetric.INTERRUPTS, NodeMetricInterval.LAST_1H).series()).isEmpty();

        verify(metrics, never()).summary(any());
        verify(metrics, never()).info(any());
        verify(metrics, never()).trend(any(), any(), any(), any(), any());
    }

    @Test
    void KPI는_부팅_시각으로_가동_시간을_계산하고_나머지는_그대로_전달한다() {
        long bootedTenDaysAgo = Instant.now().getEpochSecond() - 10 * 86_400 - 30;
        when(metrics.summary(NODE_SEL)).thenReturn(new SystemSummary(bootedTenDaysAgo, 0.31, 0.4, 0.55, 16, 12345.7, 3456.0));

        SystemSummaryResponse summary = service.getSystemSummary("11");

        assertThat(summary.uptimeSeconds()).isBetween(10 * 86_400L, 10 * 86_400L + 90);
        assertThat(summary.load1()).isEqualTo(0.31);
        assertThat(summary.load15()).isEqualTo(0.55);
        assertThat(summary.coreCount()).isEqualTo(16);
        assertThat(summary.contextSwitchesPerSec()).isEqualTo(12345.7);
        assertThat(summary.interruptsPerSec()).isEqualTo(3456.0);

        when(metrics.summary(NODE_SEL)).thenReturn(SystemSummary.EMPTY);
        assertThat(service.getSystemSummary("11").uptimeSeconds()).isNull();
    }

    @Test
    void 시스템_정보는_라벨을_옮기고_부팅_시각과_표본_시각을_시간대_시각으로_내리며_표본_없으면_UNAVAILABLE이다() {
        Instant boot = Instant.ofEpochSecond(1_699_990_000L);
        Instant sample = Instant.ofEpochSecond(1_700_000_000L);
        when(metrics.info(NODE_SEL)).thenReturn(Optional.of(new SystemInfo(
                "k8s-cp", "Ubuntu 24.04.1 LTS", "24.04", "6.8.0-44-generic", "x86_64", boot.getEpochSecond(), sample)));

        SystemInfoResponse info = service.getSystemInfo("11");

        assertThat(info.hostname()).isEqualTo("k8s-cp");
        assertThat(info.osName()).isEqualTo("Ubuntu 24.04.1 LTS");
        assertThat(info.osVersion()).isEqualTo("24.04");
        assertThat(info.kernelRelease()).isEqualTo("6.8.0-44-generic");
        assertThat(info.architecture()).isEqualTo("x86_64");
        assertThat(info.bootTime()).isEqualTo(boot.atZone(ZoneId.systemDefault()).toOffsetDateTime());
        assertThat(info.collectStatus()).isEqualTo(MonitoringStatus.COLLECTING);
        assertThat(info.lastCollectedAt()).isEqualTo(sample.atZone(ZoneId.systemDefault()).toOffsetDateTime());

        when(metrics.info(NODE_SEL)).thenReturn(Optional.empty());
        SystemInfoResponse unavailable = service.getSystemInfo("11");
        assertThat(unavailable.collectStatus()).isEqualTo(MonitoringStatus.UNAVAILABLE);
        assertThat(unavailable.hostname()).isNull();
        assertThat(unavailable.bootTime()).isNull();
    }

    @Test
    void 시계열은_interval_구간과_기본_step으로_조회하고_미수집이면_빈_시리즈다() {
        when(metrics.trend(eq(SystemMetric.LOAD_5), eq(NODE_SEL), any(), any(), eq("15m")))
                .thenReturn(List.of(new DataPoint(1L, 0.4)));

        SystemMetricTrendResponse trend = service.getSystemMetricTrend("11", SystemMetric.LOAD_5, NodeMetricInterval.LAST_6H);

        assertThat(trend.metric()).isEqualTo(SystemMetric.LOAD_5);
        assertThat(trend.unit()).isEmpty();
        assertThat(trend.step()).isEqualTo("15m");
        assertThat(trend.to() - trend.from()).isEqualTo(6 * 3600);
        assertThat(trend.series()).hasSize(1);
        assertThat(trend.series().get(0).name()).isEqualTo("system");
        assertThat(trend.series().get(0).dataPoints()).containsExactly(new DataPoint(1L, 0.4));

        when(metrics.trend(eq(SystemMetric.CONTEXT_SWITCHES), eq(NODE_SEL), any(), any(), eq("30s"))).thenReturn(List.of());
        SystemMetricTrendResponse empty = service.getSystemMetricTrend("11", SystemMetric.CONTEXT_SWITCHES, NodeMetricInterval.LAST_10M);
        assertThat(empty.unit()).isEqualTo("/s");
        assertThat(empty.series()).isEmpty();
    }
}
