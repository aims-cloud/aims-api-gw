package com.innogrid.aims.infra.ipam.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.innogrid.aims.infra.ipam.entity.IpAddress;

public interface IpAddressRepository extends JpaRepository<IpAddress, Long>,
        JpaSpecificationExecutor<IpAddress> {

    List<IpAddress> findBySubnetId(Long subnetId);

    Optional<IpAddress> findBySubnetIdAndIpAddress(Long subnetId, String ipAddress);

    long countBySubnetId(Long subnetId);

    void deleteBySubnetId(Long subnetId);

    void deleteByZoneId(Long zoneId);
}
