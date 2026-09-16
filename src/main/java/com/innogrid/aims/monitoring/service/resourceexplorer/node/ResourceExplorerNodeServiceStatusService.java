package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusMetricTrendResponse.Series;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceUnitListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceUnitState;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostServiceStatusMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostServiceStatusMetricsQueryService.Unit;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 노드 Service Status 카테고리 패널 조회 서비스.
 * 트리에서 노드 하위 Service Status 항목 선택 시 개요(KPI 카드·유닛 목록)와 모니터링(상태별 유닛 수 차트) 탭 데이터를 제공한다.
 *
 * nodeId 는 assets.id 이며 역할 스코프 밖이면 404({@link NodeAccessService}).
 * 지표는 노드 node_exporter 의 node_systemd_unit_state 를 {@code instance} 라벨로 좁혀 읽는다({@code --collector.systemd} 필요).
 * node_exporter 타겟이 없는 노드는 Thanos 를 조회하지 않고 미수집(null·빈 목록·빈 시리즈)으로 내린다.
 */
@Service
public class ResourceExplorerNodeServiceStatusService {

    private final NodeAccessService nodeAccess;
    private final HostServiceStatusMetricsQueryService metrics;

    public ResourceExplorerNodeServiceStatusService(NodeAccessService nodeAccess, HostServiceStatusMetricsQueryService metrics) {
        this.nodeAccess = nodeAccess;
        this.metrics = metrics;
    }

    // ── 공개 API ────────────────────────────────────────────────

    /** 개요 탭 KPI 카드 — 전체·상태별 유닛 수. systemd 콜렉터가 없어 표본이 없으면 전부 null */
    public ServiceStatusSummaryResponse getServiceStatusSummary(String nodeId) {
        MetricSelector selector = selector(nodeId);
        List<Unit> units = selector == null ? List.of() : metrics.units(selector);
        if (units.isEmpty()) {
            return new ServiceStatusSummaryResponse(null, null, null, null, null, null);
        }
        return new ServiceStatusSummaryResponse(
                units.size(),
                count(units, ServiceUnitState.ACTIVE),
                count(units, ServiceUnitState.INACTIVE),
                count(units, ServiceUnitState.FAILED),
                count(units, ServiceUnitState.ACTIVATING),
                count(units, ServiceUnitState.DEACTIVATING));
    }

    /** 개요 탭 유닛 목록 — 상태 필터·유닛명 키워드(부분 일치, 대소문자 무시)·컬럼 정렬·페이징(1부터) */
    public PageResponse<ServiceUnitListItemResponse> getServiceUnits(String nodeId, ServiceUnitState state, String keyword,
                                                                     int page, int size, String sort) {
        Comparator<ServiceUnitListItemResponse> comparator = comparator(sort);   // 미지원 필드는 조회 전에 400
        MetricSelector selector = selector(nodeId);
        if (selector == null) {
            return PageResponse.of(List.of(), page, size);
        }
        String lowerKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim().toLowerCase(Locale.ROOT);
        List<ServiceUnitListItemResponse> rows = metrics.units(selector).stream()
                .filter(u -> state == null || u.state() == state)
                .filter(u -> lowerKeyword == null || u.name().toLowerCase(Locale.ROOT).contains(lowerKeyword))
                .map(u -> new ServiceUnitListItemResponse(u.name(), u.type(), u.state()))
                .sorted(comparator)
                .toList();
        return PageResponse.of(rows, page, size);
    }

    /** 모니터링 탭 차트 — 상태별 유닛 수 시계열. 조회 구간은 지금 기준 interval 기간, 버킷 간격은 기간별 defaultStep */
    public ServiceStatusMetricTrendResponse getServiceStatusMetricTrend(String nodeId, ServiceStatusMetric metric, NodeMetricInterval interval) {
        MetricSelector selector = selector(nodeId);
        Instant to = Instant.now();
        Instant from = to.minus(interval.window());
        String step = interval.defaultStep();

        List<DataPoint> points = selector == null ? List.of() : metrics.trend(metric, selector, from, to, step);
        return new ServiceStatusMetricTrendResponse(metric, metric.unit(), from.getEpochSecond(), to.getEpochSecond(), step,
                points.isEmpty() ? List.of() : List.of(new Series("service_status", points)));
    }

    // ── 조회 대상 ───────────────────────────────────────────────

    /** 스코프 검증(404) 뒤 node_exporter 선택자. exporter 타겟이 없으면 null — 호출 측은 조회 없이 미수집으로 처리한다 */
    private MetricSelector selector(String nodeId) {
        String instance = nodeAccess.require(nodeId).host().instance();
        return instance == null ? null : MetricSelector.instance(instance);
    }

    // ── 집계·정렬 ───────────────────────────────────────────────

    private static int count(List<Unit> units, ServiceUnitState state) {
        return (int) units.stream().filter(u -> u.state() == state).count();
    }

    /** sort 파라미터("{필드},{asc|desc}") — name|type|state, 동률·값 없음은 유닛명순 */
    private static Comparator<ServiceUnitListItemResponse> comparator(String sort) {
        String[] parts = sort.split(",", 2);
        String field = parts[0].trim();
        boolean desc = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim());

        Comparator<ServiceUnitListItemResponse> comparator = switch (field) {
            case "name" -> Comparator.comparing(ServiceUnitListItemResponse::name, String.CASE_INSENSITIVE_ORDER);
            case "type" -> Comparator.comparing(ServiceUnitListItemResponse::type, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "state" -> Comparator.comparing(ServiceUnitListItemResponse::state);
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "지원하지 않는 정렬 필드입니다: " + field);
        };
        Comparator<ServiceUnitListItemResponse> stable = comparator.thenComparing(ServiceUnitListItemResponse::name, String.CASE_INSENSITIVE_ORDER);
        return desc ? stable.reversed() : stable;
    }
}
