package com.innogrid.aims.monitoring.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmLevel;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmTargetScope;
import com.innogrid.aims.monitoring.entity.AlarmPolicy;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

/**
 * 알람 정책 목록 필터 — 역할·자원 영역·자원 유형·특정 자원·레벨·키워드(정책명)를 AND 로 묶는다.
 *
 * <ul>
 *   <li>역할 스코프: {@code visibleRoles} 가 null 이면 제한 없음, 아니면 {@code role_name IN visibleRoles}. 정책은 역할이 필수라
 *       역할 없는 행은 없다.</li>
 *   <li>resourceId(자원 탐색기 알람 탭): 그 자원을 개별 대상으로 고른 정책, 또는 대상 범위가 ALL 인 정책. ALL 정책이 실제로
 *       그 자원을 덮는지(역할·영역·유형)는 원장만으로 판정할 수 없어 서비스가 후처리한다.</li>
 * </ul>
 */
public final class AlarmPolicySpecifications {

    private AlarmPolicySpecifications() {}

    public static Specification<AlarmPolicy> filter(String roleName,
                                                     String areaId,
                                                     String resourceType,
                                                     String resourceId,
                                                     AlarmLevel level,
                                                     String keyword,
                                                     Collection<String> visibleRoles) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (notBlank(roleName)) {
                p.add(cb.equal(root.get("roleName"), roleName.trim()));
            }
            if (notBlank(areaId)) {
                p.add(cb.equal(root.get("areaId"), areaId.trim()));
            }
            if (notBlank(resourceType)) {
                p.add(cb.equal(cb.lower(root.get("resourceType")), resourceType.trim().toLowerCase(Locale.ROOT)));
            }
            if (level != null) {
                p.add(cb.equal(root.get("level"), level));
            }
            if (notBlank(keyword)) {
                String pattern = "%" + escapeLike(keyword.trim().toLowerCase(Locale.ROOT)) + "%";
                p.add(cb.like(cb.lower(root.get("name")), pattern, '\\'));
            }
            if (notBlank(resourceId)) {
                Subquery<Long> individual = query.subquery(Long.class);
                var sub = individual.from(AlarmPolicy.class);
                individual.select(sub.get("id"))
                        .where(cb.equal(sub.join("targetResourceIds"), resourceId.trim()));
                p.add(cb.or(cb.equal(root.get("targetScope"), AlarmTargetScope.ALL), root.get("id").in(individual)));
            }
            if (visibleRoles != null) {
                if (visibleRoles.isEmpty()) {
                    p.add(cb.disjunction());
                } else {
                    p.add(root.get("roleName").in(visibleRoles));
                }
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
