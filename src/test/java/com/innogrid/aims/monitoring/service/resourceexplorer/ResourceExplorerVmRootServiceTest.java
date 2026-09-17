package com.innogrid.aims.monitoring.service.resourceexplorer;

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
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusDistributionResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.grouproot.VmRootNamespaceListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.grouproot.VmRootSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbInfra;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbNode;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbSnapshot;
import com.innogrid.aims.monitoring.service.resourceexplorer.vm.TumblebugPanelSupport;
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
 * 가상머신 그룹 루트 패널 테스트 — 토큰 역할 검증(404), 매핑 없음 빈 응답(Tumblebug 미조회),
 * KPI(Tumblebug 에 없는 Namespace 제외·상태 버킷·실패 표시), 도넛, Namespace 목록 정렬·검색·페이징·400.
 *
 * 대역: ADMIN → ns-a, ns-b, ns-gone 매핑. Tumblebug 에는 ns-a("OpenStack")·ns-b("AWS")만 존재.
 *   ns-a: infra-1(vm-1 Running, vm-2 Terminated), infra-2(vm-3 Creating)
 *   ns-b: infra-3(vm-4 Failed) — 이번 갱신 조회 실패(이전 값)
 */
class ResourceExplorerVmRootServiceTest {

    AuthService authService = mock(AuthService.class);
    RoleNamespaceRepository roleNamespaceRepository = mock(RoleNamespaceRepository.class);
    TumblebugTreeBuilder tumblebugTreeBuilder = mock(TumblebugTreeBuilder.class);
    ResourceExplorerVmRootService service = new ResourceExplorerVmRootService(
            new TumblebugPanelSupport(authService, roleNamespaceRepository, tumblebugTreeBuilder));

    @BeforeEach
    void setUp() {
        when(authService.currentUserRoleIds()).thenReturn(new LinkedHashSet<>(List.of("ADMIN", "USER")));
        Role admin = new Role("ADMIN", "관리자");
        when(roleNamespaceRepository.findByRole_NameIn(anyCollection())).thenAnswer(inv -> {
            Collection<String> roles = inv.getArgument(0);
            return roles.contains("ADMIN")
                    ? List.of(new RoleNamespace(admin, "ns-b"), new RoleNamespace(admin, "ns-a"), new RoleNamespace(admin, "ns-gone"))
                    : List.of();
        });
        when(tumblebugTreeBuilder.snapshot(anyCollection())).thenReturn(new TbSnapshot(Instant.now(),
                Map.of("ns-a", "OpenStack", "ns-b", "AWS"),
                Map.of("ns-a", List.of(
                                new TbInfra("infra-1", "infra-1", "Running:2", List.of(
                                        new TbNode("vm-1", "vm-1", "Running", "g1", "openstack", null, "172.16.0.1"),
                                        new TbNode("vm-2", "vm-2", "Terminated", "g1", "openstack", null, null))),
                                new TbInfra("infra-2", "infra-2", "Creating:1", List.of(
                                        new TbNode("vm-3", "vm-3", "Creating", null, "openstack", null, null)))),
                        "ns-b", List.of(
                                new TbInfra("infra-3", "infra-3", "Failed:1", List.of(
                                        new TbNode("vm-4", "vm-4", "Failed", "g1", "aws", null, null))))),
                Set.of("ns-a", "ns-b"), Set.of("ns-b"), false));
    }

