package com.innogrid.aims.dashboard.dto;

import java.util.List;

/**
 * @param gpuTypes        GPU 모델명(예: NVIDIA A100) — 계약 유지, 화면은 쓰지 않는다
 * @param allocationTypes 할당 유형 PASSTHROUGH·MIG·VGPU — 피그마 "유형" 필터(R-04), 2026-10-06 신설
 */
public record GpuDashboardFilter(
        List<String> roles,
        List<String> deploymentIds,
        List<String> vmIds,
        List<String> gpuTypes,
        List<String> allocationTypes) {

    public GpuDashboardFilter(List<String> roles, List<String> deploymentIds, List<String> vmIds, List<String> gpuTypes) {
        this(roles, deploymentIds, vmIds, gpuTypes, null);
    }

    public static GpuDashboardFilter all() {
        return new GpuDashboardFilter(null, null, null, null, null);
    }
}
