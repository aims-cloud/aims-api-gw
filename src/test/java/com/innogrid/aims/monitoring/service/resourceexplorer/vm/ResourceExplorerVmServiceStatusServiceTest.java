package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.RoleNamespace;
import com.innogrid.aims.infra.repository.RoleNamespaceRepository;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceUnitListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceUnitState;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostServiceStatusMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostServiceStatusMetricsQueryService.Unit;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbInfra;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbNode;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VM Service Status 카테고리 패널 테스트 — 스코프·부재 404(Thanos 미조회), systemd 콜렉터 없음 미수집,
 * KPI 상태별 집계, 유닛 목록 state 필터·키워드·정렬·페이징·400, interval 시계열.
 *
 * 대역: ns openstack / infra openstack-infra-01 / vm vm-1, ADMIN → openstack 매핑
 */
class ResourceExplorerVmServiceStatusServiceTest {

    AuthService authService = mock(AuthService.class);
    RoleNamespaceRepository roleNamespaceRepository = mock(RoleNamespaceRepository.class);
    TumblebugTreeBuilder tumblebugTreeBuilder = mock(TumblebugTreeBuilder.class);
    HostServiceStatusMetricsQueryService metrics = mock(HostServiceStatusMetricsQueryService.class);
    TumblebugPanelSupport support = new TumblebugPanelSupport(authService, roleNamespaceRepository, tumblebugTreeBuilder);

    ResourceExplorerVmServiceStatusService service = new ResourceExplorerVmServiceStatusService(support, metrics);

    static final String NS = "openstack";
    static final String INFRA = "openstack-infra-01";
    static final String VM = "vm-1";
    static final MetricSelector SEL = MetricSelector.tumblebugNode(NS, INFRA, VM);

    static final List<Unit> UNITS = List.of(
            new Unit("apt-daily.service", "oneshot", ServiceUnitState.INACTIVE),
            new Unit("cloud-final.service", "oneshot", ServiceUnitState.FAILED),
            new Unit("node_exporter.service", "simple", ServiceUnitState.ACTIVE),
            new Unit("ssh.service", "notify", ServiceUnitState.ACTIVE));

    @BeforeEach
    void setUp() {
        when(authService.currentUserRoleIds()).thenReturn(new LinkedHashSet<>(List.of("ADMIN")));
        Role admin = new Role("ADMIN", "관리자");
        when(roleNamespaceRepository.findByRole_NameIn(anyCollection())).thenAnswer(inv -> {
            Collection<String> roles = inv.getArgument(0);
            return roles.contains("ADMIN") ? List.of(new RoleNamespace(admin, NS)) : List.of();
        });
        when(tumblebugTreeBuilder.snapshot(anyCollection())).thenReturn(new TbSnapshot(Instant.now(),
                Map.of(NS, NS),
                Map.of(NS, List.of(new TbInfra(INFRA, INFRA, "Running:1 (R:1/1)", List.of(
                        new TbNode(VM, VM, "Running", "g1", "openstack", null, "172.16.0.1"))))),
                Set.of(NS), Set.of(), false));

        when(metrics.units(SEL)).thenReturn(UNITS);
    }

    @Test
    void 스코프_밖_Namespace나_없는_VM은_404이고_Thanos를_조회하지_않는다() {
        assertThatThrownBy(() -> service.getServiceStatusSummary(null, "other-ns", INFRA, VM))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getServiceUnits("GUEST", NS, INFRA, VM, null, null, 1, 10, "name,asc")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getServiceStatusMetricTrend(null, NS, INFRA, "vm-999", ServiceStatusMetric.ACTIVE_UNITS, NodeMetricInterval.LAST_1H))
                .isInstanceOf(BusinessException.class);

