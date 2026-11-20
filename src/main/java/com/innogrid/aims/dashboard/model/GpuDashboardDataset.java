package com.innogrid.aims.dashboard.model;

import java.util.List;

public record GpuDashboardDataset(GpuDashboardThreshold thresholds, List<GpuDeviceMetrics> gpus) {
}
