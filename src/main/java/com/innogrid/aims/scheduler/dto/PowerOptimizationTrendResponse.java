package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 클러스터 전력 최적화 추세 — {@code GET /scheduler/optimization/power/trend}.
 *
 * <p>{@code /optimization/power}가 "현재 시점"에 계산하는 총 전력·가동/유휴 노드·회수 가능 전력·PEI를, 메트릭 저장소의
 * 구간 질의로 <b>과거 시각마다 다시 계산</b>한 것이다. 원시 시계열이 Thanos에 이미 있으므로 저장하지 않고 조회 시점에
 * 정제한다. 배치 알고리즘을 켠 뒤 효과가 어떻게 변했는지 화면이 그릴 재료다.</p>
 *
 * <p>전력 메트릭(IPMI)이 없는 시각은 {@code measured=false}이고, 전력 지표는 0으로 채우지 않고 null로 둔다. CPU 기반
 * 지표(가동/유휴 노드 수)만 값이 있다. 일부 노드만 결손인 시각은 그 노드를 0W로 세어 합하므로 구간 에너지는
 * {@link Energy}로 따로 낸다(docs/13 4장).</p>
 *
 * @param energy  구간 전체의 에너지 요약. 낼 수 없으면 null
 * @param message 결손·조회 실패 사유. 정상이면 null
 */
public record PowerOptimizationTrendResponse(
        OffsetDateTime from,
        OffsetDateTime to,
        String step,
        List<Point> points,
        String message,
        Energy energy
) {
    public PowerOptimizationTrendResponse {
        points = points == null ? List.of() : List.copyOf(points);
    }

    public static PowerOptimizationTrendResponse empty(OffsetDateTime from, OffsetDateTime to, String step, String message) {
        return new PowerOptimizationTrendResponse(from, to, step, List.of(), message, null);
    }

    /**
     * 구간 전체의 에너지 요약 — 지점 배열을 클라이언트가 적분하지 않아도 되게 한다.
     *
     * <p><b>지점 배열을 더해서 낸 값이 아니다.</b> 지점의 총 전력은 그 시각에 전력을 못 읽은 노드를 0으로 채워
     * 만든 값이라, 그 배열을 적분하면 결손이 "0 W를 썼다"로 읽혀 조용히 적게 나온다. 대신 <b>0을 채우기 전의
     * 원시 구간 시계열</b>을 노드별로 적분한다 — 그 맵에는 결손 자리가 아예 없다. 소비와 회수 가능이 같은 방식이고,
     * 회수 가능 쪽만 시각마다 가동 판정과 유휴 역산을 한 번 더 거친다.</p>
     *
     * <p><b>물은 구간과 잰 구간이 다를 수 있다.</b> 시계열이 늦게 시작했거나 중간이 비면 그만큼 짧게 적분한다.
     * 그래서 실제로 적분이 걸친 구간과 시간을 함께 싣는다 — 24시간을 물었는데 21시간치가 왔다면 이 칸으로 안다.</p>
     *
     * @param consumedKwh            실측 전력의 소비 에너지(kWh). 실측 노드가 없으면 null
     * @param reclaimableKwh         유휴 노드를 반납했다면 아꼈을 에너지(kWh). 적분할 표본이 없으면 null
     * @param reclaimableEstimatedKwh 그중 실측이 아닌 값에서 온 몫(kWh) — 정격으로 어림한 미계측 노드가 여기 든다
     * @param integratedFrom         회수 가능 적분이 실제로 걸친 구간의 시작. 없으면 null
     * @param integratedTo           회수 가능 적분이 실제로 걸친 구간의 끝. 없으면 null
     * @param integratedHours        회수 가능 적분에서 한 노드가 덮은 시간 중 가장 긴 것(시). 끊긴 구멍은 빠져 있다
     * @param integratedNodes        회수 가능 적분에 쓴 노드 수
     * @param gaps                   표본이 멀어 잇지 않고 끊은 횟수
     * @param reason                 값이 비거나 좁아진 까닭. 정상이면 null
     */
    @Schema(description = "구간 전체의 에너지 요약. 지점 배열을 적분한 값이 아니다 — 결손을 0 으로 읽지 않으려고 따로 낸다")
    public record Energy(
            @Schema(description = "실측 전력의 소비 에너지(kWh). 실측 노드가 없으면 null")
            Double consumedKwh,
            @Schema(description = "유휴 노드를 반납했다면 아꼈을 에너지(kWh). 적분할 표본이 없으면 null")
            Double reclaimableKwh,
            @Schema(description = "회수 가능 에너지 중 실측이 아닌 값에서 온 몫(kWh). 미계측 노드는 정격으로 어림하므로 여기 든다. "
                    + "두 끝 중 하나라도 추정이면 그 조각을 통째로 세므로 경계에서 조금 크게 잡힌다")
            Double reclaimableEstimatedKwh,
            @Schema(description = "회수 가능 적분이 실제로 걸친 구간의 시작(UTC). 물은 구간보다 늦을 수 있다")
            OffsetDateTime integratedFrom,
            @Schema(description = "회수 가능 적분이 실제로 걸친 구간의 끝(UTC)")
            OffsetDateTime integratedTo,
            @Schema(description = "한 노드가 덮은 시간 중 가장 긴 것(시). 회수 가능 적분 기준이고, 이어지지 않은 자리는 빠져 있다")
            Double integratedHours,
            @Schema(description = "회수 가능 적분에 쓴 노드 수")
            Integer integratedNodes,
            @Schema(description = "이어 붙이지 않고 끊은 횟수. 표본이 없었을 수도, 그 시각 노드가 가동 중이라 대상이 아니었을 수도 있다")
            Integer gaps,
            @Schema(description = "값이 비거나 좁아진 까닭. 정상이면 null")
            String reason
    ) {}

    /**
     * 한 시각의 클러스터 상태. 계산식은 현재 진단·전력 효율 요약과 같다.
     *
     * @param totalNodes           그 시각에 CPU 시계열이 있는 노드 수
     * @param activeNodes          CPU 사용률이 가동 기준(설정 activeCpuPercent)을 넘는 노드 수
     * @param idleNodes            totalNodes − activeNodes
     * @param totalPowerWatts      전력이 있는 노드의 소비 전력 합. 미계측이면 null
     * @param reclaimableIdleWatts 유휴 노드를 반납하면 아낄 전력 합 — 계측된 유휴 노드는 실측에서 역산, 그 외 정격 × idleRatio. 미계측이면 null
     * @param avgPeiRaw            평가 가능(전력 > 0) 노드의 PEI(CPU% ÷ W) 평균. 없으면 null
     * @param minPeiRaw            같은 노드들의 최소. 없으면 null
     * @param maxPeiRaw            같은 노드들의 최대. 없으면 null
     * @param evaluableNodes       전력이 있어 PEI를 낼 수 있는 노드 수
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
