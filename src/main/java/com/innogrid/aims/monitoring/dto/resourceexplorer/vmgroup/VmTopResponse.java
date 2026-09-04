package com.innogrid.aims.monitoring.dto.resourceexplorer.vmgroup;

import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeStatus;

/**
 * 가상머신 Top-N 항목. value 는 요청한 metric 의 값이고,
 * 세 지표를 모두 담아 프론트가 토글 시 보조 지표를 재조회 없이 표시한다.
 */
public record VmTopResponse(
        String vmId,
        String name,
        String alias,
        NodeStatus status,
        double value,
        double cpuUtilization,
        double memoryUtilization,
        double fileSystemUtilization
) {
}
