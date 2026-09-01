package com.innogrid.aims.scheduler.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.scheduler.service.ConsolidationService;
import com.innogrid.aims.scheduler.service.PowerOptimizationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 클러스터 전력 최적화 진단.
 *
 * <p>노드 하나의 효율은 {@code /scheduler/nodes/power-efficiency} 가 답한다. 여기서는 한 층 위에서
 * <b>클러스터 전체로 무엇을 할 수 있는지</b>를 답한다 — 몇 대가 놀고 있고, 통합하면 얼마를
 * 반납할 수 있는가.</p>
 *
 * <p>비용·VM 단위 진단인 {@code /api/v1/optimization/**} 과는 <b>관점이 다르다.</b> 그쪽은 과금
 * 데이터로 비용을 보고, 이쪽은 전력 메트릭으로 소비 전력을 본다. 경로 접두어가 달라 서로
 * 영향을 주지 않는다.</p>
 *
 * <p><b>{@code SchedulerController} 와 별도 클래스로 둔다.</b> 그쪽은 {@code /nodes/{nodeId}} 같은
 * 경로 변수를 잡고 있어, 같은 클래스에 리터럴 경로를 더하면 어느 쪽이 먼저 잡히는지가 매핑
 * 순서에 걸린다. 단계 5 에서 {@code /nodes/power-efficiency} 가 {@code /nodes/{nodeId}} 로 잡힐
 * 뻔한 선례가 있다.</p>
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
                       + "전력 메트릭이 없으면 measured=false 와 사유를 돌려주고 findings 는 빈다.")
    @GetMapping("/power")
    public ApiResponse<?> getPowerOptimization() {
        return ApiResponse.ok(optimizationService.diagnoseCurrent());
    }

    @Operation(summary = "워크로드 통합 가능성 진단",
               description = "노드별로 워크로드를 다른 노드에 배치해 보고 반납 가능 여부를 판정한다. "
                       + "판정 기준은 순절감(반납하는 유휴 전력 − 옮기는 비용)이며, "
                       + "시도는 계산일 뿐 실제로 배치하지 않는다.")
    @GetMapping("/consolidation")
    public ApiResponse<?> getConsolidation() {
        return ApiResponse.ok(consolidationService.diagnose());
    }
}
