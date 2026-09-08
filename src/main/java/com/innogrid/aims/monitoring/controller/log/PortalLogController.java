package com.innogrid.aims.monitoring.controller.log;

import java.time.LocalDate;
import java.util.List;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.log.PortalLogDetailResponse;
import com.innogrid.aims.monitoring.dto.log.PortalLogListItemResponse;
import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.monitoring.service.log.PortalLogQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관측 > 로그 관리 API — 포털 로그(portal_logs) 목록·상세 (docs/08 포털 로그 설계 §8).
 *
 * 포털에서 일어난 일(인증·데이터 변경·인프라 작업·알람 이벤트·시스템 동작)을 type 으로 구분해 한 표로 보여준다.
 * 기록은 {@code @PortalLog} Aspect 와 {@code PortalLogService} 가 맡고 이 컨트롤러는 조회만 한다.
 * Loki 분산 로그(LogController)·자원 탐색기 서비스 로그와는 별개 도메인이다.
 */
@Tag(name = "관측 > 로그 관리", description = "포털 로그 목록·상세 조회")
@RestController
@RequestMapping("/api/v1/monitoring/portal-logs")
public class PortalLogController {

    private final PortalLogQueryService portalLogQueryService;

    public PortalLogController(PortalLogQueryService portalLogQueryService) {
        this.portalLogQueryService = portalLogQueryService;
    }

    @Operation(summary = "포털 로그 목록",
            description = "생성/완료 일시, 사용자, 화면, 작업, 내용, 결과(SUCCESS|FAILURE|IN_PROGRESS), 설명. "
                    + "type(AUTH|AUDIT|ACTION|ALARM|SYSTEM|ACCESS, 다중)·keyword(사용자명/화면/작업/내용/설명 부분 일치)·"
                    + "from/to(YYYY-MM-DD, 양끝 포함, 한쪽만 지정 가능, 미지정 시 전체)·userId·result 필터와 "
                    + "컬럼 정렬(startedAt|completedAt|type|userName|screen|action|content|result|description), 페이징을 지원합니다. "
                    + "page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다. 시각은 ISO 원본이며 표시 포맷은 프론트가 맡습니다.")
    @GetMapping
    public ApiResponse<PageResponse<PortalLogListItemResponse>> getPortalLogs(
            @RequestParam(required = false) List<PortalLogType> type,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) PortalLogResult result,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "startedAt,desc") String sort) {
        return ApiResponse.ok(portalLogQueryService.getLogs(type, keyword, from, to, userId, result, page, size, sort));
    }

    @Operation(summary = "포털 로그 상세",
            description = "하단 상세 패널과 로그 관리 상세 페이지 공용 — 목록 항목에 사용자 ID, 접속 IP, 연관 원장(refType/refId), "
                    + "요청 추적 ID, HTTP 메서드·URI 를 더해 조회합니다. 없으면 404 입니다.")
    @GetMapping("/{logId}")
    public ApiResponse<PortalLogDetailResponse> getPortalLog(@PathVariable Long logId) {
        return ApiResponse.ok(portalLogQueryService.getLog(logId));
    }
}
