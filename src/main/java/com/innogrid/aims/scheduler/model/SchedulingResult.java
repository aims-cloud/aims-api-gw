package com.innogrid.aims.scheduler.model;

import java.util.List;

/**
 * 스케줄링 엔진의 결정 결과.
 */
public record SchedulingResult(
        boolean success,
        String selectedMdcId,
        String selectedNodeId,
        double finalScore,
        String reason,
        List<NodeScore> candidateScores
) {
    /**
     * 각 후보 노드의 개별 점수.
     */
    public record NodeScore(
            String mdcId,
            String nodeId,
            double cpuScore,
            double memoryScore,
            double powerScore,
            double temperatureScore,
            double networkScore,
            double totalScore,
            boolean eligible,
            String exclusionReason
    ) {}

    public static SchedulingResult failure(String reason) {
        return new SchedulingResult(false, null, null, 0, reason, List.of());
    }
}
