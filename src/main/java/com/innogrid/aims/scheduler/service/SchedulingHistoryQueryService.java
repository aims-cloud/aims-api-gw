package com.innogrid.aims.scheduler.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.util.SortSupport;
import com.innogrid.aims.scheduler.dto.SchedulingHistoryResponse;
import com.innogrid.aims.scheduler.engine.constraint.ExclusionReason;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;

import jakarta.persistence.criteria.Predicate;

/**
 * 배치 이력 페이징 조회 — {@code GET /scheduler/history/page}.
 *
 * <p>기존 {@code GET /history} 는 배열로 돌려준다 — 응답 형태를 바꾸면 프런트가 깨지므로 그 경로는 배열 그대로 두고,
 * 페이징·필터·정렬은 새 경로에서 한다. 다만 그 배열은 <b>더 이상 전량이 아니다</b>: 이력은 삭제되지 않아 단조
 * 증가하므로 최근 건수만 돌려준다(단계 23, {@code SchedulerController.HISTORY_MAX}). <b>여기 총건수에는 상한이
 * 없다</b> — 그래서 배열 길이와 이 API 의 {@code totalElements} 는 이력이 상한을 넘으면 서로 달라진다. 규칙은 스케줄 목록과 같다 — page 1부터,
 * size 최대 100, sort 는 "필드,방향".</p>
 *
 * <p>필터는 {@link Specification} 으로 null 이 아닌 조건만 붙인다. JPQL 의 {@code :x is null or h.x = :x} 는 PostgreSQL 이
 * null 파라미터의 타입을 정하지 못해 실패할 수 있고 H2 에서는 통과해 배포에서만 드러난다(단계 13 점검).</p>
 */
@Service
public class SchedulingHistoryQueryService {

    static final Set<String> SORTABLE = Set.of("createdAt", "finalScore", "workloadName", "success");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final SchedulingHistoryRepository repository;

    public SchedulingHistoryQueryService(SchedulingHistoryRepository repository) {
        this.repository = repository;
    }

    /**
     * 한 페이지. {@code page} 는 1부터다. 필터는 전부 선택 — {@code scheduleRunId} 는 지정한 스케줄 실행이 만든 결정만
     * 돌려주므로 실행 상세의 결정 목록과 모집단이 같고, {@code exclusionCode} 는 그 코드로 후보가 하나 이상 빠진 결정만
     * 돌려준다.
     */
    @Transactional(readOnly = true)
    public PageResponse<SchedulingHistoryResponse> page(Long workloadId, Boolean success, String engineName,
                                                        Long scheduleRunId, ExclusionReason exclusionCode,
                                                        int page, int size, String sort) {
        Sort order = SortSupport.parse(sort, SORTABLE, DEFAULT_SORT);
        Page<SchedulingHistory> found = repository.findAll(
                filter(workloadId, success, engineName, scheduleRunId, exclusionCode), PageRequest.of(page - 1, size, order));
        Page<SchedulingHistoryResponse> mapped = found.map(SchedulingHistoryResponse::from);
        return new PageResponse<>(mapped.getContent(), page, size, mapped.getTotalElements(), mapped.getTotalPages());
    }

    private static Specification<SchedulingHistory> filter(Long workloadId, Boolean success, String engineName,
                                                           Long scheduleRunId, ExclusionReason exclusionCode) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (workloadId != null) predicates.add(cb.equal(root.get("workloadId"), workloadId));
            if (success != null) {
                // NULL 은 옛 행이다 — 이 컬럼이 생기기 전에 쌓인 행, 그리고 단건 배치가 실행 연결 없이 만든 행.
                // 실패는 언제나 명시적 false 로 저장되므로 NULL 은 전부 성공이고, 성공을 물을 때만 포함한다
                // (실패에 끼우면 없는 실패를 발명하게 된다).
                predicates.add(Boolean.TRUE.equals(success)
                        ? cb.or(cb.equal(root.get("success"), Boolean.TRUE), cb.isNull(root.get("success")))
                        : cb.equal(root.get("success"), Boolean.FALSE));
            }
            if (engineName != null && !engineName.isBlank()) predicates.add(cb.equal(root.get("engineName"), engineName.trim()));
            // 실행 id 가 NULL 인 행(단건 배치, 스케줄 삭제나 실행 이력 보존 기간 정리로 연결이 끊긴 행)은 어떤 값에도
            // 걸리지 않는다 — success 의 NULL 포함 규칙과 다르다. 그쪽 NULL 은 "성공" 이지만 여기 NULL 은 "실행 없음" 이라
            // 대신 넣을 값이 없다.
            if (scheduleRunId != null) predicates.add(cb.equal(root.get("scheduleRunId"), scheduleRunId));
            // 집계 문자열은 {"CODE":n,...} 이라 키 토큰("CODE":) 단위로 맞춘다 — TEMPERATURE_EXCEEDED 로 물어도
            // WORKLOAD_/PREDICTED_TEMPERATURE_EXCEEDED 만 든 행은 앞 따옴표 때문에 걸리지 않는다. 밑줄은 LIKE 와일드카드라
            // 이스케이프한다. 집계가 NULL 인 행(제외 없음 · 옛 행)은 어떤 코드에도 걸리지 않는다. 앞이 % 인 LIKE 라 일반
            // 인덱스로는 줄일 수 없어 전수 검색이다(이 열에는 인덱스도 없다).
            if (exclusionCode != null) {
                String key = SchedulingHistory.exclusionCodeKey(exclusionCode.name());
                predicates.add(cb.like(root.get("exclusionCodes"), "%" + escapeLike(key) + "%", '\\'));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
