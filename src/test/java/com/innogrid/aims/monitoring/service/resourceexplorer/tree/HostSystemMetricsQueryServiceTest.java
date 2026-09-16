package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemMetric;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostSystemMetricsQueryService.SystemInfo;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostSystemMetricsQueryService.SystemSummary;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** System 패널 Thanos 조회 — PromQL, KPI 반올림·부재, uname/os 라벨 정보(pretty_name 폴백), 시계열, 장애 처리. */
class HostSystemMetricsQueryServiceTest {

    private static final MetricSelector SEL = MetricSelector.instance("10.0.0.1:9100");
    private static final String INSTANCE = "{instance=\"10.0.0.1:9100\"}";

    VictoriaMetricsClient client = mock(VictoriaMetricsClient.class);
    HostSystemMetricsQueryService service = new HostSystemMetricsQueryService(client);

    @Test
    void PromQL은_loadavg_stat_boottime_uname_os_기본_콜렉터_지표만_쓴다() {
        assertThat(HostSystemMetricsQueryService.trendQuery(SystemMetric.LOAD_1, SEL, "5m")).isEqualTo("node_load1" + INSTANCE);
        assertThat(HostSystemMetricsQueryService.trendQuery(SystemMetric.LOAD_15, SEL, "5m")).isEqualTo("node_load15" + INSTANCE);
        assertThat(HostSystemMetricsQueryService.trendQuery(SystemMetric.CONTEXT_SWITCHES, SEL, "1h"))
                .isEqualTo("rate(node_context_switches_total" + INSTANCE + "[1h])");
        assertThat(HostSystemMetricsQueryService.trendQuery(SystemMetric.INTERRUPTS, SEL, "5m"))
                .isEqualTo("rate(node_intr_total" + INSTANCE + "[5m])");
        assertThat(HostSystemMetricsQueryService.coreCount(SEL))
                .isEqualTo("count by (instance) (node_cpu_seconds_total{mode=\"idle\",instance=\"10.0.0.1:9100\"})");
        assertThat(HostSystemMetricsQueryService.bootTime(SEL)).isEqualTo("node_boot_time_seconds" + INSTANCE);
        assertThat(HostSystemMetricsQueryService.unameInfo(SEL)).isEqualTo("node_uname_info" + INSTANCE);
        assertThat(HostSystemMetricsQueryService.osInfo(SEL)).isEqualTo("node_os_info" + INSTANCE);
    }

