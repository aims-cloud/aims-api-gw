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
import com.innogrid.aims.monitoring.dto.resourceexplorer.namespace.InfraListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.namespace.NamespaceSummaryResponse;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Namespace 패널 테스트 — Namespace 스코프(404), roleName 한정, KPI 집계, Infra 목록 검색·정렬·페이징, 장애 플래그.
 *
 * 대역: ns openstack (표시명 "OpenStack")
 *   infra-a "Running:2 (R:2/2)": vm-1 Running, vm-2 Terminated
 *   infra-b "Creating:1":        vm-3 Creating, vm-4 Failed, vm-5 Running
 *   ADMIN → openstack 매핑, USER → 매핑 없음
 */
class ResourceExplorerNamespaceServiceTest {

    AuthService authService = mock(AuthService.class);
    RoleNamespaceRepository roleNamespaceRepository = mock(RoleNamespaceRepository.class);
    TumblebugTreeBuilder tumblebugTreeBuilder = mock(TumblebugTreeBuilder.class);
    ResourceExplorerNamespaceService service = new ResourceExplorerNamespaceService(
            new TumblebugPanelSupport(authService, roleNamespaceRepository, tumblebugTreeBuilder));

    static final String NS = "openstack";

    @BeforeEach
    void setUp() {
        when(authService.currentUserRoleIds()).thenReturn(new LinkedHashSet<>(List.of("ADMIN", "USER")));
        Role admin = new Role("ADMIN", "관리자");
        when(roleNamespaceRepository.findByRole_NameIn(anyCollection())).thenAnswer(inv -> {
            Collection<String> roles = inv.getArgument(0);
            return roles.contains("ADMIN") ? List.of(new RoleNamespace(admin, NS)) : List.of();
        });
        when(tumblebugTreeBuilder.snapshot(anyCollection())).thenReturn(snapshot(false));
    }

    static TbSnapshot snapshot(boolean failed) {
        Map<String, List<TbInfra>> infras = Map.of(NS, List.of(
                new TbInfra("infra-b", "infra-b", "Creating:1", List.of(
                        new TbNode("vm-3", "vm-3", "Creating", "g1", "openstack", null, "172.16.0.3"),
                        new TbNode("vm-4", "vm-4", "Failed", null, "openstack", null, null),
                        new TbNode("vm-5", "vm-5", "Running", "g1", "openstack", null, "172.16.0.5"))),
                new TbInfra("infra-a", "infra-a", "Running:2 (R:2/2)", List.of(
                        new TbNode("vm-1", "vm-1", "Running", "g1", "aws", "10.0.0.1", "172.16.0.1"),
                        new TbNode("vm-2", "vm-2", "Terminated", "g1", "aws", null, null)))));
        return new TbSnapshot(Instant.now(), Map.of(NS, "OpenStack"), infras,
                Set.of(NS), failed ? Set.of(NS) : Set.of(), false);
    }

    // ── 스코프 ──────────────────────────────────────────────────

    @Test
    void 역할에_매핑되지_않은_Namespace는_404() {
        assertThatThrownBy(() -> service.getNamespaceSummary(null, "other-ns"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(tumblebugTreeBuilder, never()).snapshot(anyCollection());
    }

    @Test
    void roleName을_지정하면_그_역할의_매핑만_본다() {
        assertThatThrownBy(() -> service.getNamespaceSummary("USER", NS))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getNamespaceSummary("GUEST", NS))     // 토큰에 없는 역할
                .isInstanceOf(BusinessException.class);

        assertThat(service.getNamespaceSummary("ADMIN", NS).nsId()).isEqualTo(NS);
        verify(tumblebugTreeBuilder).snapshot(argThat(c -> c.size() == 1 && c.contains(NS)));
    }

    // ── KPI ─────────────────────────────────────────────────────

    @Test
    void KPI는_Namespace_안_모든_VM_상태를_버킷으로_집계한다() {
        NamespaceSummaryResponse res = service.getNamespaceSummary(null, NS);

        assertThat(res.name()).isEqualTo("OpenStack");
        assertThat(res.infraCount()).isEqualTo(2);
        assertThat(res.vmCount()).isEqualTo(5);
        assertThat(res.runningCount()).isEqualTo(2);
        assertThat(res.pendingCount()).isEqualTo(1);
        assertThat(res.stopCount()).isEqualTo(1);
        assertThat(res.errorCount()).isEqualTo(1);
        assertThat(res.tumblebugUnavailable()).isFalse();
    }

    @Test
    void Tumblebug_조회_실패_Namespace는_장애_플래그를_켠다() {
        when(tumblebugTreeBuilder.snapshot(anyCollection())).thenReturn(snapshot(true));

        assertThat(service.getNamespaceSummary(null, NS).tumblebugUnavailable()).isTrue();
    }

    @Test
    void Infra가_없는_Namespace는_0으로_내려간다() {
        when(tumblebugTreeBuilder.snapshot(anyCollection())).thenReturn(
                new TbSnapshot(Instant.now(), Map.of(NS, NS), Map.of(), Set.of(NS), Set.of(), false));

        NamespaceSummaryResponse res = service.getNamespaceSummary(null, NS);

        assertThat(res.name()).isEqualTo(NS);
        assertThat(res.infraCount()).isZero();
        assertThat(res.vmCount()).isZero();
        assertThat(service.getNamespaceInfras(null, NS, null, 1, 10, "name,asc").items()).isEmpty();
    }

    // ── Infra 목록 ──────────────────────────────────────────────

    @Test
    void Infra_목록은_상태_원문과_VM_스택바를_담고_이름순이다() {
        PageResponse<InfraListItemResponse> page = service.getNamespaceInfras(null, NS, null, 1, 10, "name,asc");

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.items()).extracting(InfraListItemResponse::infraId).containsExactly("infra-a", "infra-b");
        InfraListItemResponse a = page.items().get(0);
        assertThat(a.tumblebugStatus()).isEqualTo("Running:2 (R:2/2)");
        assertThat(a.vmCount()).isEqualTo(2);
        assertThat(a.vmStatus().running()).isEqualTo(1);
        assertThat(a.vmStatus().stop()).isEqualTo(1);
        InfraListItemResponse b = page.items().get(1);
        assertThat(b.vmStatus().total()).isEqualTo(3);
        assertThat(b.vmStatus().pending()).isEqualTo(1);
        assertThat(b.vmStatus().error()).isEqualTo(1);
        assertThat(b.vmStatus().running()).isEqualTo(1);
    }

    @Test
    void Infra_목록은_검색_정렬_페이징을_지원한다() {
        assertThat(service.getNamespaceInfras(null, NS, "INFRA-B", 1, 10, "name,asc").items())
                .extracting(InfraListItemResponse::infraId).containsExactly("infra-b");

        assertThat(service.getNamespaceInfras(null, NS, null, 1, 10, "vmCount,desc").items())
                .extracting(InfraListItemResponse::infraId).containsExactly("infra-b", "infra-a");

        PageResponse<InfraListItemResponse> second = service.getNamespaceInfras(null, NS, null, 2, 1, "name,asc");
        assertThat(second.items()).extracting(InfraListItemResponse::infraId).containsExactly("infra-b");
        assertThat(second.totalPages()).isEqualTo(2);

        assertThatThrownBy(() -> service.getNamespaceInfras(null, NS, null, 1, 10, "provider,asc"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
