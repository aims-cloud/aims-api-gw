package com.innogrid.aims.monitoring.controller;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.mock.MockDataProvider;
import com.innogrid.aims.monitoring.dto.MonitoringDto;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 알람 관리 API.
 * [요구사항 #48] 알람 설정 — 임계치·조건 기반 알람 규칙, 알림 채널 설정
 * [요구사항 #49] 알람 이력 — 알람 이력·처리 상태 통합 조회
 */
@Tag(name = "알람", description = "알람 규칙 설정, 알람 이력")
@RestController
@RequestMapping("/api/v1/monitoring/alarms")
public class AlarmController {

    private final MockDataProvider mock;

    public AlarmController(MockDataProvider mock) {
        this.mock = mock;
    }

    // ── 알람 규칙 설정 ──────────────────────────────────────────

    /** 알람 규칙 목록 */
    @GetMapping("/rules")
    public ApiResponse<?> listAlarmRules() {
        return ApiResponse.ok(mock.getAlarmRules());
    }

    /** 알람 규칙 생성 */
    @PostMapping("/rules")
    public ApiResponse<?> createAlarmRule(@RequestBody MonitoringDto.AlarmRuleRequest request) {
        return ApiResponse.ok(null);
    }

    /** 알람 규칙 수정 */
    @PutMapping("/rules/{ruleId}")
    public ApiResponse<?> updateAlarmRule(@PathVariable Long ruleId,
                                          @RequestBody MonitoringDto.AlarmRuleRequest request) {
        return ApiResponse.ok(null);
    }

    /** 알람 규칙 삭제 */
    @DeleteMapping("/rules/{ruleId}")
    public ApiResponse<Void> deleteAlarmRule(@PathVariable Long ruleId) {
        return ApiResponse.ok(null);
    }

    // ── 알람 이력 ───────────────────────────────────────────────

    /** 알람 이력 조회 */
    @GetMapping("/history")
    public ApiResponse<?> listAlarmHistory(@RequestParam(required = false) String mdcId,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(mock.getAlarmHistory());
    }

    /** 알람 처리 상태 변경 (확인/해결) */
    @PatchMapping("/history/{alarmId}/status")
    public ApiResponse<Void> updateAlarmStatus(@PathVariable Long alarmId,
                                               @RequestParam String status) {
        return ApiResponse.ok(null);
    }
}
