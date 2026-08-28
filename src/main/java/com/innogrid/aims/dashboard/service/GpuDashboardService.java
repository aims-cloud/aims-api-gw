package com.innogrid.aims.dashboard.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.innogrid.aims.dashboard.GpuDashboardProperties;
import com.innogrid.aims.dashboard.dto.GpuDashboardFilter;
import com.innogrid.aims.dashboard.dto.GpuDashboardResponse;
import com.innogrid.aims.dashboard.dto.GpuDistributionResponse;
import com.innogrid.aims.dashboard.dto.GpuFilterOptionsResponse;
import com.innogrid.aims.dashboard.dto.GpuFilterOptionsResponse.VmOption;
import com.innogrid.aims.dashboard.dto.GpuMetric;
import com.innogrid.aims.dashboard.dto.GpuRankingResponse;
import com.innogrid.aims.dashboard.dto.GpuSummaryResponse;
import com.innogrid.aims.dashboard.dto.GpuThresholdResponse;
import com.innogrid.aims.dashboard.dto.GpuTrendResponse;
import com.innogrid.aims.dashboard.dto.GpuTrendResponse.DataPoint;
import com.innogrid.aims.dashboard.dto.UpdateGpuThresholdRequest;
import com.innogrid.aims.dashboard.model.GpuDashboardThreshold;
import com.innogrid.aims.dashboard.model.GpuDeviceMetrics;
import com.innogrid.aims.dashboard.model.GpuMetricHistory;
import com.innogrid.aims.mock.MockDataProvider;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import org.springframework.stereotype.Service;

@Service
public class GpuDashboardService {

    private static final GpuDashboardThreshold DEFAULT_THRESHOLDS = new GpuDashboardThreshold(10.0, 60.0, 60.0);

    // TODO:: 실제 Thanos 연동 시 Mock 플래그 의존성 제거
    private final GpuDashboardProperties properties;
    private final VictoriaMetricsClient metricsClient;
    // TODO:: 실제 Thanos 연동 시 MockDataProvider 의존성 제거
    private final MockDataProvider mockDataProvider;
    private final AtomicReference<GpuDashboardThreshold> thresholds = new AtomicReference<>();

    // TODO:: 실제 Thanos 연동 시 GpuDashboardProperties와 MockDataProvider 생성자 주입 제거
    public GpuDashboardService(GpuDashboardProperties properties,
                               VictoriaMetricsClient metricsClient,
                               MockDataProvider mockDataProvider) {
        this.properties = properties;
        this.metricsClient = metricsClient;
        this.mockDataProvider = mockDataProvider;
    }

    // ── Controller 호출 메서드 ──────────────────────────────────

    public GpuDashboardResponse getDashboard(GpuDashboardFilter filter, OffsetDateTime from, OffsetDateTime to) {
        List<GpuDeviceMetrics> gpuMetrics = filteredGpuMetrics(filter);
        return new GpuDashboardResponse(
                summary(gpuMetrics),
                distribution(gpuMetrics, true),
                distribution(gpuMetrics, false),
                rankings(gpuMetrics, GpuMetric.GPU_UTILIZATION, 5, true),
                rankings(gpuMetrics, GpuMetric.VRAM_UTILIZATION, 5, true),
                rankings(gpuMetrics, GpuMetric.GPU_UTILIZATION, 5, false),
                rankings(gpuMetrics, GpuMetric.VRAM_UTILIZATION, 5, false),
                getThresholds(),
                // TODO:: 실제 Thanos 연동 시 데이터 출처를 THANOS로 고정하거나 응답 필드 제거
                properties.isMockEnabled() ? "MOCK" : "THANOS");
    }

    public GpuFilterOptionsResponse getFilterOptions() {
        List<GpuDeviceMetrics> gpuMetrics = loadGpuMetrics();
        List<VmOption> vms = new ArrayList<>(gpuMetrics.stream().filter(s -> s.vmId() != null)
                .collect(Collectors.toMap(GpuDeviceMetrics::vmId, s -> new VmOption(s.vmId(), s.vmName()),
                        (left, right) -> left, LinkedHashMap::new)).values());
        return new GpuFilterOptionsResponse(
                distinct(gpuMetrics, GpuDeviceMetrics::role),
                distinct(gpuMetrics, GpuDeviceMetrics::deploymentId),
                vms,
                distinct(gpuMetrics, GpuDeviceMetrics::gpuType),
                distinct(gpuMetrics, GpuDeviceMetrics::allocationType));
    }

    public GpuSummaryResponse getSummary(GpuDashboardFilter filter) {
        return summary(filteredGpuMetrics(filter));
    }

    public List<GpuDistributionResponse> getDistribution(GpuDashboardFilter filter, boolean byVm) {
        return distribution(filteredGpuMetrics(filter), byVm);
    }

