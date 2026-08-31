package com.innogrid.aims.scheduler.controller;

import java.util.Map;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "스케줄링 정책", description = "스케줄링 가중치, 임계치, 전력/온도 인지 설정")
@RestController
@RequestMapping("/api/v1/scheduler/policies")
public class SchedulingPolicyController {

    private final SchedulingPolicyRepository repository;

    public SchedulingPolicyController(SchedulingPolicyRepository repository) {
        this.repository = repository;
    }

    @Operation(summary = "정책 목록")
    @GetMapping
    public ApiResponse<?> listPolicies() {
        return ApiResponse.ok(repository.findAll());
    }

    @Operation(summary = "정책 생성")
    @PostMapping
    public ApiResponse<?> createPolicy(@RequestBody Map<String, Object> request) {
        SchedulingPolicy policy = new SchedulingPolicy(
                (String) request.get("name"),
                (String) request.getOrDefault("description", ""));
        applyWeights(policy, request);
        return ApiResponse.ok(repository.save(policy));
    }

    @Operation(summary = "정책 상세")
    @GetMapping("/{policyId}")
    public ApiResponse<?> getPolicy(@PathVariable Long policyId) {
        return ApiResponse.ok(repository.findById(policyId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "정책을 찾을 수 없습니다")));
    }

    @Operation(summary = "정책 수정")
    @PutMapping("/{policyId}")
    public ApiResponse<?> updatePolicy(@PathVariable Long policyId,
                                       @RequestBody Map<String, Object> request) {
        SchedulingPolicy policy = repository.findById(policyId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "정책을 찾을 수 없습니다"));
        if (request.containsKey("name")) policy.setName((String) request.get("name"));
        if (request.containsKey("description")) policy.setDescription((String) request.get("description"));
        applyWeights(policy, request);
        return ApiResponse.ok(repository.save(policy));
    }

    @Operation(summary = "정책 삭제")
    @DeleteMapping("/{policyId}")
    public ApiResponse<Void> deletePolicy(@PathVariable Long policyId) {
        repository.deleteById(policyId);
        return ApiResponse.ok(null);
    }

    private void applyWeights(SchedulingPolicy policy, Map<String, Object> request) {
        if (request.containsKey("resourceType")) {
            Object raw = request.get("resourceType");
            policy.setResourceType(raw == null ? null : parseResourceType(raw.toString()));
        }
        if (request.containsKey("defaultEngine"))
            policy.setDefaultEngine((String) request.get("defaultEngine"));
        if (request.containsKey("warningTemperatureCelsius")) {
            Object raw = request.get("warningTemperatureCelsius");
            policy.setWarningTemperatureCelsius(raw == null ? null : ((Number) raw).doubleValue());
        }
        if (request.containsKey("active")) policy.setActive((Boolean) request.get("active"));
        if (request.containsKey("cpuWeight")) policy.setCpuWeight(((Number) request.get("cpuWeight")).doubleValue());
        if (request.containsKey("memoryWeight")) policy.setMemoryWeight(((Number) request.get("memoryWeight")).doubleValue());
        if (request.containsKey("powerWeight")) policy.setPowerWeight(((Number) request.get("powerWeight")).doubleValue());
        if (request.containsKey("temperatureWeight")) policy.setTemperatureWeight(((Number) request.get("temperatureWeight")).doubleValue());
        if (request.containsKey("networkWeight")) policy.setNetworkWeight(((Number) request.get("networkWeight")).doubleValue());
        if (request.containsKey("maxCpuUsagePercent")) policy.setMaxCpuUsagePercent(((Number) request.get("maxCpuUsagePercent")).doubleValue());
        if (request.containsKey("maxMemoryUsagePercent")) policy.setMaxMemoryUsagePercent(((Number) request.get("maxMemoryUsagePercent")).doubleValue());
        if (request.containsKey("maxTemperatureCelsius")) policy.setMaxTemperatureCelsius(((Number) request.get("maxTemperatureCelsius")).doubleValue());
        if (request.containsKey("maxPowerUsagePercent")) policy.setMaxPowerUsagePercent(((Number) request.get("maxPowerUsagePercent")).doubleValue());
        if (request.containsKey("powerAwareEnabled")) policy.setPowerAwareEnabled((Boolean) request.get("powerAwareEnabled"));
        if (request.containsKey("temperatureAwareEnabled")) policy.setTemperatureAwareEnabled((Boolean) request.get("temperatureAwareEnabled"));
    }

    /** 잘못된 리소스 종류가 500 이 아니라 400 이 되도록 감싼다. */
    private Workload.ResourceType parseResourceType(String value) {
        try {
            return Workload.ResourceType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, String.format(
                    "지원하지 않는 리소스 종류입니다: %s (가능한 값: %s)",
                    value, java.util.Arrays.toString(Workload.ResourceType.values())));
        }
    }
}
