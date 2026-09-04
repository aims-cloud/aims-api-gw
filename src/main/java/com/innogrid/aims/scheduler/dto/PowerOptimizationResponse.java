package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 클러스터 전력 최적화 진단.
 *
 * <p>노드 하나가 효율적인지는 {@link PowerEfficiencyResponse} 가 답한다. 이 응답은 한 층 위에서
 * <b>클러스터 전체로 무엇을 할 수 있는지</b>를 답한다 — 몇 대가 놀고 있고, 통합하면 얼마를
 * 반납할 수 있는가.</p>
 *
 * <p>전력을 수집하지 못하면 {@code measured=false} 이고 {@code findings} 가 빈다. 오류가 아니다 —
 * IPMI 가 연동되지 않은 환경에서는 이것이 정상 응답이며, 사유를 함께 담아 화면이 "왜 비었는지"
 * 설명할 수 있게 한다.</p>
 *
 * <p>시각은 UTC 다. 표출 변환은 프런트가 한다.</p>
 */
public record PowerOptimizationResponse(
        OffsetDateTime evaluatedAt,
        Cluster cluster,
        List<Mdc> mdcs,
        List<Finding> findings,
        Summary summary
) {
    public PowerOptimizationResponse {
        mdcs = mdcs == null ? List.of() : List.copyOf(mdcs);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /**
     * MDC 한 곳의 전력 현황. MDC 식별자 오름차순이다.
     *
     * <p>클러스터 합계는 "전체가 얼마를 쓰는가"만 답한다. 그런데 전력 상한은 실제로 <b>MDC 단위</b>로 걸린다 —
     * 컨테이너·랙의 수전 용량이 곧 상한이다. 이 배열은 그 단위로 쪼갠 현황이며, 예산 원장에 값을 넣은 MDC 는
     * 남은 여유와 초과 여부까지 함께 보여 준다.</p>
     *
     * <p><b>합계 기준</b>은 클러스터 합계와 같은 실측이다. 배치 판정(C6)은 예약분까지 반영한 실효 전력을 쓰므로,
     * 배치가 막 쌓인 시점에는 판정 쪽 합계가 이 값보다 크다. 이 배열은 "지금 무엇을 쓰고 있나"를 보여 주는
     * 진단이고, C6 은 "하나 더 얹으면 어떻게 되나"를 보는 판정이라 기준이 다르다.</p>
     *
     * @param measuredNodes    전력을 수집한 노드 수. {@code nodeCount} 보다 적으면 합계가 과소 평가다
     * @param budgetWatts      예산 원장에 등록된 총량. 등록하지 않았으면 null
     * @param remainingWatts   예산 − 합계. 예산이 없으면 null. 음수면 이미 넘긴 것이다
     * @param overBudget       합계가 예산을 넘었는지. 예산이 없으면 false
     */
    public record Mdc(
            String mdcId,
            int nodeCount,
            int measuredNodes,
            double totalPowerWatts,
            Double budgetWatts,
            Double remainingWatts,
            boolean overBudget
    ) {}

    /**
     * 클러스터 전력 현황.
     *
     * @param reclaimableIdleWatts 비어 있는 노드들의 유휴 전력 합. 통합했다면 아낄 수 있었을 양이다.
     *                             워크로드를 옮길 수단이 없어 <b>실현된 절감이 아니다</b>
     * @param measured             전력을 수집한 노드가 하나라도 있는지
     * @param unmeasuredReason     수집하지 못했을 때의 사유. 화면에 그대로 띄운다
     * @param excludedNodes        핵심 메트릭을 알 수 없어 수집 단계에서 뺀 노드 수.
     *                             {@code totalNodes} 는 판단 가능한 노드만 센다 — 이 값이 0 이 아니면
     *                             클러스터가 실제로는 더 크다는 뜻이다
     * @param excluded             뺀 노드와 사유
     */
    public record Cluster(
            int totalNodes,
            int activeNodes,
            int idleNodes,
            double totalPowerWatts,
            double reclaimableIdleWatts,
            boolean measured,
            String unmeasuredReason,
            int excludedNodes,
            List<ExcludedNodeResponse> excluded
    ) {
        public Cluster {
            excluded = excluded == null ? List.of() : List.copyOf(excluded);
        }
    }

    /**
     * 진단 항목 하나.
     *
     * @param type                 {@code IDLE_NODE} · {@code POWER_WASTE} · {@code LOW_HEADROOM}
     * @param severity             {@code HIGH} · {@code MEDIUM} · {@code LOW}
     * @param estimatedSavingWatts 이 항목을 해소하면 아낄 수 있는 전력. 해당 없으면 null
     */
    public record Finding(
            String type,
            String severity,
            String mdcId,
            String nodeId,
            String title,
            String detail,
            Double estimatedSavingWatts,
            String action
    ) {}

    /**
     * 진단 요약.
     *
     * @param savingPercent 절감 가능 비율(%). 총 전력이 0 이면 null
     */
    public record Summary(
            int findingCount,
            double estimatedSavingWatts,
            Double savingPercent
    ) {}
}
