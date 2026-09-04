package com.innogrid.aims.scheduler.controller;

import java.util.List;

import org.springframework.web.bind.annotation.*;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.scheduler.dto.MdcPowerBudgetRequest;
import com.innogrid.aims.scheduler.dto.MdcPowerBudgetResponse;
import com.innogrid.aims.scheduler.service.MdcPowerBudgetService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * MDC 전력 예산 원장 API — MDC 한 곳이 쓸 수 있는 전력 총량(W)을 운영자가 입력한다.
 *
 * <p>노드 전력 사양(정격)이 <b>한 대의 상한</b>이라면 이 예산은 <b>한 곳의 총량</b>이다. 값을 넣으면 다음 배치부터
 * 그 MDC 의 전력 합계(예약분 포함)가 예산을 넘는 노드는 후보에서 빠지고, 전력 최적화 진단의 MDC 요약에
 * 예산·남은 여유·초과 여부가 함께 나온다. 예산을 등록하지 않은 MDC 는 종전과 같이 총량 제약을 받지 않는다.</p>
 *
 * <p><b>남은 여유는 예약분까지 반영한 실효 전력 기준</b>이다 — 실측만 보면 "여유가 있다"고 보여 주고도 실제 배치는
 * 막히는 어긋남이 생긴다. 진단이 초과라고 하면 배치도 막힌다고 믿어도 된다.</p>
 */
@Tag(name = "MDC 전력 예산", description = "MDC 별 전력 총량(W) 원장. 값을 넣으면 다음 배치부터 그 MDC 의 합계 상한으로 쓰인다")
@RestController
@RequestMapping("/api/v1/scheduler/mdc-power-budgets")
public class MdcPowerBudgetController {

    private final MdcPowerBudgetService service;

    public MdcPowerBudgetController(MdcPowerBudgetService service) {
        this.service = service;
    }

    @Operation(summary = "전력 예산 목록", description = "MDC 식별자 오름차순. mdcId 를 주면 그 문자열을 포함하는 MDC 만(부분 일치, 대소문자 무시).")
    @GetMapping
    public ApiResponse<List<MdcPowerBudgetResponse>> list(
            @Parameter(description = "MDC 식별자 부분 일치 필터") @RequestParam(required = false) String mdcId) {
        return ApiResponse.ok(service.list(mdcId).stream().map(MdcPowerBudgetResponse::from).toList());
    }

    @Operation(summary = "전력 예산 상세")
    @GetMapping("/{budgetId}")
    public ApiResponse<MdcPowerBudgetResponse> get(@PathVariable Long budgetId) {
        return ApiResponse.ok(MdcPowerBudgetResponse.from(service.require(budgetId)));
    }

    @Operation(summary = "전력 예산 등록",
               description = "mdcId(Prometheus mdc_id 라벨과 같은 문자열, 라벨 없는 노드는 unknown)와 budgetWatts 필수. 예산은 0 보다 큰 유한한 값이면 "
                       + "무엇이든 받는다 — 실데이터가 없어 크기 상한을 두지 않았다. **합계는 전력을 수집한 노드만 더하므로** 미계측 노드가 많은 MDC 는 "
                       + "판정이 과소 평가된다(제외 사유에 미계측 대수를 적는다). source 는 MANUAL(기본)·SPEC_SHEET·MEASURED(예약), 대소문자 무시. "
                       + "같은 MDC 를 다시 등록하면 409.")
    @PostMapping
    public ApiResponse<MdcPowerBudgetResponse> create(@RequestBody(required = false) MdcPowerBudgetRequest request) {
        return ApiResponse.ok(MdcPowerBudgetResponse.from(service.create(request)));
    }

    @Operation(summary = "전력 예산 수정",
               description = "부분 수정 — 요청에 있는 키만 반영합니다. description 에 null 을 주면 비웁니다. MDC 식별자를 다른 MDC 의 것으로 바꾸면 409.")
    @PutMapping("/{budgetId}")
    public ApiResponse<MdcPowerBudgetResponse> update(@PathVariable Long budgetId,
                                                      @RequestBody(required = false) MdcPowerBudgetRequest request) {
        return ApiResponse.ok(MdcPowerBudgetResponse.from(service.update(budgetId, request)));
    }

    @Operation(summary = "전력 예산 삭제", description = "지우면 다음 배치부터 그 MDC 는 총량 제약을 받지 않습니다.")
    @DeleteMapping("/{budgetId}")
    public ApiResponse<Void> delete(@PathVariable Long budgetId) {
        service.delete(budgetId);
        return ApiResponse.ok(null);
    }
}
