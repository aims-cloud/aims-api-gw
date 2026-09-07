package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.RoleNamespace;
import com.innogrid.aims.infra.repository.RoleNamespaceRepository;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeSearchField;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeGroupSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.VmListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.VmTopResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostMetricsQueryService.HostUtilization;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbInfra;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbNode;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbSnapshot;
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
 * NodeGroup 패널 테스트 — Namespace 스코프, nodeGroupId 필터(전체/그룹/미분류), 상태 버킷 KPI,
 * tb_* 라벨 조인 Top-N, VM 목록 필터·정렬. Tumblebug 스냅샷·Thanos 는 대역.
 *
 * 대역: ns openstack / infra openstack-infra-01
 *   g1: vm-1 Running(aws, 10.0.0.1/172.16.0.1, cpu 45.2)  vm-2 Terminated(aws, cpu 5.0)
 *   미분류: vm-3 Creating(openstack, exporter 없음 → 미수집)
 *   ADMIN → openstack 매핑, USER → 매핑 없음
 */
class ResourceExplorerNodeGroupServiceTest {

    AuthService authService = mock(AuthService.class);
    RoleNamespaceRepository roleNamespaceRepository = mock(RoleNamespaceRepository.class);
    TumblebugTreeBuilder tumblebugTreeBuilder = mock(TumblebugTreeBuilder.class);
    HostMetricsQueryService metricsQueryService = mock(HostMetricsQueryService.class);
    ResourceExplorerNodeGroupService service = new ResourceExplorerNodeGroupService(
            authService, roleNamespaceRepository, tumblebugTreeBuilder, metricsQueryService);

    static final String NS = "openstack";
    static final String INFRA = "openstack-infra-01";

    @BeforeEach
    void setUp() {
        when(authService.currentUserRoleIds()).thenReturn(new java.util.LinkedHashSet<>(List.of("ADMIN", "USER")));
        Role admin = new Role("ADMIN", "관리자");
        when(roleNamespaceRepository.findByRole_NameIn(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<String> roles = inv.getArgument(0);
            return roles.contains("ADMIN") ? List.of(new RoleNamespace(admin, NS)) : List.of();
        });

        when(tumblebugTreeBuilder.snapshot(anyCollection())).thenReturn(new TbSnapshot(Instant.now(),
                Map.of(NS, "openstack"),
                Map.of(NS, List.of(new TbInfra(INFRA, INFRA, "Running:3 (R:3/3)", List.of(
                        new TbNode("vm-2", "vm-2", "Terminated", "g1", "aws", "10.0.0.2", "172.16.0.2"),
                        new TbNode("vm-1", "vm-1", "Running", "g1", "aws", "10.0.0.1", "172.16.0.1"),
                        new TbNode("vm-3", "vm-3", "Creating", null, "openstack", null, "172.16.0.3"))))),
                false));

        when(metricsQueryService.utilizationByTumblebugNode(any())).thenReturn(Map.of(
                HostMetricsQueryService.tumblebugKey(NS, INFRA, "vm-1"), new HostUtilization(45.2, 62.8, 38.6, 61.4, 100.0),
                HostMetricsQueryService.tumblebugKey(NS, INFRA, "vm-2"), new HostUtilization(5.0, 10.0, 20.0, 80.0, 100.0)));
    }

    // ── 스코프·필터 ─────────────────────────────────────────────

    @Test
    void 토큰_역할에_매핑되지_않은_Namespace는_빈_결과다() {
        assertThat(service.getNodeGroupSummary("USER", NS, INFRA, null).totalCount()).isZero();
        assertThat(service.getNodeGroupSummary(null, "other-ns", INFRA, null).totalCount()).isZero();
        assertThat(service.getNodeGroupSummary("GUEST", NS, INFRA, null).totalCount()).isZero();  // 토큰에 없는 역할
        verify(tumblebugTreeBuilder, never()).snapshot(anyCollection());

        when(authService.currentUserRoleIds()).thenReturn(Set.of());
        assertThat(service.getNodeGroupTop(null, NS, INFRA, null, NodeMetric.CPU, NodeMetricInterval.LAST_10M, 5)).isEmpty();
    }

    @Test
    void nodeGroupId로_전체_그룹_미분류를_구분한다() {
        assertThat(service.getNodeGroupSummary(null, NS, INFRA, null).totalCount()).isEqualTo(3);        // Infra 전체
        assertThat(service.getNodeGroupSummary("ADMIN", NS, INFRA, "g1").totalCount()).isEqualTo(2);
        assertThat(service.getNodeGroupSummary(null, NS, INFRA, TumblebugTreeBuilder.UNCLASSIFIED_KEY).totalCount()).isEqualTo(1);
        assertThat(service.getNodeGroupSummary(null, NS, INFRA, "g9").totalCount()).isZero();
        assertThat(service.getNodeGroupSummary(null, NS, "other-infra", null).totalCount()).isZero();
        verify(tumblebugTreeBuilder, org.mockito.Mockito.atLeastOnce()).snapshot(Set.of(NS));   // 매핑된 Namespace 만 요청
    }

    // ── KPI ─────────────────────────────────────────────────────

    @Test
    void KPI는_Tumblebug_상태를_버킷으로_묶어_집계한다() {
        NodeGroupSummaryResponse s = service.getNodeGroupSummary("ADMIN", NS, INFRA, null);
        assertThat(s.totalCount()).isEqualTo(3);
        assertThat(s.runningCount()).isEqualTo(1);     // Running
        assertThat(s.pendingCount()).isEqualTo(1);     // Creating
        assertThat(s.stopCount()).isEqualTo(1);        // Terminated
        assertThat(s.errorCount()).isZero();
    }

