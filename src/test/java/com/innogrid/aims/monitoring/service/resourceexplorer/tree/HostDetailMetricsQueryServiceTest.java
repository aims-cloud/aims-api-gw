package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeDetailMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeMetricTrendResponse.Series;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostDetailMetricsQueryService.DailyMax;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostDetailMetricsQueryService.HostSummaryMetrics;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 노드 상세 Thanos 조회 — instance 필터 PromQL, 단위 변환, 미수집 처리, 일간 최대 subquery. */
class HostDetailMetricsQueryServiceTest {

    private static final double GB = 1024.0 * 1024 * 1024;
    private static final String NODE = "10.0.0.1:9100";
    private static final String DCGM = "10.0.0.1:9400";
    private static final MetricSelector NODE_S = MetricSelector.instance(NODE);
    private static final MetricSelector DCGM_S = MetricSelector.instance(DCGM);

    VictoriaMetricsClient client = mock(VictoriaMetricsClient.class);
    HostDetailMetricsQueryService service = new HostDetailMetricsQueryService(client);

    @Test
    void 주요_지표는_instance로_필터한_즉시_쿼리를_GB_단위로_변환한다() {
        when(client.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            assertThat(q).containsAnyOf("instance=\"" + NODE + "\"", "instance=\"" + DCGM + "\"");
            if (q.startsWith("100 - ")) return vector("32.46");
            if (q.startsWith("count by (instance) (node_cpu")) return vector("40");
            if (q.startsWith("node_memory_MemTotal")) return vector(String.valueOf(512 * GB));
            if (q.startsWith("node_memory_MemAvailable")) return vector(String.valueOf(248 * GB));
            if (q.contains("node_filesystem_size")) return vector(String.valueOf(1024 * GB));
            if (q.contains("node_filesystem_avail")) return vector(String.valueOf(896 * GB));
            if (q.contains("receive_bytes_total")) return vector("1048576");
            if (q.contains("transmit_bytes_total")) return vector("524288");
            if (q.startsWith("node_boot_time")) return vector("1700000000");
            if (q.startsWith("avg by (instance) (DCGM_FI_DEV_GPU_UTIL")) return vector("88.5");
            if (q.startsWith("count by (instance) (DCGM_FI_DEV_GPU_UTIL")) return vector("2");
            if (q.contains("FB_USED")) return vector("63897.6");        // MB
            if (q.contains("FB_FREE")) return vector("18022.4");
            return List.of();
        });

        HostSummaryMetrics m = service.summary(NODE_S, DCGM_S);

