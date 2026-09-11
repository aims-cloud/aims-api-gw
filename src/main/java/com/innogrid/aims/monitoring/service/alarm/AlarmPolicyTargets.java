package com.innogrid.aims.monitoring.service.alarm;

import java.util.List;
import java.util.Set;

import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmResourceType;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmTargetScope;
import com.innogrid.aims.monitoring.entity.AlarmPolicy;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedVm;

/**
 * 정책이 실제로 적용되는 자원 목록 — 역할 스코프({@link Scope}) → 자원 영역 → 자원 유형 → 대상 범위 순으로 좁힌다.
 * 목록의 "대상 자원 수", 대상 자원 탭, 평가기의 표본 필터가 모두 이 한 곳을 쓴다.
 *
 * <ul>
 *   <li>node·gpu: 역할의 물리 호스트. id 는 {@code assets.id}, 표본 매칭 키는 node_exporter instance(address:port).</li>
 *   <li>vm: 역할 Namespace 의 Tumblebug VM({@code TB:{ns}/{infra}/{node}}). 1세부 VM({@code SEBU1:})은 Thanos 라벨이 없어 제외.</li>
 *   <li>areaId 가 있으면 MDC(mdcId) 또는 Namespace({@code TB:{nsId}})로, INDIVIDUAL 이면 선택한 id 로 다시 걸러낸다.</li>
 * </ul>
 */
public final class AlarmPolicyTargets {

    private AlarmPolicyTargets() {}

    /** 정책 대상 자원 1건 */
    public record Target(String resourceId, String name, String alias, String resourceType,
                         String mdcId, String nsId, String instance) {
        /** 이력 areaId — Namespace 면 {@code TB:{nsId}}, 아니면 mdcId */
        public String areaId() {
            return nsId != null ? "TB:" + nsId : mdcId;
        }
    }

    public static List<Target> resolve(AlarmPolicy policy, Scope scope) {
        AlarmResourceType type = AlarmResourceType.fromCode(policy.getResourceType()).orElse(null);
        String areaId = policy.getAreaId();
        Set<String> picked = policy.getTargetScope() == AlarmTargetScope.INDIVIDUAL ? policy.getTargetResourceIds() : null;

        List<Target> targets = type == null ? List.of() : switch (type) {
            case VM -> scope.vmsOf(policy.getRoleName()).stream()
                    .filter(v -> v.resourceId() != null && v.resourceId().startsWith("TB:"))
                    .filter(v -> areaId == null || areaId.equals("TB:" + v.nsId()))
                    .map(AlarmPolicyTargets::of)
                    .toList();
            case NODE, GPU -> scope.hostsOf(policy.getRoleName()).stream()
                    .filter(h -> areaId == null || areaId.equals(h.mdcId()))
                    .map(h -> of(h, type.code()))
                    .toList();
        };
        if (picked == null) return targets;
        return targets.stream().filter(t -> picked.contains(t.resourceId())).toList();
    }

    private static Target of(ScopedHost h, String type) {
        return new Target(String.valueOf(h.assetId()), h.name(), h.alias(), type, h.mdcId(), null, h.instance());
    }

    private static Target of(ScopedVm v) {
        return new Target(v.resourceId(), v.name(), null, AlarmResourceType.VM.code(), null, v.nsId(), null);
    }
}
