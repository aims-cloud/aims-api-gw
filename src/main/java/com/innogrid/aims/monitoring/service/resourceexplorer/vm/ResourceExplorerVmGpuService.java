package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.util.ListSort;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuDeviceSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu.VmGpuAllocationResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu.VmGpuGroupSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu.VmGpuInstanceInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu.VmGpuInstanceListItemResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.GpuPanelAssembler;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostGpuMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostGpuMetricsQueryService.GpuSample;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbNode;
import org.springframework.stereotype.Service;

import static com.innogrid.aims.common.util.MetricFormat.mbToGb;
import static com.innogrid.aims.common.util.MetricFormat.round1;
import static com.innogrid.aims.common.util.MetricFormat.toOffsetDateTime;

/**
 * 자원 탐색기 VM GPU 카테고리 패널 조회 서비스.
 * 그룹(VM 안 GPU 인스턴스 전체)과 개별 인스턴스의 개요·모니터링 탭 데이터를 함께 제공한다.
 *
 * VM 안에서 도는 dcgm_exporter 지표를 VM 의 {@code tb_ns/tb_infra/tb_node} 라벨로 좁혀 읽는다({@link MetricSelector#tumblebugNode}).
 * 조회·조립은 노드 GPU 패널과 같다({@link HostGpuMetricsQueryService}, {@link GpuPanelAssembler}) — 선택자만 다르다.
 * 인스턴스는 GPU UUID 로 식별하며(트리 GPU 장치 노드의 resourceId), 최근 24시간 안에 수집된 적 없으면 개별 패널은 404 다.
 *
 * VM 안의 정보만으로는 PassThrough·vGPU 를 구분할 수 없어 할당 방식은 MIG 만 채우고, 매핑된 물리 GPU·라이선스 상태는 null 이다
 * (1세부 GPU 할당 원장 확정 후 채움). VM 상세 패널은 ResourceExplorerVmService, 노드 GPU 는 ResourceExplorerNodeGpuService 가 담당한다.
 */
@Service
public class ResourceExplorerVmGpuService {

    /**
     * sort 파라미터("{필드},{asc|desc}") — instanceName(인덱스 숫자순)|instanceId|allocationType|gpuUtilCurrentPercent|gpuUtilAvgPercent|
     * gpuMemUtilCurrentPercent|gpuMemUtilAvgPercent|vramUsedGb|vramAllocatedGb|collectStatus|recentAlarmCount.
     * 값 없음은 방향과 무관하게 마지막, 동률은 인스턴스 이름순. 미지원 필드는 400 — 호출 측은 Thanos 조회 전에 먼저 부른다.
     */
    private static final ListSort.Spec<VmGpuInstanceListItemResponse> SORT = ListSort.spec(
            Map.ofEntries(
                    Map.entry("instanceName", ListSort.by(VmGpuInstanceListItemResponse::instanceName, GpuPanelAssembler::compareGpuName)),
                    Map.entry("instanceId", ListSort.by(VmGpuInstanceListItemResponse::instanceId)),
                    Map.entry("allocationType", ListSort.by(VmGpuInstanceListItemResponse::allocationType)),
                    Map.entry("gpuUtilCurrentPercent", ListSort.by(VmGpuInstanceListItemResponse::gpuUtilCurrentPercent)),
                    Map.entry("gpuUtilAvgPercent", ListSort.by(VmGpuInstanceListItemResponse::gpuUtilAvgPercent)),
                    Map.entry("gpuMemUtilCurrentPercent", ListSort.by(VmGpuInstanceListItemResponse::gpuMemUtilCurrentPercent)),
                    Map.entry("gpuMemUtilAvgPercent", ListSort.by(VmGpuInstanceListItemResponse::gpuMemUtilAvgPercent)),
                    Map.entry("vramUsedGb", ListSort.by(VmGpuInstanceListItemResponse::vramUsedGb)),
                    Map.entry("vramAllocatedGb", ListSort.by(VmGpuInstanceListItemResponse::vramAllocatedGb)),
                    Map.entry("collectStatus", ListSort.by(VmGpuInstanceListItemResponse::collectStatus,
                            Comparator.comparing(MonitoringStatus::name))),   // 선언 순서가 아닌 이름순
                    Map.entry("recentAlarmCount", ListSort.by(VmGpuInstanceListItemResponse::recentAlarmCount))),
            Comparator.comparing(VmGpuInstanceListItemResponse::instanceName, GpuPanelAssembler::compareGpuName));

