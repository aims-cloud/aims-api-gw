package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;

/**
 * Tumblebug 상태값 → 집계 버킷 매핑 (docs/06 §6.1).
 *
 * 트리 노드와 상세 화면에는 Tumblebug 상태값을 원문으로 노출하고,
 * 역할·노드그룹 패널의 상태 개수 카드(running/pending/stop/error)에서만 이 버킷을 사용한다.
 */
public final class TumblebugStatusMapper {

    private TumblebugStatusMapper() {}

    public static ResourceStatus bucket(String tumblebugStatus) {
        if (tumblebugStatus == null || tumblebugStatus.isBlank()) {
            return ResourceStatus.ERROR;                                // Undefined 취급
        }
        // Infra 상태는 "Running-(3/3)" 처럼 접미가 붙을 수 있어 접두만 비교한다
        String head = tumblebugStatus.trim().split("[-(]", 2)[0].trim();
        return switch (head) {
            case "Running" -> ResourceStatus.RUNNING;
            case "Creating", "Registering", "Reconciling", "Suspending", "Resuming", "Rebooting", "Terminating",
                 "Preparing", "Prepared" -> ResourceStatus.PENDING;
            case "Suspended", "Terminated" -> ResourceStatus.STOP;
            default -> ResourceStatus.ERROR;                            // Failed, Undefined, 그 외
        };
    }
}
