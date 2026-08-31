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

    /**
     * 스케줄 실행 대상 조회.
     * 우선순위가 높고 먼저 등록된 것부터 배치한다.
     */
    List<Workload> findByStatusAndResourceTypeOrderByPriorityDescCreatedAtAsc(
            Workload.WorkloadStatus status, Workload.ResourceType resourceType);

    /**
     * 리소스 종류가 지정되지 않은(=VM 으로 해석하는) 워크로드.
     * 기존 행이 null 이므로 VM 대상 실행에서 함께 잡아야 한다.
     */
    List<Workload> findByStatusAndResourceTypeIsNullOrderByPriorityDescCreatedAtAsc(
            Workload.WorkloadStatus status);
}
