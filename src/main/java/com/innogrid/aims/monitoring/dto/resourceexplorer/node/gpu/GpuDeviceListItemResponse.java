package com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu;

/**
 * GPU 그룹 개요 탭의 GPU 목록 1행.
 * gpuId 는 장치 UUID(트리 GPU 장치 노드의 resourceId), gpuName 은 트리와 같은 표시 이름("GPU 0").
 * Util 컬럼은 "현재/평균" 표기를 위해 현재값과 최근 1시간 평균을 함께 담는다. 지금 수집되지 않는 값은 null(미수집).
 */
public record GpuDeviceListItemResponse(
        String gpuId,
        String gpuName,
        String model,
        Double gpuUtilCurrentPercent,
        Double gpuUtilAvgPercent,
        Double vramUtilCurrentPercent,
        Double vramUtilAvgPercent,
        Double vramUsedGb,
        Double vramTotalGb,
        Double temperatureCelsius,
        Double powerWatts,
        Integer recentAlarmCount          // GPU 알람이 노드 단위로 기록돼 GPU 별로 셀 수 없어 null — TODO:: 알람이 GPU(UUID) 단위로 기록되면 집계
) {
}
