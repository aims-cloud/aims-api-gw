package com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu;

/**
 * GPU 인스턴스 상세의 가상머신 할당 정보 표 — 가상화 계층(할당) 관점 데이터.
 * DCGM 관점의 장치 정보는 VmGpuInstanceInfoResponse 가 담당한다.
 *
 * VM 안의 dcgm_exporter 로 알 수 있는 값만 채운다. 할당 방식은 MIG 만 판별되고, 매핑된 물리 GPU·라이선스 상태는
 * 하이퍼바이저 쪽 정보라 null 이다 — TODO:: 1세부 GPU 할당 원장(gpu_allocations) 확정 후 채움.
 */
public record VmGpuAllocationResponse(
        String instanceName,            // 트리와 같은 표시 이름 (예: GPU 0)
        String vmId,
        String vmName,                  // 화면에서 VM 상세로 링크
        String allocationType,          // MIG 면 "MIG", 그 외(PassThrough·vGPU 구분 불가)는 null
        String physicalGpuId,           // 매핑된 물리 GPU — 할당 원장 전까지 null
        Double vramAllocatedGb,
        Double vramUsedGb,
        Double vramUsedPercent,
        String licenseStatus            // vGPU 라이선스 상태 — 원천·값 체계 확정 전까지 null
) {
}
