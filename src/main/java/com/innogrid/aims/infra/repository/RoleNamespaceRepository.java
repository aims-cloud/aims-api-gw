package com.innogrid.aims.infra.repository;

import java.util.Collection;
import java.util.List;

import com.innogrid.aims.infra.entity.RoleNamespace;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleNamespaceRepository extends JpaRepository<RoleNamespace, Long> {

    /** 토큰 역할 스코프 조회 — 역할 이름 목록으로 매핑된 Tumblebug Namespace 를 얻는다 */
    List<RoleNamespace> findByRole_NameIn(Collection<String> roleNames);

    List<RoleNamespace> findByRole_Id(Long roleId);

    boolean existsByRole_IdAndNsId(Long roleId, String nsId);
}
