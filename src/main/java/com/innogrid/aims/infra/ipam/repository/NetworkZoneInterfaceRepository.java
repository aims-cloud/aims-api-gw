package com.innogrid.aims.infra.ipam.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.infra.ipam.entity.NetworkZoneInterface;

public interface NetworkZoneInterfaceRepository extends JpaRepository<NetworkZoneInterface, Long> {
    List<NetworkZoneInterface> findByZoneId(Long zoneId);
    void deleteByZoneId(Long zoneId);
}
