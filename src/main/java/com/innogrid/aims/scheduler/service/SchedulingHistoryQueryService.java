package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.common.util.SortSupport;
import com.innogrid.aims.scheduler.dto.ExclusionSummaryResponse;
import com.innogrid.aims.scheduler.dto.SchedulingHistoryResponse;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.model.ExclusionReason;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * 배치 이력 페이징 조회와 제외 사유 합계 — {@code GET /scheduler/history/page} · {@code /history/exclusion-summary}.
 *
 * <p>기존 {@code GET /history}는 배열로 돌려준다 — 응답 형태를 바꾸면 프런트가 깨지므로 그 경로는 배열 그대로 두고,
 * 페이징·필터·정렬은 새 경로에서 한다. 다만 그 배열은 <b>더 이상 전량이 아니다</b>: 이력은 삭제되지 않아 단조
 * 증가하므로 최근 건수만 돌려준다({@code SchedulerController.HISTORY_MAX}). <b>여기 총건수에는 상한이
 * 없다</b> — 그래서 배열 길이와 이 API의 {@code totalElements}는 이력이 상한을 넘으면 서로 달라진다. 규칙은 스케줄 목록과 같다 — page 1부터,
 * size 최대 100, sort는 "필드,방향".</p>
 *
 * <p>필터는 {@link Specification}으로 null이 아닌 조건만 붙인다. JPQL의 {@code :x is null or h.x = :x}는 PostgreSQL이
 * null 파라미터의 타입을 정하지 못해 실패할 수 있고 H2에서는 통과해 배포에서만 드러난다.</p>
 */
@Service
public class SchedulingHistoryQueryService {

    private static final Logger log = LoggerFactory.getLogger(SchedulingHistoryQueryService.class);

    static final Set<String> SORTABLE = Set.of("createdAt", "finalScore", "workloadName", "success");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");
    /**
     * 합계에서 {@code from}·{@code to}를 둘 다 비웠을 때의 창 — docs/13 3장 「합계의 창과 페이징」 행과
     * {@code ExclusionSummaryResponse}의 {@code from} 설명이 같은 수를 적는다.
     */
    public static final int DEFAULT_SUMMARY_WINDOW_DAYS = 30;

    private final SchedulingHistoryRepository repository;
    /** 합계의 열 투영 질의용 — 저장소 메서드 없이 같은 Specification을 재사용하려고 공유 EntityManager를 직접 쓴다(새 빈 등록 없음). */
    private final EntityManager entityManager;

