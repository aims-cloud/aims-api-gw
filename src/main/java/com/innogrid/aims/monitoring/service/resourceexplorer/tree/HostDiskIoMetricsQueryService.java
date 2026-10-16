package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.innogrid.aims.common.util.MetricFormat;
import com.innogrid.aims.monitoring.ThanosResults;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 자원 1개의 Disk I/O 카테고리 패널 지표를 Thanos 에서 읽는다 — 블록 장치별 현재값과 합산/장치 시계열 전용.
 *
 * 전부 node_exporter diskstats 콜렉터의 카운터({@code node_disk_read_bytes_total, written_bytes_total,
 * reads_completed_total, writes_completed_total, read_time_seconds_total, write_time_seconds_total, io_time_seconds_total})를
 * rate 로 읽는다. 가상·루프 장치({@code loop*, ram*, dm-*, sr*})는 제외한다.
 * 지연(ms) = 소요 시간 증가분 / 완료 건수 증가분 × 1000 이라 구간에 I/O 가 없으면 값이 없다(null).
 * 자원은 {@link MetricSelector} 로 가리키고, Thanos 장애나 시계열 부재는 빈 목록(미수집)으로 돌려준다.
 */
@Component
public class HostDiskIoMetricsQueryService {

    private static final Logger log = LoggerFactory.getLogger(HostDiskIoMetricsQueryService.class);
    private static final String RATE_WINDOW = "5m";
    static final String DEVICE_FILTER = "device!~\"loop.*|ram.*|dm-.*|sr.*\"";

    /** 장치 1개의 현재 I/O — 구간에 I/O 가 없어 계산할 수 없는 지연은 null */
    public record DeviceIo(String device,
                           Double readBytesPerSec, Double writeBytesPerSec,
                           Double readIops, Double writeIops,
                           Double readLatencyMs, Double writeLatencyMs,
                           Double utilizationPercent) {}

    private final VictoriaMetricsClient client;

    public HostDiskIoMetricsQueryService(VictoriaMetricsClient client) {
        this.client = client;
    }

    // ── PromQL ──────────────────────────────────────────────────

    /** 장치별 카운터 rate — 결과는 device 라벨을 가진 벡터 */
    static String deviceRate(String counter, MetricSelector s, String window, String device) {
        return "rate(" + counter + s.braces(matchers(device)) + "[" + window + "])";
    }

    /** 장치별 지연(ms) — 소요 시간 rate / 완료 건수 rate × 1000 (라벨이 같아 벡터 매칭으로 나눠진다) */
    static String deviceLatencyMs(String timeCounter, String completedCounter, MetricSelector s, String window, String device) {
        return deviceRate(timeCounter, s, window, device) + " / " + deviceRate(completedCounter, s, window, device) + " * 1000";
    }

    /** 장치별 사용률(%) — io_time 증가분 비율 */
    static String deviceUtilization(MetricSelector s, String window, String device) {
        return deviceRate("node_disk_io_time_seconds_total", s, window, device) + " * 100";
    }

    /**
     * 시계열 쿼리 — 장치 지정이면 그 장치, 아니면 장치 전체 합산(바이트·IOPS 는 합, 지연은 합산 시간/합산 건수, 사용률은 평균).
     */
    static String trendQuery(DiskIoMetric metric, MetricSelector s, String window, String device) {
        return switch (metric) {
            case DISK_READ_BYTES -> sum(deviceRate("node_disk_read_bytes_total", s, window, device));
            case DISK_WRITE_BYTES -> sum(deviceRate("node_disk_written_bytes_total", s, window, device));
            case DISK_READ_IOPS -> sum(deviceRate("node_disk_reads_completed_total", s, window, device));
            case DISK_WRITE_IOPS -> sum(deviceRate("node_disk_writes_completed_total", s, window, device));
            case DISK_READ_LATENCY -> sum(deviceRate("node_disk_read_time_seconds_total", s, window, device))
                    + " / " + sum(deviceRate("node_disk_reads_completed_total", s, window, device)) + " * 1000";
            case DISK_WRITE_LATENCY -> sum(deviceRate("node_disk_write_time_seconds_total", s, window, device))
                    + " / " + sum(deviceRate("node_disk_writes_completed_total", s, window, device)) + " * 1000";
            case DISK_UTILIZATION -> "avg by (instance) (" + deviceRate("node_disk_io_time_seconds_total", s, window, device) + ") * 100";
        };
    }

