package com.innogrid.aims.monitoring.service.log;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.common.util.SortSupport;
import com.innogrid.aims.monitoring.dto.log.PortalLogDetailResponse;
import com.innogrid.aims.monitoring.dto.log.PortalLogListItemResponse;
import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import com.innogrid.aims.monitoring.repository.PortalLogRepository;
import com.innogrid.aims.monitoring.repository.PortalLogSpecifications;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관측 > 로그 관리 조회 서비스 (docs/08 §8) — portal_logs 목록·상세.
 *
 * <ul>
 *   <li>기간은 날짜(YYYY-MM-DD) 양끝 포함으로 받아 서버 타임존 기준 {@code [from 00:00, to+1일 00:00)} 으로 바꾼다.
 *       한쪽만 지정한 열린 범위도 허용하고, 둘 다 없으면 전체다.</li>
 *   <li>정렬은 허용 컬럼만 받는다({@link SortSupport}). 기본 startedAt 내림차순.</li>
 *   <li>기록 경로({@code PortalLogWriter})와 달리 조회는 읽기 전용 트랜잭션이다.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class PortalLogQueryService {

    static final Set<String> SORTABLE = Set.of(
            "startedAt", "completedAt", "type", "userName", "screen", "action", "content", "result", "description");
    static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "startedAt");

    private final PortalLogRepository repository;
    private final ZoneId zone;

    @Autowired
    public PortalLogQueryService(PortalLogRepository repository) {
        this(repository, ZoneId.systemDefault());
    }

    /** 테스트용 — 기간 변환 타임존을 고정한다 */
    PortalLogQueryService(PortalLogRepository repository, ZoneId zone) {
        this.repository = repository;
        this.zone = zone;
    }

    /** 목록 — 타입(다중)·키워드·기간·사용자·결과 필터, 정렬, 페이징(1부터) */
    public PageResponse<PortalLogListItemResponse> getLogs(List<PortalLogType> types, String keyword,
                                                          LocalDate from, LocalDate to, String userId,
                                                          PortalLogResult result, int page, int size, String sort) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "시작일은 종료일보다 늦을 수 없습니다: " + from + " > " + to);
        }
        Sort order = SortSupport.parse(sort, SORTABLE, DEFAULT_SORT);
        // 동률 정렬 안정화 — 같은 시각 행이 페이지 사이에서 흔들리지 않게 id 를 2차 키로 둔다
        Sort stable = order.and(Sort.by(Sort.Direction.DESC, "id"));

        Page<PortalLogEntry> result0 = repository.findAll(
                PortalLogSpecifications.filter(types, keyword, startOf(from), endOf(to), userId, result),
                PageRequest.of(page - 1, size, stable));

        return new PageResponse<>(
                result0.getContent().stream().map(PortalLogListItemResponse::from).toList(),
                page, size, result0.getTotalElements(), result0.getTotalPages());
    }

    /** 상세 — 없으면 404 */
    public PortalLogDetailResponse getLog(Long logId) {
        return repository.findById(logId)
                .map(PortalLogDetailResponse::from)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "로그를 찾을 수 없습니다: " + logId));
    }

    OffsetDateTime startOf(LocalDate date) {
        return date == null ? null : date.atStartOfDay(zone).toOffsetDateTime();
    }

    OffsetDateTime endOf(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
    }
}
