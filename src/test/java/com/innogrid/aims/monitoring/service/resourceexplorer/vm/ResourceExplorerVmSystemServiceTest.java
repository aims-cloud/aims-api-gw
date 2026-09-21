package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.RoleNamespace;
import com.innogrid.aims.infra.repository.RoleNamespaceRepository;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostSystemMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostSystemMetricsQueryService.SystemInfo;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostSystemMetricsQueryService.SystemSummary;
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
 * VM System 카테고리 패널 테스트 — 스코프·부재 404(Thanos 미조회), tb_* 선택자 전달, KPI 가동 시간 계산·미수집 null,
 * 시스템 정보 라벨 이동·시간대 변환·UNAVAILABLE, interval 시계열·빈 시리즈.
 *
 * 대역: ns openstack / infra openstack-infra-01 / vm vm-1, ADMIN → openstack 매핑
 */
class ResourceExplorerVmSystemServiceTest {

    AuthService authService = mock(AuthService.class);
    RoleNamespaceRepository roleNamespaceRepository = mock(RoleNamespaceRepository.class);
    TumblebugTreeBuilder tumblebugTreeBuilder = mock(TumblebugTreeBuilder.class);
    HostSystemMetricsQueryService metrics = mock(HostSystemMetricsQueryService.class);
    TumblebugPanelSupport support = new TumblebugPanelSupport(authService, roleNamespaceRepository, tumblebugTreeBuilder);

    ResourceExplorerVmSystemService service = new ResourceExplorerVmSystemService(support, metrics);

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
        assertThatThrownBy(() -> service.getSystemSummary(null, "other-ns", INFRA, VM))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getSystemInfo("GUEST", NS, INFRA, VM)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getSystemMetricTrend(null, NS, INFRA, "vm-999", SystemMetric.LOAD_1, NodeMetricInterval.LAST_1H))
                .isInstanceOf(BusinessException.class);

        verify(metrics, never()).summary(any());
        verify(metrics, never()).info(any());
        verify(metrics, never()).trend(any(), any(), any(), any(), any());
    }

    @Test
    void KPI는_부팅_시각으로_가동_시간을_계산하고_나머지는_그대로_전달하며_미수집은_null이다() {
        long bootedTenDaysAgo = Instant.now().getEpochSecond() - 10 * 86_400 - 30;
        when(metrics.summary(SEL)).thenReturn(new SystemSummary(bootedTenDaysAgo, 0.31, 0.4, 0.55, 2, 1234.5, 456.0));

        SystemSummaryResponse summary = service.getSystemSummary("ADMIN", NS, INFRA, VM);

        assertThat(summary.uptimeSeconds()).isBetween(10 * 86_400L, 10 * 86_400L + 90);
        assertThat(summary.load1()).isEqualTo(0.31);
        assertThat(summary.load5()).isEqualTo(0.4);
        assertThat(summary.load15()).isEqualTo(0.55);
        assertThat(summary.coreCount()).isEqualTo(2);
        assertThat(summary.contextSwitchesPerSec()).isEqualTo(1234.5);
        assertThat(summary.interruptsPerSec()).isEqualTo(456.0);

        when(metrics.summary(SEL)).thenReturn(SystemSummary.EMPTY);
        SystemSummaryResponse empty = service.getSystemSummary(null, NS, INFRA, VM);
        assertThat(empty.uptimeSeconds()).isNull();
        assertThat(empty.coreCount()).isNull();
    }

    @Test
    void 시스템_정보는_게스트_OS_라벨을_옮기고_시각을_시간대_시각으로_내리며_표본_없으면_UNAVAILABLE이다() {
        Instant boot = Instant.ofEpochSecond(1_699_990_000L);
        Instant sample = Instant.ofEpochSecond(1_700_000_000L);
        when(metrics.info(SEL)).thenReturn(Optional.of(new SystemInfo(
                "vm-1", "Ubuntu 22.04.4 LTS", "22.04", "5.15.0-113-generic", "x86_64", boot.getEpochSecond(), sample)));

        SystemInfoResponse info = service.getSystemInfo(null, NS, INFRA, VM);

        assertThat(info.hostname()).isEqualTo("vm-1");
        assertThat(info.osName()).isEqualTo("Ubuntu 22.04.4 LTS");
        assertThat(info.osVersion()).isEqualTo("22.04");
        assertThat(info.kernelRelease()).isEqualTo("5.15.0-113-generic");
        assertThat(info.architecture()).isEqualTo("x86_64");
        assertThat(info.bootTime()).isEqualTo(boot.atZone(ZoneId.systemDefault()).toOffsetDateTime());
        assertThat(info.collectStatus()).isEqualTo("COLLECTING");
        assertThat(info.lastCollectedAt()).isEqualTo(sample.atZone(ZoneId.systemDefault()).toOffsetDateTime());

        when(metrics.info(SEL)).thenReturn(Optional.empty());
        SystemInfoResponse unavailable = service.getSystemInfo(null, NS, INFRA, VM);
        assertThat(unavailable.collectStatus()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.hostname()).isNull();
        assertThat(unavailable.bootTime()).isNull();
        assertThat(unavailable.lastCollectedAt()).isNull();
    }

    @Test
    void 시계열은_interval_구간과_기본_step으로_조회하고_미수집이면_빈_시리즈다() {
        when(metrics.trend(eq(SystemMetric.CONTEXT_SWITCHES), eq(SEL), any(), any(), eq("15m")))
                .thenReturn(List.of(new DataPoint(1L, 1234.5)));

        SystemMetricTrendResponse trend = service.getSystemMetricTrend(null, NS, INFRA, VM, SystemMetric.CONTEXT_SWITCHES, NodeMetricInterval.LAST_6H);

        assertThat(trend.metric()).isEqualTo(SystemMetric.CONTEXT_SWITCHES);
        assertThat(trend.unit()).isEqualTo("/s");
        assertThat(trend.step()).isEqualTo("15m");
        assertThat(trend.to() - trend.from()).isEqualTo(6 * 3600L);
        assertThat(trend.series()).hasSize(1);
        assertThat(trend.series().get(0).name()).isEqualTo("system");
        assertThat(trend.series().get(0).dataPoints()).containsExactly(new DataPoint(1L, 1234.5));

        when(metrics.trend(eq(SystemMetric.LOAD_1), eq(SEL), any(), any(), eq("30s"))).thenReturn(List.of());
        SystemMetricTrendResponse empty = service.getSystemMetricTrend(null, NS, INFRA, VM, SystemMetric.LOAD_1, NodeMetricInterval.LAST_10M);
        assertThat(empty.unit()).isEmpty();
        assertThat(empty.series()).isEmpty();
    }
}
