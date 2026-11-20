package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.util.ListSort;
import com.innogrid.aims.common.util.MetricFormat;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoDeviceListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoMetricTrendResponse.Series;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostDiskIoMetricsQueryService.DeviceIo;

/**
 * Disk I/O 카테고리 패널 응답 조립 — 노드·VM 패널 서비스가 공유하는 순수 계산 부분.
 *
 * Thanos 표본({@link DeviceIo})을 KPI 카드(합산·IOPS 가중 지연·평균 사용률)·장치 목록 행·시계열 응답으로 바꾸는 규칙만 담고,
 * 자원 식별(노드 assets.id / VM nsId·infraId·vmId)과 스코프 검증은 각 패널 서비스가 맡는다.
 */
public final class DiskIoPanelAssembler {

    /**
     * sort 파라미터("{필드},{asc|desc}") — device|readBytesPerSec|writeBytesPerSec|readIops|writeIops|readLatencyMs|writeLatencyMs|utilizationPercent,
     * 동률·값 없음은 장치명순. 미지원 필드는 400 — 호출 측은 Thanos 조회 전에 먼저 부른다.
     */
    public static final ListSort.Spec<DiskIoDeviceListItemResponse> SORT = ListSort.spec(
            Map.ofEntries(
                    Map.entry("device", ListSort.by(DiskIoDeviceListItemResponse::device)),
                    Map.entry("readBytesPerSec", ListSort.by(DiskIoDeviceListItemResponse::readBytesPerSec)),
                    Map.entry("writeBytesPerSec", ListSort.by(DiskIoDeviceListItemResponse::writeBytesPerSec)),
                    Map.entry("readIops", ListSort.by(DiskIoDeviceListItemResponse::readIops)),
                    Map.entry("writeIops", ListSort.by(DiskIoDeviceListItemResponse::writeIops)),
                    Map.entry("readLatencyMs", ListSort.by(DiskIoDeviceListItemResponse::readLatencyMs)),
                    Map.entry("writeLatencyMs", ListSort.by(DiskIoDeviceListItemResponse::writeLatencyMs)),
                    Map.entry("utilizationPercent", ListSort.by(DiskIoDeviceListItemResponse::utilizationPercent))),
            Comparator.comparing(DiskIoDeviceListItemResponse::device));

    private DiskIoPanelAssembler() {
    }

    /** 시계열 조회 — from/to/step 을 받아 DataPoint 목록을 돌려준다(미수집은 빈 목록) */
    @FunctionalInterface
    public interface TrendQuery {
        List<DataPoint> points(Instant from, Instant to, String step);
    }

    // ── KPI ─────────────────────────────────────────────────────

    /**
     * 개요 탭 KPI 카드 — 장치 수, 읽기·쓰기 B/s, 읽기·쓰기 IOPS, 읽기·쓰기 평균 지연(IOPS 가중), 평균 사용률.
     * 장치가 하나도 없으면 장치 수 0 에 값 전부 null.
     */
    public static DiskIoSummaryResponse summary(List<DeviceIo> devices) {
        if (devices.isEmpty()) {
            return new DiskIoSummaryResponse(0, null, null, null, null, null, null, null);
        }
        return new DiskIoSummaryResponse(
                devices.size(),
                MetricFormat.round2(sum(devices, DeviceIo::readBytesPerSec)),
                MetricFormat.round2(sum(devices, DeviceIo::writeBytesPerSec)),
                MetricFormat.round2(sum(devices, DeviceIo::readIops)),
                MetricFormat.round2(sum(devices, DeviceIo::writeIops)),
                MetricFormat.round2(weightedLatency(devices, DeviceIo::readLatencyMs, DeviceIo::readIops)),
                MetricFormat.round2(weightedLatency(devices, DeviceIo::writeLatencyMs, DeviceIo::writeIops)),
                MetricFormat.round2(average(devices, DeviceIo::utilizationPercent)));
    }

    // ── 목록 ────────────────────────────────────────────────────

    /** 개요 탭 장치 목록 — 장치별 읽기·쓰기 B/s·IOPS·지연·사용률을 정렬해 페이징(1부터)한다 */
    public static PageResponse<DiskIoDeviceListItemResponse> devicePage(List<DeviceIo> devices,
                                                                        Comparator<DiskIoDeviceListItemResponse> comparator,
                                                                        int page, int size) {
        List<DiskIoDeviceListItemResponse> rows = devices.stream()
                .map(DiskIoPanelAssembler::row)
                .sorted(comparator)
                .toList();
        return PageResponse.of(rows, page, size);
    }

    static DiskIoDeviceListItemResponse row(DeviceIo d) {
        return new DiskIoDeviceListItemResponse(d.device(),
                MetricFormat.round2(d.readBytesPerSec()), MetricFormat.round2(d.writeBytesPerSec()),
                MetricFormat.round2(d.readIops()), MetricFormat.round2(d.writeIops()),
                MetricFormat.round2(d.readLatencyMs()), MetricFormat.round2(d.writeLatencyMs()),
                MetricFormat.round2(d.utilizationPercent()));
    }

    // ── 시계열 ──────────────────────────────────────────────────

    /** device 파라미터 정규화 — 빈 값은 "장치 전체 합산"(null) */
    public static String normalizeDevice(String device) {
        return device == null || device.isBlank() ? null : device.trim();
    }

    /**
     * 모니터링 차트 — 장치 전체 합산이면 "disk_io", 장치 지정이면 장치명 시리즈.
     * 조회 구간은 지금 기준 interval 기간, 버킷 간격은 기간별 defaultStep. 미수집이면 빈 시리즈
     */
    public static DiskIoMetricTrendResponse trend(DiskIoMetric metric, String device, NodeMetricInterval interval, TrendQuery query) {
        Instant to = Instant.now();
        Instant from = to.minus(interval.window());
        String step = interval.defaultStep();
        List<DataPoint> points = query.points(from, to, step);
        String seriesName = device == null ? "disk_io" : device;
        return new DiskIoMetricTrendResponse(metric, metric.unit(), device,
                from.getEpochSecond(), to.getEpochSecond(), step,
                points.isEmpty() ? List.of() : List.of(new Series(seriesName, points)));
    }

    // ── 집계 ────────────────────────────────────────────────────

    /** 값이 있는 장치만 합산 — 전부 null 이면 null */
    static Double sum(List<DeviceIo> devices, Function<DeviceIo, Double> field) {
        double total = 0;
        boolean any = false;
        for (DeviceIo d : devices) {
            Double v = field.apply(d);
            if (v != null) {
                total += v;
                any = true;
            }
        }
        return any ? total : null;
    }

    /** 값이 있는 장치만 평균 — 전부 null 이면 null */
    static Double average(List<DeviceIo> devices, Function<DeviceIo, Double> field) {
        double total = 0;
        int count = 0;
        for (DeviceIo d : devices) {
            Double v = field.apply(d);
            if (v != null) {
                total += v;
                count++;
            }
        }
        return count == 0 ? null : total / count;
    }

    /** 지연을 IOPS 로 가중 평균 — 지연·IOPS 둘 다 있는 장치만, IOPS 합이 0 이면 null(구간에 I/O 없음) */
    static Double weightedLatency(List<DeviceIo> devices, Function<DeviceIo, Double> latency, Function<DeviceIo, Double> iops) {
        double weightedSum = 0;
        double weight = 0;
        for (DeviceIo d : devices) {
            Double l = latency.apply(d);
            Double w = iops.apply(d);
            if (l != null && w != null && w > 0) {
                weightedSum += l * w;
                weight += w;
            }
        }
        return weight == 0 ? null : weightedSum / weight;
    }
}