    public List<GpuRankingResponse> getRankings(GpuDashboardFilter filter, GpuMetric metric, int limit, boolean byVm) {
        return rankings(filteredGpuMetrics(filter), metric, limit, byVm);
    }

    public List<GpuTrendResponse> getTrends(GpuDashboardFilter filter, GpuMetric metric, int limit,
                                            OffsetDateTime from, OffsetDateTime to, boolean byVm) {
        List<GpuDeviceMetrics> gpuMetrics = filteredGpuMetrics(filter);
        Set<String> topIds = rankings(gpuMetrics, metric, limit, byVm).stream()
                .map(GpuRankingResponse::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // TODO:: 실제 Thanos 연동 시 Mock 분기를 제거하고 liveTrends(...)를 바로 호출
        if (!properties.isMockEnabled()) {
            return liveTrends(gpuMetrics, topIds, metric, from, to, byVm);
        }

        return topIds.stream().map(id -> {
            List<GpuDeviceMetrics> group = gpuMetrics.stream().filter(s -> id.equals(byVm ? s.vmId() : s.gpuId())).toList();
            Map<OffsetDateTime, List<Double>> values = new LinkedHashMap<>();
            group.stream().flatMap(s -> s.history().stream())
                    .filter(p -> !p.time().isBefore(from) && !p.time().isAfter(to))
                    .forEach(p -> values.computeIfAbsent(p.time(), key -> new ArrayList<>())
                            .add(historyValue(p, metric, group.get(0).vramTotalMb())));
            List<DataPoint> points = values.entrySet().stream()
                    .map(e -> new DataPoint(e.getKey(), average(e.getValue())))
                    .toList();
            return new GpuTrendResponse(id, displayName(group.get(0), byVm), points);
        }).toList();
    }

    public GpuThresholdResponse getThresholds() {
        GpuDashboardThreshold value = currentThresholds();
        return new GpuThresholdResponse(value.idleGpuUtilization(), value.highGpuUtilization(),
                value.highVramUtilization());
    }

    public GpuThresholdResponse updateThresholds(UpdateGpuThresholdRequest request) {
        if (request.idleGpuUtilization() > request.highGpuUtilization()) {
            throw new IllegalArgumentException("유휴 GPU 임계치는 고활용 GPU 임계치보다 클 수 없습니다.");
        }
        thresholds.set(new GpuDashboardThreshold(request.idleGpuUtilization(), request.highGpuUtilization(),
                request.highVramUtilization()));
        return getThresholds();
    }

    // ── Service 내부 사용 메서드 ────────────────────────────────

    private List<GpuDeviceMetrics> filteredGpuMetrics(GpuDashboardFilter filter) {
        return loadGpuMetrics().stream()
                .filter(s -> matches(filter.roles(), s.role()))
                .filter(s -> matches(filter.deploymentIds(), s.deploymentId()))
                .filter(s -> matches(filter.vmIds(), s.vmId()))
                .filter(s -> matches(filter.gpuTypes(), s.gpuType()))
                .toList();
    }

    private GpuSummaryResponse summary(List<GpuDeviceMetrics> gpuMetrics) {
        GpuDashboardThreshold value = currentThresholds();
        long allocated = gpuMetrics.stream().filter(s -> s.vmId() != null).count();
        long idle = gpuMetrics.stream().filter(s -> s.gpuUtilization() <= value.idleGpuUtilization()).count();
        long high = gpuMetrics.stream().filter(s -> s.gpuUtilization() >= value.highGpuUtilization()
                && s.vramUtilization() >= value.highVramUtilization()).count();
        double used = gpuMetrics.stream().mapToDouble(GpuDeviceMetrics::vramUsedMb).sum();
        double total = gpuMetrics.stream().mapToDouble(GpuDeviceMetrics::vramTotalMb).sum();
        return new GpuSummaryResponse(allocated, idle, high, total == 0 ? 0.0 : used / total * 100.0,
                used, total, gpuMetrics.stream().mapToDouble(GpuDeviceMetrics::peakVramMb).max().orElse(0.0));
    }

    private List<GpuDistributionResponse> distribution(List<GpuDeviceMetrics> gpuMetrics, boolean byVm) {
        Function<GpuDeviceMetrics, String> key = byVm ? GpuDeviceMetrics::vmId : GpuDeviceMetrics::gpuType;
        return gpuMetrics.stream().filter(s -> key.apply(s) != null).collect(Collectors.groupingBy(key, LinkedHashMap::new,
                        Collectors.toList())).entrySet().stream().map(e -> {
            GpuDeviceMetrics first = e.getValue().get(0);
            return new GpuDistributionResponse(e.getKey(), byVm ? first.vmName() : first.gpuType(),
                    e.getValue().stream().mapToDouble(GpuDeviceMetrics::gpuUtilization).average().orElse(0),
                    e.getValue().stream().mapToDouble(GpuDeviceMetrics::vramUtilization).average().orElse(0),
                    e.getValue().size());
        }).toList();
    }

    private List<GpuRankingResponse> rankings(List<GpuDeviceMetrics> gpuMetrics, GpuMetric metric, int limit, boolean byVm) {
        Function<GpuDeviceMetrics, String> key = byVm ? GpuDeviceMetrics::vmId : GpuDeviceMetrics::gpuId;
        return gpuMetrics.stream().filter(s -> key.apply(s) != null).collect(Collectors.groupingBy(key, LinkedHashMap::new,
                        Collectors.toList())).entrySet().stream().map(e -> rankingRow(e.getKey(), e.getValue(), metric, byVm))
                .sorted(Comparator.comparingDouble(row -> -row.value()))
                .limit(limit).toList();
    }

    private GpuRankingResponse rankingRow(String id, List<GpuDeviceMetrics> group, GpuMetric metric, boolean byVm) {
        GpuDeviceMetrics first = group.get(0);
        return new GpuRankingResponse(id, displayName(first, byVm),
                group.stream().mapToDouble(s -> metricValue(s, metric)).average().orElse(0),
                metric == GpuMetric.VRAM_USED || metric == GpuMetric.PEAK_VRAM ? "MB" : "%",
                first.projectName(), first.credentialName(), first.vmId(), first.gpuType(), first.allocationType());
    }

    private List<GpuTrendResponse> liveTrends(List<GpuDeviceMetrics> gpuMetrics, Set<String> topIds, GpuMetric metric,
                                              OffsetDateTime from, OffsetDateTime to, boolean byVm) {
        // TODO:: 실제 Thanos 연동 시 VM에 여러 GPU가 할당된 경우를 반영하도록 PromQL 집계 기준 추가
        String promQl = rangeMetric(metric);
        // TODO:: 실제 Thanos 연동 시 최대 조회 기간, step, 타임아웃 및 캐시 정책 적용
        return metricsClient.queryRangeResults(promQl, from.toInstant(), to.toInstant(), step(from, to)).stream()
                .map(result -> toLiveTrend(result, topIds, byVm)).filter(Objects::nonNull).toList();
    }

    @SuppressWarnings("unchecked")
    private GpuTrendResponse toLiveTrend(Map<String, Object> result, Set<String> topIds, boolean byVm) {
        Map<String, String> labels = (Map<String, String>) result.getOrDefault("metric", Map.of());
        String id = byVm ? labels.get("vm_id") : gpuId(labels);
        if (id == null || !topIds.contains(id)) return null;
        List<List<Object>> raw = (List<List<Object>>) result.getOrDefault("values", List.of());
        List<DataPoint> points = raw.stream().map(value -> new DataPoint(
                OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(((Number) value.get(0)).longValue()),
                        java.time.ZoneOffset.UTC), Double.parseDouble(value.get(1).toString()))).toList();
        return new GpuTrendResponse(id, labels.getOrDefault(byVm ? "vm_name" : "UUID", id), points);
    }

