package com.innogrid.aims.scheduler.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.util.SortSupport;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.WorkloadResponse;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * 워크로드 페이징 조회 — {@code GET /scheduler/workloads/page}.
 *
 * <p>기존 {@code GET /workloads}는 배열로 상한 없이 돌려준다 — 응답 형태를 바꾸면 프런트가 깨지므로 그 경로는 그대로 두고,
 * 페이징·필터·정렬은 새 경로에서 한다(배치 이력의 {@code /history} ↔ {@code /history/page}와 같은 나눔).
 * 워크로드는 자동 정리가 없어 계속 늘어나므로 표 화면은 이쪽을 쓴다.</p>
 *
 * <p>필터는 {@link Specification}으로 null이 아닌 조건만 붙인다. {@code resourceType=VM}은 종류를 비워 둔 행(생성 때
 * 생략한 행·옛 행)까지 포함한다 — 실행이 비어 있는 종류를 VM으로 해석하는 것과 같은 규칙이다. {@code keyword}는 이름·설명의 소문자
 * 부분 일치이고 {@code %}·{@code _}는 문자 그대로 찾는다. 같은 값으로 정렬된 행은 {@code id}로 한 번 더 정렬해
 * 페이지 경계에서 겹치거나 빠지지 않는다({@link StableSort}).</p>
 */
@Service
public class WorkloadQueryService {

    /**
     * 정렬을 허용할 필드. {@code priority}는 문자열로 저장돼 그대로 정렬하면 사전순(CRITICAL·HIGH·LOW·NORMAL)이 되므로,
     * 등급 순(LOW → NORMAL → HIGH → CRITICAL)으로 바꿔 정렬한다({@link #priorityRank}).
     */
    static final Set<String> SORTABLE = Set.of(
            "createdAt", "name", "status", "resourceType", "priority",
            "requiredCpu", "requiredMemoryMb", "requiredGpu", "requiredDiskGb",
            "retryCount", "scheduledAt", "lastFailedAt");
    private static final String PRIORITY = "priority";
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final WorkloadRepository repository;
    /** 재시도 소진 판정의 상한 — 읽기만 한다. */
    private final SchedulerProperties properties;

