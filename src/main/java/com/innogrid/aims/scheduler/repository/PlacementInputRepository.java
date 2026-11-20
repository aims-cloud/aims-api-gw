package com.innogrid.aims.scheduler.repository;

import java.time.OffsetDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.scheduler.entity.PlacementInput;

/** 단건 배치 입력 저장소. 보존 정리가 저장 시각 기준으로 지운다. */
public interface PlacementInputRepository extends JpaRepository<PlacementInput, Long> {

    /**
     * {@code cutoff} 전에 저장한 입력을 한 번에 지운다. 행을 읽어 들이지 않는다 — payload가 크다.
     *
     * <p>보존 정리({@code ScheduleRunner})는 트랜잭션 없이 돌므로 {@link ScheduleRunInputRepository}처럼 쿼리 메서드에
     * 직접 트랜잭션을 건다.</p>
     */
    @Transactional
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("delete from PlacementInput i where i.capturedAt < :cutoff")
    int deleteCapturedBefore(@Param("cutoff") OffsetDateTime cutoff);
}
