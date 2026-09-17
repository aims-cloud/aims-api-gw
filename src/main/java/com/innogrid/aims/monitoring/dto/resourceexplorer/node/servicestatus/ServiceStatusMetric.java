package com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus;

/**
 * Service Status 카테고리 모니터링 탭의 차트 지표 3종 — 상태별 systemd 유닛 수의 시계열.
 * {@code sum(node_systemd_unit_state{state="…"})} 이라 해당 상태 유닛이 없으면 0 이 나온다.
 */
public enum ServiceStatusMetric {
    ACTIVE_UNITS(ServiceUnitState.ACTIVE),
    FAILED_UNITS(ServiceUnitState.FAILED),
    INACTIVE_UNITS(ServiceUnitState.INACTIVE);

    private final ServiceUnitState state;

    ServiceStatusMetric(ServiceUnitState state) {
        this.state = state;
    }

    public ServiceUnitState state() {
        return state;
    }

    public String unit() {
        return "count";
    }
}
