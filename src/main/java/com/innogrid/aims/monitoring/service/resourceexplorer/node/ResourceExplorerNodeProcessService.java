package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;
import java.util.List;

import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.process.ProcessMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.process.ProcessMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.process.ProcessMetricTrendResponse.Series;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.process.ProcessSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.process.ProcessSummaryResponse.StateCount;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostProcessMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostProcessMetricsQueryService.ProcessSummary;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 노드 Process 카테고리 패널 조회 서비스.
 * 트리에서 노드 하위 Process 항목 선택 시 개요(KPI 카드·상태별 개수)와 모니터링(지표 5종 차트) 탭 데이터를 제공한다.
 *
 * nodeId 는 assets.id 이며 역할 스코프 밖이면 404({@link NodeAccessService}).
 * 지표는 노드 node_exporter 의 node_procs_*·node_forks_total(기본)과 node_processes_*({@code --collector.processes})를
 * {@code instance} 라벨로 좁혀 읽는다. node_exporter 타겟이 없는 노드는 Thanos 를 조회하지 않고 미수집(null·빈 시리즈)으로 내린다.
 * PID 별 프로세스 목록은 제공하지 않는다(docs/06 §5.4).
 */
@Service
public class ResourceExplorerNodeProcessService {

    private final NodeAccessService nodeAccess;
    private final HostProcessMetricsQueryService metrics;

    public ResourceExplorerNodeProcessService(NodeAccessService nodeAccess, HostProcessMetricsQueryService metrics) {
        this.nodeAccess = nodeAccess;
        this.metrics = metrics;
    }

    /** 개요 탭 KPI 카드 — 실행 중·블록·전체 프로세스·스레드 수, fork /s(최근 5분), 상태별 개수. 미수집은 null/빈 목록 */
    public ProcessSummaryResponse getProcessSummary(String nodeId) {
        MetricSelector selector = nodeAccess.selector(nodeId);
        ProcessSummary summary = selector == null ? ProcessSummary.EMPTY : metrics.summary(selector);
        return new ProcessSummaryResponse(
                summary.running(), summary.blocked(), summary.processes(), summary.threads(), summary.forksPerSec(),
                summary.byState().stream().map(s -> new StateCount(s.state(), s.count())).toList());
    }

    /** 모니터링 탭 차트 — 지표별 시계열. 조회 구간은 지금 기준 interval 기간, 버킷 간격은 기간별 defaultStep */
    public ProcessMetricTrendResponse getProcessMetricTrend(String nodeId, ProcessMetric metric, NodeMetricInterval interval) {
        MetricSelector selector = nodeAccess.selector(nodeId);
        Instant to = Instant.now();
        Instant from = to.minus(interval.window());
        String step = interval.defaultStep();

        List<DataPoint> points = selector == null ? List.of() : metrics.trend(metric, selector, from, to, step);
        return new ProcessMetricTrendResponse(metric, metric.unit(), from.getEpochSecond(), to.getEpochSecond(), step,
                points.isEmpty() ? List.of() : List.of(new Series("process", points)));
    }
}
