package com.innogrid.aims.scheduler.model;

import java.util.List;
import java.util.Map;

/**
 * 스케줄링 엔진의 결정 결과.
 *
 * <p>{@code policyName}은 이 결정에 적용된 배치 정책의 이름이다. 엔진은 정책을 모르고 결과를 만들므로 6인자 생성자로 만든 결과는
 * {@code null}이고, 단건 배치·모의 배치 응답에서는 {@code SchedulingService}가 {@link #withPolicyName}으로 채운다 — 성공·실패·
 * 실행 계층 거부 결과 모두에 남아, 단건 배치의 실패처럼 이력이 없는 결정에서도 어느 정책으로 판정했는지 알 수 있다.
 * 스케줄 실행 경로의 결과는 채우지 않으며(응답으로 나가지 않음) 적용 정책은 배치 이력의 {@code policyName}으로 남는다.</p>
 */
public record SchedulingResult(
        boolean success,
        String selectedMdcId,
        String selectedNodeId,
        double finalScore,
        String reason,
        List<NodeScore> candidateScores,
        String policyName
) {
    /** 정책 이름 없는 기존 형태 — 엔진과 비교 판이 쓴다. 정책은 단건 배치·모의 배치에서 {@code SchedulingService}가 뒤에 채운다. */
    public SchedulingResult(boolean success, String selectedMdcId, String selectedNodeId, double finalScore,
                            String reason, List<NodeScore> candidateScores) {
        this(success, selectedMdcId, selectedNodeId, finalScore, reason, candidateScores, null);
    }

    /** 다른 값은 그대로 두고 적용 정책 이름만 채운 사본. */
    public SchedulingResult withPolicyName(String policyName) {
        return new SchedulingResult(success, selectedMdcId, selectedNodeId, finalScore, reason, candidateScores, policyName);
    }

    /**
     * 각 후보 노드의 개별 점수.
     *
     * <p>{@code cpuScore} ~ {@code networkScore}는 엔진마다 담는 값의 의미가 달라
     * 그대로 보면 오해하기 쉽다. 엔진이 실제로 무엇을 계산했는지는 {@code details}에
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
         * details 없이 만드는 기존 형태 — <b>적격 후보 전용</b>. 빈 맵은 <b>가변</b>으로 둔다. 엔진 후처리가
         * {@code details().put}으로 진단 키를 사후에 더하므로({@code PowerAwareScheduler}의 통합여유), 불변 맵이면 그
         * 경로에서 예외가 난다.
         *
         * <p>제외 사유 문장을 넘기면 거부한다. 이 생성자는 코드를 {@code null}로 두므로, 문장만 있고 코드가 없는 후보를
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

    /** 정책이 정해진 뒤의 실패 — 후보가 없거나 실행 계층이 거부한 경우에도 적용 정책을 남긴다. */
    public static SchedulingResult failure(String reason, String policyName) {
        return new SchedulingResult(false, null, null, 0, reason, List.of(), policyName);
    }
}
