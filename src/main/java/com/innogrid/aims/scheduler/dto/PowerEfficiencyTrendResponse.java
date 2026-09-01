package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 노드 하나의 전력 효율 추세.
 *
 * <p>메트릭 저장소에서 그때그때 읽어 만든다. 별도 테이블에 쌓지 않는다 — 원본 시계열은
 * 이미 저장소가 보관하고 있어 같은 데이터를 두 곳에 두면 어긋날 일만 생긴다.</p>
 *
 * <p>저장소가 응답하지 않거나 해당 구간에 데이터가 없으면 {@code points} 가 비고
 * {@code message} 에 사유가 담긴다. 오류가 아니다 — IPMI 가 연동되지 않은 환경에서는
 * 이것이 정상 응답이다.</p>
 */
public record PowerEfficiencyTrendResponse(
        String nodeId,
        OffsetDateTime from,
        OffsetDateTime to,
        String step,
        List<Point> points,
        String message
) {
    public PowerEfficiencyTrendResponse {
        points = points == null ? List.of() : List.copyOf(points);
    }

    /** 데이터가 없을 때. 사유를 함께 담아 화면이 "왜 비었는지" 설명할 수 있게 한다. */
    public static PowerEfficiencyTrendResponse empty(String nodeId, OffsetDateTime from,
                                                     OffsetDateTime to, String step, String message) {
        return new PowerEfficiencyTrendResponse(nodeId, from, to, step, List.of(), message);
    }

    /**
     * 한 시점의 값.
     *
     * <p>전력과 CPU 는 서로 다른 시계열이라 한쪽만 값이 있는 지점이 생길 수 있다.
     * 그때 {@code peiRaw} 는 계산하지 않고 {@code null} 로 둔다.</p>
     */
    public record Point(
            OffsetDateTime at,
            Double powerWatts,
            Double cpuUsagePercent,
            Double peiRaw
    ) {}
}
