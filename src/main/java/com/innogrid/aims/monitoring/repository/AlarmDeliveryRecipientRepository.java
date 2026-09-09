package com.innogrid.aims.monitoring.repository;

import com.innogrid.aims.monitoring.entity.AlarmDeliveryRecipient;
import org.springframework.data.jpa.repository.JpaRepository;

/** 발송기가 수신자 1행의 결과를 짧은 트랜잭션으로 갱신할 때 쓴다 */
public interface AlarmDeliveryRecipientRepository extends JpaRepository<AlarmDeliveryRecipient, Long> {
}
