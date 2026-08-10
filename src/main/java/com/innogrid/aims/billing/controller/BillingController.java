package com.innogrid.aims.billing.controller;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.mock.MockDataProvider;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 빌링 API.
 * [요구사항 #12] 청구 금액 — MDC·퍼블릭 클라우드 사용에 대한 사용자별·기간별 청구 금액 산정
 * [요구사항 #13] 이용금액 현황 — 다수 MDC 및 퍼블릭 클라우드 이용 금액과 추이
 */
@Tag(name = "빌링", description = "청구 금액, 이용금액 현황")
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private final MockDataProvider mock;

    public BillingController(MockDataProvider mock) {
        this.mock = mock;
    }

    /** 청구 금액 조회 */
    @GetMapping("/charges")
    public ApiResponse<?> getCharges(@RequestParam(required = false) String userId,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to) {
        return ApiResponse.ok(mock.getCharges());
    }

    /** 청구 금액 상세 */
    @GetMapping("/charges/{chargeId}")
    public ApiResponse<?> getChargeDetail(@PathVariable Long chargeId) {
        return ApiResponse.ok(mock.getCharges().get(0));
    }

    /** 이용금액 현황 조회 */
    @GetMapping("/usage-costs")
    public ApiResponse<?> getUsageCosts(@RequestParam(required = false) String mdcId,
                                        @RequestParam(required = false) String period) {
        return ApiResponse.ok(mock.getUsageCosts());
    }

    /** 이용금액 추이 */
    @GetMapping("/usage-costs/trend")
    public ApiResponse<?> getUsageCostTrend(@RequestParam String from,
                                            @RequestParam String to) {
        return ApiResponse.ok(mock.getUsageCosts());
    }
}
