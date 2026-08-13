package com.innogrid.aims.monitoring.controller;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.repository.EventRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "이벤트", description = "MDC 이벤트 조회")
@RestController
@RequestMapping("/api/v1/monitoring/events")
public class EventController {

    private final EventRepository eventRepository;

    public EventController(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Operation(summary = "이벤트 목록 조회")
    @GetMapping
    public ApiResponse<?> listEvents(@RequestParam(required = false) String mdcId,
                                     @RequestParam(required = false) String severity) {
        if (mdcId != null) {
            return ApiResponse.ok(eventRepository.findByMdcIdOrderByTimeDesc(mdcId));
        }
        if (severity != null) {
            return ApiResponse.ok(eventRepository.findBySeverityOrderByTimeDesc(severity.toUpperCase()));
        }
        return ApiResponse.ok(eventRepository.findAllByOrderByTimeDesc());
    }

    @Operation(summary = "이벤트 상세 조회")
    @GetMapping("/{eventId}")
    public ApiResponse<?> getEvent(@PathVariable Long eventId) {
        return ApiResponse.ok(eventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "이벤트를 찾을 수 없습니다")));
    }
}
