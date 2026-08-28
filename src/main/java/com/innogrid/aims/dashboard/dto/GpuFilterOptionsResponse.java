package com.innogrid.aims.dashboard.dto;

import java.util.List;

public record GpuFilterOptionsResponse(
        List<String> roles,
        List<String> deploymentIds,
        List<VmOption> vms,
        List<String> gpuTypes,
        List<String> allocationTypes) {

    public record VmOption(String id, String name) {}
}
