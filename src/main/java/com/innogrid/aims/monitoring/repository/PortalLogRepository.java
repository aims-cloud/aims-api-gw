package com.innogrid.aims.monitoring.repository;

import java.time.OffsetDateTime;
import java.util.Optional;

import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 포털 로그 저장·조회. 목록 필터는 {@link PortalLogSpecifications} 로 조합한다.
 */
public interface PortalLogRepository extends JpaRepository<PortalLogEntry, Long>, JpaSpecificationExecutor<PortalLogEntry> {

    /** ACTION 작업 완료 갱신용 — 연관 원장 키로 아직 진행중인 행을 찾는다 */
    Optional<PortalLogEntry> findFirstByRefTypeAndRefIdAndResultOrderByStartedAtDesc(String refType, String refId,
                                                                                PortalLogResult result);

    /**
     * 보존 배치용 — 지정 타입({@code SYSTEM})의 오래된 행을 {@code limit} 건까지 지운다. 호출자가 트랜잭션을 연다.
     * JPQL 은 DELETE 에 LIMIT 를 못 써 네이티브로 두었다(PostgreSQL). 인덱스 {@code (type, started_at)} 을 탄다.
     */
    @Modifying
    @Query(value = """
            DELETE FROM portal_logs WHERE id IN (
                SELECT id FROM portal_logs WHERE type = :type AND started_at < :cutoff ORDER BY started_at LIMIT :limit)
            """, nativeQuery = true)
    int deleteSystemOlderThan(@Param("type") String type, @Param("cutoff") OffsetDateTime cutoff, @Param("limit") int limit);

    /** 보존 배치용 — 지정 타입을 뺀 나머지 타입의 오래된 행을 {@code limit} 건까지 지운다. 인덱스 {@code (started_at)} 을 탄다 */
    @Modifying
    @Query(value = """
            DELETE FROM portal_logs WHERE id IN (
                SELECT id FROM portal_logs WHERE type <> :type AND started_at < :cutoff ORDER BY started_at LIMIT :limit)
            """, nativeQuery = true)
    int deleteOthersOlderThan(@Param("type") String type, @Param("cutoff") OffsetDateTime cutoff, @Param("limit") int limit);
}
