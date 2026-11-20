package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.time.Instant;
import java.util.List;

import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.memory.VmMemoryMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.memory.VmMemoryMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.memory.VmMemoryMetricTrendResponse.Series;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostCategoryMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 VM Memory 카테고리 패널 조회 서비스.
 * 트리에서 VM 하위 Memory 항목 선택 시 모니터링 탭 차트 데이터를 제공한다.
 * (개요 탭은 화면 설계 미확정 — 추가 시 CPU 패턴대로 확장한다)
 *
 * VM 은 nsId·infraId·vmId 로 식별하고(스코프 밖·부재는 404), 지표는 VM 안 node_exporter 의 node_memory_* 를
 * {@code tb_ns/tb_infra/tb_node} 라벨로 좁혀 읽는다.
 * VM 상세 패널은 ResourceExplorerVmService, 노드 Memory 는 ResourceExplorerNodeMemoryService 가 담당한다.
 */
@Service
public class ResourceExplorerVmMemoryService {

    private final TumblebugPanelSupport support;
    private final HostCategoryMetricsQueryService metrics;

    public ResourceExplorerVmMemoryService(TumblebugPanelSupport support, HostCategoryMetricsQueryService metrics) {
        this.support = support;
        this.metrics = metrics;
    }

    /** 모니터링 탭 차트 — 지표별 시계열 9종 (Free=MemFree, Available=MemAvailable 구분). 미수집이면 빈 시리즈 */
    public VmMemoryMetricTrendResponse getMemoryMetricTrend(String roleName, String nsId, String infraId, String vmId,
                                                            VmMemoryMetric metric, NodeMetricInterval interval) {
        support.accessibleNode(roleName, nsId, infraId, vmId);
        MetricSelector sel = MetricSelector.tumblebugNode(nsId, infraId, vmId);
        Instant to = Instant.now();
        Instant from = to.minus(interval.window());
        String step = interval.defaultStep();
        List<DataPoint> points = metrics.memoryTrend(metric, sel, from, to, step);
        return new VmMemoryMetricTrendResponse(metric, metric.unit(), from.getEpochSecond(), to.getEpochSecond(), step,
                points.isEmpty() ? List.of() : List.of(new Series("memory", points)));
    }
}
