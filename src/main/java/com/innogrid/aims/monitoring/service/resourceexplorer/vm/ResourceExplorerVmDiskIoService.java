package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.util.Comparator;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoDeviceListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.DiskIoPanelAssembler;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostDiskIoMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 VM Disk I/O 카테고리 패널 조회 서비스.
 * 트리에서 VM 하위 Disk &gt; Disk I/O 항목 선택 시 개요(KPI 카드·장치 목록)와 모니터링(지표 7종 차트) 탭 데이터를 제공한다.
 *
 * VM 은 nsId·infraId·vmId 로 식별하고(스코프 밖·부재는 404), 지표는 VM 안 node_exporter 의 node_disk_* 를
 * {@code tb_ns/tb_infra/tb_node} 라벨로 좁혀 읽는다(loop·ram·dm-·sr 장치 제외). VM 은 exporter 원장이 없어
 * 항상 Thanos 를 조회하고 표본이 없으면 미수집(장치 수 0·빈 목록·빈 시리즈)이다.
 * 응답 DTO·조립 규칙은 노드 Disk I/O 패널과 같다({@link DiskIoPanelAssembler}).
 */
@Service
public class ResourceExplorerVmDiskIoService {

    private final TumblebugPanelSupport support;
    private final HostDiskIoMetricsQueryService metrics;

    public ResourceExplorerVmDiskIoService(TumblebugPanelSupport support, HostDiskIoMetricsQueryService metrics) {
        this.support = support;
        this.metrics = metrics;
    }

    /** 개요 탭 KPI 카드 — 장치 수, 읽기·쓰기 B/s·IOPS·지연(IOPS 가중)·평균 사용률. 장치가 없으면 장치 수 0 에 값 전부 null */
    public DiskIoSummaryResponse getDiskIoSummary(String roleName, String nsId, String infraId, String vmId) {
        return DiskIoPanelAssembler.summary(metrics.devices(selector(roleName, nsId, infraId, vmId)));
    }

    /** 개요 탭 장치 목록 — 장치별 읽기·쓰기 B/s·IOPS·지연·사용률, 페이징(1부터)·컬럼 정렬 */
    public PageResponse<DiskIoDeviceListItemResponse> getDiskIoDevices(String roleName, String nsId, String infraId, String vmId,
                                                                       int page, int size, String sort) {
        Comparator<DiskIoDeviceListItemResponse> comparator = DiskIoPanelAssembler.comparator(sort);   // 미지원 필드는 조회 전에 400
        return DiskIoPanelAssembler.devicePage(metrics.devices(selector(roleName, nsId, infraId, vmId)), comparator, page, size);
    }

    /** 모니터링 탭 차트 — device 를 주면 그 장치만, 없으면 장치 전체 합산(지연은 가중, 사용률은 평균) */
    public DiskIoMetricTrendResponse getDiskIoMetricTrend(String roleName, String nsId, String infraId, String vmId,
                                                          DiskIoMetric metric, String device, NodeMetricInterval interval) {
        String selectedDevice = DiskIoPanelAssembler.normalizeDevice(device);
        MetricSelector sel = selector(roleName, nsId, infraId, vmId);
        return DiskIoPanelAssembler.trend(metric, selectedDevice, interval, (from, to, step) ->
                metrics.trend(metric, sel, selectedDevice, from, to, step));
    }

    /** 스코프·존재 검증(404) 뒤 tb_* 라벨 선택자 */
    private MetricSelector selector(String roleName, String nsId, String infraId, String vmId) {
        support.accessibleNode(roleName, nsId, infraId, vmId);
        return MetricSelector.tumblebugNode(nsId, infraId, vmId);
    }
}
