package com.innogrid.aims.dashboard.service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.innogrid.aims.dashboard.GpuDashboardProperties;
import com.innogrid.aims.dashboard.dto.GpuDashboardFilter;
import com.innogrid.aims.dashboard.dto.GpuDashboardResponse;
import com.innogrid.aims.dashboard.dto.GpuDistributionResponse;
import com.innogrid.aims.dashboard.dto.GpuFilterOptionsResponse;
import com.innogrid.aims.dashboard.dto.GpuFilterOptionsResponse.DeploymentOption;
import com.innogrid.aims.dashboard.dto.GpuFilterOptionsResponse.VmOption;
import com.innogrid.aims.dashboard.dto.GpuMetric;
import com.innogrid.aims.dashboard.dto.GpuNodeCoverageResponse;
import com.innogrid.aims.dashboard.dto.GpuRankingResponse;
import com.innogrid.aims.dashboard.dto.GpuSummaryResponse;
import com.innogrid.aims.dashboard.dto.GpuThresholdResponse;
import com.innogrid.aims.dashboard.dto.GpuTrendResponse;
import com.innogrid.aims.dashboard.dto.GpuTrendResponse.DataPoint;
import com.innogrid.aims.dashboard.dto.GpuUnitPointResponse;
import com.innogrid.aims.dashboard.dto.UpdateGpuThresholdRequest;
import com.innogrid.aims.dashboard.model.GpuDashboardThreshold;
import com.innogrid.aims.dashboard.model.GpuDeviceMetrics;
import com.innogrid.aims.dashboard.model.GpuMetricHistory;
import com.innogrid.aims.dashboard.model.GpuWindow;
import com.innogrid.aims.dashboard.service.DcgmSnapshotLoader.DcgmSnapshot;
import com.innogrid.aims.dashboard.service.DcgmSnapshotLoader.DcgmUnit;
import com.innogrid.aims.dashboard.service.GpuNodeScopeService.GpuNode;
import com.innogrid.aims.dashboard.service.GpuNodeScopeService.GpuNodeScope;
import com.innogrid.aims.mock.MockDataProvider;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import org.springframework.stereotype.Service;

/**
 * GPU 대시보드 (Confluence 2320072739 — 1단계 기반·2단계 GPU 단위 실데이터, 2026-10-06).
 *
 * 실데이터 경로: 기준 목록은 DB — 토큰 역할 스코프 안의 DCGM 수집 대상({@link GpuNodeScopeService})이고, Thanos 의 DCGM 값은
 * 조회 기간 평균으로 {@link DcgmSnapshotLoader} 가 읽어 {@code instance} 가 스코프에 있는 것만 쓴다. 역할·배포영역(MDC)은 DB 에서 붙인다.
 * 판정: 활성 사용률 &gt; active, 유휴 &lt; idle(같으면 어느 쪽도 아님), 고활용 사용률 ≥ high AND VRAM ≥ highVram — 기간에 표본이 있는
 * GPU 만 대상이고, 표본이 없는 GPU(수집 끊김)는 개수에만 들어간다. VM·할당 유형(PassThrough·vGPU)은 3단계 매핑 원장에서 채운다.
 */
@Service
public class GpuDashboardService {

    static final List<String> ALLOCATION_TYPES = List.of("PASSTHROUGH", "MIG", "VGPU");
    static final String UNKNOWN_ALLOCATION = "UNKNOWN";
    static final int DASHBOARD_TOP = 5;

    // TODO:: 실제 Thanos 연동 시 Mock 플래그 의존성 제거
    private final GpuDashboardProperties properties;
    private final VictoriaMetricsClient metricsClient;
    // TODO:: 실제 Thanos 연동 시 MockDataProvider 의존성 제거
    private final MockDataProvider mockDataProvider;
    private final GpuThresholdService thresholdService;
    private final GpuNodeScopeService scopeService;
    private final DcgmSnapshotLoader dcgm;
    private Clock clock = Clock.systemUTC();

