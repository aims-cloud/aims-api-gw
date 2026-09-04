package com.innogrid.aims.monitoring.service.resourceexplorer;

import java.util.List;
import java.util.Set;

import com.innogrid.aims.auth.repository.UserRepository;
import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusDistributionResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleAreaListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleAreaListItemResponse.AreaKind;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedNamespace;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedVm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 역할 패널 집계 테스트 — 토큰 역할 검증(404), KPI, 도넛, 자원 영역(MDC·Namespace) 테이블.
 * 스코프는 대역으로 고정하고 집계 규칙만 검증한다.
 *
 * 대역 스코프 (ADMIN):
 *   mdc-01: node-01 RUNNING / 1세부 VM web-01 RUNNING
 *   mdc-02: node-02 ERROR, node-03 PENDING
 *   ns-a "Namespace A": TB vm-1 RUNNING, vm-2 STOP
 *   ns-b "Namespace B": VM 없음
 */
class ResourceExplorerRoleServiceTest {

    ResourceScopeService scopeService = mock(ResourceScopeService.class);
    UserRepository userRepository = mock(UserRepository.class);
    AuthService authService = mock(AuthService.class);
    ResourceExplorerRoleService service = new ResourceExplorerRoleService(scopeService, userRepository, authService);

    @BeforeEach
    void setUp() {
        when(authService.currentUserRoleIds()).thenReturn(Set.of("ADMIN", "USER"));
        when(userRepository.countByRoles_Name("ADMIN")).thenReturn(3L);
        when(scopeService.scopeOf(anyCollection())).thenReturn(Scope.empty());
        when(scopeService.scopeOf(List.of("ADMIN"))).thenReturn(new Scope(Set.of("ADMIN"),
                List.of(new ScopedHost("ADMIN", 11L, "node-01", "mdc-01", ResourceStatus.RUNNING),
                        new ScopedHost("ADMIN", 12L, "node-02", "mdc-02", ResourceStatus.ERROR),
                        new ScopedHost("ADMIN", 13L, "node-03", "mdc-02", ResourceStatus.PENDING)),
                List.of(new ScopedVm("ADMIN", "SEBU1:uuid-1", "web-01", ResourceStatus.RUNNING, "mdc-01", null),
                        new ScopedVm("ADMIN", "TB:ns-a/infra-1/vm-1", "vm-1", ResourceStatus.RUNNING, null, "ns-a"),
                        new ScopedVm("ADMIN", "TB:ns-a/infra-1/vm-2", "vm-2", ResourceStatus.STOP, null, "ns-a")),
                List.of(new ScopedNamespace("ADMIN", "ns-a", "Namespace A"),
                        new ScopedNamespace("ADMIN", "ns-b", "Namespace B"))));
    }

    @Test
    void 토큰에_없는_역할은_404이고_스코프를_조회하지_않는다() {
        assertThatThrownBy(() -> service.getRoleSummary("GUEST")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getNodeStatus("GUEST")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getRoleAreas("GUEST", null, 1, 10, "name,asc")).isInstanceOf(BusinessException.class);
        verify(scopeService, never()).scopeOf(anyCollection());
    }

    @Test
    void 개요_KPI는_역할_스코프의_자원과_상태를_집계한다() {
        RoleSummaryResponse summary = service.getRoleSummary("ADMIN");

        assertThat(summary.memberCount()).isEqualTo(3);
        assertThat(summary.mdcCount()).isEqualTo(2);
        assertThat(summary.namespaceCount()).isEqualTo(2);
        assertThat(summary.nodeCount()).isEqualTo(3);
        assertThat(summary.vmCount()).isEqualTo(3);
        assertThat(summary.runningCount()).isEqualTo(3);               // node-01, web-01, vm-1
        assertThat(summary.pendingCount()).isEqualTo(1);
        assertThat(summary.stopCount()).isEqualTo(1);
        assertThat(summary.errorCount()).isEqualTo(1);
    }

    @Test
    void 자원이_없는_역할은_0으로_집계된다() {
        RoleSummaryResponse summary = service.getRoleSummary("USER");
        assertThat(summary.nodeCount()).isZero();
        assertThat(summary.vmCount()).isZero();
        assertThat(summary.namespaceCount()).isZero();
        assertThat(service.getRoleAreas("USER", null, 1, 10, "name,asc").items()).isEmpty();
    }

