package com.innogrid.aims.monitoring.repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

/**
 * 포털 로그 목록 필터 (docs/08 §8) — 타입(다중), 키워드, 기간, 사용자, 결과를 AND 로 묶는다.
 * 키워드는 화면과 같은 5개 컬럼(user_name, screen, action, content, description)에서 대소문자 무시 부분 일치로 찾고,
 * user_id·client_ip 는 검색하지 않는다.
 */
public final class PortalLogSpecifications {

    private PortalLogSpecifications() {}

    public static Specification<PortalLogEntry> filter(Collection<PortalLogType> types,
                                                  String keyword,
                                                  OffsetDateTime from,
                                                  OffsetDateTime to,
                                                  String userId,
                                                  PortalLogResult result) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (types != null && !types.isEmpty()) {
                predicates.add(root.get("type").in(types));
            }
            if (keyword != null && !keyword.isBlank()) {
                String pattern = "%" + escapeLike(keyword.trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("userName")), pattern, '\\'),
                        cb.like(cb.lower(root.get("screen")), pattern, '\\'),
                        cb.like(cb.lower(root.get("action")), pattern, '\\'),
                        cb.like(cb.lower(root.get("content")), pattern, '\\'),
                        cb.like(cb.lower(root.get("description")), pattern, '\\')));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("startedAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("startedAt"), to));
            }
            if (userId != null && !userId.isBlank()) {
                predicates.add(cb.equal(root.get("userId"), userId.trim()));
            }
            if (result != null) {
                predicates.add(cb.equal(root.get("result"), result));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** LIKE 와일드카드(%, _)와 이스케이프 문자를 리터럴로 취급한다 */
    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
