package com.innogrid.aims.scheduler.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.scheduler.entity.Workload;

/** 워크로드 저장소. 페이징 목록({@code /workloads/page})의 선택 필터는 {@link JpaSpecificationExecutor}로 조합한다. */
public interface WorkloadRepository extends JpaRepository<Workload, Long>, JpaSpecificationExecutor<Workload> {
    List<Workload> findByStatusOrderByCreatedAtDesc(Workload.WorkloadStatus status);
    List<Workload> findByAssignedNodeIdOrderByScheduledAtDesc(String nodeId);
    List<Workload> findAllByOrderByCreatedAtDesc();

    /** 예약(이미 배치된 워크로드) 집계에 쓴다. */
    List<Workload> findByStatusIn(Collection<Workload.WorkloadStatus> statuses);

    /**
     * 스케줄 실행이 배치할 후보 워크로드.
     *
     * <p>재시도 횟수가 상한에 이른 워크로드는 제외한다. 자원이 영영 모자란 워크로드가
     * 매 실행마다 다시 시도되어 이력을 채우는 것을 막는다. 값이 null인 기존 행은
     * 0회로 본다.</p>
     *
     * <p><b>정렬은 여기서 하지 않는다.</b> 우선순위가 문자열로 저장돼 있어 SQL의
     * {@code order by}로는 CRITICAL·HIGH·LOW·NORMAL 순(사전순)이 되어 버린다.
     * 호출부가 열거형 순서로 다시 정렬한다.</p>
     *
     * @param matchNullType 리소스 종류가 없는 기존 행까지 포함할지 (VM 대상 실행에서 true)
     */
    @Query("""
           select w from Workload w
            where w.status in :statuses
              and (w.resourceType = :resourceType
                   or (:matchNullType = true and w.resourceType is null))
              and (w.retryCount is null or w.retryCount < :maxRetryCount)
              and (w.lastFailedAt is null or w.lastFailedAt <= :retryableBefore)
           """)
    List<Workload> findSchedulingCandidates(
            @Param("statuses") Collection<Workload.WorkloadStatus> statuses,
            @Param("resourceType") Workload.ResourceType resourceType,
            @Param("matchNullType") boolean matchNullType,
            @Param("maxRetryCount") int maxRetryCount,
            @Param("retryableBefore") OffsetDateTime retryableBefore);

