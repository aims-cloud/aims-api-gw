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
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.process.ProcessMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.process.ProcessMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.process.ProcessSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostProcessMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostProcessMetricsQueryService.ProcessSummary;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostProcessMetricsQueryService.StateCount;
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
 * VM Process 카테고리 패널 테스트 — 스코프·부재 404(Thanos 미조회), tb_* 선택자로 KPI·상태별 개수 전달,
 * processes 콜렉터 없음 부분 null, interval 시계열·빈 시리즈.
 *
 * 대역: ns openstack / infra openstack-infra-01 / vm vm-1, ADMIN → openstack 매핑
 */
class ResourceExplorerVmProcessServiceTest {

    AuthService authService = mock(AuthService.class);
    RoleNamespaceRepository roleNamespaceRepository = mock(RoleNamespaceRepository.class);
    TumblebugTreeBuilder tumblebugTreeBuilder = mock(TumblebugTreeBuilder.class);
    HostProcessMetricsQueryService metrics = mock(HostProcessMetricsQueryService.class);
    TumblebugPanelSupport support = new TumblebugPanelSupport(authService, roleNamespaceRepository, tumblebugTreeBuilder);

    ResourceExplorerVmProcessService service = new ResourceExplorerVmProcessService(support, metrics);

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
        assertThatThrownBy(() -> service.getProcessSummary(null, "other-ns", INFRA, VM))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getProcessSummary("GUEST", NS, INFRA, VM)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getProcessMetricTrend(null, NS, INFRA, "vm-999", ProcessMetric.PROCS_RUNNING, NodeMetricInterval.LAST_1H))
                .isInstanceOf(BusinessException.class);

        verify(metrics, never()).summary(any());
        verify(metrics, never()).trend(any(), any(), any(), any(), any());
    }

    @Test
    void KPI는_조회_값과_상태별_개수를_응답으로_옮기고_processes_콜렉터가_없으면_부분_null이다() {
        when(metrics.summary(SEL)).thenReturn(new ProcessSummary(2, 0, 156, 402, 1.75,
                List.of(new StateCount("I", 60), new StateCount("R", 2), new StateCount("S", 94))));

        ProcessSummaryResponse summary = service.getProcessSummary("ADMIN", NS, INFRA, VM);

        assertThat(summary.runningCount()).isEqualTo(2);
        assertThat(summary.blockedCount()).isZero();
        assertThat(summary.processCount()).isEqualTo(156);
        assertThat(summary.threadCount()).isEqualTo(402);
        assertThat(summary.forksPerSec()).isEqualTo(1.75);
        assertThat(summary.processesByState()).extracting(ProcessSummaryResponse.StateCount::state).containsExactly("I", "R", "S");
        assertThat(summary.processesByState().get(2).count()).isEqualTo(94);

        when(metrics.summary(SEL)).thenReturn(new ProcessSummary(1, 0, null, null, 0.5, List.of()));   // processes 콜렉터 없음
        ProcessSummaryResponse partial = service.getProcessSummary(null, NS, INFRA, VM);
        assertThat(partial.runningCount()).isEqualTo(1);
        assertThat(partial.processCount()).isNull();
        assertThat(partial.threadCount()).isNull();
        assertThat(partial.processesByState()).isEmpty();

        when(metrics.summary(SEL)).thenReturn(ProcessSummary.EMPTY);                                     // 표본 없음
        assertThat(service.getProcessSummary(null, NS, INFRA, VM).runningCount()).isNull();
    }

    @Test
    void 시계열은_interval_구간과_기본_step으로_조회하고_미수집이면_빈_시리즈다() {
        when(metrics.trend(eq(ProcessMetric.FORKS_RATE), eq(SEL), any(), any(), eq("15m")))
                .thenReturn(List.of(new DataPoint(1L, 1.75)));

        ProcessMetricTrendResponse trend = service.getProcessMetricTrend(null, NS, INFRA, VM, ProcessMetric.FORKS_RATE, NodeMetricInterval.LAST_6H);

        assertThat(trend.metric()).isEqualTo(ProcessMetric.FORKS_RATE);
        assertThat(trend.unit()).isEqualTo("/s");
        assertThat(trend.step()).isEqualTo("15m");
        assertThat(trend.to() - trend.from()).isEqualTo(6 * 3600L);
        assertThat(trend.series()).hasSize(1);
        assertThat(trend.series().get(0).name()).isEqualTo("process");
        assertThat(trend.series().get(0).dataPoints()).containsExactly(new DataPoint(1L, 1.75));

        when(metrics.trend(eq(ProcessMetric.THREADS), eq(SEL), any(), any(), eq("30s"))).thenReturn(List.of());
        ProcessMetricTrendResponse empty = service.getProcessMetricTrend(null, NS, INFRA, VM, ProcessMetric.THREADS, NodeMetricInterval.LAST_10M);
        assertThat(empty.unit()).isEqualTo("count");
        assertThat(empty.series()).isEmpty();
    }
}
