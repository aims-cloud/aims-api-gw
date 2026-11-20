package com.innogrid.aims.infra.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.infra.entity.IpAllocation;

public interface IpAllocationRepository extends JpaRepository<IpAllocation, Long> {

    List<IpAllocation> findByMdcId(String mdcId);

    List<IpAllocation> findByMdcIdAndStatus(String mdcId, IpAllocation.IpStatus status);
}
