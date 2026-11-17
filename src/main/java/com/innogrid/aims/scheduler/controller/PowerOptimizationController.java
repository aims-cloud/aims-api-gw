package com.innogrid.aims.scheduler.controller;

import java.time.OffsetDateTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.scheduler.dto.ConsolidationResponse;
import com.innogrid.aims.scheduler.dto.PowerOptimizationResponse;
import com.innogrid.aims.scheduler.dto.PowerOptimizationTrendResponse;
import com.innogrid.aims.scheduler.service.ConsolidationService;
import com.innogrid.aims.scheduler.service.PowerOptimizationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 클러스터 전력 최적화 진단.
 *
 * <p>노드 하나의 효율은 {@code /scheduler/nodes/power-efficiency}가 답한다. 여기서는 한 층 위에서
 * <b>클러스터 전체로 무엇을 할 수 있는지</b>를 답한다 — 몇 대가 놀고 있고, 통합하면 얼마를
 * 반납할 수 있는가.</p>
 *
 * <p>비용·VM 단위 진단인 {@code /api/v1/optimization/**}과는 <b>관점이 다르다.</b> 그쪽은 과금
 * 데이터로 비용을 보고, 이쪽은 전력 메트릭으로 소비 전력을 본다. 경로 접두어가 달라 서로
 * 영향을 주지 않는다.</p>
 *
 * <p><b>{@code SchedulerController}와 별도 클래스로 둔다.</b> 경로 접두어 {@code /optimization}이 달라
 * 그쪽 경로와 겹치지 않는다. 리터럴 경로와 경로 변수가 겹쳐도 스프링은 선언 순서와 상관없이 리터럴 경로를
 * 먼저 고른다 — 그쪽의 {@code /nodes/power-efficiency}가 {@code /nodes/{nodeId}}보다 먼저 잡히는 것을
 * {@code PowerEfficiencyApiTest}가 확인한다.</p>
 */
@Tag(name = "스케줄러 (전력 최적화 진단)",
     description = "클러스터 전력 진단, 워크로드 통합 가능성")
@RestController
@RequestMapping("/api/v1/scheduler/optimization")
public class PowerOptimizationController {

    private final PowerOptimizationService optimizationService;
    private final ConsolidationService consolidationService;

    public PowerOptimizationController(PowerOptimizationService optimizationService,
                                       ConsolidationService consolidationService) {
        this.optimizationService = optimizationService;
        this.consolidationService = consolidationService;
    }

    @Operation(summary = "전력 최적화 진단",
               description = "클러스터 전력 현황과 개선 항목(유휴 노드·전력 낭비·여유 부족). "
                       + "전력 메트릭이 없으면 measured=false 와 사유를 돌려주고 findings 는 빈다. "
                       + "**mdcs** 는 MDC 별 현황(식별자 오름차순)이다 — 전력을 두 값으로 준다: totalPowerWatts 는 실측 합"
                       + "(클러스터 합계와 같은 기준이라 MDC 별 합을 더하면 클러스터 합계가 된다), effectivePowerWatts 는 "
                       + "예약분까지 반영한 합으로 **배치 판정(C6)이 예산과 비교하는 기준선**이다. 둘의 차이가 배정됐지만 아직 "
                       + "메트릭에 잡히지 않은 부하다. remainingWatts·overBudget 은 실효 기준이라 진단과 실제 배치가 어긋나지 않는다. "
                       + "예산을 등록하지 않은 MDC 는 budgetWatts·remainingWatts 가 null 이다.")
    @GetMapping("/power")
    public ApiResponse<PowerOptimizationResponse> getPowerOptimization() {
        return ApiResponse.ok(optimizationService.diagnoseCurrent());
    }

    @Operation(summary = "전력 최적화 추세",
               description = "구간의 시각마다 총 전력·가동/유휴 노드·회수 가능 전력·PEI 를 메트릭 저장소 구간 질의로 다시 계산합니다(저장하지 않음). "
                       + "기본 최근 24시간·5분 간격, 지점 수 상한(설정 trendMaxPoints)을 넘으면 400. "
                       + "전력 메트릭이 없는 시각은 measured=false 이고 전력 지표는 null 입니다(가동/유휴 노드 수만). "
                       + "저장소가 응답하지 않으면 사유를 담은 빈 결과(200)입니다. "
                       + "energy 는 구간 전체의 에너지 요약입니다 — 지점 배열을 적분한 값이 아닙니다. "
                       + "지점의 총 전력은 전력을 못 읽은 노드를 0 으로 채워 만들기 때문에, 그 배열을 적분하면 결손이 0W 로 읽혀 적게 나옵니다. "
                       + "소비와 회수 가능 둘 다 0 을 채우기 전의 원시 구간 시계열을 노드별로 적분합니다. 회수 가능 쪽만 시각마다 가동 판정과 유휴 역산을 한 번 더 거칩니다. "
                       + "물은 구간과 실제로 적분한 구간이 다를 수 있어 integratedFrom·integratedTo·integratedHours 를 함께 냅니다(회수 가능 적분 기준). "
                       + "표본을 잇는 간격은 step 의 두 배이고 하한이 5분입니다 — 그보다 멀어진 자리는 잇지 않고 뺍니다. "
                       + "mdcId 를 주면 지점과 energy 를 그 MDC 노드만으로 계산합니다 — 그 MDC 로 수집된 노드가 없으면 사유를 담은 빈 결과(200)입니다.")
    @GetMapping("/power/trend")
    public ApiResponse<PowerOptimizationTrendResponse> getPowerTrend(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String step,
            @Parameter(description = "MDC 식별자 — 노드 목록의 mdcId 와 같은 문자열(앞뒤 공백 무시 · 대소문자 구분 · 라벨 없는 노드는 unknown). 비우면 클러스터 전체")
            @RequestParam(required = false) String mdcId) {
        return ApiResponse.ok(optimizationService.trend(from, to, step, mdcId));
    }

    @Operation(summary = "워크로드 통합 가능성 진단",
               description = "노드별로 워크로드를 다른 노드에 배치해 보고 반납 가능 여부를 판정한다. "
                       + "판정 기준은 순절감(반납하는 유휴 전력 − 옮기는 비용)이며, "
                       + "시도는 계산일 뿐 실제로 배치하지 않는다.")
    @GetMapping("/consolidation")
    public ApiResponse<ConsolidationResponse> getConsolidation() {
        return ApiResponse.ok(consolidationService.diagnose());
    }
}
