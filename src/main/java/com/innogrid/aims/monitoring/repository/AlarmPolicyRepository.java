package com.innogrid.aims.monitoring.repository;

import java.util.List;

import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyType;
import com.innogrid.aims.monitoring.entity.AlarmPolicy;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * 알람 정책 원장(alarm_policies). 목록 필터는 {@link AlarmPolicySpecifications} 로 조합한다.
 */
public interface AlarmPolicyRepository extends JpaRepository<AlarmPolicy, Long>, JpaSpecificationExecutor<AlarmPolicy> {

    /** 평가기 — 활성 정책 전부(대상 자원 포함). 주기마다 한 번 읽는다 */
    @EntityGraph(attributePaths = "targetResourceIds")
    List<AlarmPolicy> findByEnabledTrueAndType(AlarmPolicyType type);
}
