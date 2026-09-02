package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 워크로드 통합 가능성 진단.
 *
 * <p>"이 노드의 워크로드를 다른 노드로 옮길 수 있는가"를 <b>실제로 배치를 시도해</b> 답한다.
 * 시도는 계산일 뿐 아무것도 배치하지 않는다 — 워크로드 상태도 배치 이력도 바뀌지 않는다.</p>
 *
 * <p><b>유휴 여부를 고정 임계로 정하지 않는다.</b> "CPU 가 5% 미만이면 유휴" 같은 기준은
 * 64코어의 5%(3.2코어)와 8코어의 5%(0.4코어)를 같게 취급하는 자의적 절벽을 만든다. 대신
 * <b>순절감</b>을 계산한다 — 노드를 비워 아끼는 유휴 전력에서, 그 워크로드를 다른 노드로
 * 옮기는 데 드는 전력을 뺀 값이다. 이 값이 양수여야 통합할 가치가 있다.</p>
 */
public record ConsolidationResponse(
        OffsetDateTime evaluatedAt,
        boolean feasible,
        List<Candidate> candidates,
        Summary summary,
        String limitation
) {
    public ConsolidationResponse {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /** 워크로드를 옮길 수단이 없다는 사실을 응답이 스스로 밝힌다. */
    public static final String LIMITATION =
            "이 시스템에는 워크로드를 옮기거나 노드 전원을 끄는 수단이 없어 계산상 절감량입니다";

    /**
     * 반납 후보 노드 하나의 판정.
     *
     * @param evaluated           상한에 걸려 평가하지 못했으면 false. 이때 나머지 값은 의미가 없다
     * @param skipReason          평가하지 못한 사유
     * @param reclaimable         워크로드를 전부 옮길 수 있고 순절감이 양수인가
     * @param basis               판정 근거. {@code NET_SAVING}(순절감) · {@code ALREADY_EMPTY}(워크로드 0건)
     * @param idlePowerWatts      이 노드를 반납해 아끼는 유휴 전력
     * @param relocationCostWatts 워크로드를 옮기는 데 드는 전력 합
     * @param netSavingWatts      {@code idlePowerWatts − relocationCostWatts}
     * @param powerRatioToIdle    실측 전력 ÷ 유휴 전력. 1 에 가까울수록 실제로 놀고 있다.
     *                            전력을 수집하지 못하면 null
     * @param blockedReason       옮길 수 없을 때의 사유
     */
    public record Candidate(
            String mdcId,
            String nodeId,
            boolean evaluated,
            String skipReason,

            boolean reclaimable,
            String basis,
            int workloadCount,

            Double idlePowerWatts,
            Double relocationCostWatts,
            Double netSavingWatts,
            Double powerRatioToIdle,

            List<Move> moves,
            String blockedReason,
            String detail
    ) {
        public Candidate {
            moves = moves == null ? List.of() : List.copyOf(moves);
        }

        /** 상한에 걸려 평가하지 못한 후보. */
        public static Candidate skipped(String mdcId, String nodeId, int workloadCount, String reason) {
            return new Candidate(mdcId, nodeId, false, reason,
                    false, null, workloadCount,
                    null, null, null, null,
                    List.of(), null, null);
        }
    }

    /**
     * 워크로드 하나를 어디로 옮길지.
     *
     * @param marginalWatts 받는 노드에서 늘어나는 전력. 이 값들의 합이 옮기는 비용이다
     */
    public record Move(
            Long workloadId,
            String workloadName,
            String toNodeId,
            double marginalWatts
    ) {}

    /**
     * 통합 요약.
     *
     * @param truncated       상한에 걸려 평가하지 못한 후보가 있는지.
     *                        <b>이 값을 숨기면 부분 결과가 전수 조사처럼 보인다</b>
     * @param savingPercent   절감 가능 비율(%). 총 전력이 0 이면 null
     * @param excludedNodes   핵심 메트릭을 알 수 없어 수집 단계에서 뺀 노드 수. 후보 심사 자체를 받지
     *                        못한 노드라 {@code candidateCount} 에 들어 있지 않다
     * @param excluded        뺀 노드와 사유
     */
    public record Summary(
            int candidateCount,
            int reclaimableNodes,
            double reclaimableWatts,
            Double savingPercent,
            boolean truncated,
            String truncatedReason,
            int excludedNodes,
            List<ExcludedNodeResponse> excluded
    ) {
        public Summary {
            excluded = excluded == null ? List.of() : List.copyOf(excluded);
        }
    }
}
