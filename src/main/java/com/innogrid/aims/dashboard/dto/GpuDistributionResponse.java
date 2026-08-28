package com.innogrid.aims.dashboard.dto;

public record GpuDistributionResponse(
        String id,
        String name,
        double gpuUtilization,
        double vramUtilization,
        int gpuCount) {
}
