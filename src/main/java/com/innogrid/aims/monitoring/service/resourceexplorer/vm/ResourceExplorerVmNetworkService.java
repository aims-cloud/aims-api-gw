package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.time.Instant;
import java.util.List;

import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.network.VmNetworkMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.network.VmNetworkMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.network.VmNetworkMetricTrendResponse.Series;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostCategoryMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 VM Network 카테고리 패널 조회 서비스.
 * 트리에서 VM 하위 Network 항목 선택 시 모니터링 탭 차트 데이터를 제공한다.
 * (개요 탭은 화면 설계 미확정 — 추가 시 CPU 패턴대로 확장한다)
 *
 * VM 은 nsId·infraId·vmId 로 식별하고(스코프 밖·부재는 404), 지표는 VM 안 node_exporter 의 node_network_*_total 을
 * {@code tb_ns/tb_infra/tb_node} 라벨로 좁혀 NIC 합산 rate 로 읽는다(lo·가상 인터페이스 제외).
 * VM 상세 패널은 ResourceExplorerVmService, 노드 Network 는 ResourceExplorerNodeNetworkService 가 담당한다.
 */
@Service
public class ResourceExplorerVmNetworkService {

    private final TumblebugPanelSupport support;
    private final HostCategoryMetricsQueryService metrics;

    public ResourceExplorerVmNetworkService(TumblebugPanelSupport support, HostCategoryMetricsQueryService metrics) {
        this.support = support;
        this.metrics = metrics;
    }

    /** 모니터링 탭 차트 — 지표별 시계열 8종 (VM NIC 합산). 미수집이면 빈 시리즈 */
    public VmNetworkMetricTrendResponse getNetworkMetricTrend(String roleName, String nsId, String infraId, String vmId,
                                                              VmNetworkMetric metric, NodeMetricInterval interval) {
        support.accessibleNode(roleName, nsId, infraId, vmId);
        MetricSelector sel = MetricSelector.tumblebugNode(nsId, infraId, vmId);
        Instant to = Instant.now();
        Instant from = to.minus(interval.window());
        String step = interval.defaultStep();
        List<DataPoint> points = metrics.networkTrend(metric, sel, from, to, step);
        return new VmNetworkMetricTrendResponse(metric, metric.unit(), from.getEpochSecond(), to.getEpochSecond(), step,
                points.isEmpty() ? List.of() : List.of(new Series("network", points)));
    }
}
