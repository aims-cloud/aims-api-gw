package com.innogrid.aims.resource.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.resource.entity.VmGroupConnection;

public interface VmGroupConnectionRepository extends JpaRepository<VmGroupConnection, Long> {

    List<VmGroupConnection> findByVmGroupId(Long vmGroupId);

    void deleteByVmGroupId(Long vmGroupId);
}
