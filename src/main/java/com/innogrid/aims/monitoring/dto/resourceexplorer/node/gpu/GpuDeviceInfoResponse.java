package com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu;

import java.time.OffsetDateTime;

import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;

/**
 * 개별 GPU 개요 탭의 GPU 정보 표.
 * (화면 라벨은 "File system 정보" 로 표기돼 있으나 디자인 오타로 보고 GPU 정보로 정의)
 * 모델·UUID·PCI Bus ID 는 dcgm_exporter 기본 라벨이다. 드라이버 버전은 exporter 가 DCGM_FI_DRIVER_VERSION 라벨을 붙일 때만,
 * CUDA·NVML·VBIOS 버전과 P-State 는 기본 수집 항목에 없어 null 이다 — TODO:: 수집 설정 확정 후 채움.
 */
public record GpuDeviceInfoResponse(
        String model,                   // 예: NVIDIA A100-SXM4-80GB
        String uuid,
        String pciBusId,
        String cudaVersion,
        String driverVersion,
        String nvmlVersion,
        String vbiosVersion,
        String pState,                  // 예: 5 (P5)
        MonitoringStatus collectStatus, // 지금 수집 중이면 COLLECTING, 끊겼으면 UNAVAILABLE
        OffsetDateTime lastCollectedAt
) {
}
