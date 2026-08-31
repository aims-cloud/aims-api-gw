package com.innogrid.aims.monitoring.dto.resourceexplorer.node.service;

import java.util.List;

/**
 * 노드 에이전트가 탐지한 수집 후보 서비스 1건 — 서비스 추가 위저드 1단계 드롭다운에 사용한다.
 * 선택 시 설명(기본값)/PID/포트/수집 가능 배지가 화면에 표시된다.
 */
public record DiscoveredServiceResponse(
        String serviceName,
        String description,
        Integer pid,
        Integer port,
        List<CollectCapability> capabilities
) {
    public DiscoveredServiceResponse {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
