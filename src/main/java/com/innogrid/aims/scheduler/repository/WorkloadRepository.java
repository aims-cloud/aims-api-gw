package com.innogrid.aims.scheduler.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 스케줄 실행이 배치할 후보 워크로드.
     *
     * <p>재시도 횟수가 상한에 이른 워크로드는 제외한다. 자원이 영영 모자란 워크로드가
     * 매 실행마다 다시 시도되어 이력을 채우는 것을 막는다. 값이 null 인 기존 행은
     * 0회로 본다.</p>
     *
     * <p><b>정렬은 여기서 하지 않는다.</b> 우선순위가 문자열로 저장돼 있어 SQL 의
     * {@code order by} 로는 CRITICAL·HIGH·LOW·NORMAL 순(사전순)이 되어 버린다.
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
     * 재시도 상한에 걸려 대상에서 빠진 워크로드 수.
     * 실행 결과 메시지에 함께 적어, 대상 0건이 "할 일이 없어서"인지
     * "전부 소진되어서"인지 구분할 수 있게 한다.
     */
    /**
     * 재시도 상한에 걸려 대상에서 빠진 워크로드. 일괄 재개가 되살릴 후보다.
     *
     * <p>리소스 종류와 기간은 <b>선택</b>이지만 호출부가 둘 중 하나는 반드시 준다 — 조건 없는
     * 전체 일괄은 실수로 수백 건을 되살리는 사고 경로라 서비스에서 막는다.</p>
     */
    /**
     * <p><b>널 파라미터를 쓰지 않는다.</b> {@code (:from is null or ...)} 형태는 H2 에서는 통과하지만
     * PostgreSQL 이 "could not determine data type of parameter" 로 거절한다 — 값이 없는 자리의 타입을
     * 추론할 수 없기 때문이다. 그래서 호출부가 경계값과 깃발을 채워 넘기고, 여기서는 전부
     * 값이 있는 비교만 한다.</p>
     */
    @Query("""
           select w from Workload w
            where w.status = :status
              and w.retryCount is not null and w.retryCount >= :maxRetryCount
              and (:anyType = true or w.resourceType = :resourceType)
              and ((:anyPeriod = true and w.lastFailedAt is null)
                   or (w.lastFailedAt >= :from and w.lastFailedAt <= :to))
            order by w.lastFailedAt asc nulls last, w.id asc
           """)
    List<Workload> findRetryExhausted(
            @Param("status") Workload.WorkloadStatus status,
            @Param("maxRetryCount") int maxRetryCount,
            @Param("anyType") boolean anyType,
            @Param("resourceType") Workload.ResourceType resourceType,
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
}
