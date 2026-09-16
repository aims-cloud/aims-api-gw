package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoMetric;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostDiskIoMetricsQueryService.DeviceIo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Disk I/O 패널 Thanos 조회 — 장치 필터·지연·합산 PromQL, 장치별 결합(지연 null), 시계열 파싱, 장애 처리. */
class HostDiskIoMetricsQueryServiceTest {

    private static final MetricSelector SEL = MetricSelector.instance("10.0.0.1:9100");
    private static final String INSTANCE = "instance=\"10.0.0.1:9100\"";
    private static final String DEV = "device!~\"loop.*|ram.*|dm-.*|sr.*\"";

    VictoriaMetricsClient client = mock(VictoriaMetricsClient.class);
    HostDiskIoMetricsQueryService service = new HostDiskIoMetricsQueryService(client);

    @Test
    void 장치별_PromQL은_가상_장치를_제외하고_지연은_시간을_건수로_나눈다() {
        assertThat(HostDiskIoMetricsQueryService.deviceRate("node_disk_read_bytes_total", SEL, "5m", null))
                .isEqualTo("rate(node_disk_read_bytes_total{" + DEV + "," + INSTANCE + "}[5m])");
        assertThat(HostDiskIoMetricsQueryService.deviceLatencyMs("node_disk_read_time_seconds_total", "node_disk_reads_completed_total", SEL, "5m", null))
                .isEqualTo("rate(node_disk_read_time_seconds_total{" + DEV + "," + INSTANCE + "}[5m])"
                        + " / rate(node_disk_reads_completed_total{" + DEV + "," + INSTANCE + "}[5m]) * 1000");
        assertThat(HostDiskIoMetricsQueryService.deviceUtilization(SEL, "5m", "sda"))
                .isEqualTo("rate(node_disk_io_time_seconds_total{" + DEV + ",device=\"sda\"," + INSTANCE + "}[5m]) * 100");
    }

    @Test
    void 시계열_PromQL은_전체_합산이면_sum_지연은_합산_비율_사용률은_평균이고_장치_지정이면_매처를_붙인다() {
        String read = "rate(node_disk_read_bytes_total{" + DEV + "," + INSTANCE + "}[5m])";
        assertThat(HostDiskIoMetricsQueryService.trendQuery(DiskIoMetric.DISK_READ_BYTES, SEL, "5m", null))
                .isEqualTo("sum by (instance) (" + read + ")");
        assertThat(HostDiskIoMetricsQueryService.trendQuery(DiskIoMetric.DISK_WRITE_LATENCY, SEL, "1h", null))
                .isEqualTo("sum by (instance) (rate(node_disk_write_time_seconds_total{" + DEV + "," + INSTANCE + "}[1h]))"
                        + " / sum by (instance) (rate(node_disk_writes_completed_total{" + DEV + "," + INSTANCE + "}[1h])) * 1000");
        assertThat(HostDiskIoMetricsQueryService.trendQuery(DiskIoMetric.DISK_UTILIZATION, SEL, "5m", null))
                .isEqualTo("avg by (instance) (rate(node_disk_io_time_seconds_total{" + DEV + "," + INSTANCE + "}[5m])) * 100");
        assertThat(HostDiskIoMetricsQueryService.trendQuery(DiskIoMetric.DISK_WRITE_IOPS, SEL, "5m", "nvme0n1"))
                .isEqualTo("sum by (instance) (rate(node_disk_writes_completed_total{" + DEV + ",device=\"nvme0n1\"," + INSTANCE + "}[5m]))");
    }

