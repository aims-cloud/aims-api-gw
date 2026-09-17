package com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus;

/**
 * Service Status 카테고리 개요 탭의 유닛 목록 1행 — node_exporter {@code node_systemd_unit_state} 라벨 그대로.
 * type 은 서비스 유형(simple·oneshot·notify·forking …, systemd 콜렉터 버전에 따라 없을 수 있어 null 가능).
 */
public record ServiceUnitListItemResponse(
        String name,                // 예: ssh.service
        String type,
        ServiceUnitState state
) {
}
