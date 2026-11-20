package com.innogrid.aims.monitoring.controller;

import java.util.Map;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.monitoring.repository.SystemLogRepository;
import com.innogrid.aims.monitoring.service.LokiLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "로그", description = "분산 로그 수집·검색 (Loki 기반) 및 시스템 로그 관리")
@RestController
@RequestMapping("/api/v1/monitoring/logs")
public class LogController {

    private final SystemLogRepository logRepository;
    private final LokiLogService lokiLogService;

    public LogController(SystemLogRepository logRepository, LokiLogService lokiLogService) {
        this.logRepository = logRepository;
        this.lokiLogService = lokiLogService;
    }

    @Operation(summary = "분산 로그 검색 (Loki)",
            description = "Loki에 수집된 분산 MDC 로그를 LogQL 기반으로 검색. job: 로그 소스(syslog, auth-log, aims-app 등), level: 로그 레벨, keyword: 본문 검색어, hours: 조회 범위(기본 24시간), limit: 최대 건수(기본 100)")
    @GetMapping("/search")
    public ApiResponse<?> searchLogs(@RequestParam(required = false) String job,
                                     @RequestParam(required = false) String level,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) Integer hours,
                                     @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(lokiLogService.searchLogs(job, level, keyword, hours, limit));
    }

    @Operation(summary = "로그 소스 목록", description = "Loki에 등록된 로그 수집 소스(job) 목록 조회")
    @GetMapping("/sources")
    public ApiResponse<?> getLogSources() {
        return ApiResponse.ok(lokiLogService.getLogSources());
    }

    @Operation(summary = "로그 라벨 목록", description = "Loki에서 사용 가능한 라벨 목록 조회")
    @GetMapping("/labels")
    public ApiResponse<?> getLogLabels() {
        return ApiResponse.ok(lokiLogService.getLogLabels());
    }

    @Operation(summary = "Loki 헬스체크", description = "Loki 로그 시스템 상태 확인")
    @GetMapping("/health")
    public ApiResponse<?> lokiHealth() {
        boolean healthy = lokiLogService.isHealthy();
        return ApiResponse.ok(Map.of("status", healthy ? "UP" : "DOWN"));
    }

    @Operation(summary = "시스템 로그 조회 (DB)", description = "PostgreSQL에 저장된 시스템 로그 조회")
    @GetMapping("/system")
    public ApiResponse<?> getSystemLogs(@RequestParam(required = false) String mdcId) {
        if (mdcId != null) {
            return ApiResponse.ok(logRepository.findByMdcIdOrderByTimeDesc(mdcId));
        }
        return ApiResponse.ok(logRepository.findAllByOrderByTimeDesc());
    }
}
