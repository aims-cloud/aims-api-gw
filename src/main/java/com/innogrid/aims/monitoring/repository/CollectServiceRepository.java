package com.innogrid.aims.monitoring.repository;

import java.util.List;
import java.util.Optional;
import com.innogrid.aims.monitoring.entity.CollectService;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectServiceRepository extends JpaRepository<CollectService, Long> {
    List<CollectService> findByAsset_Id(Long assetId);
    Optional<CollectService> findByIdAndAsset_Id(Long id, Long assetId);
    boolean existsByAsset_IdAndServiceNameAndIdNot(Long assetId, String serviceName, Long id);
}