    // TODO:: 실제 Thanos 연동 시 GpuDashboardProperties와 MockDataProvider 생성자 주입 제거
    public GpuDashboardService(GpuDashboardProperties properties,
                               VictoriaMetricsClient metricsClient,
                               MockDataProvider mockDataProvider,
                               GpuThresholdService thresholdService,
                               GpuNodeScopeService scopeService,
                               DcgmSnapshotLoader dcgm) {
        this.properties = properties;
        this.metricsClient = metricsClient;
        this.mockDataProvider = mockDataProvider;
        this.thresholdService = thresholdService;
        this.scopeService = scopeService;
        this.dcgm = dcgm;
    }

    /** 테스트용 — 기본 조회 기간(최근 1시간)의 기준 시각 */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    // ── Controller 호출 메서드 ──────────────────────────────────

    public GpuDashboardResponse getDashboard(GpuDashboardFilter filter, OffsetDateTime from, OffsetDateTime to) {
        View view = view(filter, window(from, to));
        List<GpuDeviceMetrics> gpus = view.gpus();
        return new GpuDashboardResponse(
                summary(gpus),
                distribution(gpus, true),
                distribution(gpus, false),
                rankings(gpus, GpuMetric.GPU_UTILIZATION, DASHBOARD_TOP, true),
                rankings(gpus, GpuMetric.VRAM_UTILIZATION, DASHBOARD_TOP, true),
                rankings(gpus, GpuMetric.GPU_UTILIZATION, DASHBOARD_TOP, false),
                rankings(gpus, GpuMetric.VRAM_UTILIZATION, DASHBOARD_TOP, false),
                getThresholds(),
                // TODO:: 실제 Thanos 연동 시 데이터 출처를 THANOS로 고정하거나 응답 필드 제거
                properties.isMockEnabled() ? "MOCK" : "THANOS",
                view.coverage(),
                points(gpus),
                rankings(gpus, GpuMetric.VRAM_USED, DASHBOARD_TOP, false),
                rankings(gpus, GpuMetric.PEAK_VRAM, DASHBOARD_TOP, false));
    }

    /**
     * 필터 목록. roles 는 토큰 역할 전체, 배포영역은 {@code role} 을 주면 그 역할들이 가진 MDC 로 좁힌다(R-02).
     * 할당 유형은 실데이터에서 고정 값 집합(PASSTHROUGH·MIG·VGPU)이다.
     */
    public GpuFilterOptionsResponse getFilterOptions(List<String> role) {
        GpuDashboardFilter roleFilter = new GpuDashboardFilter(role, null, null, null, null);
        List<GpuDeviceMetrics> gpus = view(roleFilter, window(null, null)).gpus();
        List<VmOption> vms = new ArrayList<>(gpus.stream().filter(s -> s.vmId() != null)
                .collect(Collectors.toMap(GpuDeviceMetrics::vmId, s -> new VmOption(s.vmId(), s.vmName()),
                        (left, right) -> left, LinkedHashMap::new)).values());
        if (!properties.isMockEnabled()) {
            // 실데이터: 역할·배포영역은 시계열 유무와 상관없이 스코프(DB)에서 — GPU 가 아직 수집되지 않은 노드도 고를 수 있다
            List<String> roles = scopeService.currentScope(null, null).roles().stream().sorted().toList();
            List<String> mdcIds = List.copyOf(scopeService.currentScope(role, null).mdcIds());
            return new GpuFilterOptionsResponse(roles, mdcIds, vms, distinct(gpus, GpuDeviceMetrics::gpuType),
                    ALLOCATION_TYPES, deployments(mdcIds));
        }
        List<String> mdcIds = distinct(gpus, GpuDeviceMetrics::deploymentId);
        return new GpuFilterOptionsResponse(
                distinct(view(GpuDashboardFilter.all(), window(null, null)).gpus(), GpuDeviceMetrics::role),
                mdcIds, vms,
                distinct(gpus, GpuDeviceMetrics::gpuType),
                distinct(gpus, GpuDeviceMetrics::allocationType),
                deployments(mdcIds));
    }

    public GpuSummaryResponse getSummary(GpuDashboardFilter filter, OffsetDateTime from, OffsetDateTime to) {
        return summary(view(filter, window(from, to)).gpus());
    }

