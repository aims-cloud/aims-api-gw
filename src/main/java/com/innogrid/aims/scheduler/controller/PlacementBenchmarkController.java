package com.innogrid.aims.scheduler.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse;
import com.innogrid.aims.scheduler.service.PlacementBenchmarkService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 배치 전략 비교 — 우리 엔진과 대조군을 같은 조건에서 돌린 결과.
 *
 * <p><b>저장소에는 아무것도 쓰지 않는다.</b> 워크로드·정책·노드가 모두 파일과 코드에서 만든 비영속 객체이고,
 * 서비스가 저장소를 하나도 주입받지 않아 저장할 경로 자체가 없다. 그래서 이 진단을 몇 번 불러도
 * 워크로드 목록·배치 이력·정책 목록이 달라지지 않는다. 서비스가 같은 입력의 응답 본문을 메모리에 보관하지만
 * 그것은 프로세스 안의 캐시라 재시작하면 사라진다.</p>
 *
 * <p><b>{@code SchedulerController} 와 별도 클래스로 둔다.</b> 그쪽은 {@code /nodes/{nodeId}} 같은 경로
 * 변수를 잡고 있어 같은 클래스에 리터럴 경로를 더하면 어느 쪽이 먼저 잡히는지가 매핑 순서에 걸린다.
 * 접두어를 {@code /benchmark} 로 갈라 두면 그 경합이 생기지 않는다.</p>
 */
@Tag(name = "스케줄러 (배치 전략 비교)",
     description = "우리 엔진과 대조군(선착순 두 변종·엔진 전력식 공유 ΔP 단독·자원 최밀집·단순 규칙·무작위)을 같은 판·같은 워크로드·같은 제약에서 비교")
@RestController
@RequestMapping("/api/v1/scheduler/benchmark")
public class PlacementBenchmarkController {

    private final PlacementBenchmarkService benchmarkService;

    public PlacementBenchmarkController(PlacementBenchmarkService benchmarkService) {
        this.benchmarkService = benchmarkService;
    }

