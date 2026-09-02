package com.innogrid.aims.scheduler.controller;

import java.util.List;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.scheduler.dto.SchedulingPolicyRequest;
import com.innogrid.aims.scheduler.dto.SchedulingPolicyResponse;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.service.SchedulingPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 배치 정책 API. 요청은 {@link SchedulingPolicyRequest}, 응답은 {@link SchedulingPolicyResponse}.
 * 검증·저장 규칙은 {@link SchedulingPolicyService} 에 있다.
 */
@Tag(name = "스케줄링 정책", description = "스케줄링 가중치, 임계치, 전력/온도 인지 설정")
@RestController
@RequestMapping("/api/v1/scheduler/policies")
public class SchedulingPolicyController {

    private final SchedulingPolicyRepository repository;
    private final SchedulingPolicyService service;

    public SchedulingPolicyController(SchedulingPolicyRepository repository, SchedulingPolicyService service) {
        this.repository = repository;
        this.service = service;
    }

    @Operation(summary = "정책 목록")
    @GetMapping
    public ApiResponse<List<SchedulingPolicyResponse>> listPolicies() {
        return ApiResponse.ok(repository.findAll().stream().map(SchedulingPolicyResponse::from).toList());
    }

    @Operation(summary = "정책 생성",
               description = "name 은 필수·중복 불가(409). 가중치는 0 이상이고 합이 0 보다 커야 하며(합≠1 허용), "
                       + "사용률 임계는 0 초과 100 이하, 온도 임계는 0 초과. defaultEngine 은 등록된 엔진이어야 합니다.")
    @PostMapping
    public ApiResponse<SchedulingPolicyResponse> createPolicy(@RequestBody SchedulingPolicyRequest request) {
        return ApiResponse.ok(SchedulingPolicyResponse.from(service.create(request)));
    }

    @Operation(summary = "정책 상세")
    @GetMapping("/{policyId}")
    public ApiResponse<SchedulingPolicyResponse> getPolicy(@PathVariable Long policyId) {
        return ApiResponse.ok(SchedulingPolicyResponse.from(service.require(policyId)));
    }

    @Operation(summary = "정책 수정",
               description = "부분 수정 — 요청에 있는 키만 반영합니다. resourceType 에 null 을 주면 공통 정책으로, "
                       + "defaultEngine 에 null 을 주면 기본 엔진으로, warningTemperatureCelsius 에 null 을 주면 설정 기본값으로 돌아갑니다.")
    @PutMapping("/{policyId}")
    public ApiResponse<SchedulingPolicyResponse> updatePolicy(@PathVariable Long policyId,
                                                              @RequestBody SchedulingPolicyRequest request) {
        return ApiResponse.ok(SchedulingPolicyResponse.from(service.update(policyId, request)));
    }

    @Operation(summary = "정책 삭제")
    @DeleteMapping("/{policyId}")
    public ApiResponse<Void> deletePolicy(@PathVariable Long policyId) {
        repository.deleteById(policyId);
        return ApiResponse.ok(null);
    }
}