    public List<GpuDistributionResponse> getDistribution(GpuDashboardFilter filter, boolean byVm,
                                                         OffsetDateTime from, OffsetDateTime to) {
        return distribution(view(filter, window(from, to)).gpus(), byVm);
    }

    public List<GpuUnitPointResponse> getGpuDistribution(GpuDashboardFilter filter, OffsetDateTime from, OffsetDateTime to) {
        return points(view(filter, window(from, to)).gpus());
    }

    public List<GpuRankingResponse> getRankings(GpuDashboardFilter filter, GpuMetric metric, int limit, boolean byVm,
                                                OffsetDateTime from, OffsetDateTime to) {
        return rankings(view(filter, window(from, to)).gpus(), metric, limit, byVm);
    }

    /** 순위 상위 limit 개의 시계열. 순위와 같은 GPU 행 집합·같은 기간을 써서 id 집합이 항상 일치한다(B-6) */
    public List<GpuTrendResponse> getTrends(GpuDashboardFilter filter, GpuMetric metric, int limit,
                                            OffsetDateTime from, OffsetDateTime to, boolean byVm) {
        GpuWindow window = window(from, to);
        List<GpuDeviceMetrics> gpus = view(filter, window).gpus();
        Set<String> topIds = rankings(gpus, metric, limit, byVm).stream()
                .map(GpuRankingResponse::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // TODO:: 실제 Thanos 연동 시 Mock 분기를 제거하고 liveTrends(...)를 바로 호출
        if (!properties.isMockEnabled()) {
            return liveTrends(topIds, metric, window, byVm);
        }

        OffsetDateTime start = window.from().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = window.to().atOffset(ZoneOffset.UTC);
        return topIds.stream().map(id -> {
            List<GpuDeviceMetrics> group = gpus.stream().filter(s -> id.equals(byVm ? s.vmId() : s.gpuId())).toList();
            Map<OffsetDateTime, List<Double>> values = new LinkedHashMap<>();
            group.stream().flatMap(s -> s.history().stream())
                    .filter(p -> !p.time().isBefore(start) && !p.time().isAfter(end))
                    .forEach(p -> values.computeIfAbsent(p.time(), key -> new ArrayList<>())
                            .add(historyValue(p, metric, group.get(0).vramTotalMb())));
            List<DataPoint> points = values.entrySet().stream()
                    .map(e -> new DataPoint(e.getKey(), average(e.getValue())))
                    .toList();
            return new GpuTrendResponse(id, displayName(group.get(0), byVm), points);
        }).toList();
    }

    public GpuThresholdResponse getThresholds() {
        return toResponse(thresholdService.current());
    }

    public GpuThresholdResponse updateThresholds(UpdateGpuThresholdRequest request) {
        return toResponse(thresholdService.update(request));
    }

    private static GpuThresholdResponse toResponse(GpuDashboardThreshold value) {
        return new GpuThresholdResponse(value.idleGpuUtilization(), value.highGpuUtilization(),
                value.highVramUtilization(), value.activeGpuUtilization());
    }

    // ── 한 요청이 보는 GPU 행 ───────────────────────────────────

    /** 한 요청이 보는 GPU 행과 노드 수집 현황 */
    private record View(List<GpuDeviceMetrics> gpus, GpuNodeCoverageResponse coverage) {}

    private GpuWindow window(OffsetDateTime from, OffsetDateTime to) {
        return GpuWindow.of(from, to, clock);
    }

    private View view(GpuDashboardFilter filter, GpuWindow window) {
        // TODO:: 실제 Thanos 연동 시 Mock 분기 제거 (mock 은 고정값이라 기간과 무관하다)
        if (properties.isMockEnabled()) {
            List<GpuDeviceMetrics> gpus = mockDataProvider.getGpuDashboardDataset().gpus().stream()
                    .filter(s -> matches(filter.roles(), s.role()))
                    .filter(s -> matches(filter.deploymentIds(), s.deploymentId()))
                    .filter(s -> matches(filter.vmIds(), s.vmId()))
                    .filter(s -> matches(filter.gpuTypes(), s.gpuType()))
                    .filter(s -> matches(filter.allocationTypes(), s.allocationType()))
                    .toList();
            return new View(gpus, mockCoverage(gpus));
        }
        GpuNodeScope scope = scopeService.currentScope(filter.roles(), filter.deploymentIds());
        DcgmSnapshot snapshot = dcgm.load(window);
        Map<String, GpuNode> nodes = scope.byInstance();
        List<GpuDeviceMetrics> gpus = snapshot.units().stream()
                .filter(u -> nodes.containsKey(u.instance()))
                .map(u -> toRow(u, nodes.get(u.instance())))
                .filter(s -> matches(filter.vmIds(), s.vmId()))
                .filter(s -> matches(filter.gpuTypes(), s.gpuType()))
                .filter(s -> matches(filter.allocationTypes(), s.allocationType()))
                .toList();
        return new View(gpus, liveCoverage(scope, snapshot));
    }

    /** DCGM 단위 + DB 노드 → 화면 행. 역할·배포영역은 노드에서, 할당 유형은 MIG 라벨만 알 수 있다(나머지는 3단계 매핑) */
    private static GpuDeviceMetrics toRow(DcgmUnit unit, GpuNode node) {
        double used = unit.fbUsedMb() == null ? 0.0 : unit.fbUsedMb();
        double total = unit.fbUsedMb() == null || unit.fbFreeMb() == null ? 0.0 : unit.fbUsedMb() + unit.fbFreeMb();
        double peak = unit.peakFbUsedMb() == null ? used : unit.peakFbUsedMb();
        // TODO:: 3단계 — VM·PASSTHROUGH/VGPU 는 gpu_allocations 원장에서
        return new GpuDeviceMetrics(unit.id(), unit.gpuIndex(), unit.uuid(),
                unit.hostname() != null ? unit.hostname() : node.assetName(),
                node.primaryRole(), node.mdcId(), null, null, null, null, unit.modelName(),
                unit.mig() ? "MIG" : UNKNOWN_ALLOCATION,
                unit.utilization() == null ? 0.0 : unit.utilization(), used, total, peak, List.of(),
                unit.name(), unit.temperatureC(), unit.powerWatts(), unit.collecting());
    }

    /** 스코프 노드별 수집 여부 + ADMIN 이면 미등록 노드 수(Thanos 에 있으나 DCGM 수집 대상 원장에 없는 instance) */
    private GpuNodeCoverageResponse liveCoverage(GpuNodeScope scope, DcgmSnapshot snapshot) {
        List<GpuNodeCoverageResponse.Node> rows = scope.nodes().stream()
                .map(n -> new GpuNodeCoverageResponse.Node(n.assetId(), n.assetName(), n.mdcId(), n.instance(),
                        snapshot.collectingInstances().contains(n.instance())))
                .toList();
        int collecting = (int) rows.stream().filter(GpuNodeCoverageResponse.Node::collecting).count();
        Integer unregistered = null;
        if (scope.admin()) {
            Set<String> registered = scopeService.registeredInstances();
            unregistered = (int) snapshot.collectingInstances().stream().filter(i -> !registered.contains(i)).count();
        }
        return new GpuNodeCoverageResponse(rows.size(), collecting, unregistered, rows);
    }

    private static GpuNodeCoverageResponse mockCoverage(List<GpuDeviceMetrics> gpus) {
        Map<String, GpuNodeCoverageResponse.Node> byHost = new LinkedHashMap<>();
        for (GpuDeviceMetrics gpu : gpus) {
            if (gpu.hostname() != null) {
                byHost.putIfAbsent(gpu.hostname(),
                        new GpuNodeCoverageResponse.Node(null, gpu.hostname(), gpu.deploymentId(), null, true));
            }
        }
        return new GpuNodeCoverageResponse(byHost.size(), byHost.size(), null, List.copyOf(byHost.values()));
    }

    // ── 집계 ───────────────────────────────────────────────────

    private GpuSummaryResponse summary(List<GpuDeviceMetrics> gpus) {
        GpuDashboardThreshold t = thresholdService.current();
        List<GpuDeviceMetrics> sampled = gpus.stream().filter(GpuDeviceMetrics::collecting).toList();
        long allocated = gpus.stream().filter(s -> s.vmId() != null).count();
        long active = sampled.stream().filter(s -> s.gpuUtilization() > t.activeGpuUtilization()).count();
        long idle = sampled.stream().filter(s -> s.gpuUtilization() < t.idleGpuUtilization()).count();
        long high = sampled.stream().filter(s -> s.gpuUtilization() >= t.highGpuUtilization()
                && s.vramUtilization() >= t.highVramUtilization()).count();
        double used = sampled.stream().mapToDouble(GpuDeviceMetrics::vramUsedMb).sum();
        double total = sampled.stream().mapToDouble(GpuDeviceMetrics::vramTotalMb).sum();
        GpuDeviceMetrics peak = sampled.stream().max(Comparator.comparingDouble(GpuDeviceMetrics::peakVramMb)).orElse(null);
        return new GpuSummaryResponse(allocated, idle, high, total == 0 ? 0.0 : used / total * 100.0, used, total,
                peak == null ? 0.0 : peak.peakVramMb(), gpus.size(), active,
                peak == null ? null : peak.gpuId(), peak == null ? null : peak.vmId(), peak == null ? null : peak.vmName());
    }

    /** VM(또는 모델명)별 평균 — 표본 없는 GPU 는 평균에서 빼고 gpuCount 에는 넣는다(B-4). 표본이 하나도 없는 묶음은 점이 없다 */
    private List<GpuDistributionResponse> distribution(List<GpuDeviceMetrics> gpus, boolean byVm) {
        Function<GpuDeviceMetrics, String> key = byVm ? GpuDeviceMetrics::vmId : GpuDeviceMetrics::gpuType;
        List<GpuDistributionResponse> rows = new ArrayList<>();
        gpus.stream().filter(s -> key.apply(s) != null)
                .collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList()))
                .forEach((id, group) -> {
                    List<GpuDeviceMetrics> sampled = group.stream().filter(GpuDeviceMetrics::collecting).toList();
                    if (sampled.isEmpty()) {
                        return;
                    }
                    GpuDeviceMetrics first = group.get(0);
                    rows.add(new GpuDistributionResponse(id, byVm ? first.vmName() : first.gpuType(),
                            sampled.stream().mapToDouble(GpuDeviceMetrics::gpuUtilization).average().orElse(0),
                            sampled.stream().mapToDouble(GpuDeviceMetrics::vramUtilization).average().orElse(0),
                            group.size()));
                });
        return rows;
    }

    /** GPU 할당 단위 점(R-13) — 수집 끊긴 GPU 도 행으로 두고 값은 null */
    private static List<GpuUnitPointResponse> points(List<GpuDeviceMetrics> gpus) {
        return gpus.stream().map(s -> new GpuUnitPointResponse(s.gpuId(), s.name(), s.allocationType(), s.vmId(),
                s.vmName(), s.hostname(), s.gpuType(),
                s.collecting() ? s.gpuUtilization() : null,
                s.collecting() ? s.vramUtilization() : null,
                s.temperatureC(), s.powerWatts(), s.collecting())).toList();
    }

    private List<GpuRankingResponse> rankings(List<GpuDeviceMetrics> gpus, GpuMetric metric, int limit, boolean byVm) {
        Function<GpuDeviceMetrics, String> key = byVm ? GpuDeviceMetrics::vmId : GpuDeviceMetrics::gpuId;
        return gpus.stream().filter(GpuDeviceMetrics::collecting).filter(s -> key.apply(s) != null)
                .collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream().map(e -> rankingRow(e.getKey(), e.getValue(), metric, byVm))
                .sorted(Comparator.comparingDouble(row -> -row.value()))
                .limit(limit).toList();
    }

    private GpuRankingResponse rankingRow(String id, List<GpuDeviceMetrics> group, GpuMetric metric, boolean byVm) {
        GpuDeviceMetrics first = group.get(0);
        return new GpuRankingResponse(id, displayName(first, byVm),
                group.stream().mapToDouble(s -> metricValue(s, metric)).average().orElse(0),
                metric == GpuMetric.VRAM_USED || metric == GpuMetric.PEAK_VRAM ? "MB" : "%",
                first.projectName(), first.credentialName(), first.vmId(), first.gpuType(), first.allocationType(),
                first.role(), first.deploymentId());
    }

    // ── 시계열 (Thanos query_range) ─────────────────────────────

    private List<GpuTrendResponse> liveTrends(Set<String> topIds, GpuMetric metric, GpuWindow window, boolean byVm) {
        if (topIds.isEmpty()) {
            return List.of();
        }
        // TODO:: 3단계 — VM 시계열은 gpu_allocations 로 GPU 시계열을 VM 별로 평균(지금 실데이터 VM 순위는 비어 있다)
        return metricsClient.queryRangeResults(rangeMetric(metric), window.from(), window.to(), window.step()).stream()
                .map(result -> toLiveTrend(result, topIds, byVm)).filter(Objects::nonNull).toList();
    }

    @SuppressWarnings("unchecked")
    private GpuTrendResponse toLiveTrend(Map<String, Object> result, Set<String> topIds, boolean byVm) {
        Map<String, String> labels = (Map<String, String>) result.getOrDefault("metric", Map.of());
        String id = byVm ? labels.get("vm_id") : DcgmSnapshotLoader.unitId(labels);
        if (id == null || !topIds.contains(id)) return null;
        List<List<Object>> raw = (List<List<Object>>) result.getOrDefault("values", List.of());
        List<DataPoint> points = raw.stream().map(value -> new DataPoint(
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(((Number) value.get(0)).longValue()), ZoneOffset.UTC),
                Double.parseDouble(value.get(1).toString()))).toList();
        return new GpuTrendResponse(id, byVm ? labels.getOrDefault("vm_name", id) : DcgmSnapshotLoader.unitName(labels), points);
    }

    /** 시계열 PromQL — GPU 사용률은 MIG 인스턴스면 GR_ENGINE_ACTIVE×100 으로 대신한다(스냅샷과 같은 규칙) */
    static String rangeMetric(GpuMetric metric) {
        return switch (metric) {
            case GPU_UTILIZATION -> "DCGM_FI_DEV_GPU_UTIL or (DCGM_FI_PROF_GR_ENGINE_ACTIVE{GPU_I_ID!=\"\"} * 100)";
            case VRAM_USED, PEAK_VRAM -> "DCGM_FI_DEV_FB_USED";
            case VRAM_UTILIZATION -> "100 * DCGM_FI_DEV_FB_USED / (DCGM_FI_DEV_FB_USED + DCGM_FI_DEV_FB_FREE)";
        };
    }

    // ── 단순 계산 ───────────────────────────────────────────────

    private static List<DeploymentOption> deployments(List<String> mdcIds) {
        // MDC 원장이 없어 표시명은 mdcId 그대로(알람 이력 areaName 과 같은 규칙)
        return mdcIds.stream().map(id -> new DeploymentOption(id, id)).toList();
    }

    private boolean matches(List<String> filters, String value) {
        return filters == null || filters.isEmpty()
                || value != null && filters.stream().anyMatch(value::equals);
    }

    private List<String> distinct(List<GpuDeviceMetrics> gpus, Function<GpuDeviceMetrics, String> getter) {
        return gpus.stream().map(getter).filter(Objects::nonNull).distinct().sorted().toList();
    }

    private double metricValue(GpuDeviceMetrics gpu, GpuMetric metric) {
        return switch (metric) {
            case GPU_UTILIZATION -> gpu.gpuUtilization();
            case VRAM_UTILIZATION -> gpu.vramUtilization();
            case VRAM_USED -> gpu.vramUsedMb();
            case PEAK_VRAM -> gpu.peakVramMb();
        };
    }

    private double historyValue(GpuMetricHistory point, GpuMetric metric, double vramTotalMb) {
        return switch (metric) {
            case GPU_UTILIZATION -> point.gpuUtilization();
            case VRAM_USED, PEAK_VRAM -> point.vramUsedMb();
            case VRAM_UTILIZATION -> vramTotalMb == 0 ? 0.0 : point.vramUsedMb() / vramTotalMb * 100.0;
        };
    }

    private String displayName(GpuDeviceMetrics gpu, boolean byVm) {
        return byVm ? gpu.vmName() : gpu.name();
    }

    private double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }
}
