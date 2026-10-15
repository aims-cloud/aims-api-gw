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

    boolean existsByScheduleId(String scheduleId);

    /** 이 정책을 가리키는 스케줄이 있는지 — 정책 삭제 전에 본다. */
    boolean existsByPolicyId(Long policyId);

    /**
     * 자동 실행 대상. 러너가 폴링할 때 쓴다.
     *
     * <p><b>이미 실행 중인 스케줄은 뺀다.</b> 한 번 실행이 폴링 주기보다 오래 걸리면 그동안의 폴이 같은 스케줄을
     * 계속 큐에 쌓고, 원래 실행이 끝나 상태가 풀리는 순간 쌓인 것들이 <b>차례로 정상 실행</b>된다 — cron 한 틱에
     * 실행이 두세 번 일어나고, 배치가 실패하는 워크로드는 재시도 상한을 한 틱에 다 태운다.</p>
     *
     * <p><b>파생 쿼리 대신 JPQL 을 쓰는 이유</b>: {@code …LastRunStatusNot(RUNNING)} 으로 쓰면 SQL 이
     * {@code last_run_status <> 'RUNNING'} 이 되는데, 이 컬럼은 nullable 이라 <b>한 번도 실행된 적 없는 스케줄이
     * 전부 탈락</b>한다(NULL 비교는 UNKNOWN). 새로 만든 스케줄이 영영 자동 실행되지 않는 결함이라 NULL 을 명시한다.</p>
     */
    @Query("""
           select s from Schedule s
            where s.enabled = true
              and s.autoRun = true
              and s.nextRunAt <= :now
              and (s.lastRunStatus is null or s.lastRunStatus <> :running)
            order by s.nextRunAt asc
           """)
    List<Schedule> findDueForRun(@Param("now") OffsetDateTime now,
                                 @Param("running") ScheduleRun.RunStatus running);

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

    /**
     * 실행 결과를 기록하고 <b>실행 권한을 푼다</b>.
     *
     * <p>엔티티 저장(merge)이 아니라 전용 UPDATE 인 이유가 셋이다.
     * <ol>
     *   <li><b>락 해제를 다른 작업에 종속시키지 않는다.</b> 엔티티로 저장하면 cron 재계산·이력 저장이 함께 얽혀
     *       그중 하나만 실패해도 실행 권한이 영영 안 풀린다.</li>
     *   <li><b>실행 중 편집을 되돌리지 않는다.</b> 폴러가 읽어 워커로 넘긴 엔티티는 detached 라, 저장하면 이름·cron·
     *       사용 여부까지 <b>실행 시작 시점 값으로 되돌아간다</b> — 폭주하는 스케줄을 끄려는 조작이 조용히 취소된다.</li>
     *   <li><b>다음 실행 시각을 반드시 함께 민다.</b> 권한만 풀고 {@code nextRunAt} 을 과거로 두면 폴러가 주기마다
     *       같은 실행을 다시 던진다.</li>
     * </ol>
     *
     * <p><b>전파는 기본(REQUIRED)이다.</b> 실행 서비스는 의도적으로 {@code @Transactional} 이 없어 운영에서는
     * 트랜잭션 밖에서 돌고, 그래서 이 호출이 곧 자기 트랜잭션이 된다 — 롤백에 휩쓸릴 바깥 트랜잭션이 없다.
     * {@code REQUIRES_NEW} 를 걸면 실행을 트랜잭션으로 감싼 쪽(테스트)에서 <b>바깥이 쥔 행 락을 기다리다</b>
     * 쓰기가 보이지 않게 된다.</p>
     *
     * <p>영속성 컨텍스트는 건드리지 않는다({@code clearAutomatically=false}) — 호출자가 들고 있는 다른 엔티티까지
     * 떼어내면 미저장 변경이 사라진다.</p>
     */
    @Transactional
    @Modifying(clearAutomatically = false, flushAutomatically = false)
    @Query("""
           update Schedule s
              set s.lastRunStatus = :status,
                  s.lastRunAt = :startedAt,
                  s.lastRunFinishedAt = :finishedAt,
                  s.lastRunMessage = :message,
                  s.nextRunAt = :nextRunAt
            where s.id = :id
           """)
    int recordRunResult(@Param("id") Long id,
                        @Param("status") ScheduleRun.RunStatus status,
                        @Param("startedAt") OffsetDateTime startedAt,
                        @Param("finishedAt") OffsetDateTime finishedAt,
                        @Param("message") String message,
                        @Param("nextRunAt") OffsetDateTime nextRunAt);

    /** 실행 권한이 잡힌 채 남아 있는 스케줄. 기동 회수가 쓴다. */
    List<Schedule> findByLastRunStatus(ScheduleRun.RunStatus status);

    /**
     * 다음 실행 시각을 계산할 때 쓰는 <b>현재</b> cron. 실행 도중 사용자가 바꿨을 수 있어 DB 값을 읽는다.
     *
     * <p>엔티티가 아니라 값만 읽는다 — {@code findById} 로 엔티티를 로드하면 영속성 컨텍스트에 들어가고,
     * 그 뒤 벌크 UPDATE 는 그 사본을 갱신하지 않아 <b>같은 트랜잭션 안의 재조회가 낡은 값을 돌려준다.</b></p>
     */
    @Query("select s.cronExpression from Schedule s where s.id = :id")
    Optional<String> findCronExpressionById(@Param("id") Long id);

    /** 지금 자동 실행 대상인가(사용 중 + 자동 실행). 위와 같은 이유로 값만 읽는다. */
    @Query("select case when (s.enabled = true and s.autoRun = true) then true else false end"
           + " from Schedule s where s.id = :id")
    Optional<Boolean> findAutoRunnableById(@Param("id") Long id);
}
