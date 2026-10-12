package com.innogrid.aims.dashboard.dto;

/**
 * GPU 할당 단위 기준 분포의 점 1개(R-13) — 2026-10-06 신설. 기존 gpuTypeDistribution(모델명별 집계)과 별개다.
 * 조회 기간에 표본이 없는 GPU 는 사용률 2종이 null(collecting=false)이며 화면은 점을 찍지 않는다.
 */
public record GpuUnitPointResponse(
        String id,
        String name,
        String allocationType,
        String vmId,
        String vmName,
        String hostname,
        String gpuType,
        Double gpuUtilization,
        Double vramUtilization,
        Double temperatureC,
        Double powerWatts,
        boolean collecting) {
}