    /**
     * 재시도 상한에 걸려 대상에서 빠진 워크로드. 일괄 재개가 되살릴 후보다.
     *
     * <p>리소스 종류와 기간은 <b>선택</b>이지만 호출부가 둘 중 하나는 반드시 준다 — 조건 없는
     * 전체 일괄은 실수로 수백 건을 되살리는 사고 경로라 서비스에서 막는다.</p>
     *
     * <p><b>널 파라미터를 쓰지 않는다.</b> {@code (:from is null or ...)} 형태는 H2에서는 통과하지만
     * PostgreSQL이 "could not determine data type of parameter"로 거절한다 — 값이 없는 자리의 타입을
     * 추론할 수 없기 때문이다. 그래서 호출부가 경계값과 깃발을 채워 넘기고, 여기서는 전부
     * 값이 있는 비교만 한다.</p>
     */
    @Query("""
           select w from Workload w
            where w.status = :status
              and w.retryCount is not null and w.retryCount >= :maxRetryCount
              and (:anyType = true
                   or w.resourceType = :resourceType
                   or (:matchNullType = true and w.resourceType is null))
              and ((:anyPeriod = true and w.lastFailedAt is null)
                   or (w.lastFailedAt >= :from and w.lastFailedAt <= :to))
            order by w.lastFailedAt asc nulls last, w.id asc
           """)
    List<Workload> findRetryExhausted(
            @Param("status") Workload.WorkloadStatus status,
            @Param("maxRetryCount") int maxRetryCount,
            @Param("anyType") boolean anyType,
            @Param("resourceType") Workload.ResourceType resourceType,
            @Param("matchNullType") boolean matchNullType,
            @Param("anyPeriod") boolean anyPeriod,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    /**
     * 재시도 여유는 있으나 <b>백오프가 지나지 않아</b> 이번 대상에서 빠진 워크로드 수.
     *
     * <p>소진과 구분하기 위해 따로 센다. 소진은 사람이 되살려야 풀리지만 이쪽은 시간이 지나면
     * 저절로 풀리므로, 실행 결과를 실패로 적으면 안 된다.</p>
     */
    @Query("""
           select count(w) from Workload w
            where w.status in :statuses
              and (w.resourceType = :resourceType
                   or (:matchNullType = true and w.resourceType is null))
              and (w.retryCount is null or w.retryCount < :maxRetryCount)
              and w.lastFailedAt is not null and w.lastFailedAt > :retryableBefore
           """)
    long countRetryWaiting(
            @Param("statuses") Collection<Workload.WorkloadStatus> statuses,
            @Param("resourceType") Workload.ResourceType resourceType,
            @Param("matchNullType") boolean matchNullType,
            @Param("maxRetryCount") int maxRetryCount,
            @Param("retryableBefore") OffsetDateTime retryableBefore);

    /**
     * 재시도 상한에 걸려 대상에서 빠진 워크로드 수.
     * 실행 결과 메시지에 함께 적어, 대상 0건이 "할 일이 없어서"인지
     * "전부 소진되어서"인지 구분할 수 있게 한다.
     */
    @Query("""
           select count(w) from Workload w
            where w.status = :status
              and (w.resourceType = :resourceType
                   or (:matchNullType = true and w.resourceType is null))
              and w.retryCount is not null and w.retryCount >= :maxRetryCount
           """)
    long countRetryExhausted(
            @Param("status") Workload.WorkloadStatus status,
            @Param("resourceType") Workload.ResourceType resourceType,
            @Param("matchNullType") boolean matchNullType,
            @Param("maxRetryCount") int maxRetryCount);

    /**
     * 상태가 {@code expected} 중 하나일 때만 {@code next}로 옮긴다 — 스케줄 실행의 배치 저장이 그 사이 다른 실행·단건 배치가
     * 커밋한 배치를 덮지 않게 하는 조건부 갱신이다.
     *
     * <p>호출자의 트랜잭션 안에서만 부른다({@code MANDATORY}). 갱신한 행의 잠금이 그 트랜잭션이 끝날 때까지 남아야, 같은 행을
     * 노리는 다른 실행이 이 배치가 커밋된 뒤의 상태로 조건을 다시 본다. 트랜잭션 밖에서 부르면 잠금이 곧바로 풀려 막지 못한다.</p>
     *
     * <p>스케줄 실행의 배치 저장은 1행이면 같은 트랜잭션에서 그 행을 다시 읽어 재시도 회계를 이어받은 뒤 저장한다.</p>
     *
     * @return 갱신한 행 수. 0이면 그 사이 상태가 바뀌었거나 지워졌다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("update Workload w set w.status = :next where w.id = :id and w.status in :expected")
    int updateStatusIfIn(@Param("id") Long id,
                         @Param("expected") Collection<Workload.WorkloadStatus> expected,
                         @Param("next") Workload.WorkloadStatus next);

    /**
     * 상태가 {@code expected} 중 하나일 때만 행을 잡는다(값은 바꾸지 않는 조건부 갱신) — 워크로드 편집이 읽은 뒤 저장하기 전에
     * 그 사이 단건 배치·스케줄 실행이 커밋한 배치와 삭제를 편집 전 값으로 덮지 않게 한다.
     *
     * <p>{@link #updateStatusIfIn}처럼 호출자의 트랜잭션 안에서만 부른다({@code MANDATORY}) — 잡은 행의 잠금이 그 트랜잭션이
     * 끝날 때까지 남아야 그 사이 다른 쪽이 행을 바꾸지 못한다. 실행 전에 미뤄 둔 쓰기를 내보내고 실행 뒤 영속성 컨텍스트를
     * 비우므로, 호출자는 반드시 다시 읽은 엔티티에 값을 반영해야 한다(먼저 읽은 사본은 분리된다).</p>
     *
     * @return 잡은 행 수. 0이면 그 사이 상태가 바뀌었거나 지워졌다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Workload w set w.status = w.status where w.id = :id and w.status in :expected")
    int lockIfStatusIn(@Param("id") Long id,
                       @Param("expected") Collection<Workload.WorkloadStatus> expected);

    /**
     * 지금 상태 — 조건부 갱신이 0 행이었을 때 건너뛴 사유를 적으려고 읽는다. 엔티티가 아니라 값만 읽는다(영속성 컨텍스트에
     * 올리지 않는다).
     */
    @Query("select w.status from Workload w where w.id = :id")
    Optional<Workload.WorkloadStatus> findStatusById(@Param("id") Long id);
}
