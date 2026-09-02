package com.innogrid.aims.scheduler.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.innogrid.aims.scheduler.entity.SchedulingHistory;

public interface SchedulingHistoryRepository extends JpaRepository<SchedulingHistory, Long>,
        JpaSpecificationExecutor<SchedulingHistory> {

    List<SchedulingHistory> findByWorkloadIdOrderByCreatedAtDesc(Long workloadId);

    List<SchedulingHistory> findAllByOrderByCreatedAtDesc();

    /** 실행 1건이 만든 결정들. 실행 상세에서 쓴다. */
    List<SchedulingHistory> findByScheduleRunIdOrderByCreatedAtAsc(Long scheduleRunId);

    /**
     * 실행 이력을 지울 때 연결을 끊을 대상.
     * 외래키가 없어 DB 가 정리해 주지 않으므로 코드에서 직접 찾아 끊는다.
     */
    List<SchedulingHistory> findByScheduleRunIdIn(Collection<Long> scheduleRunIds);
}
