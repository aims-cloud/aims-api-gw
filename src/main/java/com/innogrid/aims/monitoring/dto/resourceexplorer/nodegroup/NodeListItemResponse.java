package com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup;

import java.util.List;

/**
 * 노드 목록 테이블 1행 — 호스트명/별칭/상태/IP 주소/CPU·Memory·File system 사용률.
 * IP 는 노드당 여러 개(관리망/서비스망)일 수 있다.
 * 사용률(Double)이 null 이면 미수집("-") — 수집값 0% 와 구분된다.
 */
public record NodeListItemResponse(
        String nodeId,
        String name,
        String alias,
        NodeStatus status,
        List<String> ipAddresses,
        Double cpuUtilization,
        Double memoryUtilization,
        Double fileSystemUtilization
) {
    public NodeListItemResponse {
        ipAddresses = ipAddresses == null ? List.of() : List.copyOf(ipAddresses);
    }
}
