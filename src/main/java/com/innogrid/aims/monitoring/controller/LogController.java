package com.innogrid.aims.monitoring.controller;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.mock.MockDataProvider;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 로그 관리 API.
 * [요구사항 #50] 로그 탐색기 — 다수 MDC 로그 조건 검색·필터링
 * [요구사항 #51] 시스템 로그 — MDC 운영 중 시스템 로그 수집·조회
 */
@Tag(name = "로그", description = "로그 탐색, 시스템 로그")
@RestController
@RequestMapping("/api/v1/monitoring/logs")
public class LogController {

    private final MockDataProvider mock;

    public LogController(MockDataProvider mock) {
        this.mock = mock;
    }

    /** 로그 탐색 (조건 검색) */
    @GetMapping("/search")
    public ApiResponse<?> searchLogs(@RequestParam(required = false) String mdcId,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String level,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.ok(mock.getSystemLogs());
    }

    /** 시스템 로그 조회 */
    @GetMapping("/system")
    public ApiResponse<?> getSystemLogs(@RequestParam(required = false) String mdcId,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.ok(mock.getSystemLogs());
    }
}
