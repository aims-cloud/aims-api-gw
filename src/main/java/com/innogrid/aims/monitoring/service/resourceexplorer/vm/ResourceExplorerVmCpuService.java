package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.cpu.VmCpuListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.cpu.VmCpuMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.cpu.VmCpuMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.cpu.VmCpuMetricTrendResponse.Series;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostCategoryMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostCategoryMetricsQueryService.CpuDevice;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 VM CPU 카테고리 패널 조회 서비스.
 * 트리에서 VM 하위 CPU 항목 선택 시 개요(KPI 카드·CPU 목록)와 모니터링(mode 별 차트 9종) 탭 데이터를 제공한다.
 *
 * VM 은 nsId·infraId·vmId 로 식별하고(스코프 밖·부재는 404), 지표는 VM 안 node_exporter 를
 * {@code tb_ns/tb_infra/tb_node} 라벨로 좁혀 읽는다({@link MetricSelector#tumblebugNode}).
 * KPI 카드 4종은 노드 CPU 와 동일 구조라 CpuSummaryResponse 를 재사용한다.
 * VM 상세 패널은 ResourceExplorerVmService, 노드 CPU 는 ResourceExplorerNodeCpuService 가 담당한다.
 */
@Service
public class ResourceExplorerVmCpuService {

    private final TumblebugPanelSupport support;
    private final HostCategoryMetricsQueryService metrics;

    public ResourceExplorerVmCpuService(TumblebugPanelSupport support, HostCategoryMetricsQueryService metrics) {
        this.support = support;
        this.metrics = metrics;
    }

    /** 개요 탭 KPI 카드 4종 — Used / I/O Wait / System / Steal (%). 미수집은 null */
    public CpuSummaryResponse getCpuSummary(String roleName, String nsId, String infraId, String vmId) {
        return metrics.cpuSummary(selector(roleName, nsId, infraId, vmId));
    }

    /**
     * 개요 탭 CPU 목록 — 소켓/벤더/모델/코어·스레드/수집 상태, 페이징(1부터)·컬럼 정렬.
     * 수집 상태는 CPU 지표가 Thanos 에 있으면 COLLECTING, 없으면 목록 자체가 빈다.
     */
    public PageResponse<VmCpuListItemResponse> getCpuDevices(String roleName, String nsId, String infraId, String vmId,
                                                             int page, int size, String sort) {
        Comparator<VmCpuListItemResponse> comparator = comparator(sort);   // 미지원 필드는 조회 전에 400
        List<VmCpuListItemResponse> rows = metrics.cpuDevices(selector(roleName, nsId, infraId, vmId)).stream()
                .map(ResourceExplorerVmCpuService::row)
                .sorted(comparator)
                .toList();
        return PageResponse.of(rows, page, size);
    }

    /** 모니터링 탭 차트 — CPU mode 별 시계열. 조회 구간은 지금 기준 interval 기간, 버킷 간격은 기간별 defaultStep */
    public VmCpuMetricTrendResponse getCpuMetricTrend(String roleName, String nsId, String infraId, String vmId,
                                                      VmCpuMetric metric, NodeMetricInterval interval) {
        MetricSelector sel = selector(roleName, nsId, infraId, vmId);
        Instant to = Instant.now();
        Instant from = to.minus(interval.window());
        String step = interval.defaultStep();
        var points = metrics.cpuTrend(metric, sel, from, to, step);
        return new VmCpuMetricTrendResponse(metric, metric.unit(), from.getEpochSecond(), to.getEpochSecond(), step,
                points.isEmpty() ? List.of() : List.of(new Series("cpu", points)));
    }

    private MetricSelector selector(String roleName, String nsId, String infraId, String vmId) {
        support.accessibleNode(roleName, nsId, infraId, vmId);
        return MetricSelector.tumblebugNode(nsId, infraId, vmId);
    }

    private static VmCpuListItemResponse row(CpuDevice d) {
        return new VmCpuListItemResponse(d.socket(), d.vendor(), d.model(), d.coreCount(), d.threadCount(),
                MonitoringStatus.COLLECTING.name());
    }

    /** sort 파라미터("{필드},{asc|desc}") — socket|vendor|model|coreCount|threadCount, 동률은 소켓순 */
    private static Comparator<VmCpuListItemResponse> comparator(String sort) {
        String[] parts = sort.split(",", 2);
        String field = parts[0].trim();
        boolean desc = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim());

        Comparator<VmCpuListItemResponse> comparator = switch (field) {
            case "socket" -> Comparator.comparingInt(VmCpuListItemResponse::socket);
            case "vendor" -> Comparator.comparing(VmCpuListItemResponse::vendor, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "model" -> Comparator.comparing(VmCpuListItemResponse::model, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "coreCount" -> Comparator.comparingInt(VmCpuListItemResponse::coreCount);
            case "threadCount" -> Comparator.comparingInt(VmCpuListItemResponse::threadCount);
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "지원하지 않는 정렬 필드입니다: " + field);
        };
        Comparator<VmCpuListItemResponse> stable = comparator.thenComparingInt(VmCpuListItemResponse::socket);
        return desc ? stable.reversed() : stable;
    }
}