    // ── Top-N ───────────────────────────────────────────────────

    @Test
    void Top_N은_tb_라벨_조인_사용률로_정렬하고_미수집_VM은_제외한다() {
        List<VmTopResponse> top = service.getNodeGroupTop("ADMIN", NS, INFRA, "g1", NodeMetric.CPU, NodeMetricInterval.LAST_1H, 5);

        assertThat(top).extracting(VmTopResponse::name).containsExactly("vm-1", "vm-2");
        VmTopResponse first = top.get(0);
        assertThat(first.vmId()).isEqualTo("TB:openstack/openstack-infra-01/vm-1");
        assertThat(first.value()).isEqualTo(45.2);
        assertThat(first.status()).isEqualTo(NodeStatus.RUNNING);
        assertThat(first.tumblebugStatus()).isEqualTo("Running");
        assertThat(first.fileSystemFreeGb()).isEqualTo(61.4);
        verify(metricsQueryService).utilizationByTumblebugNode(NodeMetricInterval.LAST_1H);

        // 미분류 vm-3 은 exporter 가 없어 미수집 → Top-N 제외, Thanos 는 조회하되 빈 결과
        assertThat(service.getNodeGroupTop(null, NS, INFRA, TumblebugTreeBuilder.UNCLASSIFIED_KEY, NodeMetric.CPU, NodeMetricInterval.LAST_10M, 5)).isEmpty();
        // 스코프에 VM 이 없으면 Thanos 를 부르지 않는다
        org.mockito.Mockito.clearInvocations(metricsQueryService);
        assertThat(service.getNodeGroupTop(null, NS, INFRA, "g9", NodeMetric.CPU, NodeMetricInterval.LAST_10M, 5)).isEmpty();
        verify(metricsQueryService, never()).utilizationByTumblebugNode(any());
    }

    // ── VM 목록 ─────────────────────────────────────────────────

    @Test
    void VM_목록은_원문_상태_CSP_IP_사용률을_내려주고_미수집은_null이다() {
        PageResponse<VmListItemResponse> page = service.getNodeGroupVms(null, NS, INFRA, null, null, NodeSearchField.NAME, null, 1, 10, "name,asc");

        assertThat(page.totalElements()).isEqualTo(3);
        VmListItemResponse vm1 = page.items().get(0);
        assertThat(vm1.vmId()).isEqualTo("TB:openstack/openstack-infra-01/vm-1");
        assertThat(vm1.status()).isEqualTo(NodeStatus.RUNNING);
        assertThat(vm1.tumblebugStatus()).isEqualTo("Running");
        assertThat(vm1.provider()).isEqualTo("aws");
        assertThat(vm1.ipAddresses()).containsExactly("10.0.0.1", "172.16.0.1");
        assertThat(vm1.cpuUtilization()).isEqualTo(45.2);
        assertThat(vm1.fileSystemTotalGb()).isEqualTo(100.0);

        VmListItemResponse vm3 = page.items().get(2);
        assertThat(vm3.status()).isEqualTo(NodeStatus.PENDING);
        assertThat(vm3.tumblebugStatus()).isEqualTo("Creating");
        assertThat(vm3.ipAddresses()).containsExactly("172.16.0.3");   // publicIP 없음
        assertThat(vm3.cpuUtilization()).isNull();
        verify(metricsQueryService).utilizationByTumblebugNode(NodeMetricInterval.LAST_10M);   // 목록은 최근 10분 평균
    }

    @Test
    void VM_목록은_상태와_검색_필드로_필터하고_정렬한다() {
        assertThat(service.getNodeGroupVms(null, NS, INFRA, null, NodeStatus.STOP, NodeSearchField.NAME, null, 1, 10, "name,asc").items())
                .extracting(VmListItemResponse::name).containsExactly("vm-2");
        assertThat(service.getNodeGroupVms(null, NS, INFRA, null, null, NodeSearchField.IP, "172.16.0.3", 1, 10, "name,asc").items())
                .extracting(VmListItemResponse::name).containsExactly("vm-3");
        assertThat(service.getNodeGroupVms(null, NS, INFRA, null, null, NodeSearchField.ALIAS, "vm", 1, 10, "name,asc").items())
                .isEmpty();                                                  // Tumblebug VM 은 별칭 없음
        assertThat(service.getNodeGroupVms(null, NS, INFRA, null, null, NodeSearchField.NAME, null, 1, 10, "cpuUtilization,desc").items())
                .extracting(VmListItemResponse::name).containsExactly("vm-1", "vm-2", "vm-3");   // 미수집은 마지막
        assertThat(service.getNodeGroupVms(null, NS, INFRA, null, null, NodeSearchField.NAME, null, 1, 10, "cpuUtilization,asc").items())
                .extracting(VmListItemResponse::name).containsExactly("vm-2", "vm-1", "vm-3");
        assertThat(service.getNodeGroupVms(null, NS, INFRA, null, null, NodeSearchField.NAME, null, 1, 10, "provider,asc").items())
                .extracting(VmListItemResponse::name).containsExactly("vm-1", "vm-2", "vm-3");   // aws, aws, openstack
        assertThat(service.getNodeGroupVms(null, NS, INFRA, null, null, NodeSearchField.NAME, null, 2, 2, "name,asc").items())
                .extracting(VmListItemResponse::name).containsExactly("vm-3");
    }

    @Test
    void 지원하지_않는_정렬_필드는_거부한다() {
        assertThatThrownBy(() -> service.getNodeGroupVms(null, NS, INFRA, null, null, NodeSearchField.NAME, null, 1, 10, "deploymentId,asc"))
                .isInstanceOf(BusinessException.class);
    }
}
