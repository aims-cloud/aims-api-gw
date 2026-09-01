package com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup;

import java.time.Duration;

/**
 * 노드 Top-N 의 평균 집계 기간 — 화면의 "최근 10분/1시간/6시간/24시간" 선택지.
 * 실데이터 연동 시 PromQL avg_over_time 의 윈도우로 사용한다.
 */
public enum NodeMetricInterval {
    LAST_10M(Duration.ofMinutes(10)),
    LAST_1H(Duration.ofHours(1)),
    LAST_6H(Duration.ofHours(6)),
    LAST_24H(Duration.ofHours(24));

    private final Duration window;

    NodeMetricInterval(Duration window) {
        this.window = window;
    }

    /** 평균 집계 윈도우 — PromQL duration 변환용 */
    public Duration window() {
        return window;
    }
}
