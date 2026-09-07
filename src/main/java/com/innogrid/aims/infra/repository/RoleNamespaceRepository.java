package com.innogrid.aims.infra.repository;

import java.util.Collection;
import java.util.List;

import com.innogrid.aims.infra.entity.RoleNamespace;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleNamespaceRepository extends JpaRepository<RoleNamespace, Long> {

    /** 토큰 역할 스코프 조회 — 역할 이름 목록으로 매핑된 Tumblebug Namespace 를 얻는다.
     *  호출측이 트랜잭션 밖에서 role 을 읽으므로 함께 적재한다 */
    @EntityGraph(attributePaths = "role")
    List<RoleNamespace> findByRole_NameIn(Collection<String> roleNames);

    List<RoleNamespace> findByRole_Id(Long roleId);

    boolean existsByRole_IdAndNsId(Long roleId, String nsId);
}