    private List<GpuDeviceMetrics> loadGpuMetrics() {
        // TODO:: 실제 Thanos 연동 시 MockDataProvider 호출 분기 제거
        if (properties.isMockEnabled()) return mockDataProvider.getGpuDashboardDataset().gpus();
        return loadLiveGpuMetrics();
    }

    private List<GpuDeviceMetrics> loadLiveGpuMetrics() {
        Map<String, GpuDeviceMetricsBuilder> joined = new LinkedHashMap<>();
        // TODO:: 실제 Thanos 연동 시 운영 dcgm_exporter의 메트릭 이름과 수집 여부 확인
        mergeMetric(joined, "DCGM_FI_DEV_GPU_UTIL", GpuDeviceMetricsBuilder::gpuUtilization);
        mergeMetric(joined, "DCGM_FI_DEV_FB_USED", GpuDeviceMetricsBuilder::vramUsedMb);
        mergeMetric(joined, "DCGM_FI_DEV_FB_FREE", GpuDeviceMetricsBuilder::vramFreeMb);
        return joined.values().stream().map(GpuDeviceMetricsBuilder::build).toList();
    }

    @SuppressWarnings("unchecked")
    private void mergeMetric(Map<String, GpuDeviceMetricsBuilder> joined, String promQl,
                             java.util.function.BiConsumer<GpuDeviceMetricsBuilder, Double> setter) {
        for (Map<String, Object> result : metricsClient.queryResults(promQl)) {
            Map<String, String> labels = (Map<String, String>) result.getOrDefault("metric", Map.of());
            List<Object> value = (List<Object>) result.get("value");
            if (value == null || value.size() < 2) continue;
            GpuDeviceMetricsBuilder builder = joined.computeIfAbsent(
                    gpuId(labels), key -> new GpuDeviceMetricsBuilder(key, labels));
            setter.accept(builder, Double.parseDouble(value.get(1).toString()));
        }
    }