    static final String MIG = "MIG";

    private final TumblebugPanelSupport support;
    private final HostGpuMetricsQueryService metrics;

    public ResourceExplorerVmGpuService(TumblebugPanelSupport support, HostGpuMetricsQueryService metrics) {
        this.support = support;
        this.metrics = metrics;
    }

    // ── 그룹 (트리에서 VM 하위 GPU 카테고리 선택) ───────────────

    /** 그룹 개요 KPI 카드 6종 — 총 GPU 수 / 최대·평균 GPU Util / 평균·최대 VRAM 사용률 / 최근 알람 수(null). 수집 중인 GPU 가 없으면 값은 null */
    public VmGpuGroupSummaryResponse getGroupSummary(String roleName, String nsId, String infraId, String vmId) {
        List<GpuSample> gpus = metrics.devices(support.selector(roleName, nsId, infraId, vmId));
        List<GpuSample> collecting = gpus.stream().filter(GpuSample::collecting).toList();
        return new VmGpuGroupSummaryResponse(
                gpus.size(),
                round1(max(collecting, GpuSample::utilization)),
                round1(avg(collecting, GpuSample::utilization)),
                round1(avg(collecting, GpuSample::vramPercent)),
                round1(max(collecting, GpuSample::vramPercent)),
                null);
    }

    /** 그룹 개요 GPU 인스턴스 목록 — 할당 방식(MIG)/현재·최근 1시간 평균 Util·VRAM 사용률/VRAM(사용·할당)/수집 상태. 페이징(1부터)·컬럼 정렬 */
    public PageResponse<VmGpuInstanceListItemResponse> getInstances(String roleName, String nsId, String infraId, String vmId,
                                                                    int page, int size, String sort) {
        Comparator<VmGpuInstanceListItemResponse> comparator = SORT.comparator(sort);   // 미지원 필드는 조회 전에 400
        MetricSelector selector = support.selector(roleName, nsId, infraId, vmId);
        Set<String> mig = metrics.migUuids(selector);
        List<VmGpuInstanceListItemResponse> rows = metrics.devices(selector).stream()
                .map(g -> row(g, mig.contains(g.uuid())))
                .sorted(comparator)
                .toList();
        return PageResponse.of(rows, page, size);
    }

    /** 그룹 모니터링 차트 — 인스턴스별 분해 시계열. 조회 구간은 지금 기준 interval 기간, 버킷 간격은 기간별 defaultStep */
    public GpuMetricTrendResponse getGroupMetricTrend(String roleName, String nsId, String infraId, String vmId,
                                                      GpuMetric metric, NodeMetricInterval interval) {
        return trend(support.selector(roleName, nsId, infraId, vmId), null, metric, interval);
    }

    // ── 개별 GPU 인스턴스 (트리에서 GPU 장치 선택) ──────────────

    /** 인스턴스 개요 KPI 카드 6종 — Util / VRAM 사용률 / VRAM 사용·할당 / Power / 온도 / Fan. 노드 GPU 카드와 같은 구조 */
    public GpuDeviceSummaryResponse getInstanceSummary(String roleName, String nsId, String infraId, String vmId,
                                                       String instanceId) {
        return GpuPanelAssembler.deviceSummary(requireInstance(support.selector(roleName, nsId, infraId, vmId), instanceId));
    }

