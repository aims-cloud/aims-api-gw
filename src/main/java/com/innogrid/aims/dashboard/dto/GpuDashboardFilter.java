package com.innogrid.aims.dashboard.dto;

import java.util.List;

public record GpuDashboardFilter(
        List<String> roles,
        List<String> deploymentIds,
        List<String> vmIds,
        List<String> gpuTypes) {
}
