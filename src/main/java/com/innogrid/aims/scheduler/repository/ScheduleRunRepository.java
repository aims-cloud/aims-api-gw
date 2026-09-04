package com.innogrid.aims.scheduler.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.scheduler.entity.ScheduleRun;

public interface ScheduleRunRepository extends JpaRepository<ScheduleRun, Long> {

    Page<ScheduleRun> findByScheduleId(Long scheduleId, Pageable pageable);

    Optional<ScheduleRun> findByIdAndScheduleId(Long id, Long scheduleId);

    List<ScheduleRun> findByScheduleId(Long scheduleId);

    /** 보존 기간이 지난 실행 이력. 러너가 하루 한 번 정리한다. */
    List<ScheduleRun> findByStartedAtBefore(OffsetDateTime cutoff);

    /**
     * 끝나지 않은 실행 이력. 기동 회수가 쓴다.
     * 전역 상태 조회가 아니라 <b>회수 대상 스케줄로 한정</b>한다 — 상태 인덱스가 없고 이력은 90일치가 쌓인다.
     */
    List<ScheduleRun> findByScheduleIdInAndStatus(List<Long> scheduleIds, ScheduleRun.RunStatus status);
}
