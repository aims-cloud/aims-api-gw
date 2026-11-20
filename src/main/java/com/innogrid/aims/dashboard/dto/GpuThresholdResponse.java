package com.innogrid.aims.dashboard.dto;

/** GPU 판정 임계값(%) — 활성은 2026-10-06 추가(피그마 "활성 GPU 기준" 팝업, R-18) */
public record GpuThresholdResponse(
        double idleGpuUtilization,
        double highGpuUtilization,
        double highVramUtilization,
        double activeGpuUtilization) {
}
