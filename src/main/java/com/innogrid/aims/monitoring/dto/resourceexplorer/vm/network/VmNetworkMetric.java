package com.innogrid.aims.monitoring.dto.resourceexplorer.vm.network;

/**
 * VM Network 카테고리 모니터링 탭의 차트 지표 8종 — 노드 Network 에서 Total 을 제외한 구성.
 * 값은 원시 단위(B/s, pps, /s)로 내려주고 Kbps 등 표기 변환은 프론트가 담당한다.
 */
public enum VmNetworkMetric {
    NETWORK_RECV("B/s"),
    NETWORK_SENT("B/s"),
    NETWORK_PACKETS_RECV("pps"),
    NETWORK_PACKETS_SENT("pps"),
    NETWORK_ERROR_IN("/s"),
    NETWORK_ERROR_OUT("/s"),
    NETWORK_DROP_IN("/s"),
    NETWORK_DROP_OUT("/s");

    private final String unit;

    VmNetworkMetric(String unit) {
        this.unit = unit;
    }

    public String unit() {
        return unit;
    }
}
