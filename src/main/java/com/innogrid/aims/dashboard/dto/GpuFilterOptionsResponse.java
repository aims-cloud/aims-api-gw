package com.innogrid.aims.dashboard.dto;

import java.util.List;

public record GpuFilterOptionsResponse(
        List<String> roles,
        List<String> deploymentIds,
        List<VmOption> vms,
        List<String> gpuTypes,
        List<String> allocationTypes,
        List<DeploymentOption> deployments) {   // 2026-10-06 — 배포영역 id·표시명(R-02). MDC 원장이 없어 표시명은 mdcId

    public record VmOption(String id, String name) {}

    public record DeploymentOption(String id, String name) {}
}
