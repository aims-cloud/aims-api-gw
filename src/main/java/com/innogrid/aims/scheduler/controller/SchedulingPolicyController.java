package com.innogrid.aims.scheduler.controller;

import java.util.List;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.scheduler.dto.ActivationResponse;
import com.innogrid.aims.scheduler.dto.SchedulingPolicyRequest;
import com.innogrid.aims.scheduler.dto.SchedulingPolicyResponse;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.service.SchedulingPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 배치 정책 API. 요청은 {@link SchedulingPolicyRequest}, 응답은 {@link SchedulingPolicyResponse}.
 * 검증·저장 규칙은 {@link SchedulingPolicyService}에 있다.
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
               description = "name 은 필수·중복 불가(409). active 를 생략하면 그 리소스 종류(공통 포함)에 활성 정책이 없을 때만 활성으로 만들어집니다 — "
                       + "응답의 active 를 확인하세요. 명시적 active:true 가 다른 활성과 충돌하면 409. "
                       + "가중치는 0 이상이고 합이 0 보다 커야 하며(합≠1 허용), "
                       + "사용률 임계는 0 초과 100 이하, 온도 임계는 0 초과. defaultEngine 은 등록된 엔진이어야 합니다. "
                       + "affinityEnabled 를 켜면 dataMdcId 가 있는 워크로드에 MDC 링크 제약(C5)과 링크 점수가 적용됩니다(기본 꺼짐).")
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
               description = "부분 수정 — 요청에 있는 키만 반영합니다. active:true 는 같은 종류에 다른 활성 정책이 있으면 409(전환은 /activate). "
                       + "active:false 는 항상 허용되며 활성이 0 이면 배치는 default 값을 씁니다. resourceType 에 null 을 주면 공통 정책으로, "
                       + "defaultEngine 에 null 을 주면 기본 엔진으로, warningTemperatureCelsius 에 null 을 주면 설정 기본값으로 돌아갑니다.")
    @PutMapping("/{policyId}")
    public ApiResponse<SchedulingPolicyResponse> updatePolicy(@PathVariable Long policyId,
                                                              @RequestBody SchedulingPolicyRequest request) {
        return ApiResponse.ok(SchedulingPolicyResponse.from(service.update(policyId, request)));
    }

    @Operation(summary = "정책 전환(활성화)",
               description = "같은 리소스 종류의 다른 활성 정책을 끄고 이 정책을 켭니다(한 트랜잭션). 응답에 켜진 정책과 꺼진 정책 목록을 함께 돌려줍니다. "
                       + "이미 활성이면 꺼지는 것 없이 200. PUT 으로 active:true 를 주는 것은 충돌 시 409 이므로 전환은 이 API 를 쓰세요. 없으면 404.")
    @PostMapping("/{policyId}/activate")
    public ApiResponse<ActivationResponse> activatePolicy(@PathVariable Long policyId) {
        return ApiResponse.ok(service.activate(policyId));
    }

    @Operation(summary = "정책 삭제",
               description = "없으면 404. 스케줄이 참조 중이면 409 — 먼저 스케줄의 정책을 바꾸거나 스케줄을 삭제해야 합니다.")
    @DeleteMapping("/{policyId}")
    public ApiResponse<Void> deletePolicy(@PathVariable Long policyId) {
        service.delete(policyId);
        return ApiResponse.ok(null);
    }
}
