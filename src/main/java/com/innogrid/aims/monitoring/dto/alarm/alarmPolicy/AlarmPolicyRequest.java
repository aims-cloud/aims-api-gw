package com.innogrid.aims.monitoring.dto.alarm.alarmPolicy;

import java.util.List;

/**
 * 알람 정책 생성/편집 요청 — 정책 생성 위저드(대상 선택 → 조건 설정 → 전송 설정 → 요약) 기준.
 * 로그 알람(LOG)의 조건 필드는 화면 설계 확정 전 잠정(logLevel·logKeyword)이다.
 */
public record AlarmPolicyRequest(
        AlarmPolicyType alarmType,
        String policyName,
        String roleName,                  // 적용 범위 — 역할
        String areaId,            // 적용 범위 — 자원 영역 (MDC: mdcId, Namespace: TB:{nsId}, 역할 패널 areaId 와 동일)
        String resourceType,            // 대상 자원 유형 코드 node|vm|gpu (AlarmResourceType) — 한글 검증 메시지를 위해 문자열로 받아 서비스에서 판정
        AlarmTargetScope targetScope,
        List<String> targetResourceIds, // targetScope=INDIVIDUAL 일 때 대상 자원
        String description,
        Condition condition,
        Delivery delivery
) {
    /** 조건 설정 — METRIC: metricType/metric/operator/threshold, LOG: logLevel/logKeyword(잠정) */
    public record Condition(
            String metricType,          // 메트릭 유형 (예: CPU)
            String metric,              // 메트릭 (예: CPU Used(%))
            AlarmConditionOperator operator,
            Double threshold,
            AlarmLevel level,
            AlarmLevel logLevel,        // LOG 알람 전용 (잠정)
            String logKeyword           // LOG 알람 전용 (잠정)
    ) {}

    /** 전송 설정 — 조건 지속 시간(초, 즉시=0), 알림 방식, 수신자(조직/개인) */
    public record Delivery(
            int durationSeconds,
            List<AlarmChannel> channels,
            List<Recipient> recipients
    ) {
        public Delivery {
            channels = channels == null ? List.of() : List.copyOf(channels);
            recipients = recipients == null ? List.of() : List.copyOf(recipients);
        }
    }

    /** 수신자 1건 — 조직 또는 개인 */
    public record Recipient(AlarmTargetType targetType, String targetId) {}

    public AlarmPolicyRequest {
        targetResourceIds = targetResourceIds == null ? List.of() : List.copyOf(targetResourceIds);
    }
}
