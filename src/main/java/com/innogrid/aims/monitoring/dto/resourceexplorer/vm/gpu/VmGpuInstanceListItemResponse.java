package com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu;

import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;

/**
 * VM GPU 인스턴스 목록 1행.
 * instanceId 는 GPU UUID(트리 GPU 장치 노드의 resourceId), instanceName 은 트리와 같은 표시 이름("GPU 0").
 * Util 컬럼은 "현재/평균" 표기를 위해 현재값과 최근 1시간 평균을 함께 담는다. GPU Mem util 은 VRAM 사용률이다.
 * vramAllocatedGb 는 VM 이 보는 VRAM 전체(사용+여유). 지금 수집되지 않는 값은 null(미수집).
 */
public record VmGpuInstanceListItemResponse(
        String instanceId,
        String instanceName,
        String allocationType,          // VM 안에서는 MIG 만 판별 가능 — MIG 면 "MIG", 그 외(PassThrough·vGPU 구분 불가)는 null
        Double gpuUtilCurrentPercent,
        Double gpuUtilAvgPercent,
        Double gpuMemUtilCurrentPercent,
        Double gpuMemUtilAvgPercent,
        Double vramUsedGb,
        Double vramAllocatedGb,
        MonitoringStatus collectStatus, // COLLECTING | UNAVAILABLE
        Long recentAlarmCount           // GPU 알람이 GPU 단위로 기록되지 않아 null
) {
}
