package com.innogrid.aims.monitoring.dto.resourceexplorer.node.service;

import java.util.List;

/**
 * 수집 서비스 1건 — 목록 행과 추가/편집 응답에 공용.
 * 상태/PID/포트는 노드 에이전트가 관측한 런타임 정보다.
 */
public record CollectServiceResponse(
        String serviceId,
        String serviceName,
        String status,                  // TODO:: 상태 값 체계 확정 후 enum 전환 (정상 등)
        Integer port,
        Integer pid,                    // 미기동 시 null
        String description,
        List<CollectCapability> capabilities,
        CollectServiceRequest.LogCollection logCollection,
        CollectServiceRequest.TraceCollection traceCollection
) {
    public CollectServiceResponse {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
