package com.innogrid.aims.monitoring.repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmHistorySearchField;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmLevel;
import com.innogrid.aims.monitoring.entity.AlarmHistory;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

/**
 * 알람 발생 이력 목록 필터 (docs/09) — 레벨(다중)·역할·자원 영역(areaId: mdcId 또는 TB:{nsId})·자원 유형·특정 자원·정책·기간·키워드를 AND 로 묶는다.
 *
 * 역할 스코프: {@code visibleRoles} 가 null 이면 제한 없음(관리자 등), 아니면 {@code role_name IN visibleRoles OR role_name IS NULL}.
 * 역할이 없는 행(내장 시스템 규칙·외부 알람)은 모든 사용자에게 보인다.
 * 키워드는 searchField 가 가리키는 컬럼(ALL = 자원 이름 + 정책명)에서 대소문자 무시 부분 일치 — 프론트 검색 대상 Select 와 같은 범위.
 */
public final class AlarmHistorySpecifications {

    private AlarmHistorySpecifications() {}

    public static Specification<AlarmHistory> filter(Collection<AlarmLevel> levels,
                                                          String roleName,
                                                          String areaId,
                                                          String resourceType,
                                                          String resourceId,
                                                          Long policyId,
                                                          AlarmHistorySearchField searchField,
                                                          String keyword,
                                                          OffsetDateTime from,
                                                          OffsetDateTime to,
                                                          Collection<String> visibleRoles) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (levels != null && !levels.isEmpty()) {
                p.add(root.get("level").in(levels));
            }
            if (notBlank(roleName)) {
                p.add(cb.equal(root.get("roleName"), roleName.trim()));
            }
            if (notBlank(areaId)) {
                p.add(cb.equal(root.get("areaId"), areaId.trim()));
            }
            if (notBlank(resourceType)) {
                p.add(cb.equal(cb.lower(root.get("resourceType")), resourceType.trim().toLowerCase(Locale.ROOT)));
            }
            if (notBlank(resourceId)) {
                p.add(cb.equal(root.get("resourceId"), resourceId.trim()));
            }
            if (policyId != null) {
                p.add(cb.equal(root.get("policyId"), policyId));
            }
            if (notBlank(keyword)) {
                String pattern = "%" + escapeLike(keyword.trim().toLowerCase(Locale.ROOT)) + "%";
                AlarmHistorySearchField field = searchField == null ? AlarmHistorySearchField.ALL : searchField;
                p.add(switch (field) {
                    case RESOURCE_NAME -> cb.like(cb.lower(root.get("resourceName")), pattern, '\\');
                    case POLICY_NAME -> cb.like(cb.lower(root.get("policyName")), pattern, '\\');
                    case ALL -> cb.or(
                            cb.like(cb.lower(root.get("resourceName")), pattern, '\\'),
                            cb.like(cb.lower(root.get("policyName")), pattern, '\\'));
                });
            }
            if (from != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                p.add(cb.lessThan(root.get("occurredAt"), to));
            }
            if (visibleRoles != null) {
                p.add(visibleRoles.isEmpty()
                        ? cb.isNull(root.get("roleName"))
                        : cb.or(root.get("roleName").in(visibleRoles), cb.isNull(root.get("roleName"))));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
