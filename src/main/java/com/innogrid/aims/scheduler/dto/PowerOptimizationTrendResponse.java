package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 클러스터 전력 최적화 추세 — {@code GET /scheduler/optimization/power/trend}.
 *
 * <p>{@code /optimization/power} 가 "현재 시점"에 계산하는 총 전력·가동/유휴 노드·회수 가능 전력·PEI 를, 메트릭 저장소의
 * 구간 질의로 <b>과거 시각마다 다시 계산</b>한 것이다. 저장하지 않는다 — 원시 시계열은 Thanos 에 이미 있고 우리는 조회
 * 시점에 정제한다(개발순서.md 0-1). 배치 알고리즘을 켠 뒤 효과가 어떻게 변했는지 화면이 그릴 재료다.</p>
 *
 * <p>전력 메트릭(IPMI)이 없는 시각은 {@code measured=false} 이고 전력 지표는 null 이다 — 0 으로 채우지 않는다.
 * CPU 기반 지표(가동/유휴 노드 수)만 값이 있다.</p>
 *
 * @param message 결손·조회 실패 사유. 정상이면 null
 */
public record PowerOptimizationTrendResponse(
        OffsetDateTime from,
        OffsetDateTime to,
        String step,
        List<Point> points,
        String message
) {
    public PowerOptimizationTrendResponse {
        points = points == null ? List.of() : List.copyOf(points);
    }

    public static PowerOptimizationTrendResponse empty(OffsetDateTime from, OffsetDateTime to, String step, String message) {
        return new PowerOptimizationTrendResponse(from, to, step, List.of(), message);
    }

    /**
     * 한 시각의 클러스터 상태. 계산식은 현재 진단·전력 효율 요약과 같다.
     *
     * @param totalNodes           그 시각에 CPU 시계열이 있는 노드 수
     * @param activeNodes          CPU 사용률이 가동 기준(설정 activeCpuPercent)을 넘는 노드 수
     * @param idleNodes            totalNodes − activeNodes
     * @param totalPowerWatts      전력이 있는 노드의 소비 전력 합. 미계측이면 null
     * @param reclaimableIdleWatts 유휴 노드의 유휴 전력(P_max × idleRatio) 합. 미계측이면 null
     * @param avgPeiRaw            평가 가능(전력 > 0) 노드의 PEI(CPU% ÷ W) 평균. 없으면 null
     * @param minPeiRaw            같은 노드들의 최소. 없으면 null
     * @param maxPeiRaw            같은 노드들의 최대. 없으면 null
     * @param evaluableNodes       전력이 있어 PEI 를 낼 수 있는 노드 수
     * @param measured             전력이 있는 노드가 하나라도 있는지
     */
    public record Point(
            OffsetDateTime at,
            int totalNodes,
            int activeNodes,
            int idleNodes,
            Double totalPowerWatts,
            Double reclaimableIdleWatts,
            Double avgPeiRaw,
            Double minPeiRaw,
            Double maxPeiRaw,
            int evaluableNodes,
            boolean measured
    ) {}
}
