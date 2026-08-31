package com.innogrid.aims.monitoring.dto.alarm.alarmHistory;

import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmChannel;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmTargetType;

import java.util.List;

/**
 * 알람 1건의 전송 이력 1행 (목록 행 확장 서브테이블).
 * 개인 대상은 채널별 단일 상태, 조직 대상은 채널별 성공/전송중/실패 카운트가 채워진다.
 */
public record AlarmDeliveryResponse(
        Long deliveryId,
        AlarmTargetType targetType,
        String targetName,              // 예: 홍길동(gildong_hong), 클라우드 기획팀
        String targetId,
        List<ChannelSummary> channels
) {
    /**
     * 채널 1개의 전송 결과 — 개인: status 사용(카운트 null),
     * 조직: successCount/sendingCount/failedCount 사용(status null).
     */
    public record ChannelSummary(AlarmChannel channel, AlarmDeliveryStatus status,
                                 Long successCount, Long sendingCount, Long failedCount) {}

    public AlarmDeliveryResponse {
        channels = channels == null ? List.of() : List.copyOf(channels);
    }
}
