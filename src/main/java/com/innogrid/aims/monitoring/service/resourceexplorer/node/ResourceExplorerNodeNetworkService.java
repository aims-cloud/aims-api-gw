package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;
import java.util.List;

import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.network.NetworkMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.network.NetworkMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.network.NetworkMetricTrendResponse.Series;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostCategoryMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 노드 Network 카테고리 패널 조회 서비스.
 * 트리에서 노드 하위 Network 항목 선택 시 모니터링 탭 차트 9종(Total/Recv/Sent, Packets, Error, Drop) 데이터를 제공한다.
 * (개요 탭은 화면 설계 미확정 — 추가 시 CPU 패턴대로 summary/devices 를 확장한다)
 *
 * nodeId 는 assets.id 이며 역할 스코프 밖이면 404({@link NodeAccessService}).
 * 지표는 노드 node_exporter 의 node_network_*_total 을 {@code instance} 라벨로 좁혀 물리 NIC 합산 rate 로 읽는다
 * (lo·veth·docker·br·virbr 제외 — 상세 패널의 네트워크 카드와 같은 필터).
 * node_exporter 타겟이 없는 노드는 Thanos 를 조회하지 않고 빈 시리즈를 내린다.
 * 노드 상세 패널은 ResourceExplorerNodeService, VM Network 는 ResourceExplorerVmNetworkService 가 담당한다.
 */
@Service
public class ResourceExplorerNodeNetworkService {

    private final NodeAccessService nodeAccess;
    private final HostCategoryMetricsQueryService metrics;

    public ResourceExplorerNodeNetworkService(NodeAccessService nodeAccess, HostCategoryMetricsQueryService metrics) {
        this.nodeAccess = nodeAccess;
        this.metrics = metrics;
    }

    /** 모니터링 탭 차트 — 지표별 시계열(B/s, pps, /s). 조회 구간은 지금 기준 interval 기간, 버킷 간격은 기간별 defaultStep */
    public NetworkMetricTrendResponse getNetworkMetricTrend(String nodeId, NetworkMetric metric, NodeMetricInterval interval) {
        String instance = nodeAccess.require(nodeId).host().instance();
        Instant to = Instant.now();
        Instant from = to.minus(interval.window());
        String step = interval.defaultStep();

        List<DataPoint> points = instance == null
                ? List.of()
                : metrics.networkTrend(metric, MetricSelector.instance(instance), from, to, step);
        return new NetworkMetricTrendResponse(metric, metric.unit(), from.getEpochSecond(), to.getEpochSecond(), step,
                points.isEmpty() ? List.of() : List.of(new Series("network", points)));
    }
}
