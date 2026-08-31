package com.innogrid.aims.scheduler.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.scheduler.entity.Workload;

public interface WorkloadRepository extends JpaRepository<Workload, Long> {
    List<Workload> findByStatusOrderByCreatedAtDesc(Workload.WorkloadStatus status);
    List<Workload> findByAssignedNodeIdOrderByScheduledAtDesc(String nodeId);
    List<Workload> findAllByOrderByCreatedAtDesc();

    /** 예약(이미 배치된 워크로드) 집계에 쓴다. */
    List<Workload> findByStatusIn(Collection<Workload.WorkloadStatus> statuses);
}
