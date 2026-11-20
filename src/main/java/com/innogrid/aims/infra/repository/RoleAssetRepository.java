package com.innogrid.aims.infra.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.infra.entity.RoleAsset;

public interface RoleAssetRepository extends JpaRepository<RoleAsset, Long> {

    /** 역할 이름(토큰 roles 값) 목록으로 매핑 조회 — 자원 탐색기의 사용자 역할 스코프용.
     *  호출측이 트랜잭션 밖(외부 HTTP 조회와 분리)에서 role·asset 을 읽으므로 함께 적재한다 */
    @EntityGraph(attributePaths = {"role", "asset"})
    List<RoleAsset> findByRole_NameIn(Collection<String> roleNames);

    List<RoleAsset> findByRole_Id(Long roleId);

    List<RoleAsset> findByAsset_Id(Long assetId);

    boolean existsByRole_IdAndAsset_Id(Long roleId, Long assetId);

    void deleteByRole_IdAndAsset_Id(Long roleId, Long assetId);
}
