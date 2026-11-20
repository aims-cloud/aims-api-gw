package com.innogrid.aims.dashboard.model;

/**
 * GPU 대시보드 판정 임계값(%) — 전역 공용 (Confluence 2320072739 §3, R-07~R-09·R-18~R-21).
 * 활성: 창 평균 사용률 &gt; active, 유휴: &lt; idle(같으면 어느 쪽도 아님), 고활용: 사용률 ≥ high AND VRAM ≥ highVram.
 */
public record GpuDashboardThreshold(
        double activeGpuUtilization,
        double idleGpuUtilization,
        double highGpuUtilization,
        double highVramUtilization) {

    /** 저장된 값이 없을 때 — 피그마 기본 문구(활성 &gt; 10%, 유휴 &lt; 10%, 고활용 60% & 60%) */
    public static final GpuDashboardThreshold DEFAULT = new GpuDashboardThreshold(10.0, 10.0, 60.0, 60.0);
}
