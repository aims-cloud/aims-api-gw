package com.innogrid.aims.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

public record UpdateGpuThresholdRequest(
        @Schema(description = "유휴 GPU 판정 사용률(%) — 사용률 < 이 값", example = "10")
        @DecimalMin("0.0") @DecimalMax("100.0") double idleGpuUtilization,
        @Schema(description = "고활용 GPU 판정 사용률(%) — 사용률 ≥ 이 값 AND VRAM ≥ highVramUtilization", example = "60")
        @DecimalMin("0.0") @DecimalMax("100.0") double highGpuUtilization,
        @Schema(description = "고활용 GPU 판정 VRAM 사용률(%)", example = "60")
        @DecimalMin("0.0") @DecimalMax("100.0") double highVramUtilization,
        @Schema(description = "활성 GPU 판정 사용률(%) — 사용률 > 이 값. 생략하면 현재 값 유지(기존 클라이언트 호환)", example = "10")
        @DecimalMin("0.0") @DecimalMax("100.0") Double activeGpuUtilization) {
}
