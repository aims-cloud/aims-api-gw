package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
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
                round2(sum(devices, DeviceIo::readBytesPerSec)),
                round2(sum(devices, DeviceIo::writeBytesPerSec)),
                round2(sum(devices, DeviceIo::readIops)),
                round2(sum(devices, DeviceIo::writeIops)),
                round2(weightedLatency(devices, DeviceIo::readLatencyMs, DeviceIo::readIops)),
                round2(weightedLatency(devices, DeviceIo::writeLatencyMs, DeviceIo::writeIops)),
                round2(average(devices, DeviceIo::utilizationPercent)));
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
                round2(d.readBytesPerSec()), round2(d.writeBytesPerSec()),
                round2(d.readIops()), round2(d.writeIops()),
                round2(d.readLatencyMs()), round2(d.writeLatencyMs()),
                round2(d.utilizationPercent()));
    }

    /**
     * sort 파라미터("{필드},{asc|desc}") — device|readBytesPerSec|writeBytesPerSec|readIops|writeIops|readLatencyMs|writeLatencyMs|utilizationPercent,
     * 동률·값 없음은 장치명순. 미지원 필드는 400 — 호출 측은 Thanos 조회 전에 먼저 부른다.
     */
    public static Comparator<DiskIoDeviceListItemResponse> comparator(String sort) {
        String[] parts = sort.split(",", 2);
        String field = parts[0].trim();
        boolean desc = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim());

        Comparator<DiskIoDeviceListItemResponse> comparator = switch (field) {
            case "device" -> Comparator.comparing(DiskIoDeviceListItemResponse::device);
            case "readBytesPerSec" -> nullsLast(DiskIoDeviceListItemResponse::readBytesPerSec, desc);
            case "writeBytesPerSec" -> nullsLast(DiskIoDeviceListItemResponse::writeBytesPerSec, desc);
            case "readIops" -> nullsLast(DiskIoDeviceListItemResponse::readIops, desc);
            case "writeIops" -> nullsLast(DiskIoDeviceListItemResponse::writeIops, desc);
            case "readLatencyMs" -> nullsLast(DiskIoDeviceListItemResponse::readLatencyMs, desc);
            case "writeLatencyMs" -> nullsLast(DiskIoDeviceListItemResponse::writeLatencyMs, desc);
            case "utilizationPercent" -> nullsLast(DiskIoDeviceListItemResponse::utilizationPercent, desc);
            default -> throw BusinessException.badRequest("지원하지 않는 정렬 필드입니다: " + field);
        };
        Comparator<DiskIoDeviceListItemResponse> stable = comparator.thenComparing(DiskIoDeviceListItemResponse::device);
        return desc ? stable.reversed() : stable;
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

    private static Double round2(Double value) {
        return value == null ? null : Math.round(value * 100) / 100.0;
    }

    /** 값 없음(null)이 오름/내림 어느 방향에서도 마지막에 오는 비교자 — desc 는 바깥에서 reversed() 되므로 미리 뒤집어 상쇄한다 */
    private static <R, T extends Comparable<T>> Comparator<R> nullsLast(Function<R, T> extractor, boolean desc) {
        Comparator<T> nullHandling = desc
                ? Comparator.nullsFirst(Comparator.naturalOrder())
                : Comparator.nullsLast(Comparator.naturalOrder());
        return Comparator.comparing(extractor, nullHandling);
    }
}