    /** 인스턴스 가상머신 할당 정보 표 — VM 안에서 알 수 있는 값(이름·VM·MIG 여부·VRAM)만, 물리 GPU·라이선스는 null */
    public VmGpuAllocationResponse getInstanceAllocation(String roleName, String nsId, String infraId, String vmId,
                                                         String instanceId) {
        TbNode node = support.accessibleNode(roleName, nsId, infraId, vmId);
        MetricSelector selector = MetricSelector.tumblebugNode(nsId, infraId, vmId);
        GpuSample gpu = requireInstance(selector, instanceId);
        return new VmGpuAllocationResponse(
                gpu.displayName(),
                vmId,
                node.name(),
                allocationType(metrics.migUuids(selector).contains(gpu.uuid())),
                null,
                mbToGb(gpu.fbTotalMb()),
                mbToGb(gpu.fbUsedMb()),
                round1(gpu.vramPercent()),
                null);
    }

    /** 인스턴스 GPU 기본 정보 표 — 모델/UUID/드라이버 버전/수집 상태/마지막 수집. CUDA 버전은 수집 항목에 없어 null */
    public VmGpuInstanceInfoResponse getInstanceInfo(String roleName, String nsId, String infraId, String vmId,
                                                     String instanceId) {
        GpuSample gpu = requireInstance(support.selector(roleName, nsId, infraId, vmId), instanceId);
        return new VmGpuInstanceInfoResponse(gpu.model(), gpu.uuid(), null, gpu.driverVersion(),
                GpuPanelAssembler.collectStatus(gpu), toOffsetDateTime(gpu.lastCollectedAt()));
    }

    /** 인스턴스 모니터링 차트 — 해당 인스턴스 시계열 */
    public GpuMetricTrendResponse getInstanceMetricTrend(String roleName, String nsId, String infraId, String vmId,
                                                         String instanceId, GpuMetric metric, NodeMetricInterval interval) {
        return trend(support.selector(roleName, nsId, infraId, vmId), instanceId, metric, interval);
    }

    // ── 조회 대상 ───────────────────────────────────────────────

    private GpuSample requireInstance(MetricSelector selector, String instanceId) {
        return GpuPanelAssembler.requireDevice(metrics.devices(selector), instanceId);
    }

    private GpuMetricTrendResponse trend(MetricSelector selector, String instanceId, GpuMetric metric,
                                         NodeMetricInterval interval) {
        Instant to = Instant.now();
        Instant from = to.minus(interval.window());
        String step = interval.defaultStep();
        return new GpuMetricTrendResponse(metric, metric.unit(), from.getEpochSecond(), to.getEpochSecond(), step,
                GpuPanelAssembler.series(metrics.trend(metric, selector, instanceId, from, to, step)));
    }

    // ── 행·단위 ─────────────────────────────────────────────────

    static VmGpuInstanceListItemResponse row(GpuSample g, boolean mig) {
        return new VmGpuInstanceListItemResponse(
                g.uuid(),
                g.displayName(),
                allocationType(mig),
                round1(g.utilization()),
                round1(g.utilizationAvg()),
                round1(g.vramPercent()),
                round1(g.vramPercentAvg()),
                mbToGb(g.fbUsedMb()),
                mbToGb(g.fbTotalMb()),
                GpuPanelAssembler.collectStatus(g),
                null);
    }

    /** VM 안에서는 MIG 만 확실히 판별된다 — 그 외는 PassThrough·vGPU 를 구분할 수 없어 null */
    private static String allocationType(boolean mig) {
        return mig ? MIG : null;
    }

    private static Double max(List<GpuSample> gpus, Function<GpuSample, Double> value) {
        return gpus.stream().map(value).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }

    private static Double avg(List<GpuSample> gpus, Function<GpuSample, Double> value) {
        List<Double> values = gpus.stream().map(value).filter(Objects::nonNull).toList();
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }
}