    @Operation(summary = "배치 전략 비교",
               description = "우리 엔진 전량과 대조군(선착순 두 변종·전력 인지 최적적합·자원 최밀집·최저 온도 우선·무작위), "
                       + "그리고 엔진 설정 변종 7열(자원전용 2 · 링크 게이트 on 1 · 한계 전력 눈금 3 · 유휴 역산 차단 1 — 열마다 "
                       + "strategies[].engineConfigOverride 가 바뀐 노브를 밝히고 그 값은 설정 다이제스트에 들어갑니다)을 "
                       + "**같은 노드 스냅샷·같은 워크로드 표본·같은 제약 파이프라인**에서 "
                       + "돌린 결과입니다. **저장소에는 아무것도 쓰지 않습니다.** 같은 입력(판·분포·판 파일 다이제스트·워크로드 파일 "
                       + "다이제스트·설정 다이제스트·판정 규칙 다이제스트)의 두 번째 호출은 메모리 캐시에서 나오며 evaluatedAt 만 새로 찍힙니다. 계산은 한 번에 한 건만 "
                       + "돌고, 계산 중 들어온 호출 중 **캐시에 없는 입력**만 기다리지 않고 **429** 와 사유를 받습니다(캐시에 있는 "
                       + "입력은 계산 중에도 200). 보통 5~10초, 오래 걸리는 판은 20초까지 기다린 뒤 다시 호출하세요. Retry-After 헤더는 주지 않습니다.\n\n"
                       + "**주 지표는 엔진이 믿은 값이 아닙니다.** engineBelievedCostW 는 전력 엔진 총점의 한 항을 "
                       + "그대로 합한 값이라 그것으로 순위를 매기면 그 목적함수를 최대화한 전략이 정의상 이깁니다. "
                       + "총전력의 주 지표(generatorPowerW)는 클러스터 파일 생성식의 **선언된 유휴 전력**으로 계산하며 "
                       + "엔진은 그 값을 보지 못합니다. 두 값의 차는 **엔진의 모델 가정과 생성식의 차이**이고, 실측 서버와의 "
                       + "차이가 아닙니다 — 평가자도 같은 생성식을 씁니다(assumptions.generatorFormula). 노드별 격차는 "
                       + "boards[].idleAssumptionGaps 에 있고, 그 격차가 놓이는 판의 생성식 지수는 boards[].powerExponent "
                       + "가 싣습니다 — 1 이면 선형이고, 1 보다 크면 저부하에서 전력이 선형보다 낮은 볼록 판입니다. 엔진이 "
                       + "유휴 노드의 깨우기 비용을 얼마나 되돌리는지는 boards[].wakeupReconstructionCheck 가 선언값과 역산 "
                       + "범위를 나란히 싣습니다 — 이쪽도 판정·비율 열은 싣지 않습니다. "
                       + "엔진의 한계 전력 점수는 노드 정격이 아니라 W 눈금(engineConfig.power.marginalScaleWatts, "
                       + "0 이하면 정격)으로 정규화하며 그 값은 설정 다이제스트에 들어갑니다 — 깨우기 벌점이 "
                       + "유휴비가 아니라 절대 W 로 총점에 반영된다는 뜻입니다. 열 쪽은 boards[].thermalRiseScaleCheck 가 코어당 온도 "
                       + "상승을 엔진 식(℃·K 두 눈금)과 생성식으로 나란히 싣습니다 — 같은 엔진 식이 눈금에 따라 다른 값을 "
                       + "내므로 그 식은 절대 척도 위에서 정의되지 않았고, 이 사실은 생성식과 무관합니다. 격차·비율·판정은 "
                       + "싣지 않습니다.\n\n"
                       + "**승자 필드를 두지 않습니다.** 셀마다 metrics[].valid·invalidReason 을 실어 그 숫자가 언제 "
                       + "의미를 갖는지 함께 줍니다 — 배치 건수가 다르면 총량 열은 짝지어진 시각 지점만으로 내고 그 "
                       + "사실을 totalsIncomparableReason 에 적습니다. 시각 지점 12곳을 훑어 median·min·max 를 함께 주니, "
                       + "지점 간 폭이 전략 간 차이보다 크면 그 차이는 주장할 수 없다는 뜻입니다.\n\n"
                       + "**총전력 격차 열(gapVsPabfdPercent)** — 엔진 전력식을 공유하는 ΔP 단독 대조군(전력 인지 최적적합) 대비 총전력 "
                       + "격차(%)를 시각 지점마다 짝지어 냅니다. 짝 조건이 총량 열보다 느슨해(이 행과 기준 전략 "
                       + "둘만 봅니다) samples 가 총량 열보다 클 수 있고, 지점별 비의 중앙값이라 두 전략의 총전력 "
                       + "중앙값을 나눈 수와는 다른 숫자입니다. 기준 전략 자신의 행은 0 이 아니라 무효입니다. "
                       + "이 열만 points 를 함께 싣습니다 — 인덱스가 시각 지점이고 짝이 아닌 지점은 null 이며 "
                       + "길이는 늘 지점 수라, 두 실행을 지점별로 대조할 수 있습니다. "
                       + "응답 metrics[] 필드: key · median · min · max · samples · points · valid · invalidReason.\n\n"
                       + "**board** — main(전수 계측 주 결과판) · adversarial(우리 엔진이 지도록 설계한 반증판) · "
                       + "sparse(주 결과판과 계측 플래그만 다른 센서 공백판) · convex(주 결과판과 생성식 지수만 다른 판) · "
                       + "misrated(주 결과판과 엔진에 알려 준 정격만 다른 판) · convex-misrated(둘이 함께 걸린 복합판·판정판) · "
                       + "link(주 결과판 노드 + links 절·데이터 MDC 순환 배정 — weighted/weighted-affinity 열 쌍만 갈린다) · "
                       + "main-independent · convex-misrated-independent(짝 판과 노드 동일, 위상 독립 seeds 3 → boards[] 3장) · "
                       + "concave(주 결과판과 생성식 지수 0.625 만 다른 판). 주 결과판과 노드 구성이 같은 판들은 판 사이의 차이를 "
                       + "요인으로 읽을 수 있고, 전략 순위는 여전히 주 결과판에서 보고합니다. **판정·방향 판정 자격은 convex-misrated · main 두 판의 "
                       + "power 행에만 있고 그 밖의 판·열·지표는 전부 자격이 없습니다.** **distribution** — heavytail(작은 것 다수) 또는 "
                       + "uniform(균일). 순위가 두 분포 사이에서 뒤집히면 그 사실 자체가 결과입니다. "
                       + "모르는 판·분포는 400 입니다. "
                       + "응답 boards[] 필드: boardId · role · clusterName · digest · phaseMode · phaseSeed · nodeCount · measuredNodeCount · "
                       + "powerExponent · idleAssumptionGaps · thermalRiseScaleCheck · wakeupReconstructionCheck · cells. "
                       + "phaseMode 가 INDEPENDENT 인 판은 판 파일의 seeds 마다 Board 를 하나씩 내어 boards[] 가 여러 장이고(각 Board 의 "
                       + "phaseSeed), 서로 독립 복제입니다 — Board 사이의 시각 지점을 합쳐 세지 않습니다.\n\n"
                       + "**한계** — 가상 클러스터 계산이며 실측이 아닙니다. 평가자도 같은 생성식을 쓰므로 "
                       + "확인하는 것은 '엔진의 모델 가정과 생성식이 얼마나 다른가'입니다. 전원 제어가 없는 현 시스템에서 "
                       + "통합으로 아낄 전력은 정의상 0 이고, 열 지표는 헤드라인이 아닙니다 — 이 API 는 "
                       + "전력 기반 배치·온도 제어의 효과를 결론 수준으로 증명하지 않습니다. 배포 환경은 노드가 한 대뿐이라 "
                       + "이 비교가 성립하지 않습니다. 전문은 응답의 limitation 에 있습니다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "비교 결과(캐시 적중 포함)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "모르는 판·분포"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "다른 계산이 실행 중이고 이 입력은 캐시에 없음 — 보통 5~10초, 오래 걸리는 판은 20초까지 기다린 뒤 재호출")
    })
    @GetMapping("/placement")
    public ApiResponse<PlacementBenchmarkResponse> comparePlacement(
            @RequestParam(required = false, defaultValue = "main") String board,
            @RequestParam(required = false, defaultValue = "heavytail") String distribution) {
        return ApiResponse.ok(benchmarkService.compare(board, distribution));
    }
}