        verify(metrics, never()).units(any());
        verify(metrics, never()).trend(any(), any(), any(), any(), any());
    }

    @Test
    void systemd_콜렉터가_없어_표본이_없으면_KPI_전부_null_빈_목록이다() {
        when(metrics.units(SEL)).thenReturn(List.of());

        assertThat(service.getServiceStatusSummary("ADMIN", NS, INFRA, VM))
                .isEqualTo(new ServiceStatusSummaryResponse(null, null, null, null, null, null));
        assertThat(service.getServiceUnits(null, NS, INFRA, VM, null, null, 1, 10, "name,asc").totalElements()).isZero();
    }

    @Test
    void KPI는_전체와_상태별_유닛_수를_집계한다() {
        ServiceStatusSummaryResponse summary = service.getServiceStatusSummary(null, NS, INFRA, VM);

        assertThat(summary.totalCount()).isEqualTo(4);
        assertThat(summary.activeCount()).isEqualTo(2);
        assertThat(summary.inactiveCount()).isEqualTo(1);
        assertThat(summary.failedCount()).isEqualTo(1);
        assertThat(summary.activatingCount()).isZero();
        assertThat(summary.deactivatingCount()).isZero();
    }

    @Test
    void 유닛_목록은_상태_필터_키워드_정렬_페이징을_지원하고_미지원_정렬은_400이다() {
        PageResponse<ServiceUnitListItemResponse> all = service.getServiceUnits(null, NS, INFRA, VM, null, null, 1, 10, "name,asc");
        assertThat(all.totalElements()).isEqualTo(4);
        assertThat(all.items()).extracting(ServiceUnitListItemResponse::name)
                .containsExactly("apt-daily.service", "cloud-final.service", "node_exporter.service", "ssh.service");
        assertThat(all.items().get(1).state()).isEqualTo(ServiceUnitState.FAILED);
        assertThat(all.items().get(1).type()).isEqualTo("oneshot");

        assertThat(service.getServiceUnits(null, NS, INFRA, VM, ServiceUnitState.ACTIVE, null, 1, 10, "name,asc").items())
                .extracting(ServiceUnitListItemResponse::name).containsExactly("node_exporter.service", "ssh.service");
        assertThat(service.getServiceUnits(null, NS, INFRA, VM, null, "SSH", 1, 10, "name,asc").items())
                .extracting(ServiceUnitListItemResponse::name).containsExactly("ssh.service");
        assertThat(service.getServiceUnits(null, NS, INFRA, VM, ServiceUnitState.FAILED, "ssh", 1, 10, "name,asc").totalElements()).isZero();

        assertThat(service.getServiceUnits(null, NS, INFRA, VM, null, null, 1, 10, "state,asc").items())
                .extracting(ServiceUnitListItemResponse::name)
                .containsExactly("node_exporter.service", "ssh.service", "apt-daily.service", "cloud-final.service");   // ACTIVE→INACTIVE→FAILED, 동률 이름순
        assertThat(service.getServiceUnits(null, NS, INFRA, VM, null, null, 1, 10, "type,desc").items().get(0).type()).isEqualTo("simple");

        PageResponse<ServiceUnitListItemResponse> second = service.getServiceUnits(null, NS, INFRA, VM, null, null, 2, 3, "name,asc");
        assertThat(second.items()).extracting(ServiceUnitListItemResponse::name).containsExactly("ssh.service");
        assertThat(second.totalPages()).isEqualTo(2);

        assertThatThrownBy(() -> service.getServiceUnits(null, NS, INFRA, VM, null, null, 1, 10, "pid,asc"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void 시계열은_interval_구간과_기본_step으로_조회하고_미수집이면_빈_시리즈다() {
        when(metrics.trend(eq(ServiceStatusMetric.FAILED_UNITS), eq(SEL), any(), any(), eq("15m")))
                .thenReturn(List.of(new DataPoint(1L, 1.0)));

        ServiceStatusMetricTrendResponse trend = service.getServiceStatusMetricTrend(null, NS, INFRA, VM, ServiceStatusMetric.FAILED_UNITS, NodeMetricInterval.LAST_6H);

        assertThat(trend.metric()).isEqualTo(ServiceStatusMetric.FAILED_UNITS);
        assertThat(trend.unit()).isEqualTo("count");
        assertThat(trend.step()).isEqualTo("15m");
        assertThat(trend.to() - trend.from()).isEqualTo(6 * 3600L);
        assertThat(trend.series()).hasSize(1);
        assertThat(trend.series().get(0).name()).isEqualTo("service_status");
        assertThat(trend.series().get(0).dataPoints()).containsExactly(new DataPoint(1L, 1.0));

        when(metrics.trend(eq(ServiceStatusMetric.ACTIVE_UNITS), eq(SEL), any(), any(), eq("30s"))).thenReturn(List.of());
        assertThat(service.getServiceStatusMetricTrend(null, NS, INFRA, VM, ServiceStatusMetric.ACTIVE_UNITS, NodeMetricInterval.LAST_10M).series()).isEmpty();
    }
}
