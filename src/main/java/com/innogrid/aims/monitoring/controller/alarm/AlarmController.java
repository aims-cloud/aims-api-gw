package com.innogrid.aims.monitoring.controller.alarm;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmRuleRequest;
import com.innogrid.aims.monitoring.entity.AlarmRule;
import com.innogrid.aims.monitoring.repository.AlarmRuleRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Deprecated
@Tag(name = "알람", description = "알람 규칙 설정, 알람 이력")
@RestController
@RequestMapping("/api/v1/monitoring/alarms")
public class AlarmController {

    private final AlarmRuleRepository ruleRepository;

    public AlarmController(AlarmRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @Operation(summary = "알람 규칙 목록")
    @GetMapping("/rules")
    public ApiResponse<?> listAlarmRules() {
        return ApiResponse.ok(ruleRepository.findAll());
    }

    @Operation(summary = "알람 규칙 생성")
    @PostMapping("/rules")
    public ApiResponse<?> createAlarmRule(@RequestBody AlarmRuleRequest request) {
        AlarmRule rule = new AlarmRule(request.name(), request.metricName(),
                request.condition(), request.threshold(), request.severity(), request.notifyChannel());
        return ApiResponse.ok(ruleRepository.save(rule));
    }

    @Operation(summary = "알람 규칙 수정")
    @PutMapping("/rules/{ruleId}")
    public ApiResponse<?> updateAlarmRule(@PathVariable Long ruleId,
                                          @RequestBody AlarmRuleRequest request) {
        AlarmRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "알람 규칙을 찾을 수 없습니다"));
        rule.setName(request.name());
        rule.setMetricName(request.metricName());
        rule.setCondition(request.condition());
        rule.setThreshold(request.threshold());
        rule.setSeverity(request.severity());
        rule.setNotifyChannel(request.notifyChannel());
        return ApiResponse.ok(ruleRepository.save(rule));
    }

    @Operation(summary = "알람 규칙 삭제")
    @DeleteMapping("/rules/{ruleId}")
    public ApiResponse<Void> deleteAlarmRule(@PathVariable Long ruleId) {
        ruleRepository.deleteById(ruleId);
        return ApiResponse.ok(null);
    }


}
