package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.util.List;
import java.util.Map;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostMetricsQueryService.HostUtilization;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Thanos 호스트 사용률 기간 평균 조회 — 표준 PromQL 구성, instance 조인, GB 변환, 장애 시 미수집. */
class HostMetricsQueryServiceTest {

    VictoriaMetricsClient client = mock(VictoriaMetricsClient.class);
    HostMetricsQueryService service = new HostMetricsQueryService(client);

    private static final double GB = 1024.0 * 1024 * 1024;

    @Test
    void 네_쿼리를_instance로_조인해_사용률과_파일시스템_용량을_만든다() {
        when(client.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.contains("node_cpu_seconds_total")) {
                return List.of(vector("10.0.0.1:9100", "32.46"), vector("10.0.0.2:9100", "78.11"));
            }
            if (q.contains("MemAvailable")) {
                return List.of(vector("10.0.0.1:9100", "51.6"));
            }
            if (q.contains("node_filesystem_avail_bytes")) {
                return List.of(vector("10.0.0.1:9100", String.valueOf(896 * GB)));
            }
            if (q.contains("node_filesystem_size_bytes")) {
                return List.of(vector("10.0.0.1:9100", String.valueOf(1024 * GB)));
            }
            return List.of();
        });

        Map<String, HostUtilization> result = service.utilizationByInstance(NodeMetricInterval.LAST_1H);

        HostUtilization node01 = result.get("10.0.0.1:9100");
        assertThat(node01.cpuUtilization()).isEqualTo(32.5);          // 소수 1자리 반올림
        assertThat(node01.memoryUtilization()).isEqualTo(51.6);
        assertThat(node01.fileSystemUtilization()).isEqualTo(12.5);   // (1 - 896/1024) * 100
        assertThat(node01.fileSystemFreeGb()).isEqualTo(896.0);
        assertThat(node01.fileSystemTotalGb()).isEqualTo(1024.0);

        HostUtilization node02 = result.get("10.0.0.2:9100");
        assertThat(node02.cpuUtilization()).isEqualTo(78.1);
        assertThat(node02.memoryUtilization()).isNull();              // 일부 지표만 미수집
        assertThat(node02.fileSystemUtilization()).isNull();
        assertThat(node02.fileSystemFreeGb()).isNull();

        // 기간이 PromQL 윈도우로 들어간다
        ArgumentCaptor<String> queries = ArgumentCaptor.forClass(String.class);
        verify(client, times(4)).queryResults(queries.capture());
        assertThat(queries.getAllValues()).allSatisfy(q -> assertThat(q).contains("[1h]"));
        assertThat(queries.getAllValues().get(0)).isEqualTo(
                "100 - (avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[1h])) * 100)");
    }

    @Test
    void 기간_표기는_분_시간_일_단위로_변환된다() {
        assertThat(NodeMetricInterval.LAST_10M.promWindow()).isEqualTo("10m");
        assertThat(NodeMetricInterval.LAST_1H.promWindow()).isEqualTo("1h");
        assertThat(NodeMetricInterval.LAST_24H.promWindow()).isEqualTo("24h");
        assertThat(NodeMetricInterval.LAST_30D.promWindow()).isEqualTo("30d");
        assertThat(NodeMetricInterval.LAST_90D.promWindow()).isEqualTo("90d");
    }

    @Test
    void Thanos_장애나_비수치_샘플은_미수집으로_처리한다() {
        when(client.queryResults(any())).thenThrow(new IllegalStateException("thanos down"));
        assertThat(service.utilizationByInstance(NodeMetricInterval.LAST_10M)).isEmpty();

        // 예외 스텁 뒤의 재스텁은 when(...) 호출 자체가 던지므로 doAnswer 로 바꾼다
        doAnswer(inv -> {
            String q = inv.<String>getArgument(0);
            return q.contains("node_cpu_seconds_total") ? List.of(vector("10.0.0.1:9100", "NaN")) : List.of();
        }).when(client).queryResults(anyString());
        Map<String, HostUtilization> result = service.utilizationByInstance(NodeMetricInterval.LAST_10M);
        assertThat(result).isEmpty();                                 // NaN 은 값으로 취급하지 않는다
    }

    private static Map<String, Object> vector(String instance, String value) {
        return Map.of("metric", Map.of("instance", instance), "value", List.of(1_757_000_000, value));
    }
}
