package com.innogrid.aims.monitoring.dto.alarm.alarmPolicy;

import java.time.OffsetDateTime;
import java.util.List;

import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleAreaListItemResponse.AreaKind;

/**
 * 알람 정책 상세 — 하단 상세 탭·상세 페이지 상단 표·편집 마법사 초기값에 공용.
 * criterion 은 표시용, metricType/metric/operator/threshold 는 편집 폼용 원값이다.
 * createdBy 는 표시명(users.name, 없으면 이메일).
 */
public record AlarmPolicyDetailResponse(
        Long policyId,
        AlarmPolicyType alarmType,
        String policyName,
        String roleName,
        String areaId,
        AreaKind areaKind,
        String resourceType,
        AlarmTargetScope targetScope,
        List<String> targetResourceIds,     // targetScope=INDIVIDUAL 일 때 대상 자원 id
        String metricType,                  // CPU | Memory | File System | GPU (카탈로그 유형 라벨)
        String metric,                      // 카탈로그 코드 (예: CPU_USED_PERCENT)
        AlarmConditionOperator operator,
        double threshold,
        String criterion,                   // 기준 (예: CPU Used > 80%)
        AlarmLevel level,
        List<AlarmChannel> channels,        // 알림 방식 (예: Email + SMS)
        int durationSeconds,                // 조건 지속 시간 (즉시=0)
        List<AlarmPolicyRecipientResponse> recipients,
        boolean enabled,
        String createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String description
) {
    public AlarmPolicyDetailResponse {
        channels = channels == null ? List.of() : List.copyOf(channels);
        targetResourceIds = targetResourceIds == null ? List.of() : List.copyOf(targetResourceIds);
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
    }
}
