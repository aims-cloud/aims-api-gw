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
        List<Finding> findings,
        Summary summary
) {
    public PowerOptimizationResponse {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /**
     * 클러스터 전력 현황.
     *
     * @param reclaimableIdleWatts 비어 있는 노드들의 유휴 전력 합. 통합했다면 아낄 수 있었을 양이다.
     *                             워크로드를 옮길 수단이 없어 <b>실현된 절감이 아니다</b>
     * @param measured             전력을 수집한 노드가 하나라도 있는지
     * @param unmeasuredReason     수집하지 못했을 때의 사유. 화면에 그대로 띄운다
     */
    public record Cluster(
            int totalNodes,
            int activeNodes,
            int idleNodes,
            double totalPowerWatts,
            double reclaimableIdleWatts,
            boolean measured,
            String unmeasuredReason
    ) {}

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