    private GpuDashboardThreshold currentThresholds() {
        GpuDashboardThreshold current = thresholds.get();
        if (current != null) return current;
        // TODO:: 실제 Thanos 연동 시 MockDataProvider 호출을 제거하고 임계치를 운영 DB 또는 설정 저장소에서 조회
        GpuDashboardThreshold initial = properties.isMockEnabled()
                ? mockDataProvider.getGpuDashboardDataset().thresholds()
                : DEFAULT_THRESHOLDS;
        thresholds.compareAndSet(null, initial == null ? DEFAULT_THRESHOLDS : initial);
        return thresholds.get();
    }

    private static class GpuDeviceMetricsBuilder {
        private final String id;
        private final Map<String, String> labels;
        private double gpuUtilization;
        private double vramUsedMb;
        private double vramFreeMb;

        private GpuDeviceMetricsBuilder(String id, Map<String, String> labels) { this.id = id; this.labels = labels; }
        void gpuUtilization(double value) { gpuUtilization = value; }
        void vramUsedMb(double value) { vramUsedMb = value; }
        void vramFreeMb(double value) { vramFreeMb = value; }
        GpuDeviceMetrics build() {
            // TODO:: 실제 Thanos 연동 시 VM/배포/역할/할당 유형 레이블을 자원 관리 DB 정보와 결합
            return new GpuDeviceMetrics(id, labels.get("gpu"), labels.get("UUID"), labels.get("Hostname"),
                    labels.get("role"), labels.get("deployment_id"), labels.get("vm_id"), labels.get("vm_name"),
                    labels.get("project_name"), labels.get("credential_name"), labels.get("modelName"),
                    labels.getOrDefault("allocation_type", "UNKNOWN"), gpuUtilization, vramUsedMb,
                    vramUsedMb + vramFreeMb, vramUsedMb, List.of());
        }
    }

    // ── 비즈니스 로직 단순 계산 메서드 ─────────────────────────

    private boolean matches(List<String> filters, String value) {
        return filters == null || filters.isEmpty()
                || value != null && filters.stream().anyMatch(value::equals);
    }

    private String gpuId(Map<String, String> labels) {
        // TODO:: 실제 Thanos 연동 시 UUID, Hostname, gpu 레이블의 실제 이름과 대소문자 확인
        return labels.getOrDefault("UUID", labels.getOrDefault("Hostname", "unknown") + ":" + labels.getOrDefault("gpu", "0"));
    }

    private List<String> distinct(List<GpuDeviceMetrics> gpuMetrics, Function<GpuDeviceMetrics, String> getter) {
        return gpuMetrics.stream().map(getter).filter(Objects::nonNull).distinct().sorted().toList();
    }

    private double metricValue(GpuDeviceMetrics gpuMetrics, GpuMetric metric) {
        return switch (metric) {
            case GPU_UTILIZATION -> gpuMetrics.gpuUtilization();
            case VRAM_UTILIZATION -> gpuMetrics.vramUtilization();
            case VRAM_USED -> gpuMetrics.vramUsedMb();
            case PEAK_VRAM -> gpuMetrics.peakVramMb();
        };
    }

    private double historyValue(GpuMetricHistory point, GpuMetric metric, double vramTotalMb) {
        return switch (metric) {
            case GPU_UTILIZATION -> point.gpuUtilization();
            case VRAM_USED, PEAK_VRAM -> point.vramUsedMb();
            case VRAM_UTILIZATION -> vramTotalMb == 0 ? 0.0 : point.vramUsedMb() / vramTotalMb * 100.0;
        };
    }

    private String rangeMetric(GpuMetric metric) {
        // TODO:: 실제 Thanos 연동 시 Peak VRAM은 조회 기간을 반영한 max_over_time PromQL로 분리
        return switch (metric) {
            case GPU_UTILIZATION -> "DCGM_FI_DEV_GPU_UTIL";
            case VRAM_USED, PEAK_VRAM -> "DCGM_FI_DEV_FB_USED";
            case VRAM_UTILIZATION -> "100 * DCGM_FI_DEV_FB_USED / (DCGM_FI_DEV_FB_USED + DCGM_FI_DEV_FB_FREE)";
        };
    }

    private String step(OffsetDateTime from, OffsetDateTime to) {
        long hours = java.time.Duration.between(from, to).toHours();
        return hours <= 6 ? "60s" : hours <= 48 ? "5m" : "1h";
    }

    private String displayName(GpuDeviceMetrics gpuMetrics, boolean byVm) {
        return byVm ? gpuMetrics.vmName() : gpuMetrics.gpuId();
    }

    private double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }
}
