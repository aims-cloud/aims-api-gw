package com.innogrid.aims.infra.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.innogrid.aims.infra.entity.Asset;

public interface AssetRepository extends JpaRepository<Asset, Long>, JpaSpecificationExecutor<Asset> {

    List<Asset> findByMdcIdOrderByCreatedAtDesc(String mdcId);

    List<Asset> findByMdcIdAndTypeOrderByCreatedAtDesc(String mdcId, Asset.AssetType type);

    List<Asset> findByTypeOrderByCreatedAtDesc(Asset.AssetType type);

    /** 1세부 수신 데이터 동기화(upsert) 키 조회 */
    Optional<Asset> findBySourceAndExternalId(Asset.AssetSource source, String externalId);
}
