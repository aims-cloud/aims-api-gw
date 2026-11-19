package com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu;

import java.time.OffsetDateTime;

import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;

/**
 * CPU 카테고리 개요 탭의 CPU 목록 1행 — node_exporter cpu.info 콜렉터({@code node_cpu_info})의 package(소켓) 단위.
 * cpu.info 가 꺼진 노드는 논리 CPU 수만으로 소켓 0 한 행이 내려간다(벤더·모델·클럭 null).
 */
public record CpuDeviceListItemResponse(
        String cpuId,                   // "cpu-{socket}"
        int socket,
        String vendor,
        String model,
        int coreCount,
        int threadCount,
        Double baseClockGhz,            // model_name 의 "@ 2.00GHz" 표기에서 추출, 없으면 null
        MonitoringStatus collectStatus, // 행이 있으면 COLLECTING
        OffsetDateTime lastCollectedAt  // 목록을 만든 Thanos 표본의 시각
) {
}
