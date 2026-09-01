package com.innogrid.aims.monitoring.service.resourceexplorer;

import java.util.List;

import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeFilter;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeNodeResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeNodeType;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeSearchResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
// TODO:: 자원 관리 데이터 연동 시 MockDataProvider 기반 검증을 실제 원장 대역 기반 검증으로 교체
class ResourceExplorerTreeServiceTest {

    @Autowired
    ResourceExplorerTreeService service;

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

    private static ResourceTreeFilter noFilter() {
        return new ResourceTreeFilter(null, null, null);
    }

    @Test
    void 트리는_토큰의_역할만_노출한다() {
        loginWithRoles("ADMIN", "USER");
        ResourceTreeNodeResponse root = service.getTree(noFilter());
        assertThat(root.children()).extracting(ResourceTreeNodeResponse::name)
                .containsExactly("역할 A", "역할 B");

        loginWithRoles("USER");                                       // 역할 B만 보유
        root = service.getTree(noFilter());
        assertThat(root.children()).extracting(ResourceTreeNodeResponse::name)
                .containsExactly("역할 B");

        loginWithRoles("GUEST");                                      // 데이터셋에 없는 역할만 보유
        root = service.getTree(noFilter());
        assertThat(root.children()).isEmpty();
    }

    @Test
    void 미인증이면_빈_트리를_반환한다() {
        ResourceTreeNodeResponse root = service.getTree(noFilter());
        assertThat(root.children()).isEmpty();
    }

    @Test
    void 전체_트리는_역할부터_모니터링_항목까지_계층을_구성한다() {
        loginWithRoles("ADMIN");
        ResourceTreeNodeResponse root = service.getTree(noFilter());

        ResourceTreeNodeResponse role = root.children().get(0);
        assertThat(role.children()).extracting(ResourceTreeNodeResponse::type)
                .containsExactly(ResourceTreeNodeType.PHYSICAL_HOST_GROUP, ResourceTreeNodeType.VM_GROUP);

        ResourceTreeNodeResponse hostGroup = role.children().get(0);
        assertThat(hostGroup.name()).isEqualTo("노드 (배포영역: Openstack-GPU)");
        assertThat(hostGroup.childCount()).isEqualTo(1);

        // node 01: 모니터링 항목 4종 + GPU 그룹(장치 2개)
        ResourceTreeNodeResponse node01 = hostGroup.children().get(0);
        assertThat(node01.children()).extracting(ResourceTreeNodeResponse::name)
                .containsExactly("CPU", "File system", "Memory", "Network", "GPU");
        assertThat(node01.children().get(4).childCount()).isEqualTo(2);

        // vm 01: 모니터링 항목 9종 + GPU 그룹
        ResourceTreeNodeResponse vm01 = role.children().get(1).children().get(0);
        assertThat(vm01.children()).extracting(ResourceTreeNodeResponse::name)
                .containsExactly("CPU", "Disk", "Disk I/O", "Memory", "Network",
                        "Process", "Process status", "Swap", "System", "GPU");
    }

    @Test
    void 자식_조회는_트리_항목_ID_체계로_하위를_해석한다() {
        loginWithRoles("ADMIN");
        List<ResourceTreeNodeResponse> roots = service.getRoots(noFilter());
        assertThat(roots).hasSize(1);
        assertThat(roots.get(0).children()).isNull();                 // lazy — 자식 미포함

        List<ResourceTreeNodeResponse> groups = service.getChildren("role:ADMIN");
        assertThat(groups).hasSize(2);

        List<ResourceTreeNodeResponse> gpus = service.getChildren("gpus:host:node-01");
        assertThat(gpus).extracting(ResourceTreeNodeResponse::name)
                .containsExactly("GPU 01", "GPU 02");
    }

    @Test
    void 트리_검색은_상위_경로를_함께_반환한다() {
        loginWithRoles("ADMIN");
        List<ResourceTreeSearchResponse> results = service.search(
                "node 01", List.of(ResourceTreeNodeType.PHYSICAL_HOST), 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).node().name()).isEqualTo("node 01");
        assertThat(results.get(0).path()).extracting(p -> p.type().name())
                .containsExactly("ROOT", "ROLE", "PHYSICAL_HOST_GROUP");
    }

    @Test
    void 키워드_필터는_일치_항목의_상위_계층을_유지한다() {
        loginWithRoles("ADMIN");
        ResourceTreeNodeResponse root = service.getTree(new ResourceTreeFilter(null, null, "vm 01"));

        assertThat(root.children()).hasSize(1);
        ResourceTreeNodeResponse role = root.children().get(0);
        assertThat(role.children()).hasSize(1);                       // VM 그룹만 생존
        assertThat(role.children().get(0).children()).extracting(ResourceTreeNodeResponse::name)
                .containsExactly("vm 01");
    }
}