    public WorkloadQueryService(WorkloadRepository repository, SchedulerProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * 페이지는 1부터. 필터는 전부 선택이고 AND로 겹친다. {@code statuses}는 비어 있으면 조건 없음, 여럿이면 OR —
     * 노드 화면의 "지금 올라가 있는 것"(SCHEDULED·RUNNING)을 한 번에 거르기 위해서다. {@code retryExhausted}는
     * 재시도 소진(FAILED이고 {@code retryCount >= maxRetryCount}) 행만({@code true}) 또는 그 나머지({@code false}) —
     * 저장소의 소진 조회·집계와 같은 식이라 일괄 재개 화면의 목록이 그 건수와 맞는다. {@code priorities}도 {@code statuses}와 같이
     * 비어 있으면 조건 없음, 여럿이면 OR — 컬럼이 NOT NULL(생성 때 생략하면 NORMAL)이라 null 분기는 없다.
     */
    @Transactional(readOnly = true)
    public PageResponse<WorkloadResponse> page(Collection<Workload.WorkloadStatus> statuses, Workload.ResourceType resourceType,
                                               String keyword, String assignedNodeId, Boolean retryExhausted,
                                               Collection<Workload.WorkloadPriority> priorities,
                                               int page, int size, String sort) {
        Sort order = StableSort.withIdTiebreaker(SortSupport.parse(sort, SORTABLE, DEFAULT_SORT));
        Specification<Workload> spec = filter(statuses, resourceType, keyword, assignedNodeId, retryExhausted, priorities,
                properties.getMaxRetryCount());
        Pageable pageable;
        if (order.getOrderFor(PRIORITY) != null) {
            // 등급 순은 Pageable의 Sort로 표현할 수 없다 — unsorted로 넘겨야 Specification이 건 orderBy가 남는다
            // (Spring Data는 Pageable에 정렬이 있으면 그것으로 덮어쓰고, count 질의에서는 Specification의 정렬을 지운다).
            spec = orderedBy(spec, order);
            pageable = PageRequest.of(page - 1, size);
        } else {
            pageable = PageRequest.of(page - 1, size, order);
        }
        Page<Workload> found = repository.findAll(spec, pageable);
        Page<WorkloadResponse> mapped = found.map(WorkloadResponse::from);
        return new PageResponse<>(mapped.getContent(), page, size, mapped.getTotalElements(), mapped.getTotalPages());
    }

    /**
     * {@code order}의 키를 그대로 Criteria 정렬로 옮기되 {@code priority}만 등급 번호로 바꾼다.
     * {@code id} 보조 키와 방향은 {@link StableSort}가 이미 붙였으므로 여기서는 번역만 한다.
     */
    private static Specification<Workload> orderedBy(Specification<Workload> base, Sort order) {
        return (root, query, cb) -> {
            Predicate where = base.toPredicate(root, query, cb);
            List<Order> orders = new ArrayList<>();
            for (Sort.Order o : order) {
                Expression<?> key = PRIORITY.equals(o.getProperty()) ? priorityRank(root, cb) : root.get(o.getProperty());
                orders.add(o.isAscending() ? cb.asc(key) : cb.desc(key));
            }
            query.orderBy(orders);
            return where;
        };
    }

    /**
     * 열거형 선언 순서(LOW, NORMAL, HIGH, CRITICAL)가 곧 등급이라 값이 늘어도 따라간다. 컬럼이 NOT NULL이라
     * {@code otherwise}에는 닿지 않지만, 닿는다면 "생략하면 NORMAL" 규칙과 같게 NORMAL 자리로 둔다.
     * THEN 값은 리터럴로 — 바인드 파라미터는 드라이버·방언에 따라 ORDER BY의 형 추론이 달라질 수 있어 피한다
     * (렌더링된 SQL은 WHEN 비교값이 바인드, THEN이 리터럴이며 PostgreSQL에서 정상 동작을 확인했다).
     */
    private static Expression<Integer> priorityRank(Root<Workload> root, CriteriaBuilder cb) {
        Path<Workload.WorkloadPriority> path = root.get(PRIORITY);
        CriteriaBuilder.Case<Integer> rank = cb.selectCase();
        for (Workload.WorkloadPriority value : Workload.WorkloadPriority.values()) {
            rank = rank.when(cb.equal(path, value), cb.literal(value.ordinal()));
        }
        return rank.otherwise(cb.literal(Workload.WorkloadPriority.NORMAL.ordinal()));
    }

    private static Specification<Workload> filter(Collection<Workload.WorkloadStatus> statuses, Workload.ResourceType resourceType,
                                                  String keyword, String assignedNodeId, Boolean retryExhausted,
                                                  Collection<Workload.WorkloadPriority> priorities, int maxRetryCount) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (statuses != null && !statuses.isEmpty()) predicates.add(root.get("status").in(statuses));
            if (priorities != null && !priorities.isEmpty()) predicates.add(root.get("priority").in(priorities));
            if (retryExhausted != null) {
                // 소진 = FAILED이고 retryCount >= 상한. retryCount가 null인 행은 0회라 소진이 아니다(IS NULL을 명시해 부정에서도 남긴다)
                Predicate exhausted = cb.and(
                        cb.equal(root.get("status"), Workload.WorkloadStatus.FAILED),
                        cb.greaterThanOrEqualTo(root.get("retryCount"), maxRetryCount));
                predicates.add(retryExhausted ? exhausted
                        : cb.or(cb.notEqual(root.get("status"), Workload.WorkloadStatus.FAILED),
                                cb.isNull(root.get("retryCount")),
                                cb.lessThan(root.get("retryCount"), maxRetryCount)));
            }
            if (resourceType != null) {
                Predicate same = cb.equal(root.get("resourceType"), resourceType);
                // 종류를 비워 둔 행(생성 때 생략·옛 행)은 VM으로 해석한다 — 실행의 후보 조회(matchNullType)와 같은 규칙
                predicates.add(resourceType == Workload.ResourceType.VM
                        ? cb.or(same, cb.isNull(root.get("resourceType"))) : same);
            }
            if (keyword != null && !keyword.isBlank()) {
                String like = LikePatterns.contains(keyword.trim().toLowerCase(Locale.ROOT));
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like, LikePatterns.ESCAPE),
                        cb.like(cb.lower(cb.coalesce(root.get("description"), "")), like, LikePatterns.ESCAPE)));
            }
            if (assignedNodeId != null && !assignedNodeId.isBlank()) {
                predicates.add(cb.equal(root.get("assignedNodeId"), assignedNodeId.trim()));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
