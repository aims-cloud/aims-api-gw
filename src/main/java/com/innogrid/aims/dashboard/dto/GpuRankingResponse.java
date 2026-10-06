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
        String allocationType,          // PASSTHROUGH·MIG·VGPU (실데이터 3단계 매핑 전에는 MIG 또는 UNKNOWN)
        String roleName,                // 2026-10-06 — 메타(R-14·R-15): 역할
        String deploymentId) {          // 2026-10-06 — 메타: 배포영역(MDC)
}
