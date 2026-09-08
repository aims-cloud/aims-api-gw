package com.innogrid.aims.monitoring.controller.alarm;

import java.time.LocalDate;
import java.util.List;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmAcknowledgeRequest;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmAcknowledgementResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmChannel;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmDeliveryResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmHistoryListItemResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmHistorySearchField;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmLevel;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmMessageDetailResponse;
import com.innogrid.aims.monitoring.service.alarm.AlarmHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관측 > 알람 > 발생 이력 탭 API.
 *
 * 발생 이력 목록(필터·정렬·페이징), 행 확장 전송 이력, 메시지 상세 모달,
 * 알람 확인(쓰기)·확인 내역 모달을 제공한다 (docs/09 알람 설계).
 * 목록은 alarm_history 원장(구현 완료), 나머지는 전송 원장·확인 처리 구현 시 채운다.
 * 구 AlarmController 의 GET /history(명세서 §18)는 이 컨트롤러의 /histories 로 대체되어 제거됐다.
 */
@Tag(name = "관측 > 알람 (발생 이력)", description = "알람 발생 이력 목록, 전송 이력, 메시지 상세, 알람 확인")
@RestController
@RequestMapping("/api/v1/monitoring/alarms")
public class AlarmHistoryController {

    private final AlarmHistoryService alarmHistoryService;

    public AlarmHistoryController(AlarmHistoryService alarmHistoryService) {
        this.alarmHistoryService = alarmHistoryService;
    }

    @Operation(summary = "알람 발생 이력 목록",
            description = "발생 일시/레벨/원천/상태/대상 자원/기준/관측값/역할/자원 영역/정책명/전송 이력 수/확인·억제 여부. "
                    + "레벨(다중)·역할(roleName)·자원 영역(areaId — 트리의 MDC 는 mdcId, Namespace 는 TB:{nsId}, 역할 패널 areaId 와 동일)·"
                    + "자원 유형(node|vm|gpu)·특정 자원(resourceId — 자원 탐색기 알람 탭에서 사용)·"
                    + "정책(policyId — 정책 탭 발생 이력에서 사용)·기간·검색(searchField ALL|RESOURCE_NAME|POLICY_NAME + keyword) 필터와 "
                    + "컬럼 정렬, 페이징을 지원합니다. 역할 스코프는 토큰 역할 + 역할 없는 행(시스템·외부 알람)입니다. "
                    + "page 는 1부터, sort 는 '{필드},{asc|desc}' 형식, from/to 는 YYYY-MM-DD(양끝 포함, 한쪽만 지정 가능, 미지정 시 전체)입니다. "
                    + "areaName 은 MDC 는 mdcId, Namespace 는 Tumblebug 표시명입니다.")
    @GetMapping("/histories")
    public ApiResponse<PageResponse<AlarmHistoryListItemResponse>> getHistories(
            @RequestParam(required = false) List<AlarmLevel> level,
            @RequestParam(required = false) String roleName,
            @RequestParam(required = false) String areaId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) Long policyId,
            @RequestParam(defaultValue = "ALL") AlarmHistorySearchField searchField,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "occurredAt,desc") String sort) {
        return ApiResponse.ok(alarmHistoryService.getHistories(level, roleName, areaId, resourceType, resourceId,
                policyId, searchField, keyword, from, to, page, size, sort));
    }

    @Operation(summary = "알람 전송 이력",
            description = "목록 행 확장 서브테이블 — 대상(개인/조직)별 Email·SMS 전송 결과를 조회합니다. "
                    + "개인은 채널별 단일 상태, 조직은 채널별 성공/전송중/실패 카운트입니다.")
    @GetMapping("/histories/{alarmId}/deliveries")
    public ApiResponse<List<AlarmDeliveryResponse>> getDeliveries(@PathVariable Long alarmId) {
        return ApiResponse.ok(alarmHistoryService.getDeliveries(alarmId));
    }

    @Operation(summary = "메시지 상세",
            description = "전송 이력의 이메일/SMS 버튼 모달 — 정책명/대상 자원/알람 방식/전송 대상, "
                    + "발송 메시지 본문, 전송 대상 목록(수신 주소·전송 결과·실패 사유·전송 일시)을 조회합니다.")
    @GetMapping("/histories/{alarmId}/deliveries/{deliveryId}/message")
    public ApiResponse<AlarmMessageDetailResponse> getMessageDetail(
            @PathVariable Long alarmId,
            @PathVariable Long deliveryId,
            @RequestParam AlarmChannel channel) {
        return ApiResponse.ok(alarmHistoryService.getMessageDetail(alarmId, deliveryId, channel));
    }

    @Operation(summary = "알람 확인",
            description = "미확인 알람을 확인 처리합니다 — 확인 내용(필수, 공백 불가)을 저장하고 "
                    + "확인자·확인 시각은 인증 사용자 기준으로 서버가 기록합니다. 이미 확인된 알람은 409, "
                    + "존재하지 않거나 역할 스코프 밖이면 404 입니다. 확인은 포털 로그(ALARM)에도 남습니다.")
    @PostMapping("/histories/{alarmId}/acknowledge")
    public ApiResponse<AlarmAcknowledgementResponse> acknowledge(
            @PathVariable Long alarmId,
            @RequestBody AlarmAcknowledgeRequest request) {
        return ApiResponse.ok(alarmHistoryService.acknowledge(alarmId, request));
    }

    @Operation(summary = "알람 확인 내역",
            description = "확인 완료된 알람의 확인 내역 모달 — 발생 정보와 확인 일시/확인자/확인 내용을 조회합니다. "
                    + "미확인 알람이거나 역할 스코프 밖이면 404 입니다.")
    @GetMapping("/histories/{alarmId}/acknowledgement")
    public ApiResponse<AlarmAcknowledgementResponse> getAcknowledgement(@PathVariable Long alarmId) {
        return ApiResponse.ok(alarmHistoryService.getAcknowledgement(alarmId));
    }
}
