package com.innogrid.aims.monitoring.dto.resourceexplorer.node.network;

/**
 * Network 카테고리 모니터링 탭의 차트 지표 9종.
 * 값은 원시 단위(B/s, pps, /s)로 내려주고 Kbps 등 표기 변환은 프론트가 담당한다.
 * 집계 대상 인터페이스는 물리 NIC 기준 — lo·가상 인터페이스 제외 패턴은 테스트베드 확인 후 확정.
 */
public enum NetworkMetric {
    NETWORK_TOTAL("B/s"),          // receive + transmit 합산
    NETWORK_RECV("B/s"),
    NETWORK_SENT("B/s"),
    NETWORK_PACKETS_RECV("pps"),
    NETWORK_PACKETS_SENT("pps"),
    NETWORK_ERROR_IN("/s"),
    NETWORK_ERROR_OUT("/s"),
    NETWORK_DROP_IN("/s"),
    NETWORK_DROP_OUT("/s");

    private final String unit;

    NetworkMetric(String unit) {
        this.unit = unit;
    }

    public String unit() {
        return unit;
    }
}
