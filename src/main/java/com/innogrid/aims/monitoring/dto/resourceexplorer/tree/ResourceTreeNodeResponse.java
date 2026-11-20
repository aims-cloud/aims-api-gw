package com.innogrid.aims.monitoring.dto.resourceexplorer.tree;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 자원 탐색기에서 사용하는 공통 트리 항목 (docs/06 자원 탐색기 트리 설계 §4.3, §7).
 *
 * <ul>
 *   <li>{@code id}: 트리 내 위치를 나타내는 고유 ID (같은 자원이 여러 역할 아래 있으면 서로 다른 id)</li>
 *   <li>{@code resourceId}: 해당 자원 영역에서 실제 자원을 식별하는 안정적인 ID
 *       (물리 호스트 = assets.id, 1세부 VM = SEBU1:{externalId}, Tumblebug = TB:{nsId}/{infraId}/{nodeId})</li>
 *   <li>{@code nsId}·{@code infraId}·{@code nodeGroupId}·{@code vmId}: 가상머신 가지의 패널 API 식별자를 필드로 그대로 제공한다
 *       (Namespace 부터 VM 까지 자기 계층까지만 채움, 미분류 그룹은 {@code @unclassified}, vmId 는 Tumblebug node id).
 *       프론트가 {@code id}·{@code resourceId} 문자열을 파싱하지 않게 하기 위한 것이며 물리 가지는 null 이다</li>
 *   <li>{@code provider}: Tumblebug Node 의 CSP(providerName) — 트리에는 이 값만 싣고 connection·region 은 상세 API (§9.5)</li>
 *   <li>{@code status}: 자원 실행 상태 — 자원 노드에만 부여. Tumblebug Node 는 Tumblebug 상태값 원문 (§6.1)</li>
 *   <li>{@code monitoringStatus}: 수집 상태 — 자원·모니터링 항목 노드에 부여, 그룹 노드는 null</li>
 * </ul>
 * 실제 사용률·시계열 값은 트리에 포함하지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceTreeNodeResponse(
        String id,
        String parentId,
        String name,
        ResourceTreeNodeType type,
        String resourceId,
        String roleName,
        String mdcId,
        String nsId,
        String infraId,
        String nodeGroupId,
        String vmId,
        String provider,
        String status,
        MonitoringStatus monitoringStatus,
        boolean leaf,               // 최종 노드 Flag 값
        int childCount,
        List<ResourceTreeNodeResponse> children
) {
    public ResourceTreeNodeResponse {
        children = children == null ? null : List.copyOf(children);
    }

    /** children 으로 leaf/childCount 를 계산해 생성한다 (provider 없음). */
    public static ResourceTreeNodeResponse of(String id,
                                              String parentId,
                                              String name,
                                              ResourceTreeNodeType type,
                                              String resourceId,
                                              String roleName,
                                              String mdcId,
                                              String status,
                                              MonitoringStatus monitoringStatus,
                                              List<ResourceTreeNodeResponse> children) {
        return of(id, parentId, name, type, resourceId, roleName, mdcId, null, status, monitoringStatus, children);
    }

    /** children 으로 leaf/childCount 를 계산해 생성한다. */
    public static ResourceTreeNodeResponse of(String id,
                                              String parentId,
                                              String name,
                                              ResourceTreeNodeType type,
                                              String resourceId,
                                              String roleName,
                                              String mdcId,
                                              String provider,
                                              String status,
                                              MonitoringStatus monitoringStatus,
                                              List<ResourceTreeNodeResponse> children) {
        List<ResourceTreeNodeResponse> safe = children == null ? List.of() : children;
        return new ResourceTreeNodeResponse(id, parentId, name, type, resourceId, roleName, mdcId,
                null, null, null, null, provider, status, monitoringStatus, safe.isEmpty(), safe.size(), safe);
    }

    /**
     * 가상머신 가지(Namespace~VM) 전용 — Tumblebug 식별자 필드를 함께 싣는다. 자기 계층 아래 식별자는 null 로 넘긴다
     * (예: Infra 노드는 nodeGroupId·vmId 가 null).
     */
    public static ResourceTreeNodeResponse ofTumblebug(String id,
                                                       String parentId,
                                                       String name,
                                                       ResourceTreeNodeType type,
                                                       String resourceId,
                                                       String roleName,
                                                       String nsId,
                                                       String infraId,
                                                       String nodeGroupId,
                                                       String vmId,
                                                       String provider,
                                                       String status,
                                                       MonitoringStatus monitoringStatus,
                                                       List<ResourceTreeNodeResponse> children) {
        List<ResourceTreeNodeResponse> safe = children == null ? List.of() : children;
        return new ResourceTreeNodeResponse(id, parentId, name, type, resourceId, roleName, null,
                nsId, infraId, nodeGroupId, vmId, provider, status, monitoringStatus, safe.isEmpty(), safe.size(), safe);
    }

    /** 하위 목록을 교체한 사본 — leaf/childCount 를 다시 계산한다. */
    public ResourceTreeNodeResponse withChildren(List<ResourceTreeNodeResponse> newChildren) {
        List<ResourceTreeNodeResponse> safe = newChildren == null ? List.of() : newChildren;
        return new ResourceTreeNodeResponse(id, parentId, name, type, resourceId, roleName, mdcId,
                nsId, infraId, nodeGroupId, vmId, provider, status, monitoringStatus, safe.isEmpty(), safe.size(), safe);
    }

    /** Lazy Loading 응답용 — 하위 목록을 제외하고 leaf/childCount 는 유지한다. */
    public ResourceTreeNodeResponse withoutChildren() {
        return new ResourceTreeNodeResponse(id, parentId, name, type, resourceId, roleName, mdcId,
                nsId, infraId, nodeGroupId, vmId, provider, status, monitoringStatus, leaf, childCount, null);
    }
}
