package com.innogrid.aims.monitoring.service.resourceexplorer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusDistributionResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.root.RoleListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.root.RootSummaryResponse;
import com.innogrid.aims.auth.repository.UserRepository;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedVm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 전체 자원 패널 집계 테스트 — 스코프(ResourceScopeService)는 대역으로 고정하고 집계 규칙만 검증한다.
 * 스코프 산출 자체는 ResourceScopeServiceTest 가 검증한다.
 *
 * 대역 스코프:
 *   ADMIN → node-01(mdc-01 RUNNING), node-02(mdc-02 ERROR) / TB vm-1 RUNNING, vm-2 STOP
 *   USER  → node-02(mdc-02 ERROR, ADMIN 과 중복), node-03(mdc-02 PENDING) / VM 없음
 */
class ResourceExplorerRootServiceTest {

    ResourceScopeService scopeService = mock(ResourceScopeService.class);
    UserRepository userRepository = mock(UserRepository.class);
    ResourceExplorerRootService service = new ResourceExplorerRootService(scopeService, userRepository);

    @BeforeEach
    void setUp() {
        when(userRepository.countByRoles_Name("ADMIN")).thenReturn(2L);
        when(userRepository.countByRoles_Name("USER")).thenReturn(5L);
    }

    private void scope(Set<String> roles, List<ScopedHost> hosts, List<ScopedVm> vms) {
        when(scopeService.currentUserScope()).thenReturn(new Scope(roles, hosts, vms));
    }

    private void adminAndUserScope() {
        scope(new LinkedHashSet<>(List.of("ADMIN", "USER")),
                List.of(new ScopedHost("ADMIN", 11L, "node-01", "mdc-01", ResourceStatus.RUNNING),
                        new ScopedHost("ADMIN", 12L, "node-02", "mdc-02", ResourceStatus.ERROR),
                        new ScopedHost("USER", 12L, "node-02", "mdc-02", ResourceStatus.ERROR),
                        new ScopedHost("USER", 13L, "node-03", "mdc-02", ResourceStatus.PENDING)),
                List.of(new ScopedVm("ADMIN", "TB:ns-a/infra-1/vm-1", "vm-1", ResourceStatus.RUNNING),
                        new ScopedVm("ADMIN", "TB:ns-a/infra-1/vm-2", "vm-2", ResourceStatus.STOP)));
    }

    @Test
    void 개요_KPI는_역할_중복을_제거한_자원_수와_상태를_집계한다() {
        adminAndUserScope();
        RootSummaryResponse summary = service.getRootSummary();

        assertThat(summary.roleCount()).isEqualTo(2);
        assertThat(summary.mdcCount()).isEqualTo(2);                   // mdc-01, mdc-02
        assertThat(summary.nodeCount()).isEqualTo(3);                  // node-02 는 두 역할에 있어도 한 번
        assertThat(summary.vmCount()).isEqualTo(2);
        assertThat(summary.runningCount()).isEqualTo(2);               // node-01 + vm-1
        assertThat(summary.errorCount()).isEqualTo(1);                 // node-02
        assertThat(summary.pendingCount()).isEqualTo(1);               // node-03
        assertThat(summary.stopCount()).isEqualTo(1);                  // vm-2
    }

    @Test
    void 미인증이면_빈_집계를_반환한다() {
        scope(Set.of(), List.of(), List.of());

        RootSummaryResponse summary = service.getRootSummary();
        assertThat(summary.roleCount()).isZero();
        assertThat(summary.nodeCount()).isZero();
        assertThat(service.getNodeStatus().totalCount()).isZero();
        assertThat(service.getRoles(null, 1, 10, "name,asc").items()).isEmpty();
    }

    @Test
    void 상태_도넛은_카운트가_0인_상태도_전부_내려준다() {
        adminAndUserScope();

        ResourceStatusDistributionResponse nodes = service.getNodeStatus();
        assertThat(nodes.resourceKind()).isEqualTo("NODE");
        assertThat(nodes.totalCount()).isEqualTo(3);
        assertThat(nodes.statusCounts()).extracting(c -> c.status().name(), ResourceStatusDistributionResponse.StatusCount::count)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("RUNNING", 1L),
                        org.assertj.core.groups.Tuple.tuple("ERROR", 1L),
                        org.assertj.core.groups.Tuple.tuple("PENDING", 1L),
                        org.assertj.core.groups.Tuple.tuple("STOP", 0L));

        ResourceStatusDistributionResponse vms = service.getVmStatus();
        assertThat(vms.resourceKind()).isEqualTo("VM");
        assertThat(vms.totalCount()).isEqualTo(2);
        assertThat(vms.statusCounts()).extracting(ResourceStatusDistributionResponse.StatusCount::count)
                .containsExactly(1L, 0L, 0L, 1L);
    }

    @Test
    void 역할_테이블은_역할별_자원과_상태_스택바를_집계한다() {
        adminAndUserScope();
        PageResponse<RoleListItemResponse> page = service.getRoles(null, 1, 10, "name,asc");

        assertThat(page.items()).extracting(RoleListItemResponse::name).containsExactly("ADMIN", "USER");

        RoleListItemResponse admin = page.items().get(0);
        assertThat(admin.memberCount()).isEqualTo(2);
        assertThat(admin.managerName()).isNull();                     // 관리자 개념 미정 → "-"
        assertThat(admin.mdcCount()).isEqualTo(2);
        assertThat(admin.nodeCount()).isEqualTo(2);
        assertThat(admin.nodeStatus().running()).isEqualTo(1);
        assertThat(admin.nodeStatus().error()).isEqualTo(1);
        assertThat(admin.vmCount()).isEqualTo(2);
        assertThat(admin.vmStatus().stop()).isEqualTo(1);

        RoleListItemResponse user = page.items().get(1);
        assertThat(user.memberCount()).isEqualTo(5);
        assertThat(user.mdcCount()).isEqualTo(1);
        assertThat(user.nodeCount()).isEqualTo(2);
        assertThat(user.nodeStatus().pending()).isEqualTo(1);
        assertThat(user.vmCount()).isZero();
        assertThat(user.vmStatus()).isNull();                         // VM 없음 → 스택바 "-"
    }

    @Test
    void 역할_테이블_키워드_정렬_페이징을_지원한다() {
        adminAndUserScope();

        assertThat(service.getRoles("adm", 1, 10, "name,asc").items())
                .extracting(RoleListItemResponse::name).containsExactly("ADMIN");

        assertThat(service.getRoles(null, 1, 10, "memberCount,desc").items())
                .extracting(RoleListItemResponse::name).containsExactly("USER", "ADMIN");
        assertThat(service.getRoles(null, 1, 10, "vmCount,desc").items())
                .extracting(RoleListItemResponse::name).containsExactly("ADMIN", "USER");

        PageResponse<RoleListItemResponse> second = service.getRoles(null, 2, 1, "name,asc");
        assertThat(second.items()).extracting(RoleListItemResponse::name).containsExactly("USER");
        assertThat(second.totalElements()).isEqualTo(2);
    }

    @Test
    void 지원하지_않는_정렬_필드는_거부한다() {
        adminAndUserScope();
        assertThatThrownBy(() -> service.getRoles(null, 1, 10, "deploymentCount,asc"))
                .isInstanceOf(BusinessException.class);
    }
}
