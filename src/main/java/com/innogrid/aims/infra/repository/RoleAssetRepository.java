package com.innogrid.aims.infra.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.infra.entity.RoleAsset;

public interface RoleAssetRepository extends JpaRepository<RoleAsset, Long> {

    /** 역할 이름(토큰 roles 값) 목록으로 매핑 조회 — 자원 탐색기의 사용자 역할 스코프용 */
    List<RoleAsset> findByRole_NameIn(Collection<String> roleNames);

    List<RoleAsset> findByRole_Id(Long roleId);

    List<RoleAsset> findByAsset_Id(Long assetId);

    boolean existsByRole_IdAndAsset_Id(Long roleId, Long assetId);

    void deleteByRole_IdAndAsset_Id(Long roleId, Long assetId);
}
