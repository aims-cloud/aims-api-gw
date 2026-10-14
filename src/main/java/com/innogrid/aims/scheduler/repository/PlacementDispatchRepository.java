package com.innogrid.aims.scheduler.repository;

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

    List<PlacementDispatch> findByWorkloadIdOrderByRequestedAtDesc(Long workloadId);

    List<PlacementDispatch> findByScheduleRunIdOrderByRequestedAtAsc(Long scheduleRunId);

    long deleteByWorkloadId(Long workloadId);
}
