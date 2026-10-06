package com.innogrid.aims.dashboard.dto;

import java.util.List;

/**
 * GPU 노드 수집 현황 — 기준 목록은 DB(스코프 안 DCGM 수집 대상)이고 Thanos 는 값만 채운다 (Confluence 2320072739 §2).
 * "N대 중 M대 수집 중"의 분모·분자이며, 수집이 끊긴 노드도 nodes 에 collecting=false 로 남는다.
 *
 * @param registeredNodeCount   스코프 안 GPU 노드(등록된 DCGM 수집 대상) 수
 * @param collectingNodeCount   그중 지금 DCGM 시계열이 들어오는 노드 수
 * @param unregisteredNodeCount Thanos 에는 DCGM 시계열이 있으나 수집 대상 원장에 없는 노드 수. ADMIN 토큰에만 값, 그 외 null
 */
public record GpuNodeCoverageResponse(
        int registeredNodeCount,
        int collectingNodeCount,
        Integer unregisteredNodeCount,
        List<Node> nodes) {

    /** 노드 1대 — assetId·instance 는 mock 모드에서 null */
    public record Node(Long assetId, String name, String mdcId, String instance, boolean collecting) {}
}