    public SchedulingHistoryQueryService(SchedulingHistoryRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    /**
     * 한 페이지. {@code page}는 1부터다. 필터는 전부 선택 — {@code scheduleRunId}는 지정한 스케줄 실행이 만든 결정만
     * 돌려주므로 실행 상세의 결정 목록과 모집단이 같고, {@code exclusionCode}는 그 코드로 후보가 하나 이상 빠진 결정만
     * 돌려준다. {@code from}(포함)·{@code to}(미포함)는 {@code createdAt} 창이고 기본 창은 없다.
     */
    @Transactional(readOnly = true)
    public PageResponse<SchedulingHistoryResponse> page(Long workloadId, Boolean success, String engineName,
                                                        Long scheduleRunId, ExclusionReason exclusionCode,
                                                        OffsetDateTime from, OffsetDateTime to,
                                                        int page, int size, String sort) {
        requireOrderedWindow(from, to);
        Sort order = StableSort.withIdTiebreaker(SortSupport.parse(sort, SORTABLE, DEFAULT_SORT));
        Page<SchedulingHistory> found = repository.findAll(
                filter(workloadId, success, engineName, scheduleRunId, exclusionCode, from, to),
                PageRequest.of(page - 1, size, order));
        Page<SchedulingHistoryResponse> mapped = found.map(SchedulingHistoryResponse::from);
        return new PageResponse<>(mapped.getContent(), page, size, mapped.getTotalElements(), mapped.getTotalPages());
    }

    /**
     * 창·필터 안 결정의 제외 사유 코드 합계. 페이지와 같은 조건식을 쓰므로 응답의 {@code from}·{@code to}를 넘긴 같은 필터의
     * {@code exclusionCode=CODE} 총건수가 {@code codes[CODE].decisions}와 같다({@code skipped}가 0일 때). 창은 {@code from}·{@code to}를
     * 둘 다 비우면 {@value #DEFAULT_SUMMARY_WINDOW_DAYS}일, {@code to}만 비우면 상한 없음, {@code from}만 비우면
     * 하한 없음이다. 한쪽만 비운 경우의 규칙은 페이징과 같고, 페이징에는 기본 창이 없다.
     * 합산은 Java에서 한다 — 집계 열이 TEXT라 DB 집계로
     * 묶기 어렵고 형식이 어긋난 행은 건너뛰어 세어야 하기 때문이다. 행은 엔티티가 아니라 {@code id}·{@code exclusionCodes} 두 열만
     * 투영해 읽는다 — 창을 한쪽만 주면 모집단이 이력 전량이 될 수 있어서다. 결정 수와 집계 행은 별도 문장이라 동시에 적재되는 행이
     * 두 문장 사이에 끼면 한 건 어긋날 수 있다(READ COMMITTED, 곧 스스로 맞는다).
     */
    @Transactional(readOnly = true)
    public ExclusionSummaryResponse summary(Long workloadId, Boolean success, String engineName, Long scheduleRunId,
                                            OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime start = (from == null && to == null) ? now.minusDays(DEFAULT_SUMMARY_WINDOW_DAYS) : from;
        OffsetDateTime end = to != null ? to : (from == null ? now : null);
        requireOrderedWindow(start, end);
        Specification<SchedulingHistory> spec = filter(workloadId, success, engineName, scheduleRunId, null, start, end);
        long decisions = repository.count(spec);
        SortedMap<String, long[]> sums = new TreeMap<>();   // [결정 수, 후보 수]
        long withExclusions = 0;
        List<Long> skippedIds = new ArrayList<>();
        for (Object[] row : exclusionCodeRows(spec)) {
            Long id = (Long) row[0];
            String raw = (String) row[1];
            var parsed = SchedulingHistory.parseExclusionCodes(raw);
            if (parsed.isEmpty()) {
                skippedIds.add(id);
                continue;
            }
            if (parsed.get().isEmpty()) continue;
            withExclusions++;
            parsed.get().forEach((code, count) -> {
                long[] s = sums.computeIfAbsent(code, k -> new long[2]);
                s[0]++;
                s[1] += count;
            });
        }
        if (!skippedIds.isEmpty()) {
            log.warn("배치 이력 {}건의 제외 사유 집계 형식이 어긋나 합계에서 건너뜁니다 (id 일부 {}개: {})",
                    skippedIds.size(), Math.min(5, skippedIds.size()), skippedIds.subList(0, Math.min(5, skippedIds.size())));
        }
        SortedMap<String, ExclusionSummaryResponse.CodeCount> codes = new TreeMap<>();
        sums.forEach((code, s) -> codes.put(code, new ExclusionSummaryResponse.CodeCount(s[0], s[1])));
        return new ExclusionSummaryResponse(start, end, decisions, withExclusions, skippedIds.size(), codes);
    }

    /** 창·필터 안에서 집계가 있는 행의 {@code [id, exclusionCodes]} — 엔티티를 올리지 않고 두 열만 읽는다. */
    private List<Object[]> exclusionCodeRows(Specification<SchedulingHistory> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object[]> query = cb.createQuery(Object[].class);
        Root<SchedulingHistory> root = query.from(SchedulingHistory.class);
        query.multiselect(root.get("id"), root.get("exclusionCodes"))
                .where(spec.toPredicate(root, query, cb), cb.isNotNull(root.get("exclusionCodes")));
        return entityManager.createQuery(query).getResultList();
    }

    private static void requireOrderedWindow(OffsetDateTime from, OffsetDateTime to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "기간의 시작이 끝보다 뒤입니다");
        }
    }

    private static Specification<SchedulingHistory> filter(Long workloadId, Boolean success, String engineName,
                                                           Long scheduleRunId, ExclusionReason exclusionCode,
                                                           OffsetDateTime from, OffsetDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (workloadId != null) predicates.add(cb.equal(root.get("workloadId"), workloadId));
            if (success != null) {
                // NULL은 옛 행이다 — 이 컬럼이 생기기 전에 쌓인 행, 그리고 단건 배치가 실행 연결 없이 만든 행.
                // 실패는 언제나 명시적 false로 저장되므로 NULL은 전부 성공이고, 성공을 물을 때만 포함한다
                // (실패에 끼우면 없는 실패를 발명하게 된다).
                predicates.add(Boolean.TRUE.equals(success)
                        ? cb.or(cb.equal(root.get("success"), Boolean.TRUE), cb.isNull(root.get("success")))
                        : cb.equal(root.get("success"), Boolean.FALSE));
            }
            if (engineName != null && !engineName.isBlank()) predicates.add(cb.equal(root.get("engineName"), engineName.trim()));
            // 실행 id가 NULL인 행(단건 배치, 스케줄 삭제나 실행 이력 보존 기간 정리로 연결이 끊긴 행)은 어떤 값에도
            // 걸리지 않는다 — success의 NULL 포함 규칙과 다르다. 그쪽 NULL은 "성공"이지만 여기 NULL은 "실행 없음"이라
            // 대신 넣을 값이 없다.
            if (scheduleRunId != null) predicates.add(cb.equal(root.get("scheduleRunId"), scheduleRunId));
            // 집계 문자열은 {"CODE":n,...}이라 키 토큰("CODE":) 단위로 맞춘다 — TEMPERATURE_EXCEEDED로 물어도
            // WORKLOAD_/PREDICTED_TEMPERATURE_EXCEEDED만 든 행은 앞 따옴표 때문에 걸리지 않는다. 밑줄은 LIKE 와일드카드라
            // 이스케이프한다. 집계가 NULL인 행(제외 없음 · 옛 행)은 어떤 코드에도 걸리지 않는다. 앞이 %인 LIKE라 일반
            // 인덱스로는 줄일 수 없어 전수 검색이다(이 열에는 인덱스도 없다).
            if (exclusionCode != null) {
                String key = SchedulingHistory.exclusionCodeKey(exclusionCode.name());
                predicates.add(cb.like(root.get("exclusionCodes"), LikePatterns.contains(key), LikePatterns.ESCAPE));
            }
            // 창은 반개구간 [from, to) — createdAt 인덱스(workloadId와 함께면 복합 인덱스)를 탈 수 있다
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) predicates.add(cb.lessThan(root.get("createdAt"), to));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
