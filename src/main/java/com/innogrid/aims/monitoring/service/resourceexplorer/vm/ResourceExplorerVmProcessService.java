package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

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
 * 자원 탐색기 VM Process 카테고리 패널 조회 서비스.
 * 트리에서 VM 하위 Process 항목 선택 시 개요(KPI 카드·상태별 개수)와 모니터링(지표 5종 차트) 탭 데이터를 제공한다.
 *
 * VM 은 nsId·infraId·vmId 로 식별하고(스코프 밖·부재는 404), 지표는 VM 안 node_exporter 의
 * node_procs_*·node_forks_total(기본)과 node_processes_*({@code --collector.processes}, docs/07 배포 스크립트에 포함)를
 * {@code tb_ns/tb_infra/tb_node} 라벨로 좁혀 읽는다. VM 은 exporter 원장이 없어 항상 Thanos 를 조회하고 표본이 없으면
 * 미수집(null·빈 목록·빈 시리즈)이다. PID 별 프로세스 목록은 제공하지 않는다(docs/06 §5.4).
 * 응답 DTO 는 노드 Process 패널과 같다.
 */
@Service
public class ResourceExplorerVmProcessService {

    private final TumblebugPanelSupport support;
    private final HostProcessMetricsQueryService metrics;

    public ResourceExplorerVmProcessService(TumblebugPanelSupport support, HostProcessMetricsQueryService metrics) {
        this.support = support;
        this.metrics = metrics;
    }

    /** 개요 탭 KPI 카드 — 실행 중·블록·전체 프로세스·스레드 수, fork /s(최근 5분), 상태별 개수. 미수집은 null/빈 목록 */
    public ProcessSummaryResponse getProcessSummary(String roleName, String nsId, String infraId, String vmId) {
        ProcessSummary summary = metrics.summary(selector(roleName, nsId, infraId, vmId));
        return new ProcessSummaryResponse(
                summary.running(), summary.blocked(), summary.processes(), summary.threads(), summary.forksPerSec(),
                summary.byState().stream().map(s -> new StateCount(s.state(), s.count())).toList());
    }

    /** 모니터링 탭 차트 — 지표별 시계열. 조회 구간은 지금 기준 interval 기간, 버킷 간격은 기간별 defaultStep */
    public ProcessMetricTrendResponse getProcessMetricTrend(String roleName, String nsId, String infraId, String vmId,
                                                            ProcessMetric metric, NodeMetricInterval interval) {
        MetricSelector sel = selector(roleName, nsId, infraId, vmId);
        Instant to = Instant.now();
        Instant from = to.minus(interval.window());
        String step = interval.defaultStep();

        List<DataPoint> points = metrics.trend(metric, sel, from, to, step);
        return new ProcessMetricTrendResponse(metric, metric.unit(), from.getEpochSecond(), to.getEpochSecond(), step,
                points.isEmpty() ? List.of() : List.of(new Series("process", points)));
    }

    /** 스코프·존재 검증(404) 뒤 tb_* 라벨 선택자 */
    private MetricSelector selector(String roleName, String nsId, String infraId, String vmId) {
        support.accessibleNode(roleName, nsId, infraId, vmId);
        return MetricSelector.tumblebugNode(nsId, infraId, vmId);
    }
}
