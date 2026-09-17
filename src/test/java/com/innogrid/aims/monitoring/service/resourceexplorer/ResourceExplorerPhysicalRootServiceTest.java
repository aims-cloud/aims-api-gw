package com.innogrid.aims.monitoring.service.resourceexplorer;

import java.util.List;
import java.util.Set;

import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusDistributionResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.grouproot.PhysicalRootMdcListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.grouproot.PhysicalRootSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedNamespace;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedVm;
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
 * 물리 노드 그룹 루트 패널 테스트 — 토큰 역할 검증(404), KPI(호스트 기준 상태·1세부 VM 만 셈), 도넛, MDC 목록 정렬·검색·페이징·400.
 *
 * 대역 스코프 (ADMIN):
 *   mdc-01: node-01 RUNNING / 1세부 VM web-01 RUNNING
 *   mdc-02: node-02 ERROR, node-03 PENDING
 *   ns-a: TB vm-1 RUNNING, vm-2 STOP  (물리 그룹에서는 세지 않음)
 */
class ResourceExplorerPhysicalRootServiceTest {

    ResourceScopeService scopeService = mock(ResourceScopeService.class);
    AuthService authService = mock(AuthService.class);
    ResourceExplorerPhysicalRootService service = new ResourceExplorerPhysicalRootService(scopeService, authService);

    @BeforeEach
    void setUp() {
        when(authService.currentUserRoleIds()).thenReturn(Set.of("ADMIN", "USER"));
        when(scopeService.scopeOf(anyCollection())).thenReturn(Scope.empty());
        when(scopeService.scopeOf(List.of("ADMIN"))).thenReturn(new Scope(Set.of("ADMIN"),
                List.of(new ScopedHost("ADMIN", 11L, "node-01", null, "mdc-01", ResourceStatus.RUNNING, null, List.of()),
                        new ScopedHost("ADMIN", 12L, "node-02", null, "mdc-02", ResourceStatus.ERROR, null, List.of()),
                        new ScopedHost("ADMIN", 13L, "node-03", null, "mdc-02", ResourceStatus.PENDING, null, List.of())),
                List.of(new ScopedVm("ADMIN", "SEBU1:uuid-1", "web-01", ResourceStatus.RUNNING, "mdc-01", null),
                        new ScopedVm("ADMIN", "TB:ns-a/infra-1/vm-1", "vm-1", ResourceStatus.RUNNING, null, "ns-a"),
                        new ScopedVm("ADMIN", "TB:ns-a/infra-1/vm-2", "vm-2", ResourceStatus.STOP, null, "ns-a")),
                List.of(new ScopedNamespace("ADMIN", "ns-a", "Namespace A"))));
    }

    @Test
    void 토큰에_없는_역할은_404이고_스코프를_조회하지_않는다() {
        assertThatThrownBy(() -> service.getSummary("GUEST"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getNodeStatus("GUEST")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getMdcs("GUEST", null, 1, 10, "name,asc")).isInstanceOf(BusinessException.class);
        verify(scopeService, never()).scopeOf(anyCollection());
    }

    @Test
    void KPI는_호스트_기준_상태를_집계하고_가상자원은_1세부_VM만_센다() {
        PhysicalRootSummaryResponse summary = service.getSummary("ADMIN");

        assertThat(summary.mdcCount()).isEqualTo(2);
        assertThat(summary.nodeCount()).isEqualTo(3);
        assertThat(summary.vmCount()).isEqualTo(1);                   // web-01 만, Tumblebug VM 제외
        assertThat(summary.runningCount()).isEqualTo(1);
        assertThat(summary.errorCount()).isEqualTo(1);
        assertThat(summary.pendingCount()).isEqualTo(1);
        assertThat(summary.stopCount()).isZero();

        PhysicalRootSummaryResponse empty = service.getSummary("USER");
        assertThat(empty.mdcCount()).isZero();
        assertThat(empty.nodeCount()).isZero();
    }

    @Test
    void 노드_상태_도넛은_카운트가_0인_상태도_전부_내려준다() {
        ResourceStatusDistributionResponse nodes = service.getNodeStatus("ADMIN");

        assertThat(nodes.resourceKind()).isEqualTo("NODE");
        assertThat(nodes.totalCount()).isEqualTo(3);
        assertThat(nodes.statusCounts()).extracting(ResourceStatusDistributionResponse.StatusCount::status)
                .containsExactly(ResourceStatus.RUNNING, ResourceStatus.ERROR, ResourceStatus.PENDING, ResourceStatus.STOP);
        assertThat(nodes.statusCounts()).extracting(ResourceStatusDistributionResponse.StatusCount::count)
                .containsExactly(1L, 1L, 1L, 0L);
    }

    @Test
    void MDC_목록은_호스트와_1세부_VM_상태를_행으로_내리고_정렬_검색_페이징하며_미지원_정렬은_400이다() {
        PageResponse<PhysicalRootMdcListItemResponse> page = service.getMdcs("ADMIN", null, 1, 10, "name,asc");

        assertThat(page.totalElements()).isEqualTo(2);
        PhysicalRootMdcListItemResponse mdc01 = page.items().get(0);
        assertThat(mdc01.mdcId()).isEqualTo("mdc-01");
        assertThat(mdc01.nodeCount()).isEqualTo(1);
        assertThat(mdc01.nodeStatus().running()).isEqualTo(1);
        assertThat(mdc01.vmCount()).isEqualTo(1);
        assertThat(mdc01.vmStatus().running()).isEqualTo(1);
        PhysicalRootMdcListItemResponse mdc02 = page.items().get(1);
        assertThat(mdc02.nodeCount()).isEqualTo(2);
        assertThat(mdc02.nodeStatus().error()).isEqualTo(1);
        assertThat(mdc02.nodeStatus().pending()).isEqualTo(1);
        assertThat(mdc02.vmCount()).isZero();
        assertThat(mdc02.vmStatus().total()).isZero();

        assertThat(service.getMdcs("ADMIN", null, 1, 10, "nodeCount,desc").items())
                .extracting(PhysicalRootMdcListItemResponse::mdcId).containsExactly("mdc-02", "mdc-01");
        assertThat(service.getMdcs("ADMIN", "02", 1, 10, "name,asc").items())
                .extracting(PhysicalRootMdcListItemResponse::mdcId).containsExactly("mdc-02");
        assertThat(service.getMdcs("ADMIN", null, 2, 1, "name,asc").items())
                .extracting(PhysicalRootMdcListItemResponse::mdcId).containsExactly("mdc-02");

        assertThatThrownBy(() -> service.getMdcs("ADMIN", null, 1, 10, "kind,asc"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
