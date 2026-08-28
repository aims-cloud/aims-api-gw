package com.innogrid.aims.dashboard.model;

import java.util.List;

public record GpuDeviceMetrics(
        String gpuId,
        String gpuIndex,
        String uuid,
        String hostname,
        String role,
        String deploymentId,
        String vmId,
        String vmName,
        String projectName,
        String credentialName,
        String gpuType,
        String allocationType,
        double gpuUtilization,
        double vramUsedMb,
        double vramTotalMb,
        double peakVramMb,
        List<GpuMetricHistory> history) {

    public GpuDeviceMetrics {
        history = history == null ? List.of() : history;
    }

    public double vramUtilization() {
        return vramTotalMb == 0 ? 0.0 : vramUsedMb / vramTotalMb * 100.0;
    }
}