    @Test
    void 토큰에_없는_역할은_404이고_매핑_없는_역할은_Tumblebug를_조회하지_않고_빈_응답이다() {
        assertThatThrownBy(() -> service.getSummary("GUEST"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getNamespaces("GUEST", null, 1, 10, "name,asc")).isInstanceOf(BusinessException.class);

        VmRootSummaryResponse empty = service.getSummary("USER");
        assertThat(empty.namespaceCount()).isZero();
        assertThat(empty.vmCount()).isZero();
        assertThat(empty.tumblebugUnavailable()).isFalse();
        assertThat(service.getVmStatus("USER").totalCount()).isZero();
        assertThat(service.getNamespaces("USER", null, 1, 10, "name,asc").totalElements()).isZero();
        verify(tumblebugTreeBuilder, never()).snapshot(anyCollection());
    }

    @Test
    void KPI는_Tumblebug에_있는_매핑_Namespace만_세고_VM_상태를_버킷으로_집계하며_실패_Namespace가_있으면_표시한다() {
        VmRootSummaryResponse summary = service.getSummary("ADMIN");

        assertThat(summary.namespaceCount()).isEqualTo(2);           // ns-gone 제외
        assertThat(summary.infraCount()).isEqualTo(3);
        assertThat(summary.vmCount()).isEqualTo(4);
        assertThat(summary.runningCount()).isEqualTo(1);
        assertThat(summary.pendingCount()).isEqualTo(1);              // Creating
        assertThat(summary.stopCount()).isEqualTo(1);                 // Terminated
        assertThat(summary.errorCount()).isEqualTo(1);                // Failed
        assertThat(summary.tumblebugUnavailable()).isTrue();          // ns-b 조회 실패
    }

    @Test
    void 가상머신_상태_도넛은_카운트가_0인_상태도_전부_내려준다() {
        ResourceStatusDistributionResponse vms = service.getVmStatus("ADMIN");

        assertThat(vms.resourceKind()).isEqualTo("VM");
        assertThat(vms.totalCount()).isEqualTo(4);
        assertThat(vms.statusCounts()).extracting(ResourceStatusDistributionResponse.StatusCount::status)
                .containsExactly(ResourceStatus.RUNNING, ResourceStatus.ERROR, ResourceStatus.PENDING, ResourceStatus.STOP);
        assertThat(vms.statusCounts()).extracting(ResourceStatusDistributionResponse.StatusCount::count)
                .containsExactly(1L, 1L, 1L, 1L);
    }

    @Test
    void Namespace_목록은_Infra_VM_상태_실패여부를_행으로_내리고_정렬_검색_페이징하며_미지원_정렬은_400이다() {
        PageResponse<VmRootNamespaceListItemResponse> page = service.getNamespaces("ADMIN", null, 1, 10, "name,asc");

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.items()).extracting(VmRootNamespaceListItemResponse::name).containsExactly("AWS", "OpenStack");
        VmRootNamespaceListItemResponse aws = page.items().get(0);
        assertThat(aws.nsId()).isEqualTo("ns-b");
        assertThat(aws.infraCount()).isEqualTo(1);
        assertThat(aws.vmCount()).isEqualTo(1);
        assertThat(aws.vmStatus().error()).isEqualTo(1);
        assertThat(aws.tumblebugUnavailable()).isTrue();
        VmRootNamespaceListItemResponse openstack = page.items().get(1);
        assertThat(openstack.infraCount()).isEqualTo(2);
        assertThat(openstack.vmCount()).isEqualTo(3);
        assertThat(openstack.vmStatus().running()).isEqualTo(1);
        assertThat(openstack.vmStatus().pending()).isEqualTo(1);
        assertThat(openstack.vmStatus().stop()).isEqualTo(1);
        assertThat(openstack.tumblebugUnavailable()).isFalse();

        assertThat(service.getNamespaces("ADMIN", null, 1, 10, "vmCount,desc").items())
                .extracting(VmRootNamespaceListItemResponse::nsId).containsExactly("ns-a", "ns-b");
        assertThat(service.getNamespaces("ADMIN", "ns-b", 1, 10, "name,asc").items())
                .extracting(VmRootNamespaceListItemResponse::nsId).containsExactly("ns-b");           // nsId 로도 검색
        assertThat(service.getNamespaces("ADMIN", "open", 1, 10, "name,asc").items())
                .extracting(VmRootNamespaceListItemResponse::nsId).containsExactly("ns-a");
        assertThat(service.getNamespaces("ADMIN", null, 2, 1, "name,asc").items())
                .extracting(VmRootNamespaceListItemResponse::nsId).containsExactly("ns-a");

        assertThatThrownBy(() -> service.getNamespaces("ADMIN", null, 1, 10, "kind,asc"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
