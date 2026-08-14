package com.innogrid.aims.scheduler.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.scheduler.entity.SchedulingHistory;

public interface SchedulingHistoryRepository extends JpaRepository<SchedulingHistory, Long> {

    List<SchedulingHistory> findByWorkloadIdOrderByCreatedAtDesc(Long workloadId);

    List<SchedulingHistory> findAllByOrderByCreatedAtDesc();
}
