package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceUnitState;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostServiceStatusMetricsQueryService.Unit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Service Status 패널 Thanos 조회 — PromQL(== 1 필터, 상태 합), 유닛 목록 결합·정렬·미지 상태 제외, 시계열 정수화, 장애 처리. */
class HostServiceStatusMetricsQueryServiceTest {

    private static final MetricSelector SEL = MetricSelector.instance("10.0.0.1:9100");
    private static final String INSTANCE = "instance=\"10.0.0.1:9100\"";

    VictoriaMetricsClient client = mock(VictoriaMetricsClient.class);
    HostServiceStatusMetricsQueryService service = new HostServiceStatusMetricsQueryService(client);

    @Test
    void PromQL은_현재_상태_행만_고르고_상태별_개수는_0_1_합이다() {
        assertThat(HostServiceStatusMetricsQueryService.currentUnitStates(SEL))
                .isEqualTo("node_systemd_unit_state{" + INSTANCE + "} == 1");
        assertThat(HostServiceStatusMetricsQueryService.trendQuery(ServiceStatusMetric.FAILED_UNITS, SEL))
                .isEqualTo("sum by (instance) (node_systemd_unit_state{state=\"failed\"," + INSTANCE + "})");
        assertThat(HostServiceStatusMetricsQueryService.trendQuery(ServiceStatusMetric.ACTIVE_UNITS, SEL))
                .isEqualTo("sum by (instance) (node_systemd_unit_state{state=\"active\"," + INSTANCE + "})");
    }

    @Test
    void 유닛_목록은_name_type_state_라벨을_읽어_유닛명_순으로_정렬하고_모르는_상태는_제외한다() {
        when(client.queryResults(anyString())).thenReturn(List.of(
                unit("ssh.service", "notify", "active"),
                unit("cloud-final.service", "oneshot", "failed"),
                unit("apt-daily.service", "oneshot", "inactive"),
                unit("weird.service", null, "reloading"),                                  // 5종 밖 상태
                Map.of("metric", Map.of("state", "active"), "value", List.of(1_700_000_000L, "1"))));   // name 없음

        List<Unit> units = service.units(SEL);

        assertThat(units).containsExactly(
                new Unit("apt-daily.service", "oneshot", ServiceUnitState.INACTIVE),
                new Unit("cloud-final.service", "oneshot", ServiceUnitState.FAILED),
                new Unit("ssh.service", "notify", ServiceUnitState.ACTIVE));
    }

    @Test
    void 시계열은_정수로_내리고_step을_그대로_쓰며_부재는_빈_목록이다() {
        Instant from = Instant.ofEpochSecond(1_700_000_000L);
        Instant to = Instant.ofEpochSecond(1_700_003_600L);
        when(client.queryRangeResults(anyString(), any(), any(), anyString()))
                .thenReturn(List.of(range(List.of(List.of(1_700_000_000L, "121"), List.of(1_700_000_030L, "NaN"),
                        List.of(1_700_000_060L, "120.6")))));

        List<DataPoint> points = service.trend(ServiceStatusMetric.ACTIVE_UNITS, SEL, from, to, "30s");

        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        verify(client).queryRangeResults(q.capture(), eq(from), eq(to), eq("30s"));
        assertThat(q.getValue()).contains("state=\"active\"");
        assertThat(points).containsExactly(new DataPoint(1_700_000_000L, 121.0), new DataPoint(1_700_000_060L, 121.0));

        when(client.queryRangeResults(anyString(), any(), any(), anyString())).thenReturn(List.of());
        assertThat(service.trend(ServiceStatusMetric.INACTIVE_UNITS, SEL, from, to, "5m")).isEmpty();
    }

    @Test
    void Thanos_장애나_systemd_콜렉터_부재는_빈_목록이다() {
        when(client.queryResults(anyString())).thenReturn(List.of());                         // 콜렉터 없음 (k8s-cp 현재)
        assertThat(service.units(SEL)).isEmpty();

        when(client.queryResults(anyString())).thenThrow(new RuntimeException("connection refused"));
        when(client.queryRangeResults(anyString(), any(), any(), anyString())).thenThrow(new RuntimeException("timeout"));
        assertThat(service.units(SEL)).isEmpty();
        assertThat(service.trend(ServiceStatusMetric.FAILED_UNITS, SEL, Instant.EPOCH, Instant.EPOCH, "5m")).isEmpty();
    }

    // ── 대역 ────────────────────────────────────────────────────

    static Map<String, Object> unit(String name, String type, String state) {
        Map<String, String> labels = type == null
                ? Map.of("name", name, "state", state)
                : Map.of("name", name, "type", type, "state", state);
        return Map.of("metric", labels, "value", List.of(1_700_000_000L, "1"));
    }

    static Map<String, Object> range(List<List<Object>> values) {
        return Map.of("metric", Map.of(), "values", values);
    }
}
