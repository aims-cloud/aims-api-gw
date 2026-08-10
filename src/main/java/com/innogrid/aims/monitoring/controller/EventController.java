package com.innogrid.aims.monitoring.controller;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.mock.MockDataProvider;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 이벤트 관리 API.
 * [요구사항 #47] 이벤트 조회 — 다수 MDC 이벤트 조건별 통합 검색·조회
 */
@Tag(name = "이벤트", description = "MDC 이벤트 조회")
@RestController
@RequestMapping("/api/v1/monitoring/events")
public class EventController {

    private final MockDataProvider mock;

    public EventController(MockDataProvider mock) {
        this.mock = mock;
    }

    /** 이벤트 목록 조회 */
    @GetMapping
    public ApiResponse<?> listEvents(@RequestParam(required = false) String mdcId,
                                     @RequestParam(required = false) String severity,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(mock.getEvents());
    }

    /** 이벤트 상세 조회 */
    @GetMapping("/{eventId}")
    public ApiResponse<?> getEvent(@PathVariable Long eventId) {
        return ApiResponse.ok(mock.getEvents().get(0));
    }
}
