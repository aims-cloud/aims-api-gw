package com.innogrid.aims.monitoring.dto.resourceexplorer.node.system;

import java.time.OffsetDateTime;

import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;

/**
 * System 카테고리 개요 탭의 시스템 정보 표 — node_exporter {@code node_uname_info}(호스트명·커널·아키텍처)와
 * {@code node_os_info}(OS 이름·버전), {@code node_boot_time_seconds}(부팅 시각).
 * 값이 없으면 null — 화면에는 "-" 로 표시한다.
 */
public record SystemInfoResponse(
        String hostname,                // uname nodename
        String osName,                  // os-release PRETTY_NAME (없으면 NAME)
        String osVersion,               // os-release VERSION_ID
        String kernelRelease,           // uname release
        String architecture,            // uname machine
        OffsetDateTime bootTime,
        MonitoringStatus collectStatus, // NOT_CONFIGURED / COLLECTING / UNAVAILABLE
        OffsetDateTime lastCollectedAt  // 정보를 만든 Thanos 표본의 시각
) {
}
