package com.innogrid.aims.monitoring.controller.log;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.log.AuditLogDetailResponse;
import com.innogrid.aims.monitoring.dto.log.AuditLogListItemResponse;
import com.innogrid.aims.monitoring.service.log.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관측 > 로그 관리(감사 로그) API.
 *
 * 사용자 행위 로그(사용자/화면/작업/내용/결과) 목록과 상세(접속 IP 포함)를 제공한다.
 * Loki 분산 로그(LogController)·수집 서비스 로그(자원 탐색기)와는 별개의 감사 로그 도메인이다.
 */
@Tag(name = "로그 관리 (감사 로그)", description = "사용자 행위 감사 로그 목록·상세 조회")
@RestController
@RequestMapping("/api/v1/monitoring/audit-logs")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Operation(summary = "감사 로그 목록",
            description = "생성/완료 일시, 사용자, 화면, 작업, 내용, 결과, 설명. "
                    + "기간·키워드 필터와 컬럼 정렬, 페이징을 지원합니다. "
                    + "page 는 1부터, sort 는 '{필드},{asc|desc}' 형식, from/to 미지정 시 오늘 00시~현재입니다.")
    @GetMapping
    public ApiResponse<PageResponse<AuditLogListItemResponse>> getAuditLogs(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "startedAt,desc") String sort) {
        Instant toInstant = to != null ? Instant.parse(to) : Instant.now();
        Instant fromInstant = from != null ? Instant.parse(from) : todayStart();
        return ApiResponse.ok(auditLogService.getAuditLogs(keyword, fromInstant, toInstant, page, size, sort));
    }

    @Operation(summary = "감사 로그 상세",
            description = "하단 상세 패널과 로그 관리 상세 페이지 공용 — 화면/사용자(ID)/접속 IP/작업/내용/결과/"
                    + "생성·완료 일시/설명을 조회합니다.")
    @GetMapping("/{logId}")
    public ApiResponse<AuditLogDetailResponse> getAuditLog(@PathVariable Long logId) {
        return ApiResponse.ok(auditLogService.getAuditLog(logId));
    }

    /** from 기본값: 오늘 00:00:00 (서버 타임존) */
    private static Instant todayStart() {
        return LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }
}
