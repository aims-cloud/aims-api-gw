package com.innogrid.aims.monitoring.service.resourceexplorer.mdc;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.MdcSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeSearchField;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeTopResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostMetricsQueryService.HostUtilization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MDC 패널 집계 테스트 — 역할·MDC 필터 스코프, KPI, Thanos 기간 평균 기반 Top-N, 노드 목록 필터·정렬.
 * 스코프와 Thanos 사용률은 대역으로 고정한다.
 *
 * 대역 스코프:
 *   ADMIN → node-01(mdc-01, RUNNING, 10.0.0.1:9100, cpu 32.5/mem 51.6/fs 12.5)
 *           node-02(mdc-02, ERROR,   10.0.0.2:9100, cpu 78.1/mem 91.3/fs 76.2)
 *           node-03(mdc-02, PENDING, exporter 없음 → 전부 미수집)
 *   USER  → node-02 (ADMIN 과 중복)
 */
class ResourceExplorerMdcServiceTest {

    ResourceScopeService scopeService = mock(ResourceScopeService.class);
    HostMetricsQueryService metricsQueryService = mock(HostMetricsQueryService.class);
    AuthService authService = mock(AuthService.class);
    ResourceExplorerMdcService service = new ResourceExplorerMdcService(scopeService, metricsQueryService, authService);

    ScopedHost node01 = new ScopedHost("ADMIN", 11L, "node-01", "학습 노드", "mdc-01", ResourceStatus.RUNNING,
            "10.0.0.1:9100", List.of("10.0.0.1", "10.0.1.1"));
    ScopedHost node02 = new ScopedHost("ADMIN", 12L, "node-02", null, "mdc-02", ResourceStatus.ERROR,
            "10.0.0.2:9100", List.of("10.0.0.2"));
    ScopedHost node03 = new ScopedHost("ADMIN", 13L, "node-03", "예비 노드", "mdc-02", ResourceStatus.PENDING,
            null, List.of());
    ScopedHost node02AsUser = new ScopedHost("USER", 12L, "node-02", null, "mdc-02", ResourceStatus.ERROR,
            "10.0.0.2:9100", List.of("10.0.0.2"));

    @BeforeEach
    void setUp() {
        when(authService.currentUserRoleIds()).thenReturn(new java.util.LinkedHashSet<>(List.of("ADMIN", "USER")));
        when(scopeService.scopeOf(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<String> roles = inv.getArgument(0);
            List<ScopedHost> hosts = new java.util.ArrayList<>();
            if (roles.contains("ADMIN")) hosts.addAll(List.of(node01, node02, node03));
            if (roles.contains("USER")) hosts.add(node02AsUser);
            return new Scope(Set.copyOf(roles), hosts, List.of(), List.of());
        });
        when(metricsQueryService.utilizationByInstance(any())).thenReturn(Map.of(
                "10.0.0.1:9100", new HostUtilization(32.5, 51.6, 12.5, 896.0, 1024.0),
                "10.0.0.2:9100", new HostUtilization(78.1, 91.3, 76.2, 243.8, 1024.0)));
    }

    // ── 스코프 ──────────────────────────────────────────────────

    @Test
    void 개요_KPI는_MDC의_상태별_카운트를_집계한다() {
        MdcSummaryResponse mdc02 = service.getMdcSummary("ADMIN", "mdc-02");
        assertThat(mdc02.totalCount()).isEqualTo(2);
        assertThat(mdc02.errorCount()).isEqualTo(1);
        assertThat(mdc02.pendingCount()).isEqualTo(1);
        assertThat(mdc02.runningCount()).isZero();

        MdcSummaryResponse mdc01 = service.getMdcSummary("ADMIN", "mdc-01");
        assertThat(mdc01.totalCount()).isEqualTo(1);
        assertThat(mdc01.runningCount()).isEqualTo(1);
    }

    @Test
    void 조회는_토큰의_역할_스코프로_제한되고_역할_생략시_중복을_제거한다() {
        // 토큰에 없는 역할 → 빈 결과, 스코프 조회도 하지 않는다
        assertThat(service.getMdcSummary("GUEST", null).totalCount()).isZero();
        assertThat(service.getMdcTop("GUEST", null, NodeMetric.CPU, NodeMetricInterval.LAST_10M, 5)).isEmpty();
        verify(scopeService, never()).scopeOf(List.of("GUEST"));

        // 역할 생략 → ADMIN+USER 합산, node-02 는 한 번만
        assertThat(service.getMdcSummary(null, null).totalCount()).isEqualTo(3);
        assertThat(service.getMdcSummary("USER", null).totalCount()).isEqualTo(1);

        // 미인증 → 빈 결과
        when(authService.currentUserRoleIds()).thenReturn(Set.of());
        assertThat(service.getMdcSummary(null, null).totalCount()).isZero();
    }

    // ── Top-N ───────────────────────────────────────────────────

