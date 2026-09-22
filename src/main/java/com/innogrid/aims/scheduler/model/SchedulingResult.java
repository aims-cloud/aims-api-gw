package com.innogrid.aims.scheduler.model;

import java.util.List;
import java.util.Map;

import com.innogrid.aims.scheduler.engine.constraint.ExclusionReason;

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
            ExclusionReason exclusionCode,
            Map<String, Double> details
    ) {
        /**
         * details 없이 만드는 기존 형태 — <b>적격 후보 전용</b>. 빈 맵은 <b>가변</b>으로 둔다 — 엔진 후처리가
         * {@code details().put} 으로 진단 키를 사후에 더하므로({@code PowerAwareScheduler} 의 통합여유), 불변 맵이면 그
         * 경로에서 예외가 난다.
         *
         * <p>제외 사유 문장을 넘기면 거부한다. 이 생성자는 코드를 {@code null} 로 두므로, 문장만 있고 코드가 없는 후보를
         * 만들면 배치 이력의 제외 코드 집계에서 조용히 빠진다 — 제외 후보는 코드를 함께 넘기는 12인자 생성자로 만든다.</p>
         */
        public NodeScore(String mdcId, String nodeId,
                         double cpuScore, double memoryScore, double powerScore,
                         double temperatureScore, double networkScore, double totalScore,
                         boolean eligible, String exclusionReason) {
            this(mdcId, nodeId, cpuScore, memoryScore, powerScore,
                    temperatureScore, networkScore, totalScore, eligible, requireNoReason(exclusionReason, nodeId), null,
                    new java.util.LinkedHashMap<>());
        }

        private static String requireNoReason(String exclusionReason, String nodeId) {
            if (exclusionReason != null) {
                throw new IllegalArgumentException("제외 사유 문장만 있고 코드가 없는 후보는 만들 수 없다 — 코드를 함께 넘긴다: " + nodeId);
            }
            return null;
        }

    }

    public static SchedulingResult failure(String reason) {
        return new SchedulingResult(false, null, null, 0, reason, List.of());
    }
}
