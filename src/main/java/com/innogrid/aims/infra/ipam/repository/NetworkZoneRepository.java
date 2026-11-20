package com.innogrid.aims.infra.ipam.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.infra.ipam.entity.NetworkZone;

public interface NetworkZoneRepository extends JpaRepository<NetworkZone, Long> {
    boolean existsByName(String name);
}
