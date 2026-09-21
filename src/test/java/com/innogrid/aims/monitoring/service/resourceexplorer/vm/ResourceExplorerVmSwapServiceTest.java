package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.RoleNamespace;
import com.innogrid.aims.infra.repository.RoleNamespaceRepository;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.swap.SwapMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.swap.SwapMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.swap.SwapSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostSwapMetricsQueryService;
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
 * VM Swap 카테고리 패널 테스트 — 스코프·부재 404(Thanos 미조회), tb_* 선택자로 KPI 전달, interval 시계열·빈 시리즈.
 *
 * 대역: ns openstack / infra openstack-infra-01 / vm vm-1, ADMIN → openstack 매핑
 */
class ResourceExplorerVmSwapServiceTest {

    AuthService authService = mock(AuthService.class);
    RoleNamespaceRepository roleNamespaceRepository = mock(RoleNamespaceRepository.class);
    TumblebugTreeBuilder tumblebugTreeBuilder = mock(TumblebugTreeBuilder.class);
    HostSwapMetricsQueryService metrics = mock(HostSwapMetricsQueryService.class);
    TumblebugPanelSupport support = new TumblebugPanelSupport(authService, roleNamespaceRepository, tumblebugTreeBuilder);

    ResourceExplorerVmSwapService service = new ResourceExplorerVmSwapService(support, metrics);

    static final String NS = "openstack";
    static final String INFRA = "openstack-infra-01";
    static final String VM = "vm-1";
    static final MetricSelector SEL = MetricSelector.tumblebugNode(NS, INFRA, VM);

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
    }

    @Test
    void 스코프_밖_Namespace나_없는_VM은_404이고_Thanos를_조회하지_않는다() {
        assertThatThrownBy(() -> service.getSwapSummary(null, "other-ns", INFRA, VM))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getSwapSummary("GUEST", NS, INFRA, VM)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getSwapMetricTrend(null, NS, INFRA, "vm-999", SwapMetric.SWAP_USED, NodeMetricInterval.LAST_1H))
                .isInstanceOf(BusinessException.class);

        verify(metrics, never()).summary(any());
        verify(metrics, never()).trend(any(), any(), any(), any(), any());
    }

    @Test
    void KPI는_tb_선택자로_조회한_값을_그대로_돌려주고_스왑_없는_VM은_용량_0_사용률_null이다() {
        SwapSummaryResponse kpi = new SwapSummaryResponse(4.0, 0.5, 3.5, 12.5, 0.0, 1.2);
        when(metrics.summary(SEL)).thenReturn(kpi);
        assertThat(service.getSwapSummary("ADMIN", NS, INFRA, VM)).isSameAs(kpi);

        SwapSummaryResponse noSwap = new SwapSummaryResponse(0.0, 0.0, 0.0, null, 0.0, 0.0);   // 클라우드 VM 기본
        when(metrics.summary(SEL)).thenReturn(noSwap);
        assertThat(service.getSwapSummary(null, NS, INFRA, VM).usedPercent()).isNull();
    }

    @Test
    void 시계열은_interval_구간과_기본_step으로_조회하고_미수집이면_빈_시리즈다() {
        when(metrics.trend(eq(SwapMetric.SWAP_OUT), eq(SEL), any(), any(), eq("15m")))
                .thenReturn(List.of(new DataPoint(1L, 1.2)));

        SwapMetricTrendResponse trend = service.getSwapMetricTrend(null, NS, INFRA, VM, SwapMetric.SWAP_OUT, NodeMetricInterval.LAST_6H);

        assertThat(trend.metric()).isEqualTo(SwapMetric.SWAP_OUT);
        assertThat(trend.unit()).isEqualTo("pages/s");
        assertThat(trend.step()).isEqualTo("15m");
        assertThat(trend.to() - trend.from()).isEqualTo(6 * 3600L);
        assertThat(trend.series()).hasSize(1);
        assertThat(trend.series().get(0).name()).isEqualTo("swap");
        assertThat(trend.series().get(0).dataPoints()).containsExactly(new DataPoint(1L, 1.2));

        when(metrics.trend(eq(SwapMetric.SWAP_USED_PERCENT), eq(SEL), any(), any(), eq("30s"))).thenReturn(List.of());
        assertThat(service.getSwapMetricTrend(null, NS, INFRA, VM, SwapMetric.SWAP_USED_PERCENT, NodeMetricInterval.LAST_10M).series()).isEmpty();
    }
}
