package com.innogrid.aims.scheduler.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.innogrid.aims.scheduler.entity.PlacementDispatch;

/**
 * 디스패치 기록 조회. 선택 필터(상태·워크로드)는 {@link JpaSpecificationExecutor} 로 조합한다
 * — {@code :x is null or ...} 형태는 Postgres 에서 타입 추론이 깨진다.
 */
public interface PlacementDispatchRepository extends JpaRepository<PlacementDispatch, Long>,
        JpaSpecificationExecutor<PlacementDispatch> {

    Optional<PlacementDispatch> findByIdempotencyKey(String idempotencyKey);

    /** 워크로드의 기록을 최신순으로 — 요청 시각이 같으면 나중에 만든(id 가 큰) 기록이 앞. 첫 행을 「최신 결정의 기록」으로 쓴다. */
    List<PlacementDispatch> findByWorkloadIdOrderByRequestedAtDescIdDesc(Long workloadId);

    List<PlacementDispatch> findByScheduleRunIdOrderByRequestedAtAsc(Long scheduleRunId);

    /** 여러 워크로드의 기록 가운데 한 상태인 것 — 예약 반영이 실행 중 워크로드의 시작 시각을 한 번에 읽는다. */
    List<PlacementDispatch> findByWorkloadIdInAndStatus(Collection<Long> workloadIds, PlacementDispatch.DispatchStatus status);

    long deleteByWorkloadId(Long workloadId);
}
