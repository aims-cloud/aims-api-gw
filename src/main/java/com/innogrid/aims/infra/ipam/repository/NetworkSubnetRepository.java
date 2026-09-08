package com.innogrid.aims.infra.ipam.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.infra.ipam.entity.NetworkSubnet;

public interface NetworkSubnetRepository extends JpaRepository<NetworkSubnet, Long> {
    List<NetworkSubnet> findByZoneId(Long zoneId);
    void deleteByZoneId(Long zoneId);
    long countByZoneId(Long zoneId);
}
