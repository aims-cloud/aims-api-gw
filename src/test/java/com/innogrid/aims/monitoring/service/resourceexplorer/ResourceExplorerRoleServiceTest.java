package com.innogrid.aims.monitoring.service.resourceexplorer;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusDistributionResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleDeploymentListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleSummaryResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
// TODO:: 자원 관리 데이터 연동 시 MockDataProvider 기반 검증을 실제 원장 대역 기반 검증으로 교체
class ResourceExplorerRoleServiceTest {

    @Autowired
    ResourceExplorerRoleService service;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void loginWithRoles(String... roles) {
        var authorities = java.util.Arrays.stream(roles)
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", null, authorities));
    }

    @Test
    void 개요_KPI는_역할_스코프의_자원을_집계한다() {
        loginWithRoles("ADMIN");
        RoleSummaryResponse summary = service.getRoleSummary("ADMIN");

        assertThat(summary.memberCount()).isEqualTo(1);
        assertThat(summary.deploymentCount()).isEqualTo(1);
        assertThat(summary.nodeCount()).isEqualTo(1);
        assertThat(summary.vmCount()).isEqualTo(1);
        // 상태 카운트는 노드+가상머신 합산 (RUNNING 노드 1 + RUNNING 가상머신 1)
        assertThat(summary.runningCount()).isEqualTo(2);
        assertThat(summary.errorCount()).isZero();
    }

    @Test
    void 토큰에_없는_역할은_404다() {
        loginWithRoles("ADMIN");
        assertThatThrownBy(() -> service.getRoleSummary("USER"))     // 데이터셋에는 존재하는 역할
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getRoleDeployments("GUEST", null, 1, 10, "name,asc"))
                .isInstanceOf(BusinessException.class);              // 데이터셋에도 없는 역할

        SecurityContextHolder.clearContext();                        // 미인증
        assertThatThrownBy(() -> service.getNodeStatus("ADMIN"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 상태_도넛은_역할_스코프로_카운트가_0인_상태도_전부_내려준다() {
        loginWithRoles("USER");
        ResourceStatusDistributionResponse nodeStatus = service.getNodeStatus("USER");
        assertThat(nodeStatus.resourceKind()).isEqualTo("NODE");
        assertThat(nodeStatus.totalCount()).isEqualTo(2);            // node-02, node-03
        assertThat(nodeStatus.statusCounts()).extracting(c -> c.status())
                .containsExactly(ResourceStatus.RUNNING, ResourceStatus.ERROR,
                        ResourceStatus.PENDING, ResourceStatus.STOP);
        assertThat(nodeStatus.statusCounts()).extracting(c -> c.count())
                .containsExactly(0L, 2L, 0L, 0L);

        ResourceStatusDistributionResponse vmStatus = service.getVmStatus("USER");
        assertThat(vmStatus.resourceKind()).isEqualTo("VM");
        assertThat(vmStatus.totalCount()).isEqualTo(2);               // vm-02(ERROR) + vm-03(PENDING)
        assertThat(vmStatus.statusCounts()).extracting(c -> c.count())
                .containsExactly(0L, 1L, 1L, 0L);                     // Running/Error/Pending/Stop 순
    }

    @Test
    void 배포영역_목록은_배포영역별_자원과_상태_스택바를_집계한다() {
        loginWithRoles("ADMIN");
        PageResponse<RoleDeploymentListItemResponse> pageResponse =
                service.getRoleDeployments("ADMIN", null, 1, 10, "name,asc");

        assertThat(pageResponse.totalElements()).isEqualTo(1);
        RoleDeploymentListItemResponse row = pageResponse.items().get(0);
        assertThat(row.name()).isEqualTo("Openstack-GPU");
        assertThat(row.nodeCount()).isEqualTo(1);
        assertThat(row.nodeStatus().total()).isEqualTo(1);
        assertThat(row.nodeStatus().running()).isEqualTo(1);
        assertThat(row.nodeStatus().stop()).isZero();                // 자원이 없어도 0 (null 아님)
        assertThat(row.vmCount()).isEqualTo(1);
        assertThat(row.vmStatus().running()).isEqualTo(1);
    }

    @Test
    void 배포영역_목록은_키워드로_이름을_검색한다() {
        loginWithRoles("USER");
        assertThat(service.getRoleDeployments("USER", "GPU2", 1, 10, "name,asc")
                .totalElements()).isEqualTo(1);
        assertThat(service.getRoleDeployments("USER", "없는배포영역", 1, 10, "name,asc")
                .totalElements()).isZero();
    }

    @Test
    void 지원하지_않는_정렬_필드는_거부한다() {
        loginWithRoles("ADMIN");
        assertThatThrownBy(() -> service.getRoleDeployments("ADMIN", null, 1, 10, "unknownField,asc"))
                .isInstanceOf(BusinessException.class);
    }
}
