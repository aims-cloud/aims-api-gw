package com.innogrid.aims.billing.controller;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.metering.entity.MeteringHourly;
import com.innogrid.aims.metering.repository.MeteringHourlyRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "최적화 진단", description = "비용/자원 최적화 진단")
@RestController
@RequestMapping("/api/v1/optimization")
public class OptimizationController {

    private final MeteringHourlyRepository meteringRepository;

    public OptimizationController(MeteringHourlyRepository meteringRepository) {
        this.meteringRepository = meteringRepository;
    }

    @Operation(summary = "비용 최적화 진단", description = "자원 사용 패턴 분석 기반 비용 절감 권고")
    @GetMapping("/cost")
    public ApiResponse<?> getCostOptimization(@RequestParam(required = false) String mdcId) {
        OffsetDateTime from = OffsetDateTime.now().minusDays(30);
        OffsetDateTime to = OffsetDateTime.now();
        List<MeteringHourly> data = mdcId != null
                ? meteringRepository.findByMdcIdAndTimeBetweenOrderByTimeDesc(mdcId, from, to)
                : meteringRepository.findByTimeBetweenOrderByTimeDesc(from, to);

        List<Map<String, Object>> recommendations = analyzeOptimization(data);
        double totalSaving = recommendations.stream()
                .mapToDouble(r -> ((Number) r.get("monthlySaving")).doubleValue())
                .sum();

        return ApiResponse.ok(Map.of(
                "totalSavingPotential", Math.round(totalSaving),
                "recommendations", recommendations,
                "analysisPeriod", Map.of("from", from, "to", to),
                "dataPoints", data.size()
        ));
    }

    @Operation(summary = "VM 라이트사이징 진단", description = "과다/과소 할당 자원 분석")
    @GetMapping("/vm-rightsizing")
    public ApiResponse<?> getVmRightsizing(@RequestParam(required = false) String mdcId) {
        OffsetDateTime from = OffsetDateTime.now().minusDays(30);
        OffsetDateTime to = OffsetDateTime.now();
        List<MeteringHourly> data = mdcId != null
                ? meteringRepository.findByMdcIdAndMetricNameAndTimeBetweenOrderByTimeDesc(mdcId, "cpu_usage", from, to)
                : meteringRepository.findByTimeBetweenOrderByTimeDesc(from, to);

        // 리소스별 평균 사용률 계산
        Map<String, DoubleSummaryStatistics> stats = data.stream()
                .collect(Collectors.groupingBy(MeteringHourly::getResourceId,
                        Collectors.summarizingDouble(MeteringHourly::getAvgValue)));

        List<Map<String, Object>> analysis = stats.entrySet().stream().map(e -> {
            double avg = e.getValue().getAverage();
            String status = avg < 10 ? "IDLE" : avg < 30 ? "OVERSIZED" : "OK";
            return Map.<String, Object>of(
                    "resourceId", e.getKey(),
                    "avgUsage", Math.round(avg * 100.0) / 100.0,
                    "maxUsage", Math.round(e.getValue().getMax() * 100.0) / 100.0,
                    "status", status,
                    "sampleCount", e.getValue().getCount()
            );
        }).toList();

        return ApiResponse.ok(Map.of("vmAnalysis", analysis));
    }

    @Operation(summary = "인프라 증설 진단")
    @GetMapping("/infra-scaling")
    public ApiResponse<?> getInfraScalingRecommendation(@RequestParam(required = false) String mdcId) {
        return getCostOptimization(mdcId);
    }

    @Operation(summary = "진단 환경 설정 조회")
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

    @Operation(summary = "진단 환경 설정 변경")
    @PutMapping("/settings")
    public ApiResponse<?> updateOptimizationSettings(@RequestBody Object request) {
        return ApiResponse.ok(null);
    }

    private List<Map<String, Object>> analyzeOptimization(List<MeteringHourly> data) {
        List<Map<String, Object>> recommendations = new ArrayList<>();
        if (data.isEmpty()) return recommendations;

        Map<String, DoubleSummaryStatistics> byResource = data.stream()
                .collect(Collectors.groupingBy(MeteringHourly::getResourceId,
                        Collectors.summarizingDouble(MeteringHourly::getAvgValue)));

        for (Map.Entry<String, DoubleSummaryStatistics> entry : byResource.entrySet()) {
            double avg = entry.getValue().getAverage();
            if (avg < 10) {
                recommendations.add(Map.of(
                        "type", "IDLE_RESOURCE",
                        "target", entry.getKey(),
                        "avgUsage", Math.round(avg * 100.0) / 100.0,
                        "recommendation", "유휴 자원 — 삭제 또는 축소 권고",
                        "monthlySaving", 80000
                ));
            } else if (avg < 30) {
                recommendations.add(Map.of(
                        "type", "VM_RIGHTSIZING",
                        "target", entry.getKey(),
                        "avgUsage", Math.round(avg * 100.0) / 100.0,
                        "recommendation", "과대 할당 — 스펙 축소 권고",
                        "monthlySaving", 50000
                ));
            }
        }
        return recommendations;
    }
}
