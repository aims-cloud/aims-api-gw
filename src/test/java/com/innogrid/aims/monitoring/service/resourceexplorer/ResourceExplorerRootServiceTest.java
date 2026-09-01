package com.innogrid.aims.monitoring.service.resourceexplorer;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusDistributionResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.root.RoleListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.root.RootSummaryResponse;
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
class ResourceExplorerRootServiceTest {

    @Autowired
    ResourceExplorerRootService service;

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
    void 개요_KPI는_트리_데이터셋과_같은_수치를_집계한다() {
        loginWithRoles("ADMIN");
        RootSummaryResponse summary = service.getRootSummary();

        assertThat(summary.roleCount()).isEqualTo(1);
        assertThat(summary.deploymentCount()).isEqualTo(1);
        assertThat(summary.nodeCount()).isEqualTo(1);
        assertThat(summary.vmCount()).isEqualTo(1);
        // 상태 카운트는 노드+가상머신 합산 (RUNNING 노드 1 + RUNNING 가상머신 1)
        assertThat(summary.runningCount()).isEqualTo(2);
        assertThat(summary.errorCount()).isZero();
        assertThat(summary.pendingCount()).isZero();
        assertThat(summary.stopCount()).isZero();
    }

    @Test
    void 집계는_토큰의_역할_스코프로_제한된다() {
        // 역할 B만 보유 → 역할 B 스코프(node-02/vm-02, ERROR)만 집계
        loginWithRoles("USER");
        RootSummaryResponse summary = service.getRootSummary();
        assertThat(summary.roleCount()).isEqualTo(1);
        assertThat(summary.nodeCount()).isEqualTo(1);
        assertThat(summary.vmCount()).isEqualTo(1);
        assertThat(summary.runningCount()).isZero();
        assertThat(summary.errorCount()).isEqualTo(2);
        assertThat(service.getRoles(null, 1, 10, "name,asc").items())
                .extracting(RoleListItemResponse::name).containsExactly("역할 B");

        // 데이터셋에 없는 역할만 보유 → 전부 0
        loginWithRoles("GUEST");
        assertThat(service.getRootSummary().roleCount()).isZero();
        assertThat(service.getNodeStatus().totalCount()).isZero();
        assertThat(service.getRoles(null, 1, 10, "name,asc").totalElements()).isZero();
    }

    @Test
    void 미인증이면_빈_집계를_반환한다() {
        assertThat(service.getRootSummary().roleCount()).isZero();
        assertThat(service.getNodeStatus().totalCount()).isZero();
        assertThat(service.getVmStatus().totalCount()).isZero();
        assertThat(service.getRoles(null, 1, 10, "name,asc").items()).isEmpty();
    }

    @Test
    void 상태_도넛은_카운트가_0인_상태도_전부_내려준다() {
        loginWithRoles("ADMIN");
        ResourceStatusDistributionResponse nodeStatus = service.getNodeStatus();
        assertThat(nodeStatus.resourceKind()).isEqualTo("NODE");
        assertThat(nodeStatus.totalCount()).isEqualTo(1);
        assertThat(nodeStatus.statusCounts()).extracting(c -> c.status())
                .containsExactly(ResourceStatus.RUNNING, ResourceStatus.ERROR,
                        ResourceStatus.PENDING, ResourceStatus.STOP);
        assertThat(nodeStatus.statusCounts()).extracting(c -> c.count())
                .containsExactly(1L, 0L, 0L, 0L);

        ResourceStatusDistributionResponse vmStatus = service.getVmStatus();
        assertThat(vmStatus.resourceKind()).isEqualTo("VM");
        assertThat(vmStatus.totalCount()).isEqualTo(1);
    }

    @Test
    void 역할_테이블은_역할별_자원과_상태_스택바를_집계한다() {
        loginWithRoles("ADMIN");
        PageResponse<RoleListItemResponse> pageResponse = service.getRoles(null, 1, 10, "name,asc");

        assertThat(pageResponse.totalElements()).isEqualTo(1);
        RoleListItemResponse row = pageResponse.items().get(0);
        assertThat(row.roleId()).isEqualTo("ADMIN");
        assertThat(row.name()).isEqualTo("역할 A");
        assertThat(row.managerName()).isEqualTo("관리자");
        assertThat(row.memberCount()).isEqualTo(1);
        assertThat(row.deploymentCount()).isEqualTo(1);
        assertThat(row.nodeCount()).isEqualTo(1);
        assertThat(row.nodeStatus().total()).isEqualTo(1);
        assertThat(row.nodeStatus().running()).isEqualTo(1);
        assertThat(row.nodeStatus().stop()).isZero();
        assertThat(row.vmCount()).isEqualTo(1);
        assertThat(row.vmStatus().total()).isEqualTo(1);
        assertThat(row.vmStatus().running()).isEqualTo(1);
    }

    @Test
    void 역할_테이블_키워드는_이름과_관리자를_검색한다() {
        loginWithRoles("ADMIN");
        assertThat(service.getRoles("역할", 1, 10, "name,asc").totalElements()).isEqualTo(1);
        assertThat(service.getRoles("관리자", 1, 10, "name,asc").totalElements()).isEqualTo(1);
        assertThat(service.getRoles("없는역할", 1, 10, "name,asc").totalElements()).isZero();
    }

    @Test
    void 역할_테이블은_요청_페이지_구간만_반환한다() {
        loginWithRoles("ADMIN");
        PageResponse<RoleListItemResponse> secondPage = service.getRoles(null, 2, 10, "name,asc");
        assertThat(secondPage.items()).isEmpty();
        assertThat(secondPage.totalElements()).isEqualTo(1);
    }

    @Test
    void 지원하지_않는_정렬_필드는_거부한다() {
        loginWithRoles("ADMIN");
        assertThatThrownBy(() -> service.getRoles(null, 1, 10, "unknownField,asc"))
                .isInstanceOf(BusinessException.class);
    }
}
