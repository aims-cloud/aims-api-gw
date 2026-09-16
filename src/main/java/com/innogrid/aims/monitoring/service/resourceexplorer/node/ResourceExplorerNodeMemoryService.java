package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;
import java.util.List;

import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.memory.MemoryMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.memory.MemoryMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.memory.MemoryMetricTrendResponse.Series;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostCategoryMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 노드 Memory 카테고리 패널 조회 서비스.
 * 트리에서 노드 하위 Memory 항목 선택 시 모니터링 탭 차트 5종(Used/Used %/Free/Free %/Total) 데이터를 제공한다.
 * (개요 탭은 화면 설계 미확정 — 추가 시 CPU 패턴대로 summary/devices 를 확장한다)
 *
 * nodeId 는 assets.id 이며 역할 스코프 밖이면 404({@link NodeAccessService}).
 * 지표는 노드 node_exporter 의 node_memory_* 를 {@code instance} 라벨로 좁혀 읽고, Free 는 MemAvailable 기준이다.
 * node_exporter 타겟이 없는 노드는 Thanos 를 조회하지 않고 빈 시리즈를 내린다.
 * 노드 상세 패널은 ResourceExplorerNodeService, VM Memory 는 ResourceExplorerVmMemoryService 가 담당한다.
 */
@Service
public class ResourceExplorerNodeMemoryService {

    private final NodeAccessService nodeAccess;
    private final HostCategoryMetricsQueryService metrics;

    public ResourceExplorerNodeMemoryService(NodeAccessService nodeAccess, HostCategoryMetricsQueryService metrics) {
        this.nodeAccess = nodeAccess;
        this.metrics = metrics;
    }

    /** 모니터링 탭 차트 — 지표별 시계열(GB 또는 %). 조회 구간은 지금 기준 interval 기간, 버킷 간격은 기간별 defaultStep */
    public MemoryMetricTrendResponse getMemoryMetricTrend(String nodeId, MemoryMetric metric, NodeMetricInterval interval) {
        String instance = nodeAccess.require(nodeId).host().instance();
        Instant to = Instant.now();
        Instant from = to.minus(interval.window());
        String step = interval.defaultStep();

        List<DataPoint> points = instance == null
                ? List.of()
                : metrics.memoryTrend(metric, MetricSelector.instance(instance), from, to, step);
        return new MemoryMetricTrendResponse(metric, metric.unit(), from.getEpochSecond(), to.getEpochSecond(), step,
                points.isEmpty() ? List.of() : List.of(new Series("memory", points)));
    }
}
