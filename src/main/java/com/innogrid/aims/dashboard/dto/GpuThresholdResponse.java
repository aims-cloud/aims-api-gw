package com.innogrid.aims.dashboard.dto;

public record GpuThresholdResponse(
        double idleGpuUtilization,
        double highGpuUtilization,
        double highVramUtilization) {
}
