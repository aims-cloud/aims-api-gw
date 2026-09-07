package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;

import com.innogrid.aims.scheduler.entity.SchedulingHistory;

/**
 * 배치 결정 이력 응답 — {@code /scheduler/history}·{@code /workloads/{id}/history}.
 *
 * <p>필드 22개는 {@link SchedulingHistory} 엔티티를 그대로 직렬화하던 계약과 <b>동일</b>하다
 * ({@code ResponseJsonGoldenTest} 가 고정).</p>
 *
 * <p>{@code cpuScore}~{@code networkScore} 는 엔진마다 담는 값의 의미가 다르다(temperature 엔진은
 * 균형 점수를 {@code networkScore} 자리에 넣는다). 기존 응답이 그랬으므로 그대로 두되, 해석은
 * {@code engineName} 을 보고 해야 한다. {@code success} 가 null 인 것은 <b>이 필드가 생기기 전에 쌓인 행</b>과
 * <b>단건 배치 API 가 실행 연결 없이 만든 옛 행</b>이며(지금은 둘 다 값을 남긴다), 그 null 은 성공으로 해석한다 —
 * 실패는 언제나 명시적 false 로 저장되기 때문이다.</p>
 */
public record SchedulingHistoryResponse(
        Long id,
        Long workloadId,
        String workloadName,
        String selectedMdcId,
        String selectedNodeId,
        double finalScore,
        double cpuScore,
        double memoryScore,
        double powerScore,
        double temperatureScore,
        double networkScore,
        double nodeCpuUsage,
        double nodeMemoryUsage,
        double nodePowerWatts,
        double nodeTemperature,
        int candidateCount,
        String reason,
        String engineName,
        String policyName,
        Long scheduleRunId,
        Boolean success,
        OffsetDateTime createdAt
) {
    public static SchedulingHistoryResponse from(SchedulingHistory h) {
        return new SchedulingHistoryResponse(
                h.getId(), h.getWorkloadId(), h.getWorkloadName(),
                h.getSelectedMdcId(), h.getSelectedNodeId(),
                h.getFinalScore(), h.getCpuScore(), h.getMemoryScore(), h.getPowerScore(),
                h.getTemperatureScore(), h.getNetworkScore(),
                h.getNodeCpuUsage(), h.getNodeMemoryUsage(), h.getNodePowerWatts(), h.getNodeTemperature(),
                h.getCandidateCount(), h.getReason(), h.getEngineName(), h.getPolicyName(),
                h.getScheduleRunId(), h.getSuccess(), h.getCreatedAt());
    }
}
