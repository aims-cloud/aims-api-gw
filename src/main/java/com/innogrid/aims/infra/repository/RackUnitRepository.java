package com.innogrid.aims.infra.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.infra.entity.RackUnit;

public interface RackUnitRepository extends JpaRepository<RackUnit, Long> {

    List<RackUnit> findByRackId(Long rackId);

    java.util.Optional<RackUnit> findFirstByRackIdAndAssetName(Long rackId, String assetName);
}
