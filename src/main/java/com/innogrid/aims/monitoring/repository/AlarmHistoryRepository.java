package com.innogrid.aims.monitoring.repository;

import java.util.Optional;

import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmHistoryStatus;
import com.innogrid.aims.monitoring.entity.AlarmHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * 알람 발생 이력 원장(alarm_history). 목록 필터는 {@link AlarmHistorySpecifications} 로 조합한다.
 */
public interface AlarmHistoryRepository extends JpaRepository<AlarmHistory, Long>,
        JpaSpecificationExecutor<AlarmHistory> {

    /** 외부 알람 멱등 처리 — 같은 fingerprint 의 미해소 행 */
    Optional<AlarmHistory> findFirstByFingerprintAndStatusNotOrderByOccurredAtDesc(String fingerprint,
                                                                                         AlarmHistoryStatus excluded);
}
