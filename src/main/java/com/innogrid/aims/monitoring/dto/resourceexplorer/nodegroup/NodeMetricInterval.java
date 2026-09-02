package com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup;

import java.time.Duration;

/**
 * 자원 탐색기 공용 지표 조회 기간 — 화면의 "최근 10분/1시간/6시간/24시간/30일/90일" 선택지.
 * 노드 Top-N 은 avg_over_time 의 평균 윈도우로, 모니터링 요약 차트는 조회 구간(지금-window ~ 지금)으로 쓴다.
 * defaultStep 은 기간에 맞춘 시계열 버킷 간격이다.
 */
public enum NodeMetricInterval {
    LAST_10M(Duration.ofMinutes(10), "30s"),
    LAST_1H(Duration.ofHours(1), "5m"),
    LAST_6H(Duration.ofHours(6), "15m"),
    LAST_24H(Duration.ofHours(24), "1h"),
    LAST_30D(Duration.ofDays(30), "1d"),
    LAST_90D(Duration.ofDays(90), "3d");

    private final Duration window;
    private final String defaultStep;

    NodeMetricInterval(Duration window, String defaultStep) {
        this.window = window;
        this.defaultStep = defaultStep;
    }

    /** 조회/평균 집계 윈도우 — PromQL duration 변환용 */
    public Duration window() {
        return window;
    }

    /** 기간에 맞춘 시계열 버킷 간격 (PromQL step) */
    public String defaultStep() {
        return defaultStep;
    }
}
