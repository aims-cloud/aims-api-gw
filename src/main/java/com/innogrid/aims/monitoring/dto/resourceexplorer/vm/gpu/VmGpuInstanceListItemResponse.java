package com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu;

/**
 * VM GPU 인스턴스 목록 1행.
 * Util 컬럼은 "현재/평균" 표기를 위해 현재값과 조회 기간 평균을 함께 담는다.
 */
public record VmGpuInstanceListItemResponse(
        String instanceId,
        String allocationType,          // PassThrough | MIG | vGPU — TODO:: 값 체계 확정 후 enum 전환
        double gpuUtilCurrentPercent,
        double gpuUtilAvgPercent,
        double gpuMemUtilCurrentPercent,
        double gpuMemUtilAvgPercent,
        double vramUsedGb,
        double vramAllocatedGb,
        String collectStatus,           // TODO:: 수집 상태 값 체계 확정 후 enum 전환
        long recentAlarmCount
) {
}
