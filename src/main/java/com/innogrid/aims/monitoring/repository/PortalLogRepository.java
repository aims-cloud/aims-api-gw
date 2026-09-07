package com.innogrid.aims.monitoring.repository;

import java.util.Optional;

import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * 포털 로그 저장·조회. 목록 필터는 {@link PortalLogSpecifications} 로 조합한다.
 */
public interface PortalLogRepository extends JpaRepository<PortalLogEntry, Long>, JpaSpecificationExecutor<PortalLogEntry> {

    /** ACTION 작업 완료 갱신용 — 연관 원장 키로 아직 진행중인 행을 찾는다 */
    Optional<PortalLogEntry> findFirstByRefTypeAndRefIdAndResultOrderByStartedAtDesc(String refType, String refId,
                                                                                PortalLogResult result);
}
