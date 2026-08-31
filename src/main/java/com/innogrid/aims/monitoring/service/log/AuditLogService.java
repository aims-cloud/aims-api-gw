package com.innogrid.aims.monitoring.service.log;

import java.time.Instant;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.log.AuditLogDetailResponse;
import com.innogrid.aims.monitoring.dto.log.AuditLogListItemResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 관측 > 로그 관리(감사 로그) 조회 서비스.
 * 사용자 행위 로그(사용자/화면/작업/내용/결과)를 다루며, Loki 분산 로그(LokiLogService)와는 별개 도메인이다.
 * 감사 로그 적재는 백엔드 자체 기록(인터셉터/AOP) 구축이 전제다.
 */
@Service
public class AuditLogService {

    /** 감사 로그 목록 — 기간·키워드 필터, 컬럼 정렬, 페이징(1부터) */
    public PageResponse<AuditLogListItemResponse> getAuditLogs(
            String keyword, Instant from, Instant to, int page, int size, String sort) {
        // TODO:: 감사 로그 원장(엔티티) 정의 + 적재 체계(인증·API 호출 인터셉터) 구축 후 조회 구현
        throw dataSourceNotImplemented();
    }

    /** 감사 로그 상세 — 하단 상세 패널·상세 페이지 공용 (접속 IP 포함) */
    public AuditLogDetailResponse getAuditLog(Long logId) {
        throw dataSourceNotImplemented();
    }

    private ResponseStatusException dataSourceNotImplemented() {
        return new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "감사 로그 원장과 로그 관리 화면의 매핑 구현 전입니다.");
    }
}
