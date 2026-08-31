package com.innogrid.aims.monitoring.dto.alarm.alarmHistory;

import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmChannel;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmTargetType;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 메시지 상세 모달 — 전송 이력의 이메일/SMS 버튼으로 열린다.
 * totalRecipientCount·resultSummary 는 조직 대상일 때만 채워진다(개인은 null).
 */
public record AlarmMessageDetailResponse(
        String policyName,
        String resourceName,
        AlarmChannel channel,
        AlarmTargetType targetType,
        String targetName,
        Integer totalRecipientCount,
        ResultSummary resultSummary,
        String messageBody,             // 발송 시점에 치환된 메시지 본문
        List<Recipient> recipients
) {
    /** 조직 대상의 전송 결과 요약 — 성공/실패 건수 */
    public record ResultSummary(long successCount, long failedCount) {}

    /** 전송 대상 목록 1행 — 수신 주소는 채널에 따라 이메일 또는 전화번호, failReason 은 실패 툴팁용 */
    public record Recipient(String name, String id, String address,
                            AlarmDeliveryStatus status, String failReason, OffsetDateTime sentAt) {}

    public AlarmMessageDetailResponse {
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
    }
}
