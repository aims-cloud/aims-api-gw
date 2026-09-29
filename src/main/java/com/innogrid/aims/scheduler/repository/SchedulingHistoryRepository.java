package com.innogrid.aims.scheduler.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.innogrid.aims.scheduler.entity.SchedulingHistory;

public interface SchedulingHistoryRepository extends JpaRepository<SchedulingHistory, Long>,
        JpaSpecificationExecutor<SchedulingHistory> {

    /**
     * 워크로드별 이력. <b>반드시 상한을 받는다</b> — 이 표는 지워지지 않아 단조 증가하므로
     * 상한 없는 조회를 남겨 두면 언젠가 응답 하나가 표 전체가 된다.
     */
    List<SchedulingHistory> findByWorkloadIdOrderByCreatedAtDesc(Long workloadId, Limit limit);

    /** 전체 이력 최신순. 위와 같은 이유로 상한이 필수다. */
    List<SchedulingHistory> findAllByOrderByCreatedAtDesc(Limit limit);

    /**
     * 실행 1건이 만든 결정들. 실행 상세에서 쓴다. 결정 시각이 같으면 {@code id} 오름차순 —
     * 배치 이력 페이징 {@code sort=createdAt,asc} 와 행 순서가 같다.
     */
    List<SchedulingHistory> findByScheduleRunIdOrderByCreatedAtAscIdAsc(Long scheduleRunId);

    /**
     * 실행 이력을 지울 때 연결을 끊을 대상.
     * 외래키가 없어 DB 가 정리해 주지 않으므로 코드에서 직접 찾아 끊는다.
     */
    List<SchedulingHistory> findByScheduleRunIdIn(Collection<Long> scheduleRunIds);
}
