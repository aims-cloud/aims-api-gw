package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.time.Instant;
import java.util.List;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogFileResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogLevel;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogListResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.AgentTargetsStatusResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceRequest;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceValidationResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.DiscoveredServiceResponse;
import com.innogrid.aims.monitoring.entity.CollectOwner;
import com.innogrid.aims.monitoring.service.resourceexplorer.node.ResourceExplorerNodeLogService;
import com.innogrid.aims.monitoring.service.resourceexplorer.node.ResourceExplorerNodeLogService.LogTarget;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 VM(Tumblebug) 로그 탭 — 수집 서비스 원장·후보·검증·에이전트 반영 상태·로그 조회 (docs/11).
 *
 * VM 은 nsId·infraId·vmId 로 식별하고(스코프 밖·부재는 404, 다른 VM 패널과 같다), 원장 소유자는 Tumblebug 키,
 * Loki 선택 라벨은 {@code tb_ns·tb_infra·tb_node}, systemd 후보는 VM node_exporter 의 같은 라벨 시계열이다.
 * 동작 규칙은 물리 노드 로그 탭과 같으며 공통 로직({@link ResourceExplorerNodeLogService} 의 "…Of" 메서드)을 그대로 쓴다.
 * 트레이스는 범위 제외라 VM 에는 두지 않는다.
 */
@Service
public class ResourceExplorerVmLogService {

    private final TumblebugPanelSupport support;
    private final ResourceExplorerNodeLogService logs;

    public ResourceExplorerVmLogService(TumblebugPanelSupport support, ResourceExplorerNodeLogService logs) {
        this.support = support;
        this.logs = logs;
    }

    public PageResponse<CollectServiceResponse> getCollectServices(String roleName, String nsId, String infraId, String vmId,
                                                                   String keyword, int page, int size, String sort) {
        return logs.collectServicesOf(target(roleName, nsId, infraId, vmId), keyword, page, size, sort);
    }

    public List<DiscoveredServiceResponse> getDiscoveredServices(String roleName, String nsId, String infraId, String vmId) {
        return logs.discoveredServicesOf(target(roleName, nsId, infraId, vmId));
    }

    public CollectServiceValidationResponse validateCollectService(String roleName, String nsId, String infraId, String vmId,
                                                                   CollectServiceRequest request) {
        return logs.validateOf(target(roleName, nsId, infraId, vmId), request);
    }

    public CollectServiceResponse createCollectService(String roleName, String nsId, String infraId, String vmId,
                                                       CollectServiceRequest request) {
        return logs.createOf(target(roleName, nsId, infraId, vmId), request);
    }

    public CollectServiceResponse updateCollectService(String roleName, String nsId, String infraId, String vmId,
                                                       String serviceId, CollectServiceRequest request) {
        return logs.updateOf(target(roleName, nsId, infraId, vmId), serviceId, request);
    }

    public void deleteCollectService(String roleName, String nsId, String infraId, String vmId, String serviceId) {
        logs.deleteOf(target(roleName, nsId, infraId, vmId), serviceId);
    }

    public AgentTargetsStatusResponse getAgentTargetsStatus(String roleName, String nsId, String infraId, String vmId) {
        return logs.agentStatusOf(target(roleName, nsId, infraId, vmId));
    }

    public List<ServiceLogFileResponse> getLogFiles(String roleName, String nsId, String infraId, String vmId,
                                                    String serviceId, Instant from, Instant to) {
        return logs.logFilesOf(target(roleName, nsId, infraId, vmId), serviceId, from, to);
    }

    public ServiceLogSummaryResponse getLogSummary(String roleName, String nsId, String infraId, String vmId,
                                                   String serviceId, String file, Instant from, Instant to) {
        return logs.logSummaryOf(target(roleName, nsId, infraId, vmId), serviceId, file, from, to);
    }

    public ServiceLogTrendResponse getLogTrend(String roleName, String nsId, String infraId, String vmId, String serviceId,
                                               List<String> files, Instant from, Instant to, String interval) {
        return logs.logTrendOf(target(roleName, nsId, infraId, vmId), serviceId, files, from, to, interval);
    }

    public ServiceLogListResponse searchLogs(String roleName, String nsId, String infraId, String vmId, String serviceId,
                                             List<String> files, List<ServiceLogLevel> levels, String keyword,
                                             Instant from, Instant to, int limit) {
        return logs.searchLogsOf(target(roleName, nsId, infraId, vmId), serviceId, files, levels, keyword, from, to, limit);
    }

    /** 스코프·존재 검증(404) 뒤 VM 대상 — 원장 소유자·Loki 라벨·systemd 선택자가 모두 같은 Tumblebug 키다 */
    private LogTarget target(String roleName, String nsId, String infraId, String vmId) {
        support.accessibleNode(roleName, nsId, infraId, vmId);
        return new LogTarget(CollectOwner.vm(nsId, infraId, vmId), null, new VmLogScope(nsId, infraId, vmId),
                MetricSelector.tumblebugNode(nsId, infraId, vmId));
    }
}
