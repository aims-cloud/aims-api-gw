package com.innogrid.aims.dashboard.dto;

/**
 * 요약 카드 5장 (Confluence 2320072739 R-07~R-11). 사용률·VRAM 은 조회 기간 평균, Peak 는 기간 최대.
 * 판정은 조회 기간에 표본이 있는 GPU 만 대상이다: 활성 사용률 &gt; active, 유휴 &lt; idle, 고활용 사용률 ≥ high AND VRAM ≥ highVram.
 */
public record GpuSummaryResponse(
        long allocatedGpuCount,          // VM 에 할당된 GPU 단위 수(3단계 매핑 전 실데이터에서는 0)
        long idleGpuCount,
        long highUtilizationGpuCount,
        double vramUtilization,
        double vramUsedMb,
        double vramTotalMb,
        double peakVramMb,
        long totalGpuCount,              // 2026-10-06 — 보이는 GPU 단위 수(수집 끊긴 GPU 포함)
        long activeGpuCount,             // 2026-10-06 — 활성 GPU 수(R-07)
        String peakVramGpuId,            // 2026-10-06 — Peak VRAM 이 나온 GPU 단위(R-11). 없으면 null
        String peakVramVmId,             // 그 GPU 의 VM(3단계 매핑 전 실데이터에서는 null)
        String peakVramVmName) {
}
