package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.infra.repository.AssetRepository;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceUnitListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceUnitState;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostServiceStatusMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostServiceStatusMetricsQueryService.Unit;
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
 * 노드 Service Status 카테고리 패널 테스트 — 스코프 접근 검증(404), exporter 미등록·systemd 콜렉터 없는 노드의 미수집,
 * KPI 상태별 집계, 유닛 목록 상태 필터·키워드·정렬·페이징·400, interval 시계열.
 *
 * 대역: node-01(assets.id 11, node_exporter 10.0.0.1:9100) — ADMIN 스코프
 *       node-02(assets.id 12, exporter 없음) — ADMIN 스코프
 *       assets.id 19 — 스코프 밖
 */
class ResourceExplorerNodeServiceStatusServiceTest {

    ResourceScopeService scopeService = mock(ResourceScopeService.class);
    AssetRepository assetRepository = mock(AssetRepository.class);
    HostServiceStatusMetricsQueryService metrics = mock(HostServiceStatusMetricsQueryService.class);

    ResourceExplorerNodeServiceStatusService service = new ResourceExplorerNodeServiceStatusService(
            new NodeAccessService(scopeService, assetRepository), metrics);

    static final MetricSelector NODE_SEL = MetricSelector.instance("10.0.0.1:9100");

    static final List<Unit> UNITS = List.of(
            new Unit("apt-daily.service", "oneshot", ServiceUnitState.INACTIVE),
            new Unit("cloud-final.service", "oneshot", ServiceUnitState.FAILED),
            new Unit("node_exporter.service", "simple", ServiceUnitState.ACTIVE),
            new Unit("ssh.service", "notify", ServiceUnitState.ACTIVE));

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

        when(metrics.units(NODE_SEL)).thenReturn(UNITS);
    }

    @Test
    void 스코프_밖_노드는_404이고_Thanos를_조회하지_않는다() {
        assertThatThrownBy(() -> service.getServiceStatusSummary("19"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getServiceUnits("19", null, null, 1, 10, "name,asc")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getServiceStatusMetricTrend("19", ServiceStatusMetric.ACTIVE_UNITS, NodeMetricInterval.LAST_1H))
                .isInstanceOf(BusinessException.class);

        verify(metrics, never()).units(any());
        verify(metrics, never()).trend(any(), any(), any(), any(), any());
    }

    @Test
    void exporter가_없거나_systemd_콜렉터가_없는_노드는_전부_미수집이다() {
        assertThat(service.getServiceStatusSummary("12")).isEqualTo(new ServiceStatusSummaryResponse(null, null, null, null, null, null));
        assertThat(service.getServiceUnits("12", null, null, 1, 10, "name,asc").totalElements()).isZero();
        assertThat(service.getServiceStatusMetricTrend("12", ServiceStatusMetric.FAILED_UNITS, NodeMetricInterval.LAST_1H).series()).isEmpty();
        verify(metrics, never()).units(any());

        when(metrics.units(NODE_SEL)).thenReturn(List.of());                                  // exporter 는 있으나 systemd 콜렉터 없음
        assertThat(service.getServiceStatusSummary("11").totalCount()).isNull();
        assertThat(service.getServiceUnits("11", null, null, 1, 10, "name,asc").totalElements()).isZero();
    }

    @Test
    void KPI는_전체와_상태별_유닛_수를_집계한다() {
        ServiceStatusSummaryResponse summary = service.getServiceStatusSummary("11");

        assertThat(summary.totalCount()).isEqualTo(4);
        assertThat(summary.activeCount()).isEqualTo(2);
        assertThat(summary.inactiveCount()).isEqualTo(1);
        assertThat(summary.failedCount()).isEqualTo(1);
        assertThat(summary.activatingCount()).isZero();
        assertThat(summary.deactivatingCount()).isZero();
    }

    @Test
    void 유닛_목록은_상태_필터_키워드_정렬_페이징을_지원하고_미지원_정렬은_400이다() {
        PageResponse<ServiceUnitListItemResponse> all = service.getServiceUnits("11", null, null, 1, 10, "name,asc");
        assertThat(all.totalElements()).isEqualTo(4);
        assertThat(all.items()).extracting(ServiceUnitListItemResponse::name)
                .containsExactly("apt-daily.service", "cloud-final.service", "node_exporter.service", "ssh.service");
        assertThat(all.items().get(1).state()).isEqualTo(ServiceUnitState.FAILED);
        assertThat(all.items().get(1).type()).isEqualTo("oneshot");

        assertThat(service.getServiceUnits("11", ServiceUnitState.ACTIVE, null, 1, 10, "name,asc").items())
                .extracting(ServiceUnitListItemResponse::name).containsExactly("node_exporter.service", "ssh.service");
        assertThat(service.getServiceUnits("11", null, "SSH", 1, 10, "name,asc").items())
                .extracting(ServiceUnitListItemResponse::name).containsExactly("ssh.service");
        assertThat(service.getServiceUnits("11", ServiceUnitState.FAILED, "ssh", 1, 10, "name,asc").totalElements()).isZero();

        assertThat(service.getServiceUnits("11", null, null, 1, 10, "state,asc").items())
                .extracting(ServiceUnitListItemResponse::name)
                .containsExactly("node_exporter.service", "ssh.service", "apt-daily.service", "cloud-final.service");   // ACTIVE→INACTIVE→FAILED, 동률 이름순
        assertThat(service.getServiceUnits("11", null, null, 1, 10, "type,desc").items().get(0).type()).isEqualTo("simple");

        PageResponse<ServiceUnitListItemResponse> second = service.getServiceUnits("11", null, null, 2, 3, "name,asc");
        assertThat(second.items()).extracting(ServiceUnitListItemResponse::name).containsExactly("ssh.service");
        assertThat(second.totalPages()).isEqualTo(2);

        assertThatThrownBy(() -> service.getServiceUnits("11", null, null, 1, 10, "pid,asc"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void 시계열은_interval_구간과_기본_step으로_조회하고_미수집이면_빈_시리즈다() {
        when(metrics.trend(eq(ServiceStatusMetric.FAILED_UNITS), eq(NODE_SEL), any(), any(), eq("15m")))
                .thenReturn(List.of(new DataPoint(1L, 1.0)));

        ServiceStatusMetricTrendResponse trend = service.getServiceStatusMetricTrend("11", ServiceStatusMetric.FAILED_UNITS, NodeMetricInterval.LAST_6H);

        assertThat(trend.metric()).isEqualTo(ServiceStatusMetric.FAILED_UNITS);
        assertThat(trend.unit()).isEqualTo("count");
        assertThat(trend.step()).isEqualTo("15m");
        assertThat(trend.to() - trend.from()).isEqualTo(6 * 3600);
        assertThat(trend.series()).hasSize(1);
        assertThat(trend.series().get(0).name()).isEqualTo("service_status");
        assertThat(trend.series().get(0).dataPoints()).containsExactly(new DataPoint(1L, 1.0));

        when(metrics.trend(eq(ServiceStatusMetric.ACTIVE_UNITS), eq(NODE_SEL), any(), any(), eq("30s"))).thenReturn(List.of());
        assertThat(service.getServiceStatusMetricTrend("11", ServiceStatusMetric.ACTIVE_UNITS, NodeMetricInterval.LAST_10M).series()).isEmpty();
    }
}
