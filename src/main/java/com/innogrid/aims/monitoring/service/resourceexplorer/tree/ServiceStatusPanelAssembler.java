package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.util.ListSort;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusMetricTrendResponse.Series;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceUnitListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceUnitState;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostServiceStatusMetricsQueryService.Unit;

/**
 * Service Status 카테고리 패널 응답 조립 — 노드·VM 패널 서비스가 공유하는 순수 계산 부분.
 *
 * Thanos 표본({@link Unit})을 KPI 카드(상태별 유닛 수)·유닛 목록(상태 필터·키워드·정렬·페이징)·시계열 응답으로 바꾸는 규칙만 담고,
 * 자원 식별(노드 assets.id / VM nsId·infraId·vmId)과 스코프 검증은 각 패널 서비스가 맡는다.
 */
public final class ServiceStatusPanelAssembler {

    /**
     * sort 파라미터("{필드},{asc|desc}") — name|type|state, 동률·값 없음은 유닛명순.
     * 미지원 필드는 400 — 호출 측은 Thanos 조회 전에 먼저 부른다.
     */
    public static final ListSort.Spec<ServiceUnitListItemResponse> SORT = ListSort.spec(
            Map.ofEntries(
                    Map.entry("name", ListSort.by(ServiceUnitListItemResponse::name, String.CASE_INSENSITIVE_ORDER)),
                    Map.entry("type", ListSort.byNullsAsLargest(ServiceUnitListItemResponse::type, String.CASE_INSENSITIVE_ORDER)),
                    Map.entry("state", ListSort.by(ServiceUnitListItemResponse::state))),
            Comparator.comparing(ServiceUnitListItemResponse::name, String.CASE_INSENSITIVE_ORDER));

    private ServiceStatusPanelAssembler() {
    }

    /** 시계열 조회 — from/to/step 을 받아 DataPoint 목록을 돌려준다(미수집은 빈 목록) */
    @FunctionalInterface
    public interface TrendQuery {
        List<DataPoint> points(Instant from, Instant to, String step);
    }

    /** 개요 탭 KPI 카드 — 전체·상태별 유닛 수. systemd 콜렉터가 없어 표본이 없으면 전부 null */
    public static ServiceStatusSummaryResponse summary(List<Unit> units) {
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

    /** 개요 탭 유닛 목록 — 상태 필터·유닛명 키워드(부분 일치, 대소문자 무시)로 걸러 정렬·페이징(1부터)한다 */
    public static PageResponse<ServiceUnitListItemResponse> unitPage(List<Unit> units, ServiceUnitState state, String keyword,
                                                                     Comparator<ServiceUnitListItemResponse> comparator,
                                                                     int page, int size) {
        String lowerKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim().toLowerCase(Locale.ROOT);
        List<ServiceUnitListItemResponse> rows = units.stream()
                .filter(u -> state == null || u.state() == state)
                .filter(u -> lowerKeyword == null || u.name().toLowerCase(Locale.ROOT).contains(lowerKeyword))
                .map(u -> new ServiceUnitListItemResponse(u.name(), u.type(), u.state()))
                .sorted(comparator)
                .toList();
        return PageResponse.of(rows, page, size);
    }

    /** 모니터링 차트 — 조회 구간은 지금 기준 interval 기간, 버킷 간격은 기간별 defaultStep. 미수집이면 빈 시리즈 */
    public static ServiceStatusMetricTrendResponse trend(ServiceStatusMetric metric, NodeMetricInterval interval, TrendQuery query) {
        Instant to = Instant.now();
        Instant from = to.minus(interval.window());
        String step = interval.defaultStep();
        List<DataPoint> points = query.points(from, to, step);
        return new ServiceStatusMetricTrendResponse(metric, metric.unit(), from.getEpochSecond(), to.getEpochSecond(), step,
                points.isEmpty() ? List.of() : List.of(new Series("service_status", points)));
    }

    private static int count(List<Unit> units, ServiceUnitState state) {
        return (int) units.stream().filter(u -> u.state() == state).count();
    }
}
