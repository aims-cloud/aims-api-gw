package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.process.ProcessMetric;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostProcessMetricsQueryService.ProcessSummary;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostProcessMetricsQueryService.StateCount;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Process 패널 Thanos 조회 — PromQL, KPI(processes 콜렉터 유무), 상태별 개수 정렬, 시계열 정수화·rate 창, 장애 처리. */
class HostProcessMetricsQueryServiceTest {

    private static final MetricSelector SEL = MetricSelector.instance("10.0.0.1:9100");
    private static final String INSTANCE = "{instance=\"10.0.0.1:9100\"}";

    VictoriaMetricsClient client = mock(VictoriaMetricsClient.class);
    HostProcessMetricsQueryService service = new HostProcessMetricsQueryService(client);

    @Test
    void PromQL은_stat_기본_지표와_processes_콜렉터_지표를_쓴다() {
        assertThat(HostProcessMetricsQueryService.trendQuery(ProcessMetric.PROCS_RUNNING, SEL, "5m")).isEqualTo("node_procs_running" + INSTANCE);
        assertThat(HostProcessMetricsQueryService.trendQuery(ProcessMetric.PROCS_BLOCKED, SEL, "5m")).isEqualTo("node_procs_blocked" + INSTANCE);
        assertThat(HostProcessMetricsQueryService.trendQuery(ProcessMetric.PROCESSES_TOTAL, SEL, "5m")).isEqualTo("node_processes_pids" + INSTANCE);
        assertThat(HostProcessMetricsQueryService.trendQuery(ProcessMetric.THREADS, SEL, "5m")).isEqualTo("node_processes_threads" + INSTANCE);
        assertThat(HostProcessMetricsQueryService.trendQuery(ProcessMetric.FORKS_RATE, SEL, "1h")).isEqualTo("rate(node_forks_total" + INSTANCE + "[1h])");
        assertThat(HostProcessMetricsQueryService.byState(SEL)).isEqualTo("node_processes_state" + INSTANCE);
    }

    @Test
    void KPI는_processes_콜렉터가_있으면_전체_스레드_상태별을_채우고_fork는_소수2자리다() {
        when(client.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.startsWith("node_procs_running")) return vector("3");
            if (q.startsWith("node_procs_blocked")) return vector("0");
            if (q.startsWith("node_processes_pids")) return vector("412");
            if (q.startsWith("node_processes_threads")) return vector("1287");
            if (q.startsWith("rate(node_forks_total")) return vector("2.3456");
            if (q.startsWith("node_processes_state")) return List.of(state("S", "398"), state("R", "3"), state("I", "11"));
            return List.of();
        });

        ProcessSummary summary = service.summary(SEL);

        assertThat(summary.running()).isEqualTo(3);
        assertThat(summary.blocked()).isEqualTo(0);
        assertThat(summary.processes()).isEqualTo(412);
        assertThat(summary.threads()).isEqualTo(1287);
        assertThat(summary.forksPerSec()).isEqualTo(2.35);
        assertThat(summary.byState()).containsExactly(new StateCount("I", 11), new StateCount("R", 3), new StateCount("S", 398));   // 상태명 순
    }

    @Test
    void processes_콜렉터가_없는_노드는_기본_지표만_채우고_나머지는_null_빈_목록이다() {
        when(client.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.startsWith("node_procs_running")) return vector("1");
            if (q.startsWith("node_procs_blocked")) return vector("0");
            if (q.startsWith("rate(node_forks_total")) return vector("0.5");
            return List.of();                                       // node_processes_* 부재 (k8s-cp 현재 상태)
        });

        ProcessSummary summary = service.summary(SEL);

        assertThat(summary.running()).isEqualTo(1);
        assertThat(summary.forksPerSec()).isEqualTo(0.5);
        assertThat(summary.processes()).isNull();
        assertThat(summary.threads()).isNull();
        assertThat(summary.byState()).isEmpty();
    }

    @Test
    void 시계열은_개수를_정수로_fork는_소수2자리로_내리고_rate_창을_step에_맞춘다() {
        Instant from = Instant.ofEpochSecond(1_700_000_000L);
        Instant to = Instant.ofEpochSecond(1_700_003_600L);
        when(client.queryRangeResults(anyString(), any(), any(), anyString()))
                .thenReturn(List.of(range(List.of(List.of(1_700_000_000L, "2.6"), List.of(1_700_000_030L, "NaN"),
                        List.of(1_700_000_060L, "3.44")))));

        List<DataPoint> running = service.trend(ProcessMetric.PROCS_RUNNING, SEL, from, to, "30s");
        assertThat(running).containsExactly(new DataPoint(1_700_000_000L, 3.0), new DataPoint(1_700_000_060L, 3.0));

        List<DataPoint> forks = service.trend(ProcessMetric.FORKS_RATE, SEL, from, to, "30s");
        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        verify(client, org.mockito.Mockito.times(2)).queryRangeResults(q.capture(), eq(from), eq(to), eq("30s"));
        assertThat(q.getAllValues().get(1)).isEqualTo("rate(node_forks_total" + INSTANCE + "[5m])");   // 30s → rate 창 하한 5m
        assertThat(forks).containsExactly(new DataPoint(1_700_000_000L, 2.6), new DataPoint(1_700_000_060L, 3.44));
    }

    @Test
    void Thanos_장애는_KPI_전부_null_시계열_빈_목록이다() {
        when(client.queryResults(anyString())).thenThrow(new RuntimeException("connection refused"));
        when(client.queryRangeResults(anyString(), any(), any(), anyString())).thenThrow(new RuntimeException("timeout"));

        assertThat(service.summary(SEL)).isEqualTo(ProcessSummary.EMPTY);
        assertThat(service.trend(ProcessMetric.THREADS, SEL, Instant.EPOCH, Instant.EPOCH, "5m")).isEmpty();
    }

    // ── 대역 ────────────────────────────────────────────────────

    static List<Map<String, Object>> vector(String value) {
        return List.of(Map.of("metric", Map.of(), "value", List.of(1_700_000_000L, value)));
    }

    static Map<String, Object> state(String state, String value) {
        return Map.of("metric", Map.of("state", state), "value", List.of(1_700_000_000L, value));
    }

    static Map<String, Object> range(List<List<Object>> values) {
        return Map.of("metric", Map.of(), "values", values);
    }
}
