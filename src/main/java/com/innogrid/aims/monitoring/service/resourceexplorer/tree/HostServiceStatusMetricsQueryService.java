package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.monitoring.ThanosResults;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceUnitState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 자원 1개의 Service Status 카테고리 패널 지표를 Thanos 에서 읽는다 — systemd 유닛 목록과 상태별 개수 시계열 전용.
 *
 * node_exporter systemd 콜렉터({@code --collector.systemd})의 {@code node_systemd_unit_state{name,type,state}} 는
 * 유닛 × 상태 5종(active/inactive/failed/activating/deactivating)의 0/1 게이지다. 값이 1 인 행이 그 유닛의 현재 상태다.
 * 콜렉터가 꺼진 노드는 표본이 없어 빈 목록/빈 시계열(미수집)이 된다.
 * 자원은 {@link MetricSelector} 로 가리키고, Thanos 장애는 빈 목록으로 돌려준다.
 */
@Component
public class HostServiceStatusMetricsQueryService {

    private static final Logger log = LoggerFactory.getLogger(HostServiceStatusMetricsQueryService.class);

    /** 유닛 1개의 현재 상태 */
    public record Unit(String name, String type, ServiceUnitState state) {}

    private final VictoriaMetricsClient client;

    public HostServiceStatusMetricsQueryService(VictoriaMetricsClient client) {
        this.client = client;
    }

    // ── PromQL ──────────────────────────────────────────────────

    /** 현재 상태인 (유닛, 상태) 행만 — 값 1 */
    static String currentUnitStates(MetricSelector s) {
        return "node_systemd_unit_state" + s.braces() + " == 1";
    }

    /** 상태별 유닛 수 — 0/1 합이라 해당 상태 유닛이 없으면 0 */
    static String unitCount(ServiceUnitState state, MetricSelector s) {
        return "sum by (instance) (node_systemd_unit_state" + s.braces(MetricSelector.label("state", state.label())) + ")";
    }

    static String trendQuery(ServiceStatusMetric metric, MetricSelector s) {
        return unitCount(metric.state(), s);
    }

    // ── 조회 ────────────────────────────────────────────────────

    /** 자원의 systemd 유닛 전체와 현재 상태 — 유닛명 순. 콜렉터 없음·장애는 빈 목록 */
    public List<Unit> units(MetricSelector s) {
        try {
            List<Unit> units = new ArrayList<>();
            for (Map<String, Object> row : client.queryResults(currentUnitStates(s))) {
                String name = ThanosResults.label(row, "name");
                ServiceUnitState state = stateOf(ThanosResults.label(row, "state"));
                if (name != null && state != null) {
                    units.add(new Unit(name, ThanosResults.label(row, "type"), state));
                }
            }
            units.sort(Comparator.comparing(Unit::name, String.CASE_INSENSITIVE_ORDER));
            return units;
        } catch (RuntimeException e) {
            log.warn("Thanos systemd 유닛 조회 실패 — 빈 목록을 돌려줍니다: {}", e.getMessage());
            return List.of();
        }
    }

    /** 상태별 유닛 수 시계열 — 미수집이면 빈 목록, 값은 정수 */
    public List<DataPoint> trend(ServiceStatusMetric metric, MetricSelector s, Instant from, Instant to, String step) {
        try {
            List<Map<String, Object>> results = client.queryRangeResults(trendQuery(metric, s), from, to, step);
            if (results.isEmpty()) {
                return List.of();
            }
            List<DataPoint> points = new ArrayList<>();
            for (ThanosResults.Sample sample : ThanosResults.values(results.get(0))) {
                points.add(new DataPoint(sample.epochSecond(), (double) Math.round(sample.value())));
            }
            return points;
        } catch (RuntimeException e) {
            log.warn("Thanos Service Status 차트 조회 실패 — 빈 시계열을 돌려줍니다: {}", e.getMessage());
            return List.of();
        }
    }

    // ── 내부 ────────────────────────────────────────────────────

    static ServiceUnitState stateOf(String label) {
        if (label == null) {
            return null;
        }
        for (ServiceUnitState state : ServiceUnitState.values()) {
            if (state.label().equals(label)) {
                return state;
            }
        }
        return null;
    }
}
