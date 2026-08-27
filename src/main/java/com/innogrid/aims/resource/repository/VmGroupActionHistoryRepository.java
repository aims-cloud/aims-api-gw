package com.innogrid.aims.resource.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.resource.entity.VmGroupActionHistory;

public interface VmGroupActionHistoryRepository extends JpaRepository<VmGroupActionHistory, Long> {

    List<VmGroupActionHistory> findByVmGroupIdOrderByCreatedAtDesc(Long vmGroupId);
}
