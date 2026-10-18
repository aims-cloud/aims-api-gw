package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.util.Comparator;
import java.util.List;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoDeviceListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.DiskIoPanelAssembler;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostDiskIoMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostDiskIoMetricsQueryService.DeviceIo;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 노드 Disk I/O 카테고리 패널 조회 서비스.
 * 트리에서 노드 하위 Disk &gt; Disk I/O 항목 선택 시 개요(KPI 카드·장치 목록)와 모니터링(지표 7종 차트) 탭 데이터를 제공한다.
 *
 * nodeId 는 assets.id 이며 역할 스코프 밖이면 404({@link NodeAccessService}).
 * 지표는 노드 node_exporter 의 node_disk_* 를 {@code instance} 라벨로 좁혀 읽는다(loop·ram·dm-·sr 장치 제외).
 * node_exporter 타겟이 없는 노드는 Thanos 를 조회하지 않고 미수집(null·빈 목록·빈 시리즈)으로 내린다.
 * 응답 조립 규칙은 VM Disk I/O 패널과 공유한다({@link DiskIoPanelAssembler}).
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

    /** 개요 탭 KPI 카드 — 장치 수, 읽기·쓰기 B/s·IOPS·지연(IOPS 가중)·평균 사용률. 장치가 없으면 장치 수 0 에 값 전부 null */
    public DiskIoSummaryResponse getDiskIoSummary(String nodeId) {
        MetricSelector selector = nodeAccess.selector(nodeId);
        return DiskIoPanelAssembler.summary(selector == null ? List.of() : metrics.devices(selector));
    }

    /** 개요 탭 장치 목록 — 장치별 읽기·쓰기 B/s·IOPS·지연·사용률, 페이징(1부터)·컬럼 정렬 */
    public PageResponse<DiskIoDeviceListItemResponse> getDiskIoDevices(String nodeId, int page, int size, String sort) {
        Comparator<DiskIoDeviceListItemResponse> comparator = DiskIoPanelAssembler.SORT.comparator(sort);   // 미지원 필드는 조회 전에 400
        MetricSelector selector = nodeAccess.selector(nodeId);
        List<DeviceIo> devices = selector == null ? List.of() : metrics.devices(selector);
        return DiskIoPanelAssembler.devicePage(devices, comparator, page, size);
    }

    /** 모니터링 탭 차트 — device 를 주면 그 장치만, 없으면 장치 전체 합산(지연은 가중, 사용률은 평균) */
    public DiskIoMetricTrendResponse getDiskIoMetricTrend(String nodeId, DiskIoMetric metric, String device, NodeMetricInterval interval) {
        String selectedDevice = DiskIoPanelAssembler.normalizeDevice(device);
        MetricSelector selector = nodeAccess.selector(nodeId);
        return DiskIoPanelAssembler.trend(metric, selectedDevice, interval, (from, to, step) ->
                selector == null ? List.of() : metrics.trend(metric, selector, selectedDevice, from, to, step));
    }
}
