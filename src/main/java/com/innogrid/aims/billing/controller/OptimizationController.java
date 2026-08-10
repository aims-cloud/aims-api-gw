package com.innogrid.aims.billing.controller;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.mock.MockDataProvider;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 비용/자원 최적화 진단 API.
 * [요구사항 #14] 비용 최적화 진단 — 사용 패턴·전력·냉각 비용 분석, 절감 항목 권고
 * [요구사항 #15] 인프라 증설 진단 — MDC별 자원 사용 추이 분석, 퍼블릭 클라우드 버스팅 권고
 * [요구사항 #16] VM 자원 진단 — VM 과다/과소 할당 진단, 적정 사이징 권고
 * [요구사항 #17] 자원 최적화 진단 환경 설정
 */
@Tag(name = "최적화 진단", description = "비용/자원 최적화 진단")
@RestController
@RequestMapping("/api/v1/optimization")
public class OptimizationController {

    private final MockDataProvider mock;

    public OptimizationController(MockDataProvider mock) {
        this.mock = mock;
    }

    /** 비용 최적화 진단 결과 조회 */
    @GetMapping("/cost")
    public ApiResponse<?> getCostOptimization(@RequestParam(required = false) String mdcId) {
        return ApiResponse.ok(mock.getCostOptimization());
    }

    /** 인프라 증설 진단 */
    @GetMapping("/infra-scaling")
    public ApiResponse<?> getInfraScalingRecommendation(@RequestParam(required = false) String mdcId) {
        return ApiResponse.ok(mock.getCostOptimization());
    }

    /** VM 자원 적정 사이징 진단 */
    @GetMapping("/vm-rightsizing")
    public ApiResponse<?> getVmRightsizing(@RequestParam(required = false) String mdcId) {
        return ApiResponse.ok(mock.getVmRightsizing());
    }

    /** 진단 환경 설정 조회 */
    @GetMapping("/settings")
    public ApiResponse<?> getOptimizationSettings() {
        return ApiResponse.ok(Map.of(
                "cpuThreshold", 80.0,
                "memoryThreshold", 90.0,
                "diskThreshold", 85.0,
                "idleDays", 30,
                "analysisInterval", "DAILY",
                "enabled", true
        ));
    }

    /** 진단 환경 설정 변경 (임계치, 진단 주기 등) */
    @PutMapping("/settings")
    public ApiResponse<?> updateOptimizationSettings(@RequestBody Object request) {
        return ApiResponse.ok(null);
    }
}
