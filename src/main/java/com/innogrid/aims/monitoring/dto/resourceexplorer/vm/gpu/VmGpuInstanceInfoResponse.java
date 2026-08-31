package com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu;

import java.time.OffsetDateTime;

/**
 * GPU 인스턴스 상세의 GPU 기본 정보 표 — 노드 GPU 정보의 축소판(모델/UUID/버전/수집 메타).
 */
public record VmGpuInstanceInfoResponse(
        String model,                   // 예: NVIDIA A100-SXM4-80GB
        String uuid,
        String cudaVersion,
        String driverVersion,
        String collectStatus,           // TODO:: 수집 상태 값 체계 확정 후 enum 전환
        OffsetDateTime lastCollectedAt
) {
}
