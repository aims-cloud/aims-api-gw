package com.innogrid.aims.monitoring.service.resourceexplorer.node;

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
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostDiskIoMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostDiskIoMetricsQueryService.DeviceIo;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 노드 Disk I/O 카테고리 패널 조회 서비스.
 * 트리에서 노드 하위 Disk &gt; Disk I/O 항목 선택 시 개요(KPI 카드·장치 목록)와 모니터링(지표 7종 차트) 탭 데이터를 제공한다.
 *
 * nodeId 는 assets.id 이며 역할 스코프 밖이면 404({@link NodeAccessService}).
 * 지표는 노드 node_exporter 의 node_disk_* 를 {@code instance} 라벨로 좁혀 읽는다(loop·ram·dm-·sr 장치 제외).
 * node_exporter 타겟이 없는 노드는 Thanos 를 조회하지 않고 미수집(null·빈 목록·빈 시리즈)으로 내린다.
 * File System 은 ResourceExplorerNodeFileSystemService, 노드 상세는 ResourceExplorerNodeService 가 담당한다.
 */
@Service
public class ResourceExplorerNodeDiskIoService {

    private final NodeAccessService nodeAccess;
    private final HostDiskIoMetricsQueryService metrics;

    public ResourceExplorerNodeDiskIoService(NodeAccessService nodeAccess, HostDiskIoMetricsQueryService metrics) {
        this.nodeAccess = nodeAccess;
        this.metrics = metrics;
    }

    // ── 공개 API ────────────────────────────────────────────────

    /**
     * 개요 탭 KPI 카드 — 장치 수, 읽기·쓰기 B/s, 읽기·쓰기 IOPS, 읽기·쓰기 평균 지연(IOPS 가중), 평균 사용률(최근 5분 rate).
     * 장치가 하나도 없으면 장치 수 0 에 값 전부 null.
     */
    public DiskIoSummaryResponse getDiskIoSummary(String nodeId) {
        MetricSelector selector = selector(nodeId);
        List<DeviceIo> devices = selector == null ? List.of() : metrics.devices(selector);
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

    /** 개요 탭 장치 목록 — 장치별 읽기·쓰기 B/s·IOPS·지연·사용률, 페이징(1부터)·컬럼 정렬 */
    public PageResponse<DiskIoDeviceListItemResponse> getDiskIoDevices(String nodeId, int page, int size, String sort) {
        Comparator<DiskIoDeviceListItemResponse> comparator = comparator(sort);   // 미지원 필드는 조회 전에 400
        MetricSelector selector = selector(nodeId);
        if (selector == null) {
            return PageResponse.of(List.of(), page, size);
        }
        List<DiskIoDeviceListItemResponse> rows = metrics.devices(selector).stream()
                .map(ResourceExplorerNodeDiskIoService::row)
                .sorted(comparator)
                .toList();
        return PageResponse.of(rows, page, size);
    }

    /**
     * 모니터링 탭 차트 — 지표별 시계열. device 를 주면 그 장치만, 없으면 장치 전체 합산(지연은 가중, 사용률은 평균).
     * 조회 구간은 지금 기준 interval 기간, 버킷 간격은 기간별 defaultStep.
     */
    public DiskIoMetricTrendResponse getDiskIoMetricTrend(String nodeId, DiskIoMetric metric, String device, NodeMetricInterval interval) {
        String selectedDevice = device == null || device.isBlank() ? null : device.trim();
        MetricSelector selector = selector(nodeId);
        Instant to = Instant.now();
        Instant from = to.minus(interval.window());
        String step = interval.defaultStep();

        List<DataPoint> points = selector == null ? List.of() : metrics.trend(metric, selector, selectedDevice, from, to, step);
        String seriesName = selectedDevice == null ? "disk_io" : selectedDevice;
        return new DiskIoMetricTrendResponse(metric, metric.unit(), selectedDevice,
                from.getEpochSecond(), to.getEpochSecond(), step,
                points.isEmpty() ? List.of() : List.of(new Series(seriesName, points)));
    }

    // ── 조회 대상 ───────────────────────────────────────────────

    /** 스코프 검증(404) 뒤 node_exporter 선택자. exporter 타겟이 없으면 null — 호출 측은 조회 없이 미수집으로 처리한다 */
    private MetricSelector selector(String nodeId) {
        String instance = nodeAccess.require(nodeId).host().instance();
        return instance == null ? null : MetricSelector.instance(instance);
    }

    // ── 행·집계 ─────────────────────────────────────────────────

    static DiskIoDeviceListItemResponse row(DeviceIo d) {
        return new DiskIoDeviceListItemResponse(d.device(),
                round2(d.readBytesPerSec()), round2(d.writeBytesPerSec()),
                round2(d.readIops()), round2(d.writeIops()),
                round2(d.readLatencyMs()), round2(d.writeLatencyMs()),
                round2(d.utilizationPercent()));
    }

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

    /** sort 파라미터("{필드},{asc|desc}") — device|readBytesPerSec|writeBytesPerSec|readIops|writeIops|readLatencyMs|writeLatencyMs|utilizationPercent, 동률·값 없음은 장치명순 */
    private static Comparator<DiskIoDeviceListItemResponse> comparator(String sort) {
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
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "지원하지 않는 정렬 필드입니다: " + field);
        };
        Comparator<DiskIoDeviceListItemResponse> stable = comparator.thenComparing(DiskIoDeviceListItemResponse::device);
        return desc ? stable.reversed() : stable;
    }

    /** 값 없음(null)이 오름/내림 어느 방향에서도 마지막에 오는 비교자 — desc 는 바깥에서 reversed() 되므로 미리 뒤집어 상쇄한다 */
    private static <R, T extends Comparable<T>> Comparator<R> nullsLast(Function<R, T> extractor, boolean desc) {
        Comparator<T> nullHandling = desc
                ? Comparator.nullsFirst(Comparator.naturalOrder())
                : Comparator.nullsLast(Comparator.naturalOrder());
        return Comparator.comparing(extractor, nullHandling);
    }
}
