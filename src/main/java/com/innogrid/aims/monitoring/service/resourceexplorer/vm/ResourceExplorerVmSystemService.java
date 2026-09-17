package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostSystemMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.SystemPanelAssembler;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 VM System 카테고리 패널 조회 서비스.
 * 트리에서 VM 하위 System 항목 선택 시 개요(KPI 카드·시스템 정보 표)와 모니터링(지표 5종 차트) 탭 데이터를 제공한다.
 *
 * VM 은 nsId·infraId·vmId 로 식별하고(스코프 밖·부재는 404), 지표는 VM 안 node_exporter 의 loadavg·stat·boottime·uname·os
 * 지표를 {@code tb_ns/tb_infra/tb_node} 라벨로 좁혀 읽는다. 시스템 정보는 VM 게스트 OS 안에서 본 값(호스트명·커널)이며
 * Tumblebug 이 아는 VM 사양·이미지(ResourceExplorerVmService 의 vm/info)와는 별개다.
 * VM 은 exporter 원장이 없어 항상 Thanos 를 조회하고 표본이 없으면 미수집(null·UNAVAILABLE·빈 시리즈)이다.
 * 응답 DTO·조립 규칙은 노드 System 패널과 같다({@link SystemPanelAssembler}).
 */
@Service
public class ResourceExplorerVmSystemService {

    private final TumblebugPanelSupport support;
    private final HostSystemMetricsQueryService metrics;

    public ResourceExplorerVmSystemService(TumblebugPanelSupport support, HostSystemMetricsQueryService metrics) {
        this.support = support;
        this.metrics = metrics;
    }

    /** 개요 탭 KPI 카드 — 가동 시간(초), 부하 1/5/15, 코어 수, 컨텍스트 스위치·인터럽트 /s(최근 5분). 미수집은 null */
    public SystemSummaryResponse getSystemSummary(String roleName, String nsId, String infraId, String vmId) {
        return SystemPanelAssembler.summary(metrics.summary(selector(roleName, nsId, infraId, vmId)));
    }

    /** 개요 탭 시스템 정보 표 — 게스트 OS 호스트명·OS·커널·아키텍처·부팅 시각. 표본 없으면 UNAVAILABLE */
    public SystemInfoResponse getSystemInfo(String roleName, String nsId, String infraId, String vmId) {
        return SystemPanelAssembler.info(metrics.info(selector(roleName, nsId, infraId, vmId)));
    }

    /** 모니터링 탭 차트 — 지표별 시계열. 미수집이면 빈 시리즈 */
    public SystemMetricTrendResponse getSystemMetricTrend(String roleName, String nsId, String infraId, String vmId,
                                                          SystemMetric metric, NodeMetricInterval interval) {
        MetricSelector sel = selector(roleName, nsId, infraId, vmId);
        return SystemPanelAssembler.trend(metric, interval, (from, to, step) -> metrics.trend(metric, sel, from, to, step));
    }

    /** 스코프·존재 검증(404) 뒤 tb_* 라벨 선택자 */
    private MetricSelector selector(String roleName, String nsId, String infraId, String vmId) {
        support.accessibleNode(roleName, nsId, infraId, vmId);
        return MetricSelector.tumblebugNode(nsId, infraId, vmId);
    }
}
