package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.util.List;

import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;
import com.innogrid.aims.monitoring.entity.MonitoringTarget;
import com.innogrid.aims.monitoring.entity.MonitoringTarget.ExporterType;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MonitoringCapabilityResolver.Snapshot;

/**
 * 물리 호스트 실행 상태 판정 (docs/06 §6) — 트리의 물리 호스트 노드와 역할 스코프(노드 목록·MDC·역할 패널)가 같은 규칙을 쓴다.
 * <ol>
 *   <li>자산 상태 INACTIVE/DECOMMISSIONED → STOP, MAINTENANCE → PENDING</li>
 *   <li>Thanos 정상 스냅샷이 없음(기동 직후 장애) → PENDING (관측 계통 장애를 호스트 오류로 집계하지 않는다)</li>
 *   <li>ipmi_exporter 가 있고 섀시 전원이 꺼져 있으면 → STOP</li>
 *   <li>exporter 미등록 → PENDING (상태 미확정)</li>
 *   <li>exporter 중 하나라도 수집 중 → RUNNING, 전부 미수집 → ERROR</li>
 * </ol>
 */
// TODO:: 1세부가 물리 호스트 실행 상태를 별도 API 로 제공하면 그 값을 우선 사용하도록 교체
public final class HostStatusJudge {

    private HostStatusJudge() {
    }

    public static ResourceStatus judge(Asset asset, List<MonitoringTarget> targets, Snapshot metrics) {
        switch (asset.getStatus()) {
            case INACTIVE, DECOMMISSIONED -> { return ResourceStatus.STOP; }
            case MAINTENANCE -> { return ResourceStatus.PENDING; }
            case ACTIVE -> { }
        }
        if (metrics.degraded()) {
            return ResourceStatus.PENDING;
        }
        for (MonitoringTarget target : targets) {
            if (target.getExporterType() == ExporterType.IPMI_EXPORTER
                    && Boolean.FALSE.equals(metrics.chassisPowerOn(target.getScrapeEndpoint()))) {
                return ResourceStatus.STOP;
            }
        }
        if (targets.isEmpty()) {
            return ResourceStatus.PENDING;
        }
        boolean anyCollecting = targets.stream()
                .anyMatch(t -> metrics.statusOf(t.getScrapeEndpoint()) == MonitoringStatus.COLLECTING);
        return anyCollecting ? ResourceStatus.RUNNING : ResourceStatus.ERROR;
    }
}
