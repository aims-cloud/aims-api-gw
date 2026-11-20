package com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus;

/**
 * systemd 유닛 활성 상태 — node_exporter {@code node_systemd_unit_state} 의 {@code state} 라벨 5종.
 * 목록 필터 파라미터와 응답의 state 값(소문자 라벨 원문)에 쓴다.
 */
public enum ServiceUnitState {
    ACTIVE("active"),
    INACTIVE("inactive"),
    FAILED("failed"),
    ACTIVATING("activating"),
    DEACTIVATING("deactivating");

    private final String label;

    ServiceUnitState(String label) {
        this.label = label;
    }

    /** node_exporter state 라벨 값 */
    public String label() {
        return label;
    }
}
