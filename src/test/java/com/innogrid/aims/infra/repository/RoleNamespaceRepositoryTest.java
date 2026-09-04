package com.innogrid.aims.infra.repository;

import java.util.List;

import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.repository.RoleRepository;
import com.innogrid.aims.infra.entity.RoleNamespace;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class RoleNamespaceRepositoryTest {

    @Autowired
    RoleNamespaceRepository roleNamespaceRepository;
    @Autowired
    RoleRepository roleRepository;

    @Test
    void 역할_이름으로_매핑된_Tumblebug_Namespace를_조회한다() {
        Role admin = roleRepository.save(new Role("EXPLORER_TB_ADMIN", "자원 탐색기 테스트 역할 A"));
        Role user = roleRepository.save(new Role("EXPLORER_TB_USER", "자원 탐색기 테스트 역할 B"));

        roleNamespaceRepository.save(new RoleNamespace(admin, "ns-a"));
        roleNamespaceRepository.save(new RoleNamespace(admin, "ns-b"));
        roleNamespaceRepository.save(new RoleNamespace(user, "ns-a"));      // Namespace 하나가 여러 역할에 노출되는 N:M

        // 토큰 역할 스코프 조회 — 가상머신 가지의 Namespace 목록
        List<RoleNamespace> adminScope = roleNamespaceRepository.findByRole_NameIn(List.of("EXPLORER_TB_ADMIN"));
        assertThat(adminScope).extracting(RoleNamespace::getNsId).containsExactlyInAnyOrder("ns-a", "ns-b");

        List<RoleNamespace> bothScope = roleNamespaceRepository.findByRole_NameIn(
                List.of("EXPLORER_TB_ADMIN", "EXPLORER_TB_USER"));
        assertThat(bothScope).hasSize(3);

        assertThat(roleNamespaceRepository.existsByRole_IdAndNsId(user.getId(), "ns-a")).isTrue();
        assertThat(roleNamespaceRepository.existsByRole_IdAndNsId(user.getId(), "ns-b")).isFalse();
        assertThat(roleNamespaceRepository.findByRole_Id(admin.getId())).hasSize(2);
    }
}
