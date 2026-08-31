package com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu;

import java.time.OffsetDateTime;

/**
 * CPU 카테고리 개요 탭의 CPU 목록 1행.
 * 소켓·벤더·모델·코어/스레드·클럭은 node_exporter cpu.info 콜렉터(node_cpu_info) 기준.
 */
public record CpuDeviceListItemResponse(
        String cpuId,
        int socket,
        String vendor,
        String model,
        int coreCount,
        int threadCount,
        double baseClockGhz,
        String collectStatus,           // TODO:: 수집 상태 값 체계 확정 후 enum 전환
        OffsetDateTime lastCollectedAt
) {
}