    @Test
    void 상태_도넛은_카운트가_0인_상태도_전부_내려준다() {
        ResourceStatusDistributionResponse nodes = service.getNodeStatus("ADMIN");
        assertThat(nodes.totalCount()).isEqualTo(3);
        assertThat(nodes.statusCounts()).extracting(ResourceStatusDistributionResponse.StatusCount::count)
                .containsExactly(1L, 1L, 1L, 0L);                     // Running/Error/Pending/Stop

        ResourceStatusDistributionResponse vms = service.getVmStatus("ADMIN");
        assertThat(vms.totalCount()).isEqualTo(3);
        assertThat(vms.statusCounts()).extracting(ResourceStatusDistributionResponse.StatusCount::count)
                .containsExactly(2L, 0L, 0L, 1L);
    }

    @Test
    void 자원_영역_테이블은_MDC행과_Namespace행을_함께_내려준다() {
        PageResponse<RoleAreaListItemResponse> page = service.getRoleAreas("ADMIN", null, 1, 10, "kind,asc");

        assertThat(page.items()).extracting(RoleAreaListItemResponse::areaId)
                .containsExactly("mdc-01", "mdc-02", "TB:ns-a", "TB:ns-b");
        assertThat(page.items()).extracting(RoleAreaListItemResponse::kind)
                .containsExactly(AreaKind.MDC, AreaKind.MDC, AreaKind.NAMESPACE, AreaKind.NAMESPACE);

        RoleAreaListItemResponse mdc01 = page.items().get(0);
        assertThat(mdc01.nodeCount()).isEqualTo(1);
        assertThat(mdc01.nodeStatus().running()).isEqualTo(1);
        assertThat(mdc01.vmCount()).isEqualTo(1);                     // 1세부 VM web-01
        assertThat(mdc01.vmStatus().running()).isEqualTo(1);

        RoleAreaListItemResponse mdc02 = page.items().get(1);
        assertThat(mdc02.nodeCount()).isEqualTo(2);
        assertThat(mdc02.nodeStatus().error()).isEqualTo(1);
        assertThat(mdc02.nodeStatus().pending()).isEqualTo(1);
        assertThat(mdc02.vmCount()).isZero();
        assertThat(mdc02.vmStatus().total()).isZero();                // 자원 없어도 0/0/0/0

        RoleAreaListItemResponse nsA = page.items().get(2);
        assertThat(nsA.name()).isEqualTo("Namespace A");
        assertThat(nsA.nodeCount()).isZero();
        assertThat(nsA.vmCount()).isEqualTo(2);
        assertThat(nsA.vmStatus().stop()).isEqualTo(1);

        RoleAreaListItemResponse nsB = page.items().get(3);
        assertThat(nsB.vmCount()).isZero();                           // VM 없는 Namespace 도 행으로 나온다
    }

    @Test
    void 자원_영역_테이블_키워드_정렬_페이징을_지원한다() {
        assertThat(service.getRoleAreas("ADMIN", "namespace", 1, 10, "name,asc").items())
                .extracting(RoleAreaListItemResponse::name).containsExactly("Namespace A", "Namespace B");

        assertThat(service.getRoleAreas("ADMIN", null, 1, 10, "vmCount,desc").items())
                .extracting(RoleAreaListItemResponse::areaId).startsWith("TB:ns-a");
        assertThat(service.getRoleAreas("ADMIN", null, 1, 10, "nodeCount,desc").items())
                .extracting(RoleAreaListItemResponse::areaId).startsWith("mdc-02");

        PageResponse<RoleAreaListItemResponse> second = service.getRoleAreas("ADMIN", null, 2, 3, "name,asc");
        assertThat(second.items()).hasSize(1);
        assertThat(second.totalElements()).isEqualTo(4);
    }

    @Test
    void 지원하지_않는_정렬_필드는_거부한다() {
        assertThatThrownBy(() -> service.getRoleAreas("ADMIN", null, 1, 10, "deploymentId,asc"))
                .isInstanceOf(BusinessException.class);
    }
}
