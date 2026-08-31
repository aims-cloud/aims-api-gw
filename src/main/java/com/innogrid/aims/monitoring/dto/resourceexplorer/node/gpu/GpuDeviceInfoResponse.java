package com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu;

import java.time.OffsetDateTime;

/**
 * 개별 GPU 개요 탭의 GPU 정보 표.
 * (화면 라벨은 "File system 정보" 로 표기돼 있으나 디자인 오타로 보고 GPU 정보로 정의)
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
        String collectStatus,           // TODO:: 수집 상태 값 체계 확정 후 enum 전환 (CPU·File system 과 공통화)
        OffsetDateTime lastCollectedAt
) {
}
