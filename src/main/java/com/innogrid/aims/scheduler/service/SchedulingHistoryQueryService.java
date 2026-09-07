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

    /** 한 페이지. {@code page} 는 1부터다. 필터는 전부 선택. */
    @Transactional(readOnly = true)
    public PageResponse<SchedulingHistoryResponse> page(Long workloadId, Boolean success, String engineName,
                                                        int page, int size, String sort) {
        Sort order = SortSupport.parse(sort, SORTABLE, DEFAULT_SORT);
        Page<SchedulingHistory> found = repository.findAll(
                filter(workloadId, success, engineName), PageRequest.of(page - 1, size, order));
        Page<SchedulingHistoryResponse> mapped = found.map(SchedulingHistoryResponse::from);
        return new PageResponse<>(mapped.getContent(), page, size, mapped.getTotalElements(), mapped.getTotalPages());
    }

    private static Specification<SchedulingHistory> filter(Long workloadId, Boolean success, String engineName) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (workloadId != null) predicates.add(cb.equal(root.get("workloadId"), workloadId));
            if (success != null) {
                // NULL 은 컬럼 도입 전 기존 행 — 실패는 언제나 명시적 false 로 저장되므로 NULL 은 전부 성공이다.
                // 성공을 물을 때만 NULL 을 포함한다(실패에 끼우면 없는 실패를 발명하게 된다).
                predicates.add(Boolean.TRUE.equals(success)
                        ? cb.or(cb.equal(root.get("success"), Boolean.TRUE), cb.isNull(root.get("success")))
                        : cb.equal(root.get("success"), Boolean.FALSE));
            }
            if (engineName != null && !engineName.isBlank()) predicates.add(cb.equal(root.get("engineName"), engineName.trim()));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