        assertThat(m.cpuPercent()).isEqualTo(32.5);
        assertThat(m.coreCount()).isEqualTo(40);
        assertThat(m.memoryUsedGb()).isEqualTo(264.0);
        assertThat(m.memoryTotalGb()).isEqualTo(512.0);
        assertThat(m.fileSystemUsedGb()).isEqualTo(128.0);
        assertThat(m.fileSystemTotalGb()).isEqualTo(1024.0);
        assertThat(m.receiveBytesPerSec()).isEqualTo(1_048_576.0);
        assertThat(m.transmitBytesPerSec()).isEqualTo(524_288.0);
        assertThat(m.bootTimeSeconds()).isEqualTo(1_700_000_000L);
        assertThat(m.gpuPercent()).isEqualTo(88.5);
        assertThat(m.gpuCount()).isEqualTo(2);
        assertThat(m.vramUsedGb()).isEqualTo(62.4);
        assertThat(m.vramTotalGb()).isEqualTo(80.0);
    }

    @Test
    void exporter가_없는_그룹은_조회하지_않고_결과가_없으면_미수집이다() {
        when(client.queryResults(anyString())).thenReturn(List.of());
        HostSummaryMetrics m = service.summary(NODE_S, null);

        assertThat(m.cpuPercent()).isNull();
        assertThat(m.gpuPercent()).isNull();
        ArgumentCaptor<String> queries = ArgumentCaptor.forClass(String.class);
        verify(client, org.mockito.Mockito.atLeastOnce()).queryResults(queries.capture());
        assertThat(queries.getAllValues()).noneMatch(q -> q.contains("DCGM"));   // dcgm instance 없음

        assertThat(service.summary(null, null)).isEqualTo(HostSummaryMetrics.EMPTY);
    }

    @Test
    void 요약_차트는_range_쿼리를_시리즈로_바꾸고_NETWORK는_두_시리즈다() {
        Instant from = Instant.ofEpochSecond(1_757_000_000L);
        Instant to = from.plusSeconds(600);
        when(client.queryRangeResults(anyString(), eq(from), eq(to), eq("30s"))).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.contains("receive")) return range(List.of(1_757_000_000L, "1000"), List.of(1_757_000_030L, "NaN"), List.of(1_757_000_060L, "1200.26"));
            if (q.contains("transmit")) return range(List.of(1_757_000_000L, "500"));
            if (q.startsWith("100 - ")) return range(List.of(1_757_000_000L, "30.04"));
            return List.of();
        });

        List<Series> network = service.trend(NodeDetailMetric.NETWORK, NODE_S, DCGM_S, from, to, "30s");
        assertThat(network).extracting(Series::name).containsExactly("receive", "transmit");
        assertThat(network.get(0).dataPoints()).hasSize(2);           // NaN 샘플 제외
        assertThat(network.get(0).dataPoints().get(1).value()).isEqualTo(1200.3);

        List<Series> cpu = service.trend(NodeDetailMetric.CPU, NODE_S, DCGM_S, from, to, "30s");
        assertThat(cpu.get(0).dataPoints().get(0).value()).isEqualTo(30.0);

        assertThat(service.trend(NodeDetailMetric.GPU, NODE_S, null, from, to, "30s")).isEmpty();   // dcgm 없음
        assertThat(service.trend(NodeDetailMetric.MEMORY, NODE_S, DCGM_S, from, to, "30s")).isEmpty(); // 결과 없음
        verify(client, never()).queryRangeResults(org.mockito.ArgumentMatchers.contains("DCGM"), any(), any(), any());
    }

    @Test
    void 일간_최대는_하루_step_subquery로_조회하고_평가시각_전날을_일자로_삼는다() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate d1 = LocalDate.of(2026, 9, 2);
        LocalDate d2 = LocalDate.of(2026, 9, 3);
        long endOfD1 = d1.plusDays(1).atStartOfDay(zone).toEpochSecond();
        long endOfD2 = d2.plusDays(1).atStartOfDay(zone).toEpochSecond();

        when(client.queryRangeResults(anyString(), any(), any(), eq("1d"))).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            assertThat(q).startsWith("max_over_time((").endsWith(")[1d:5m])");
            if (q.contains("node_cpu")) return range(List.of(endOfD1, "71.04"), List.of(endOfD2, "80.3"));
            if (q.contains("MemAvailable")) return range(List.of(endOfD2, "66.1"));
            return List.of();                                            // 파일시스템은 미수집
        });

        List<DailyMax> rows = service.dailyMax(NODE_S, d1, d2);

        assertThat(rows).extracting(DailyMax::date).containsExactly(d1, d2);
        assertThat(rows.get(0).cpuPercent()).isEqualTo(71.0);
        assertThat(rows.get(0).memoryPercent()).isNull();
        assertThat(rows.get(1).cpuPercent()).isEqualTo(80.3);
        assertThat(rows.get(1).memoryPercent()).isEqualTo(66.1);
        assertThat(rows.get(1).fileSystemPercent()).isNull();

        assertThat(service.dailyMax(null, d1, d2)).isEmpty();          // node_exporter 없음
    }

    @Test
    void Thanos_장애면_카드는_미수집_차트는_빈_시리즈_일간최대는_빈_값이다() {
        when(client.queryResults(any())).thenThrow(new IllegalStateException("thanos down"));
        when(client.queryRangeResults(any(), any(), any(), any())).thenThrow(new IllegalStateException("thanos down"));

        assertThat(service.summary(NODE_S, DCGM_S)).isEqualTo(HostSummaryMetrics.EMPTY);
        assertThat(service.trend(NodeDetailMetric.CPU, NODE_S, DCGM_S, Instant.now().minusSeconds(600), Instant.now(), "30s")).isEmpty();
        List<DailyMax> rows = service.dailyMax(NODE_S, LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3));
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> assertThat(r.cpuPercent()).isNull());
    }

    @Test
    void 요약_차트의_rate_창은_step이_5분보다_짧으면_5분이다() {
        assertThat(HostDetailMetricsQueryService.rateWindow("30s")).isEqualTo("5m");
        assertThat(HostDetailMetricsQueryService.rateWindow("5m")).isEqualTo("5m");
        assertThat(HostDetailMetricsQueryService.rateWindow("15m")).isEqualTo("15m");
        assertThat(HostDetailMetricsQueryService.rateWindow("1h")).isEqualTo("1h");
        assertThat(HostDetailMetricsQueryService.rateWindow("1d")).isEqualTo("1d");
        assertThat(HostDetailMetricsQueryService.rateWindow("garbage")).isEqualTo("5m");

        Instant from = Instant.ofEpochSecond(1_757_000_000L);
        when(client.queryRangeResults(anyString(), any(), any(), eq("30s"))).thenReturn(List.of());
        service.trend(NodeDetailMetric.CPU, NODE_S, DCGM_S, from, from.plusSeconds(600), "30s");
        verify(client).queryRangeResults(org.mockito.ArgumentMatchers.contains("[5m]"), eq(from), eq(from.plusSeconds(600)), eq("30s"));
    }

    @Test
    void Tumblebug_선택자는_tb_라벨_세_개로_좁히고_dcgm도_같은_선택자를_쓴다() {
        MetricSelector vm = MetricSelector.tumblebugNode("openstack", "openstack-infra-01", "openstack-nodegroup-01-1");
        assertThat(vm.matchers()).isEqualTo(
                "tb_ns=\"openstack\",tb_infra=\"openstack-infra-01\",tb_node=\"openstack-nodegroup-01-1\"");
        assertThat(vm.braces("mode=\"idle\"")).isEqualTo(
                "{mode=\"idle\",tb_ns=\"openstack\",tb_infra=\"openstack-infra-01\",tb_node=\"openstack-nodegroup-01-1\"}");
        assertThat(MetricSelector.instance("10.0.0.1:9100").braces()).isEqualTo("{instance=\"10.0.0.1:9100\"}");
        assertThat(MetricSelector.instance("a\"b").matchers()).isEqualTo("instance=\"a\\\"b\"");   // 따옴표 이스케이프

        when(client.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            assertThat(q).contains("tb_node=\"openstack-nodegroup-01-1\"").doesNotContain("instance=");
            if (q.startsWith("100 - ")) return vector("12.3");
            if (q.startsWith("avg by (instance) (DCGM_FI_DEV_GPU_UTIL")) return vector("55.0");
            return List.of();
        });

        HostSummaryMetrics m = service.summary(vm, vm);                  // node_exporter·dcgm 모두 같은 VM 라벨
        assertThat(m.cpuPercent()).isEqualTo(12.3);
        assertThat(m.gpuPercent()).isEqualTo(55.0);
        assertThat(m.memoryTotalGb()).isNull();

        Instant from = Instant.ofEpochSecond(1_757_000_000L);
        when(client.queryRangeResults(anyString(), any(), any(), any())).thenReturn(List.of());
        service.trend(NodeDetailMetric.CPU, vm, null, from, from.plusSeconds(600), "30s");
        verify(client).queryRangeResults(org.mockito.ArgumentMatchers.contains("tb_infra=\"openstack-infra-01\""), any(), any(), eq("30s"));
        assertThat(service.dailyMax(null, LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3))).isEmpty();
    }

    private static List<Map<String, Object>> vector(String value) {
        return List.of(Map.of("metric", Map.of("instance", NODE), "value", List.of(1_757_000_000, value)));
    }

    @SafeVarargs
    private static List<Map<String, Object>> range(List<Object>... values) {
        return List.of(Map.of("metric", Map.of("instance", NODE), "values", List.of(values)));
    }
}
