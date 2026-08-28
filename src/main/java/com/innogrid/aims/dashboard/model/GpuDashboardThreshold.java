package com.innogrid.aims.dashboard.model;

public record GpuDashboardThreshold(
        double idleGpuUtilization,
        double highGpuUtilization,
        double highVramUtilization) {
}
