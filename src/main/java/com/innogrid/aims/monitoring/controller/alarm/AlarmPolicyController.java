package com.innogrid.aims.monitoring.controller.alarm;

import java.util.List;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.log.PortalLog;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
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
 * 구 AlarmController 의 규칙 CRUD(/rules)와 alarm_rules 원장은 이 컨트롤러·정책 원장으로 대체되어 제거됐다.
 *
 * 포털 로그: 생성·편집·복제·삭제는 {@code @PortalLog(AUDIT)} 로 남긴다(ref = ALARM_POLICY). 조회는 남기지 않는다.
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
            description = "정책명/역할/배포영역/대상 자원 유형/대상 자원 수/기준/알람 레벨/알림 방식/생성자/생성 일시. "
                    + "역할·배포영역·자원 유형·특정 자원(resourceId — 자원 탐색기 알람 탭에서 해당 자원이 대상에 포함된 "
                    + "정책만 조회)·레벨·키워드 필터와 컬럼 정렬, 페이징을 지원합니다. "
                    + "page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다.")
    @GetMapping
    public ApiResponse<PageResponse<AlarmPolicyListItemResponse>> getPolicies(
            @RequestParam(required = false) String roleName,
            @RequestParam(required = false) String areaId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) AlarmLevel level,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return ApiResponse.ok(alarmPolicyService.getPolicies(roleName, areaId, resourceType, resourceId, level, keyword, page, size, sort));
    }

    @Operation(summary = "알람 정책 상세",
            description = "하단 상세 탭과 알람 정책 상세 페이지 상단 표 — 기준/알림 방식/조건 지속 시간/설명 등을 조회합니다.")
    @GetMapping("/{policyId}")
    public ApiResponse<AlarmPolicyDetailResponse> getPolicy(@PathVariable Long policyId) {
        return ApiResponse.ok(alarmPolicyService.getPolicy(policyId));
    }

    @Operation(summary = "알람 정책 생성",
            description = "정책 생성 위저드 4단계(대상 선택 → 조건 설정 → 전송 설정 → 요약) payload 로 생성합니다. "
                    + "roleName 은 현재 사용자 토큰의 역할이어야 하고, areaId 는 mdcId 또는 TB:{nsId}(없으면 역할 전체), "
                    + "resourceType 은 node|vm|gpu, condition.metric 은 메트릭 카탈로그 코드(초안 별칭 허용)이며 자원 유형에 맞아야 합니다. "
                    + "targetScope=INDIVIDUAL 이면 targetResourceIds 1개 이상, 채널·수신자 1개 이상(개인 = users.id, 조직 = 조직명). "
                    + "위반은 400. 로그 알람(alarmType=LOG)은 1차 범위 밖이라 400 입니다.")
    @PortalLog(screen = "알람 정책", action = "생성", content = "'알람 정책 생성: ' + #request.policyName",
               refType = "ALARM_POLICY", refId = "#result.data.policyId")
    @PostMapping
    public ApiResponse<AlarmPolicyDetailResponse> createPolicy(@RequestBody AlarmPolicyRequest request) {
        return ApiResponse.ok(alarmPolicyService.createPolicy(request));
    }

    @Operation(summary = "알람 정책 편집", description = "생성과 같은 payload 로 전체 값을 바꿉니다. 검증 규칙도 같습니다. 스코프 밖·없는 정책은 404.")
    @PortalLog(screen = "알람 정책", action = "수정", content = "'알람 정책 수정: ' + #request.policyName + ' (id ' + #policyId + ')'",
               refType = "ALARM_POLICY", refId = "#policyId")
    @PutMapping("/{policyId}")
    public ApiResponse<AlarmPolicyDetailResponse> updatePolicy(
            @PathVariable Long policyId,
            @RequestBody AlarmPolicyRequest request) {
        return ApiResponse.ok(alarmPolicyService.updatePolicy(policyId, request));
    }

    @Operation(summary = "알람 정책 복제",
            description = "선택한 정책의 설정을 복사한 새 정책을 '{이름} (복제)' 로 생성합니다. 복제한 사용자가 생성자가 됩니다.")
    @PortalLog(screen = "알람 정책", action = "복제", content = "'알람 정책 복제: id ' + #policyId + ' → ' + #result.data.policyName",
               refType = "ALARM_POLICY", refId = "#result.data.policyId")
    @PostMapping("/{policyId}/duplicate")
    public ApiResponse<AlarmPolicyDetailResponse> duplicatePolicy(@PathVariable Long policyId) {
        return ApiResponse.ok(alarmPolicyService.duplicatePolicy(policyId));
    }

    @Operation(summary = "알람 정책 삭제",
            description = "목록 체크박스로 선택한 정책들을 삭제합니다. ids 는 콤마 구분 다중 지정이며, 하나라도 없거나 스코프 밖이면 404 로 전체 거부합니다.")
    @PortalLog(screen = "알람 정책", action = "삭제", content = "'알람 정책 삭제: id ' + #ids", refType = "ALARM_POLICY", refId = "#ids")
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
            description = "생성 위저드 조건 설정 — 유형(CPU/Memory/File System/GPU)별 메트릭 드롭다운 옵션. "
                    + "metric 은 정책에 저장하는 코드값(예: CPU_USED_PERCENT), displayName+unit 은 기준 문자열(CPU Used > 80%) 표기용입니다. "
                    + "resourceType(node|vm|gpu)을 주면 그 자원 유형에 쓸 수 있는 메트릭만 돌려주고, 모르는 유형은 400 입니다. "
                    + "미리보기 차트는 자원 탐색기 trend API 를 재사용합니다.")
    @GetMapping("/metric-catalog")
    public ApiResponse<List<AlarmMetricCatalogResponse>> getMetricCatalog(
            @RequestParam(required = false) String resourceType) {
        return ApiResponse.ok(alarmPolicyService.getMetricCatalog(resourceType));
    }

    @Operation(summary = "수신자 후보 조회",
            description = "전송 설정의 수신자 선택 — 조직 트리(하위 조직·멤버)를 조회합니다. "
                    + "조직 원장 확정 전이라 users.organization 문자열을 1단계 조직으로 삼고(하위 조직 없음), 조직 없는 사용자는 '미지정'에 묶입니다. "
                    + "orgId 는 조직명 그대로이며 정책 수신자(ORGANIZATION)의 targetId 로 씁니다. keyword 는 조직명·이름·이메일 부분 일치.")
    @GetMapping("/recipient-candidates")
    public ApiResponse<AlarmRecipientCandidatesResponse> getRecipientCandidates(
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(alarmPolicyService.getRecipientCandidates(keyword));
    }
}
