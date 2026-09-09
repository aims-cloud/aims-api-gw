package com.innogrid.aims.monitoring.repository;

import java.util.List;
import java.util.Optional;

import com.innogrid.aims.monitoring.entity.AlarmDelivery;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlarmDeliveryRepository extends JpaRepository<AlarmDelivery, Long> {

    /** 알람 1건의 전송 이력(행 확장 서브테이블) — 수신자까지 한 번에 */
    @EntityGraph(attributePaths = "recipients")
    List<AlarmDelivery> findByHistoryIdOrderByIdAsc(Long historyId);

    /** 메시지 상세 — 알람에 속한 전송 1건. 다른 알람의 deliveryId 면 empty(존재를 드러내지 않는다) */
    @EntityGraph(attributePaths = "recipients")
    Optional<AlarmDelivery> findByIdAndHistoryId(Long id, Long historyId);

    /** 발송기 — 수신자까지 한 번에 */
    @EntityGraph(attributePaths = "recipients")
    Optional<AlarmDelivery> findWithRecipientsById(Long id);
}
