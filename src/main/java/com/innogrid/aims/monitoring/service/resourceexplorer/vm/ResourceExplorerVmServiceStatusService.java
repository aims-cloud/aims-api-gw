package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.util.Comparator;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceUnitListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceUnitState;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostServiceStatusMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.ServiceStatusPanelAssembler;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 VM Service Status 카테고리 패널 조회 서비스.
 * 트리에서 VM 하위 Service Status 항목 선택 시 개요(KPI 카드·유닛 목록)와 모니터링(상태별 유닛 수 차트) 탭 데이터를 제공한다.
 *
 * VM 은 nsId·infraId·vmId 로 식별하고(스코프 밖·부재는 404), 지표는 VM 안 node_exporter 의 node_systemd_unit_state 를
 * {@code tb_ns/tb_infra/tb_node} 라벨로 좁혀 읽는다({@code --collector.systemd}, docs/07 배포 스크립트에 포함).
 * VM 은 exporter 원장이 없어 항상 Thanos 를 조회하고 표본이 없으면 미수집(null·빈 목록·빈 시리즈)이다.
 * 응답 DTO·조립 규칙은 노드 Service Status 패널과 같다({@link ServiceStatusPanelAssembler}).
 */
@Service
public class ResourceExplorerVmServiceStatusService {

    private final TumblebugPanelSupport support;
    private final HostServiceStatusMetricsQueryService metrics;

    public ResourceExplorerVmServiceStatusService(TumblebugPanelSupport support, HostServiceStatusMetricsQueryService metrics) {
        this.support = support;
        this.metrics = metrics;
    }

    /** 개요 탭 KPI 카드 — 전체·상태별 유닛 수. systemd 콜렉터가 없어 표본이 없으면 전부 null */
    public ServiceStatusSummaryResponse getServiceStatusSummary(String roleName, String nsId, String infraId, String vmId) {
        return ServiceStatusPanelAssembler.summary(metrics.units(selector(roleName, nsId, infraId, vmId)));
    }

    /** 개요 탭 유닛 목록 — 상태 필터·유닛명 키워드(부분 일치, 대소문자 무시)·컬럼 정렬·페이징(1부터) */
    public PageResponse<ServiceUnitListItemResponse> getServiceUnits(String roleName, String nsId, String infraId, String vmId,
                                                                     ServiceUnitState state, String keyword,
                                                                     int page, int size, String sort) {
        Comparator<ServiceUnitListItemResponse> comparator = ServiceStatusPanelAssembler.comparator(sort);   // 미지원 필드는 조회 전에 400
        return ServiceStatusPanelAssembler.unitPage(metrics.units(selector(roleName, nsId, infraId, vmId)),
                state, keyword, comparator, page, size);
    }

    /** 모니터링 탭 차트 — 상태별 유닛 수 시계열. 미수집이면 빈 시리즈 */
    public ServiceStatusMetricTrendResponse getServiceStatusMetricTrend(String roleName, String nsId, String infraId, String vmId,
                                                                        ServiceStatusMetric metric, NodeMetricInterval interval) {
        MetricSelector sel = selector(roleName, nsId, infraId, vmId);
        return ServiceStatusPanelAssembler.trend(metric, interval, (from, to, step) -> metrics.trend(metric, sel, from, to, step));
    }

    /** 스코프·존재 검증(404) 뒤 tb_* 라벨 선택자 */
    private MetricSelector selector(String roleName, String nsId, String infraId, String vmId) {
        support.accessibleNode(roleName, nsId, infraId, vmId);
        return MetricSelector.tumblebugNode(nsId, infraId, vmId);
    }
}
