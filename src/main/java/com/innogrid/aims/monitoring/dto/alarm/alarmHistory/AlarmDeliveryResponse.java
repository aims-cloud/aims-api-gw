package com.innogrid.aims.monitoring.dto.alarm.alarmHistory;

import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmChannel;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmTargetType;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 알람 1건의 전송 이력 1행 (목록 행 확장 서브테이블) — 전송 대상(개인/조직) 1건과 채널별 결과.
 * 전송 원장은 대상 × 채널이 한 행이라 채널마다 {@code deliveryId} 가 다르고, 메시지 상세는 그 id 로 연다.
 * 개인 대상은 채널별 단일 상태({@code status}), 조직 대상은 채널별 성공/전송중/실패 카운트가 채워진다.
 */
public record AlarmDeliveryResponse(
        AlarmTargetType targetType,
        String targetId,                // 개인 = users.id, 조직 = 조직명
        String targetName,              // 예: 홍길동, 클라우드 기획팀, (삭제된 사용자)
        List<ChannelSummary> channels
) {
    /**
     * 채널 1개의 전송 결과 — 개인: status 사용(카운트 null), 조직: successCount/sendingCount/failedCount 사용(status null).
     * sentAt 은 수신자 중 마지막 시도 시각(아직 시도 전이면 null).
     */
    public record ChannelSummary(Long deliveryId, AlarmChannel channel, AlarmDeliveryStatus status,
                                 Long successCount, Long sendingCount, Long failedCount,
                                 int recipientCount, OffsetDateTime sentAt) {}

    public AlarmDeliveryResponse {
        channels = channels == null ? List.of() : List.copyOf(channels);
    }
}
