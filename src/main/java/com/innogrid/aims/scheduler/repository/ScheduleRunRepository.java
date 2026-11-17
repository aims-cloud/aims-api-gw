package com.innogrid.aims.scheduler.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.innogrid.aims.scheduler.entity.ScheduleRun;

/**
 * 실행 이력 저장소. 목록 조회(스케줄 + 선택 필터 상태·주체·시작 시각 창)는
 * {@link JpaSpecificationExecutor}로 조합한다 — 스케줄·배치 이력·전달 기록과 같은 방식이다.
 */
public interface ScheduleRunRepository extends JpaRepository<ScheduleRun, Long>, JpaSpecificationExecutor<ScheduleRun> {

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