    @Test
    void 장치_목록은_읽기_바이트_기준_장치를_모아_지표를_결합하고_IO가_없는_장치의_지연은_null이다() {
        when(client.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.startsWith("rate(node_disk_read_bytes_total")) return List.of(dev("sdb", "0"), dev("sda", "1048576.4"));
            if (q.startsWith("rate(node_disk_written_bytes_total")) return List.of(dev("sda", "524288"), dev("sdb", "0"));
            if (q.startsWith("rate(node_disk_reads_completed_total")) return List.of(dev("sda", "120.5"), dev("sdb", "0"));
            if (q.startsWith("rate(node_disk_writes_completed_total")) return List.of(dev("sda", "30"), dev("sdb", "0"));
            if (q.startsWith("rate(node_disk_read_time_seconds_total")) return List.of(dev("sda", "0.42"), dev("sdb", "NaN"));   // sdb: 0/0
            if (q.startsWith("rate(node_disk_write_time_seconds_total")) return List.of(dev("sda", "1.5"));
            if (q.startsWith("rate(node_disk_io_time_seconds_total")) return List.of(dev("sda", "12.5"), dev("sdb", "0"));
            return List.of();
        });

        List<DeviceIo> devices = service.devices(SEL);

        assertThat(devices).extracting(DeviceIo::device).containsExactly("sda", "sdb");    // 장치명 순
        DeviceIo sda = devices.get(0);
        assertThat(sda.readBytesPerSec()).isEqualTo(1048576.4);
        assertThat(sda.writeBytesPerSec()).isEqualTo(524288.0);
        assertThat(sda.readIops()).isEqualTo(120.5);
        assertThat(sda.writeIops()).isEqualTo(30.0);
        assertThat(sda.readLatencyMs()).isEqualTo(0.42);
        assertThat(sda.writeLatencyMs()).isEqualTo(1.5);
        assertThat(sda.utilizationPercent()).isEqualTo(12.5);
        DeviceIo sdb = devices.get(1);
        assertThat(sdb.readLatencyMs()).isNull();                                            // NaN 은 버린다
        assertThat(sdb.writeLatencyMs()).isNull();                                           // 결과 없음
        assertThat(sdb.utilizationPercent()).isEqualTo(0.0);

        verify(client, times(7)).queryResults(anyString());
    }

    @Test
    void 읽기_바이트_결과가_없으면_다른_지표를_조회하지_않고_빈_목록이다() {
        when(client.queryResults(anyString())).thenReturn(List.of());

        assertThat(service.devices(SEL)).isEmpty();
        verify(client, times(1)).queryResults(anyString());
    }

    @Test
    void 시계열은_rate_창을_step에_맞추고_점을_소수2자리로_반올림하며_장애는_빈_결과다() {
        Instant from = Instant.ofEpochSecond(1_700_000_000L);
        Instant to = Instant.ofEpochSecond(1_700_003_600L);
        when(client.queryRangeResults(anyString(), any(), any(), anyString()))
                .thenReturn(List.of(range(List.of(List.of(1_700_000_000L, "0.4256"), List.of(1_700_000_030L, "NaN"),
                        List.of(1_700_000_060L, "0.5")))));

        List<DataPoint> points = service.trend(DiskIoMetric.DISK_READ_LATENCY, SEL, "sda", from, to, "30s");

        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        verify(client).queryRangeResults(q.capture(), eq(from), eq(to), eq("30s"));
        assertThat(q.getValue()).contains("device=\"sda\"").contains("[5m]");             // 30s 는 rate 창 하한 5m 으로
        assertThat(points).containsExactly(new DataPoint(1_700_000_000L, 0.43), new DataPoint(1_700_000_060L, 0.5));

        when(client.queryResults(anyString())).thenThrow(new RuntimeException("connection refused"));
        when(client.queryRangeResults(anyString(), any(), any(), anyString())).thenThrow(new RuntimeException("timeout"));
        assertThat(service.devices(SEL)).isEmpty();
        assertThat(service.trend(DiskIoMetric.DISK_READ_BYTES, SEL, null, from, to, "5m")).isEmpty();
    }

    // ── 대역 ────────────────────────────────────────────────────

    static Map<String, Object> dev(String device, String value) {
        return Map.of("metric", Map.of("device", device, "instance", "10.0.0.1:9100"), "value", List.of(1_700_000_000L, value));
    }

    static Map<String, Object> range(List<List<Object>> values) {
        return Map.of("metric", Map.of(), "values", values);
    }
}
