package com.innogrid.aims.dashboard.model;

import java.time.OffsetDateTime;

public record GpuMetricHistory(OffsetDateTime time, double gpuUtilization, double vramUsedMb) {
}
