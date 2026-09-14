package com.innogrid.aims.scheduler.model;

import java.util.List;
import java.util.Map;

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
     *
     * <p>{@code cpuScore} ~ {@code networkScore} 는 엔진마다 담는 값의 의미가 달라
     * 그대로 보면 오해하기 쉽다. 엔진이 실제로 무엇을 계산했는지는 {@code details} 에
     * 항목 이름과 함께 담는다.</p>
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
            String exclusionReason,
            Map<String, Double> details
    ) {
        /**
         * details 없이 만드는 기존 형태. 빈 맵은 <b>가변</b>으로 둔다 — 엔진 후처리가 {@code details().put} 으로
         * 진단 키를 사후에 더하므로({@code PowerAwareScheduler} 의 통합여유), 불변 맵이면 그 경로에서 예외가 난다.
         */
        public NodeScore(String mdcId, String nodeId,
                         double cpuScore, double memoryScore, double powerScore,
                         double temperatureScore, double networkScore, double totalScore,
                         boolean eligible, String exclusionReason) {
            this(mdcId, nodeId, cpuScore, memoryScore, powerScore,
                    temperatureScore, networkScore, totalScore, eligible, exclusionReason, new java.util.LinkedHashMap<>());
        }
    }

    public static SchedulingResult failure(String reason) {
        return new SchedulingResult(false, null, null, 0, reason, List.of());
    }
}
