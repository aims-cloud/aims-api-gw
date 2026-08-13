package com.innogrid.aims.billing.controller;

import java.time.OffsetDateTime;
import java.util.Map;

import com.innogrid.aims.billing.entity.PricingPolicy;
import com.innogrid.aims.billing.repository.PricingPolicyRepository;
import com.innogrid.aims.billing.service.BillingService;
import com.innogrid.aims.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "빌링", description = "청구 금액, 이용금액 현황, 가격 정책")
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private final BillingService billingService;
    private final PricingPolicyRepository pricingRepository;

    public BillingController(BillingService billingService,
                             PricingPolicyRepository pricingRepository) {
        this.billingService = billingService;
        this.pricingRepository = pricingRepository;
    }

    @Operation(summary = "청구 금액 조회", description = "미터링 집계 × 가격정책 기반 청구 금액 계산")
    @GetMapping("/charges")
    public ApiResponse<?> getCharges(@RequestParam(required = false) String mdcId,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to) {
        OffsetDateTime fromDt = from != null ? OffsetDateTime.parse(from) : OffsetDateTime.now().minusMonths(1);
        OffsetDateTime toDt = to != null ? OffsetDateTime.parse(to) : OffsetDateTime.now();
        return ApiResponse.ok(billingService.calculateCharges(mdcId, fromDt, toDt));
    }

    @Operation(summary = "이용금액 추이")
    @GetMapping("/usage-costs/trend")
    public ApiResponse<?> getUsageCostTrend(@RequestParam String from,
                                            @RequestParam String to) {
        return ApiResponse.ok(billingService.getUsageCostTrend(
                OffsetDateTime.parse(from), OffsetDateTime.parse(to)));
    }

    @Operation(summary = "가격 정책 목록")
    @GetMapping("/pricing-policies")
    public ApiResponse<?> listPricingPolicies() {
        return ApiResponse.ok(pricingRepository.findByActiveTrueOrderByMetricName());
    }

    @Operation(summary = "가격 정책 생성")
    @PostMapping("/pricing-policies")
    public ApiResponse<?> createPricingPolicy(@RequestBody Map<String, Object> request) {
        PricingPolicy policy = new PricingPolicy(
                (String) request.get("metricName"),
                (String) request.getOrDefault("unit", "hour"),
                ((Number) request.get("unitPrice")).doubleValue(),
                (String) request.get("description"));
        return ApiResponse.ok(pricingRepository.save(policy));
    }
}