    private static String sum(String vector) {
        return "sum by (instance) (" + vector + ")";
    }

    private static String matchers(String device) {
        return device == null ? DEVICE_FILTER : DEVICE_FILTER + "," + MetricSelector.label("device", device);
    }

    // ── 조회 ────────────────────────────────────────────────────

    /** 자원의 블록 장치 전체 현재 I/O(최근 5분 rate) — 장치명 순. 장애·부재는 빈 목록 */
    public List<DeviceIo> devices(MetricSelector s) {
        try {
            Map<String, Double> readBytes = byDevice(deviceRate("node_disk_read_bytes_total", s, RATE_WINDOW, null));
            if (readBytes.isEmpty()) {
                return List.of();
            }
            Map<String, Double> writeBytes = byDevice(deviceRate("node_disk_written_bytes_total", s, RATE_WINDOW, null));
            Map<String, Double> readIops = byDevice(deviceRate("node_disk_reads_completed_total", s, RATE_WINDOW, null));
            Map<String, Double> writeIops = byDevice(deviceRate("node_disk_writes_completed_total", s, RATE_WINDOW, null));
            Map<String, Double> readLatency = byDevice(deviceLatencyMs("node_disk_read_time_seconds_total",
                    "node_disk_reads_completed_total", s, RATE_WINDOW, null));
            Map<String, Double> writeLatency = byDevice(deviceLatencyMs("node_disk_write_time_seconds_total",
                    "node_disk_writes_completed_total", s, RATE_WINDOW, null));
            Map<String, Double> utilization = byDevice(deviceUtilization(s, RATE_WINDOW, null));

            List<DeviceIo> rows = new ArrayList<>();
            for (Map.Entry<String, Double> entry : new TreeMap<>(readBytes).entrySet()) {
                String device = entry.getKey();
                rows.add(new DeviceIo(device,
                        entry.getValue(), writeBytes.get(device),
                        readIops.get(device), writeIops.get(device),
                        readLatency.get(device), writeLatency.get(device),
                        utilization.get(device)));
            }
            return rows;
        } catch (RuntimeException e) {
            log.warn("Thanos Disk I/O 장치 조회 실패 — 빈 목록을 돌려줍니다: {}", e.getMessage());
            return List.of();
        }
    }

    /** 시계열 — 장치 지정 시 그 장치, 아니면 전체 합산. 미수집이면 빈 목록 */
    public List<DataPoint> trend(DiskIoMetric metric, MetricSelector s, String device, Instant from, Instant to, String step) {
        String query = trendQuery(metric, s, HostDetailMetricsQueryService.rateWindow(step), device);
        try {
            List<Map<String, Object>> results = client.queryRangeResults(query, from, to, step);
            if (results.isEmpty()) {
                return List.of();
            }
            List<DataPoint> points = new ArrayList<>();
            for (ThanosResults.Sample sample : ThanosResults.values(results.get(0))) {
                points.add(new DataPoint(sample.epochSecond(), MetricFormat.round2(sample.value())));
            }
            return points;
        } catch (RuntimeException e) {
            log.warn("Thanos Disk I/O 차트 조회 실패 — 빈 시계열을 돌려줍니다: {}", e.getMessage());
            return List.of();
        }
    }

    // ── 내부 ────────────────────────────────────────────────────

    /** 즉시 쿼리 벡터를 device 라벨 → 값으로. 비수치(NaN 등)는 건너뛴다 */
    private Map<String, Double> byDevice(String query) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (Map<String, Object> result : client.queryResults(query)) {
            String device = ThanosResults.label(result, "device");
            Double value = ThanosResults.value(result);
            if (device != null && value != null) {
                values.putIfAbsent(device, value);
            }
        }
        return values;
    }
}
