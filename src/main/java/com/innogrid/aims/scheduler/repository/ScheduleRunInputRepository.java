package com.innogrid.aims.scheduler.repository;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.scheduler.entity.ScheduleRunInput;

/** 스케줄 실행 입력 저장소. 실행 이력을 지우는 자리가 같은 실행 id로 함께 지운다. */
public interface ScheduleRunInputRepository extends JpaRepository<ScheduleRunInput, Long> {

    Optional<ScheduleRunInput> findByScheduleRunId(Long scheduleRunId);

    /**
     * 실행 id 목록의 입력을 한 번에 지운다. 행을 읽어 들이지 않는다 — payload가 크다.
     *
     * <p>트랜잭션을 여기에 건다. 보존 정리({@code ScheduleRunner})는 트랜잭션 없이 돌므로, {@code ScheduleRepository}의
     * 갱신 쿼리처럼 쿼리 메서드에 직접 건다. 호출자의 영속성 컨텍스트는 건드리지 않는다.</p>
     */
    @Transactional
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("delete from ScheduleRunInput i where i.scheduleRunId in :runIds")
    int deleteByScheduleRunIds(@Param("runIds") Collection<Long> runIds);
}
