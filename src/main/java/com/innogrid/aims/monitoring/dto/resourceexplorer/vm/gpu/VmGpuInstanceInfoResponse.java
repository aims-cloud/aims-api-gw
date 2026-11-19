package com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu;

import java.time.OffsetDateTime;

import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;

/**
 * GPU 인스턴스 상세의 GPU 기본 정보 표 — 노드 GPU 정보의 축소판(모델/UUID/버전/수집 메타).
 * 드라이버 버전은 dcgm_exporter 의 DCGM_FI_DRIVER_VERSION 라벨, CUDA 버전은 기본 수집 항목에 없어 null 이다.
 */
public record VmGpuInstanceInfoResponse(
        String model,                   // 예: NVIDIA A100-SXM4-80GB
        String uuid,
        String cudaVersion,
        String driverVersion,
        MonitoringStatus collectStatus, // COLLECTING | UNAVAILABLE
        OffsetDateTime lastCollectedAt
) {
}