    @Test
    void KPI는_부하_소수2자리_rate_소수1자리_코어수_정수이고_부재는_null이다() {
        when(client.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.startsWith("node_boot_time_seconds")) return vector("1700000000");
            if (q.startsWith("count by (instance) (node_cpu_seconds_total")) return vector("16");
            if (q.startsWith("node_load1{")) return vector("0.3149");
            if (q.startsWith("node_load5{")) return vector("0.4");
            if (q.startsWith("rate(node_context_switches_total")) return vector("12345.678");
            return List.of();                                                     // load15·intr 부재
        });

        SystemSummary summary = service.summary(SEL);

        assertThat(summary.bootTimeSeconds()).isEqualTo(1_700_000_000L);
        assertThat(summary.coreCount()).isEqualTo(16);
        assertThat(summary.load1()).isEqualTo(0.31);
        assertThat(summary.load5()).isEqualTo(0.4);
        assertThat(summary.load15()).isNull();
        assertThat(summary.contextSwitchesPerSec()).isEqualTo(12345.7);
        assertThat(summary.interruptsPerSec()).isNull();
    }

    @Test
    void 시스템_정보는_uname_os_라벨을_읽고_pretty_name이_없으면_name으로_대체하며_uname이_없으면_empty다() {
        when(client.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.startsWith("node_uname_info")) return List.of(Map.of("metric", Map.of(
                    "nodename", "k8s-cp", "release", "6.8.0-44-generic", "machine", "x86_64", "sysname", "Linux"),
                    "value", List.of(1_700_000_000L, "1")));
            if (q.startsWith("node_os_info")) return List.of(Map.of("metric", Map.of(
                    "name", "Ubuntu", "pretty_name", "Ubuntu 24.04.1 LTS", "version_id", "24.04"),
                    "value", List.of(1_700_000_000L, "1")));
            if (q.startsWith("node_boot_time_seconds")) return vector("1699990000");
            return List.of();
        });

        Optional<SystemInfo> info = service.info(SEL);

        assertThat(info).isPresent();
        assertThat(info.get().hostname()).isEqualTo("k8s-cp");
        assertThat(info.get().osName()).isEqualTo("Ubuntu 24.04.1 LTS");
        assertThat(info.get().osVersion()).isEqualTo("24.04");
        assertThat(info.get().kernelRelease()).isEqualTo("6.8.0-44-generic");
        assertThat(info.get().architecture()).isEqualTo("x86_64");
        assertThat(info.get().bootTimeSeconds()).isEqualTo(1_699_990_000L);
        assertThat(info.get().collectedAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_000L));

        when(client.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.startsWith("node_uname_info")) return List.of(Map.of("metric", Map.of("nodename", "vm-1"), "value", List.of(1_700_000_000L, "1")));
            if (q.startsWith("node_os_info")) return List.of(Map.of("metric", Map.of("name", "Rocky Linux"), "value", List.of(1_700_000_000L, "1")));
            return List.of();
        });
        Optional<SystemInfo> fallback = service.info(SEL);
        assertThat(fallback.get().osName()).isEqualTo("Rocky Linux");           // pretty_name 없음 → name
        assertThat(fallback.get().osVersion()).isNull();
        assertThat(fallback.get().bootTimeSeconds()).isNull();

        when(client.queryResults(anyString())).thenReturn(List.of());
        assertThat(service.info(SEL)).isEmpty();
    }

    @Test
    void 시계열은_rate_창을_step에_맞추고_부하는_소수2자리_rate는_소수1자리로_반올림한다() {
        Instant from = Instant.ofEpochSecond(1_700_000_000L);
        Instant to = Instant.ofEpochSecond(1_700_003_600L);
        when(client.queryRangeResults(anyString(), any(), any(), anyString()))
                .thenReturn(List.of(range(List.of(List.of(1_700_000_000L, "0.3149"), List.of(1_700_000_030L, "NaN"),
                        List.of(1_700_000_060L, "1.5")))));

        List<DataPoint> load = service.trend(SystemMetric.LOAD_1, SEL, from, to, "30s");
        assertThat(load).containsExactly(new DataPoint(1_700_000_000L, 0.31), new DataPoint(1_700_000_060L, 1.5));

        List<DataPoint> intr = service.trend(SystemMetric.INTERRUPTS, SEL, from, to, "30s");
        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        verify(client, org.mockito.Mockito.times(2)).queryRangeResults(q.capture(), eq(from), eq(to), eq("30s"));
        assertThat(q.getAllValues().get(1)).isEqualTo("rate(node_intr_total" + INSTANCE + "[5m])");     // 30s → rate 창 하한 5m
        assertThat(intr).containsExactly(new DataPoint(1_700_000_000L, 0.3), new DataPoint(1_700_000_060L, 1.5));
    }

    @Test
    void Thanos_장애는_KPI_전부_null_정보_empty_시계열_빈_목록이다() {
        when(client.queryResults(anyString())).thenThrow(new RuntimeException("connection refused"));
        when(client.queryRangeResults(anyString(), any(), any(), anyString())).thenThrow(new RuntimeException("timeout"));

        assertThat(service.summary(SEL)).isEqualTo(SystemSummary.EMPTY);
        assertThat(service.info(SEL)).isEmpty();
        assertThat(service.trend(SystemMetric.LOAD_5, SEL, Instant.EPOCH, Instant.EPOCH, "5m")).isEmpty();
    }

    // ── 대역 ────────────────────────────────────────────────────

    static List<Map<String, Object>> vector(String value) {
        return List.of(Map.of("metric", Map.of(), "value", List.of(1_700_000_000L, value)));
    }

    static Map<String, Object> range(List<List<Object>> values) {
        return Map.of("metric", Map.of(), "values", values);
    }
}
