package com.innogrid.aims.dashboard.dto;

public record GpuRankingResponse(
        String id,
        String name,
        double value,
        String unit,
        String projectName,
        String credentialName,
        String vmId,
        String gpuType,
        String allocationType) {
}
