package com.innogrid.aims.scheduler.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.ScheduleRun;

/**
 * 목록 조회는 {@link JpaSpecificationExecutor} 로 처리한다.
 * 검색어·리소스 종류·사용 여부가 전부 선택 조건이라, JPQL 에 null 비교를 늘어놓는 것보다
 * 조건을 조합하는 편이 읽기 쉽고 파라미터 타입 추론 문제도 없다.
 */
public interface ScheduleRepository extends JpaRepository<Schedule, Long>, JpaSpecificationExecutor<Schedule> {

    Optional<Schedule> findByScheduleId(String scheduleId);

    boolean existsByScheduleId(String scheduleId);

    /** 이 정책을 가리키는 스케줄이 있는지 — 정책 삭제 전에 본다. */
    boolean existsByPolicyId(Long policyId);

    /** 자동 실행 대상. 러너가 폴링할 때 쓴다. */
    List<Schedule> findByEnabledTrueAndAutoRunTrueAndNextRunAtLessThanEqual(OffsetDateTime now);

    /**
     * 실행 권한을 원자적으로 잡는다.
     *
     * <p>이미 RUNNING 이면 0 을 돌려준다. 자동 실행과 즉시 실행이 겹쳤을 때 한쪽만
     * 진행하게 만드는 장치다. 앱이 한 대라 DB 락만으로 충분하다.</p>
     *
     * @return 갱신된 행 수. 1 이면 이 호출이 실행 권한을 얻었다
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           update Schedule s
              set s.lastRunStatus = :running,
                  s.lastRunAt = :startedAt,
                  s.lastRunFinishedAt = null,
                  s.lastRunMessage = null
            where s.id = :id
              and (s.lastRunStatus is null or s.lastRunStatus <> :running)
           """)
    int acquireForRun(@Param("id") Long id,
                      @Param("running") ScheduleRun.RunStatus running,
                      @Param("startedAt") OffsetDateTime startedAt);
}
