package com.innogrid.aims.monitoring.controller.alarm;

import java.util.List;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmLevel;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmMetricCatalogResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyDetailResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyListItemResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyRecipientResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyRequest;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyResourceResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmRecipientCandidatesResponse;
import com.innogrid.aims.monitoring.service.alarm.AlarmPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관측 > 알람 > 정책 탭 API.
 *
 * 정책 목록(필터·정렬·페이징)·상세와 생성/편집/복제/삭제(다중),
 * 하단 탭(대상 자원/전송 대상), 생성 위저드 보조(메트릭 카탈로그·수신자 후보 조직 트리)를 제공한다.
 * 발생 이력 탭은 AlarmHistoryController 의 /histories?policyId= 를 재사용하고,
 * 조건 설정의 미리보기 차트는 자원 탐색기 trend API 를 재사용한다.
 * 기존 AlarmController 의 규칙 CRUD(/rules)는 이 컨트롤러로 대체 예정이다.
 */
@Tag(name = "관측 > 알람 (정책)", description = "알람 정책 목록·상세·CRUD·복제, 대상 자원/전송 대상, 생성 위저드 보조")
@RestController
@RequestMapping("/api/v1/monitoring/alarms/policies")
public class AlarmPolicyController {

    private final AlarmPolicyService alarmPolicyService;

    public AlarmPolicyController(AlarmPolicyService alarmPolicyService) {
        this.alarmPolicyService = alarmPolicyService;
    }

    @Operation(summary = "알람 정책 목록",
            description = "정책명/역할/배포영역/대상 자원 유형/기준/알람 레벨/생성자/생성 일시. "
                    + "역할·배포영역·레벨·키워드 필터와 컬럼 정렬, 페이징을 지원합니다. "
                    + "page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다.")
    @GetMapping
    public ApiResponse<PageResponse<AlarmPolicyListItemResponse>> getPolicies(
            @RequestParam(required = false) String roleId,
            @RequestParam(required = false) String deploymentId,
            @RequestParam(required = false) AlarmLevel level,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return ApiResponse.ok(alarmPolicyService.getPolicies(
                roleId, deploymentId, level, keyword, page, size, sort));
    }

    @Operation(summary = "알람 정책 상세",
            description = "하단 상세 탭과 알람 정책 상세 페이지 상단 표 — 기준/알림 방식/조건 지속 시간/설명 등을 조회합니다.")
    @GetMapping("/{policyId}")
    public ApiResponse<AlarmPolicyDetailResponse> getPolicy(@PathVariable Long policyId) {
        return ApiResponse.ok(alarmPolicyService.getPolicy(policyId));
    }

    @Operation(summary = "알람 정책 생성",
            description = "정책 생성 위저드 4단계(대상 선택 → 조건 설정 → 전송 설정 → 요약) payload 로 생성합니다. "
                    + "로그 알람의 조건 필드는 화면 확정 전 잠정입니다.")
    @PostMapping
    public ApiResponse<AlarmPolicyDetailResponse> createPolicy(@RequestBody AlarmPolicyRequest request) {
        return ApiResponse.ok(alarmPolicyService.createPolicy(request));
    }

    @Operation(summary = "알람 정책 편집")
    @PutMapping("/{policyId}")
    public ApiResponse<AlarmPolicyDetailResponse> updatePolicy(
            @PathVariable Long policyId,
            @RequestBody AlarmPolicyRequest request) {
        return ApiResponse.ok(alarmPolicyService.updatePolicy(policyId, request));
    }

    @Operation(summary = "알람 정책 복제",
            description = "선택한 정책의 설정을 복사한 새 정책을 생성합니다.")
    @PostMapping("/{policyId}/duplicate")
    public ApiResponse<AlarmPolicyDetailResponse> duplicatePolicy(@PathVariable Long policyId) {
        return ApiResponse.ok(alarmPolicyService.duplicatePolicy(policyId));
    }

    @Operation(summary = "알람 정책 삭제",
            description = "목록 체크박스로 선택한 정책들을 삭제합니다. ids 는 콤마 구분 다중 지정입니다.")
    @DeleteMapping
    public ApiResponse<Void> deletePolicies(@RequestParam List<Long> ids) {
        alarmPolicyService.deletePolicies(ids);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "정책 대상 자원 목록",
            description = "대상 자원 탭 — 정책이 적용되는 자원(이름/별칭/ID) 목록. 키워드 필터와 페이징을 지원합니다.")
    @GetMapping("/{policyId}/resources")
    public ApiResponse<PageResponse<AlarmPolicyResourceResponse>> getPolicyResources(
            @PathVariable Long policyId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name,asc") String sort) {
        return ApiResponse.ok(alarmPolicyService.getPolicyResources(policyId, keyword, page, size, sort));
    }

    @Operation(summary = "정책 전송 대상 목록",
            description = "전송 대상 탭 — 정책의 수신자(이름/유형: 조직·개인) 목록을 조회합니다.")
    @GetMapping("/{policyId}/recipients")
    public ApiResponse<List<AlarmPolicyRecipientResponse>> getPolicyRecipients(@PathVariable Long policyId) {
        return ApiResponse.ok(alarmPolicyService.getPolicyRecipients(policyId));
    }

    @Operation(summary = "메트릭 카탈로그",
            description = "생성 위저드 조건 설정 — 유형/메트릭 드롭다운 옵션. "
                    + "미리보기 차트는 자원 탐색기 trend API 를 재사용합니다.")
    @GetMapping("/metric-catalog")
    public ApiResponse<List<AlarmMetricCatalogResponse>> getMetricCatalog(
            @RequestParam(required = false) String resourceType) {
        return ApiResponse.ok(alarmPolicyService.getMetricCatalog(resourceType));
    }

    @Operation(summary = "수신자 후보 조회",
            description = "생성 위저드 전송 설정 — 수신자 선택용 조직 트리(하위 조직·멤버)를 조회합니다.")
    @GetMapping("/recipient-candidates")
    public ApiResponse<AlarmRecipientCandidatesResponse> getRecipientCandidates(
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(alarmPolicyService.getRecipientCandidates(keyword));
    }
}
