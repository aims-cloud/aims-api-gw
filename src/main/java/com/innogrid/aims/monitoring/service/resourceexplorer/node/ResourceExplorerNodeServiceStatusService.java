package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.util.Comparator;
import java.util.List;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceUnitListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceUnitState;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostServiceStatusMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostServiceStatusMetricsQueryService.Unit;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.ServiceStatusPanelAssembler;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 노드 Service Status 카테고리 패널 조회 서비스.
 * 트리에서 노드 하위 Service Status 항목 선택 시 개요(KPI 카드·유닛 목록)와 모니터링(상태별 유닛 수 차트) 탭 데이터를 제공한다.
 *
 * nodeId 는 assets.id 이며 역할 스코프 밖이면 404({@link NodeAccessService}).
 * 지표는 노드 node_exporter 의 node_systemd_unit_state 를 {@code instance} 라벨로 좁혀 읽는다({@code --collector.systemd} 필요).
 * node_exporter 타겟이 없는 노드는 Thanos 를 조회하지 않고 미수집(null·빈 목록·빈 시리즈)으로 내린다.
 * 응답 조립 규칙은 VM Service Status 패널과 공유한다({@link ServiceStatusPanelAssembler}).
 */
@Service
public class ResourceExplorerNodeServiceStatusService {

    private final NodeAccessService nodeAccess;
    private final HostServiceStatusMetricsQueryService metrics;

    public ResourceExplorerNodeServiceStatusService(NodeAccessService nodeAccess, HostServiceStatusMetricsQueryService metrics) {
        this.nodeAccess = nodeAccess;
        this.metrics = metrics;
    }

    /** 개요 탭 KPI 카드 — 전체·상태별 유닛 수. systemd 콜렉터가 없어 표본이 없으면 전부 null */
    public ServiceStatusSummaryResponse getServiceStatusSummary(String nodeId) {
        MetricSelector selector = nodeAccess.selector(nodeId);
        return ServiceStatusPanelAssembler.summary(selector == null ? List.of() : metrics.units(selector));
    }

    /** 개요 탭 유닛 목록 — 상태 필터·유닛명 키워드(부분 일치, 대소문자 무시)·컬럼 정렬·페이징(1부터) */
    public PageResponse<ServiceUnitListItemResponse> getServiceUnits(String nodeId, ServiceUnitState state, String keyword,
                                                                     int page, int size, String sort) {
        Comparator<ServiceUnitListItemResponse> comparator = ServiceStatusPanelAssembler.SORT.comparator(sort);   // 미지원 필드는 조회 전에 400
        MetricSelector selector = nodeAccess.selector(nodeId);
        List<Unit> units = selector == null ? List.of() : metrics.units(selector);
        return ServiceStatusPanelAssembler.unitPage(units, state, keyword, comparator, page, size);
    }

    /** 모니터링 탭 차트 — 상태별 유닛 수 시계열. 미수집이면 빈 시리즈 */
    public ServiceStatusMetricTrendResponse getServiceStatusMetricTrend(String nodeId, ServiceStatusMetric metric, NodeMetricInterval interval) {
        MetricSelector selector = nodeAccess.selector(nodeId);
        return ServiceStatusPanelAssembler.trend(metric, interval, (from, to, step) ->
                selector == null ? List.of() : metrics.trend(metric, selector, from, to, step));
    }
}