    @Test
    void Top_N은_요청_지표_내림차순으로_보조_지표를_동봉하고_미수집_호스트는_제외한다() {
        List<NodeTopResponse> top = service.getMdcTop("ADMIN", null, NodeMetric.CPU, NodeMetricInterval.LAST_1H, 5);

        assertThat(top).extracting(NodeTopResponse::name).containsExactly("node-02", "node-01");   // node-03 미수집 제외
        NodeTopResponse first = top.get(0);
        assertThat(first.nodeId()).isEqualTo("12");                   // assets.id
        assertThat(first.value()).isEqualTo(78.1);
        assertThat(first.memoryUtilization()).isEqualTo(91.3);
        assertThat(first.fileSystemUtilization()).isEqualTo(76.2);
        assertThat(first.status()).isEqualTo(NodeStatus.ERROR);
        verify(metricsQueryService).utilizationByInstance(NodeMetricInterval.LAST_1H);   // 기간이 Thanos 평균 윈도우로 전달된다

        List<NodeTopResponse> fs = service.getMdcTop("ADMIN", null, NodeMetric.FILE_SYSTEM, NodeMetricInterval.LAST_10M, 1);
        assertThat(fs).hasSize(1);
        assertThat(fs.get(0).name()).isEqualTo("node-02");
        assertThat(fs.get(0).fileSystemFreeGb()).isEqualTo(243.8);
        assertThat(fs.get(0).fileSystemTotalGb()).isEqualTo(1024.0);
    }

    @Test
    void Top_N은_스코프에_호스트가_없으면_Thanos를_조회하지_않는다() {
        assertThat(service.getMdcTop("ADMIN", "mdc-99", NodeMetric.CPU, NodeMetricInterval.LAST_10M, 5)).isEmpty();
        verify(metricsQueryService, never()).utilizationByInstance(any());
    }

    // ── 노드 목록 ───────────────────────────────────────────────

    @Test
    void 노드_목록은_사용률과_복수_IP를_내려주고_미수집은_null_이다() {
        PageResponse<NodeListItemResponse> page = service.getMdcNodes(
                "ADMIN", null, null, NodeSearchField.NAME, null, 1, 10, "name,asc");

        assertThat(page.totalElements()).isEqualTo(3);
        NodeListItemResponse first = page.items().get(0);
        assertThat(first.name()).isEqualTo("node-01");
        assertThat(first.alias()).isEqualTo("학습 노드");
        assertThat(first.ipAddresses()).containsExactly("10.0.0.1", "10.0.1.1");
        assertThat(first.cpuUtilization()).isEqualTo(32.5);
        assertThat(first.memoryUtilization()).isEqualTo(51.6);
        assertThat(first.fileSystemUtilization()).isEqualTo(12.5);

        NodeListItemResponse missing = page.items().get(2);
        assertThat(missing.name()).isEqualTo("node-03");
        assertThat(missing.status()).isEqualTo(NodeStatus.PENDING);
        assertThat(missing.ipAddresses()).isEmpty();
        assertThat(missing.cpuUtilization()).isNull();
        assertThat(missing.memoryUtilization()).isNull();
        assertThat(missing.fileSystemUtilization()).isNull();
        verify(metricsQueryService).utilizationByInstance(NodeMetricInterval.LAST_10M);   // 목록은 최근 10분 평균
    }

    @Test
    void 노드_목록은_상태와_검색_필드로_필터하고_정렬한다() {
        assertThat(service.getMdcNodes(null, null, NodeStatus.ERROR, NodeSearchField.NAME, null, 1, 10, "name,asc").items())
                .extracting(NodeListItemResponse::name).containsExactly("node-02");

        assertThat(service.getMdcNodes(null, null, null, NodeSearchField.ALIAS, "예비", 1, 10, "name,asc").items())
                .extracting(NodeListItemResponse::name).containsExactly("node-03");
        assertThat(service.getMdcNodes(null, null, null, NodeSearchField.NAME, "예비", 1, 10, "name,asc").items())
                .isEmpty();                                           // 검색 필드가 NAME 이면 별칭은 보지 않는다
        assertThat(service.getMdcNodes(null, null, null, NodeSearchField.IP, "10.0.1.1", 1, 10, "name,asc").items())
                .extracting(NodeListItemResponse::name).containsExactly("node-01");

        // 미수집(null)은 오름/내림 어느 방향에서도 마지막
        assertThat(service.getMdcNodes(null, null, null, NodeSearchField.NAME, null, 1, 10, "cpuUtilization,desc").items())
                .extracting(NodeListItemResponse::name).containsExactly("node-02", "node-01", "node-03");
        assertThat(service.getMdcNodes(null, null, null, NodeSearchField.NAME, null, 1, 10, "cpuUtilization,asc").items())
                .extracting(NodeListItemResponse::name).containsExactly("node-01", "node-02", "node-03");
        assertThat(service.getMdcNodes(null, null, null, NodeSearchField.NAME, null, 1, 10, "alias,asc").items())
                .extracting(NodeListItemResponse::name).containsExactly("node-03", "node-01", "node-02");
    }

    @Test
    void 지원하지_않는_정렬_필드는_거부한다() {
        assertThatThrownBy(() -> service.getMdcNodes(null, null, null, NodeSearchField.NAME, null, 1, 10, "deploymentId,asc"))
                .isInstanceOf(BusinessException.class);
    }
}
