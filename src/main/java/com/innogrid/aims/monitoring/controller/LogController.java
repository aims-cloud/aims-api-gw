package com.innogrid.aims.monitoring.controller;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.monitoring.repository.SystemLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "로그", description = "로그 탐색, 시스템 로그")
@RestController
@RequestMapping("/api/v1/monitoring/logs")
public class LogController {

    private final SystemLogRepository logRepository;

    public LogController(SystemLogRepository logRepository) {
        this.logRepository = logRepository;
    }

    @Operation(summary = "로그 검색", description = "MDC, 키워드, 로그 레벨 기반 조건 검색")
    @GetMapping("/search")
    public ApiResponse<?> searchLogs(@RequestParam(required = false) String mdcId,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String level) {
        return ApiResponse.ok(logRepository.search(mdcId, level, keyword));
    }

    @Operation(summary = "시스템 로그 조회")
    @GetMapping("/system")
    public ApiResponse<?> getSystemLogs(@RequestParam(required = false) String mdcId) {
        if (mdcId != null) {
            return ApiResponse.ok(logRepository.findByMdcIdOrderByTimeDesc(mdcId));
        }
        return ApiResponse.ok(logRepository.findAllByOrderByTimeDesc());
    }
}
