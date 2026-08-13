package com.innogrid.aims.infra.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.infra.entity.Asset;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    List<Asset> findByMdcIdOrderByCreatedAtDesc(String mdcId);

    List<Asset> findByMdcIdAndTypeOrderByCreatedAtDesc(String mdcId, Asset.AssetType type);

    List<Asset> findByTypeOrderByCreatedAtDesc(Asset.AssetType type);
}
