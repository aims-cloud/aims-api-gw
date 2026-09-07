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
import com.innogrid.aims.monitoring.dto.resourceexplorer.infra.InfraSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.infra.NodeGroupListItemResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbInfra;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbNode;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Infra 패널 테스트 — Namespace 스코프·Infra 존재(404), 상태 원문·버킷 카드, NodeGroup 수, NodeGroup·미분류 목록 순서.
 *
 * 대역: ns openstack / infra openstack-infra-01 "Running:4 (R:4/5)"
 *   g2: vm-4 Running                      g1: vm-1 Running, vm-2 Suspended
 *   미분류: vm-3 Creating, vm-5 Failed
 *   ADMIN → openstack 매핑
 */
class ResourceExplorerInfraServiceTest {

    AuthService authService = mock(AuthService.class);
    RoleNamespaceRepository roleNamespaceRepository = mock(RoleNamespaceRepository.class);
    TumblebugTreeBuilder tumblebugTreeBuilder = mock(TumblebugTreeBuilder.class);
    ResourceExplorerInfraService service = new ResourceExplorerInfraService(
            new TumblebugPanelSupport(authService, roleNamespaceRepository, tumblebugTreeBuilder));

    static final String NS = "openstack";
    static final String INFRA = "openstack-infra-01";

    @BeforeEach
    void setUp() {
        when(authService.currentUserRoleIds()).thenReturn(new LinkedHashSet<>(List.of("ADMIN")));
        Role admin = new Role("ADMIN", "관리자");
        when(roleNamespaceRepository.findByRole_NameIn(anyCollection())).thenAnswer(inv -> {
            Collection<String> roles = inv.getArgument(0);
            return roles.contains("ADMIN") ? List.of(new RoleNamespace(admin, NS)) : List.of();
        });
        when(tumblebugTreeBuilder.snapshot(anyCollection())).thenReturn(snapshot(List.of(
                new TbNode("vm-4", "vm-4", "Running", "g2", "openstack", null, "172.16.0.4"),
                new TbNode("vm-3", "vm-3", "Creating", null, "openstack", null, "172.16.0.3"),
                new TbNode("vm-1", "vm-1", "Running", "g1", "openstack", "101.79.72.62", "172.16.0.1"),
                new TbNode("vm-5", "vm-5", "Failed", "", "openstack", null, null),
                new TbNode("vm-2", "vm-2", "Suspended", "g1", "openstack", null, "172.16.0.2"))));
    }

    static TbSnapshot snapshot(List<TbNode> nodes) {
        return new TbSnapshot(Instant.now(), Map.of(NS, NS),
                Map.of(NS, List.of(new TbInfra(INFRA, INFRA, "Running:4 (R:4/5)", nodes))),
                Set.of(NS), Set.of(), false);
    }

    // ── 스코프 ──────────────────────────────────────────────────

    @Test
    void 역할에_매핑되지_않은_Namespace는_404() {
        assertThatThrownBy(() -> service.getInfraSummary(null, "other-ns", INFRA))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(tumblebugTreeBuilder, never()).snapshot(anyCollection());
    }

    @Test
    void 스냅샷에_없는_Infra는_404() {
        assertThatThrownBy(() -> service.getInfraSummary(null, NS, "no-such-infra"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getInfraNodeGroups(null, NS, "no-such-infra"))
                .isInstanceOf(BusinessException.class);
    }

    // ── 개요 ────────────────────────────────────────────────────

    @Test
    void 개요는_상태_원문과_버킷_카운트_NodeGroup_수를_담는다() {
        InfraSummaryResponse res = service.getInfraSummary("ADMIN", NS, INFRA);

        assertThat(res.infraId()).isEqualTo(INFRA);
        assertThat(res.tumblebugStatus()).isEqualTo("Running:4 (R:4/5)");
        assertThat(res.vmCount()).isEqualTo(5);
        assertThat(res.runningCount()).isEqualTo(2);
        assertThat(res.pendingCount()).isEqualTo(1);
        assertThat(res.stopCount()).isEqualTo(1);
        assertThat(res.errorCount()).isEqualTo(1);
        assertThat(res.nodeGroupCount()).isEqualTo(2);
        assertThat(res.unclassifiedCount()).isEqualTo(2);
    }

    // ── NodeGroup 목록 ──────────────────────────────────────────

    @Test
    void NodeGroup_목록은_이름순이고_미분류는_마지막이다() {
        List<NodeGroupListItemResponse> rows = service.getInfraNodeGroups(null, NS, INFRA);

        assertThat(rows).extracting(NodeGroupListItemResponse::nodeGroupId)
                .containsExactly("g1", "g2", TumblebugTreeBuilder.UNCLASSIFIED_KEY);
        assertThat(rows).extracting(NodeGroupListItemResponse::unclassified).containsExactly(false, false, true);

        NodeGroupListItemResponse g1 = rows.get(0);
        assertThat(g1.name()).isEqualTo("g1");
        assertThat(g1.vmCount()).isEqualTo(2);
        assertThat(g1.vmStatus().running()).isEqualTo(1);
        assertThat(g1.vmStatus().stop()).isEqualTo(1);

        NodeGroupListItemResponse unclassified = rows.get(2);
        assertThat(unclassified.name()).isNotBlank();
        assertThat(unclassified.vmCount()).isEqualTo(2);
        assertThat(unclassified.vmStatus().pending()).isEqualTo(1);
        assertThat(unclassified.vmStatus().error()).isEqualTo(1);
    }

    @Test
    void 미분류_VM이_없으면_미분류_행도_없다() {
        when(tumblebugTreeBuilder.snapshot(anyCollection())).thenReturn(snapshot(List.of(
                new TbNode("vm-1", "vm-1", "Running", "g1", "openstack", null, null))));

        List<NodeGroupListItemResponse> rows = service.getInfraNodeGroups(null, NS, INFRA);

        assertThat(rows).extracting(NodeGroupListItemResponse::nodeGroupId).containsExactly("g1");
        assertThat(service.getInfraSummary(null, NS, INFRA).unclassifiedCount()).isZero();
    }

    @Test
    void VM이_없는_Infra는_빈_목록과_0_카드다() {
        when(tumblebugTreeBuilder.snapshot(anyCollection())).thenReturn(snapshot(List.of()));

        assertThat(service.getInfraNodeGroups(null, NS, INFRA)).isEmpty();
        InfraSummaryResponse res = service.getInfraSummary(null, NS, INFRA);
        assertThat(res.vmCount()).isZero();
        assertThat(res.nodeGroupCount()).isZero();
    }
}
