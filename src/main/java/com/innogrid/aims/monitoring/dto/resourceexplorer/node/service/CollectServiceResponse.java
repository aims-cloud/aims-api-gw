package com.innogrid.aims.monitoring.dto.resourceexplorer.node.service;

import java.util.List;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.LogStatus;

/**
 * 수집 서비스 1건 — 목록 행과 추가/편집 응답에 공용.
 * 상태/PID/포트는 노드 에이전트가 관측한 런타임 정보다.
 */
public record CollectServiceResponse(
        String serviceId,
        String serviceName,
        String status,                  // 실행 상태는 미관측(null)
        LogStatus logStatus,
        Integer port,
        Integer pid,                    // 미관측(null)
        String description,
        List<CollectCapability> capabilities, // 지원 여부 미확정: 빈 목록. 희망 설정은 아래 필드 사용
        CollectServiceRequest.LogCollection logCollection,
        CollectServiceRequest.TraceCollection traceCollection
) {
    public CollectServiceResponse {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
