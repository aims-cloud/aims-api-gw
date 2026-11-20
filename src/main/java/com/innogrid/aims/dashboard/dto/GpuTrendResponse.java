package com.innogrid.aims.dashboard.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record GpuTrendResponse(String id, String name, List<DataPoint> dataPoints) {
    public record DataPoint(OffsetDateTime timestamp, double value) {}
}
