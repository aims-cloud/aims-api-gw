package com.innogrid.aims.monitoring.dto.resourceexplorer.node.log;

/** 서비스 로그 레벨 — 추이 차트 스택과 목록 레벨 필터 칩에 사용한다. */
public enum ServiceLogLevel {
    INFO, WARNING, ERROR, DEBUG, UNKNOWN;

    public static ServiceLogLevel normalize(String value) {
        if (value == null) return UNKNOWN;
        return switch (value.toUpperCase(java.util.Locale.ROOT)) {
            case "INFO" -> INFO;
            case "WARN", "WARNING" -> WARNING;
            case "ERROR", "CRITICAL", "FATAL" -> ERROR;
            case "DEBUG" -> DEBUG;
            default -> UNKNOWN;
        };
    }
}
