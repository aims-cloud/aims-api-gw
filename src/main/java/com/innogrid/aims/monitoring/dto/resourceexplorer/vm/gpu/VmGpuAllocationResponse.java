package com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu;

/**
 * GPU 인스턴스 상세의 가상머신 할당 정보 표 — 가상화 계층(할당) 관점 데이터.
 * DCGM 관점의 장치 정보는 VmGpuInstanceInfoResponse 가 담당한다.
 */
public record VmGpuAllocationResponse(
        String instanceName,            // 예: gpu-instance-01
        String vmId,
        String vmName,                  // 화면에서 VM 상세로 링크
        String allocationType,          // PassThrough | MIG | vGPU
        String physicalGpuId,           // 매핑된 물리 GPU (예: GPU-01)
        double vramAllocatedGb,
        double vramUsedGb,
        double vramUsedPercent,
        String licenseStatus            // TODO:: vGPU 라이선스 상태 원천·값 체계 확정 필요 (활성화 등)
) {
}
