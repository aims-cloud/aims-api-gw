package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.common.util.ListSort;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.ClocksEventReason;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuClocksEventsResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuClocksEventsResponse.GpuClocksEvents;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuCollectState;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuCollectStatusResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuCollectStatusResponse.GpuCollectStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuDeviceCollectStatusResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuDeviceCollectStatusResponse.MetricCollectStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuDeviceInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuDeviceListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuDeviceSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuGroupSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuHealthLevel;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuHealthSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuHealthSummaryResponse.HealthCard;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuMetricTrendResponse.Series;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuPcieReplayResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuPcieReplayResponse.GpuCounter;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuPcieReplayResponse.TrendSeries;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuXidErrorsResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.GpuPanelAssembler;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostGpuMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostGpuMetricsQueryService.GpuHealth;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostGpuMetricsQueryService.GpuSample;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.springframework.stereotype.Service;

import static com.innogrid.aims.common.util.MetricFormat.mbToGb;
import static com.innogrid.aims.common.util.MetricFormat.round1;
import static com.innogrid.aims.common.util.MetricFormat.toOffsetDateTime;
import static com.innogrid.aims.monitoring.service.resourceexplorer.tree.GpuPanelAssembler.gpuName;

/**
 * 자원 탐색기 노드 GPU 카테고리 패널 조회 서비스.
 * 그룹(GPU 전체 집계)과 개별 GPU 의 개요(KPI·상태 요약·목록/정보)·모니터링 탭 데이터를 함께 제공한다.
 *
 * nodeId 는 assets.id 이며 역할 스코프 밖이면 404({@link NodeAccessService}).
 * 지표는 노드에 등록된 dcgm_exporter 수집 대상(monitoring_target)의 {@code instance} 라벨로 좁혀 읽는다({@link HostGpuMetricsQueryService}).
 * dcgm_exporter 가 등록되지 않은 노드는 Thanos 를 조회하지 않고 미수집(0·빈 목록·빈 시리즈)으로 내린다.
 * 개별 GPU 는 gpuId(장치 UUID)로 식별하며, 최근 24시간 안에 수집된 적 없는 GPU 의 개요·정보·수집 상태는 404 다.
 * 수집 상태는 기대 지표가 전부 수집되면 HEALTHY, 하나라도 빠지면(전부 끊긴 경우 포함) FAILED 다.
 * 상태 요약은 GPU 1장마다 수집·ECC DBE·PCIe Replay·Clock 이벤트 단계를 구하고 그룹은 가장 나쁜 단계를 쓴다(GpuHealthSummaryResponse 참고).
 * XID 오류 상세는 오류 번호별 해석 자료와 발생 이력 적재가 필요해 아직 501 이다.
 * 노드 상세 패널은 ResourceExplorerNodeService 가 담당한다.
 */
@Service
public class ResourceExplorerNodeGpuService {

    /**
     * sort 파라미터("{필드},{asc|desc}") — gpuName(인덱스 숫자순)|gpuId|model|gpuUtilCurrentPercent|gpuUtilAvgPercent|
     * vramUtilCurrentPercent|vramUtilAvgPercent|vramUsedGb|vramTotalGb|temperatureCelsius|powerWatts|recentAlarmCount.
     * 값 없음은 방향과 무관하게 마지막, 동률은 GPU 이름순. 미지원 필드는 400 — 호출 측은 Thanos 조회 전에 먼저 부른다.
     */
    private static final ListSort.Spec<GpuDeviceListItemResponse> SORT = ListSort.spec(
            Map.ofEntries(
                    Map.entry("gpuName", ListSort.by(GpuDeviceListItemResponse::gpuName, GpuPanelAssembler::compareGpuName)),
                    Map.entry("gpuId", ListSort.by(GpuDeviceListItemResponse::gpuId)),
                    Map.entry("model", ListSort.by(GpuDeviceListItemResponse::model)),
                    Map.entry("gpuUtilCurrentPercent", ListSort.by(GpuDeviceListItemResponse::gpuUtilCurrentPercent)),
                    Map.entry("gpuUtilAvgPercent", ListSort.by(GpuDeviceListItemResponse::gpuUtilAvgPercent)),
                    Map.entry("vramUtilCurrentPercent", ListSort.by(GpuDeviceListItemResponse::vramUtilCurrentPercent)),
                    Map.entry("vramUtilAvgPercent", ListSort.by(GpuDeviceListItemResponse::vramUtilAvgPercent)),
                    Map.entry("vramUsedGb", ListSort.by(GpuDeviceListItemResponse::vramUsedGb)),
                    Map.entry("vramTotalGb", ListSort.by(GpuDeviceListItemResponse::vramTotalGb)),
                    Map.entry("temperatureCelsius", ListSort.by(GpuDeviceListItemResponse::temperatureCelsius)),
                    Map.entry("powerWatts", ListSort.by(GpuDeviceListItemResponse::powerWatts)),
                    Map.entry("recentAlarmCount", ListSort.by(GpuDeviceListItemResponse::recentAlarmCount))),
            Comparator.comparing(GpuDeviceListItemResponse::gpuName, GpuPanelAssembler::compareGpuName));

    private static final Duration PCIE_TREND_WINDOW = Duration.ofHours(24);

    private final NodeAccessService nodeAccess;
    private final HostGpuMetricsQueryService metrics;

    public ResourceExplorerNodeGpuService(NodeAccessService nodeAccess, HostGpuMetricsQueryService metrics) {
        this.nodeAccess = nodeAccess;
        this.metrics = metrics;
    }

    // ── 그룹 (트리에서 GPU 카테고리 선택) ───────────────────────

    /** 그룹 개요 KPI 카드 4종 — 총 GPU 수 / VRAM Util(사용·전체) / Power(합산·Peak) / 최대 온도. 수집 중인 GPU 가 없으면 값은 null */
    public GpuGroupSummaryResponse getGroupSummary(String nodeId) {
        List<GpuSample> gpus = devices(nodeAccess.dcgmSelector(nodeId));
        List<GpuSample> collecting = gpus.stream().filter(GpuSample::collecting).toList();
        // VRAM 은 사용·여유가 둘 다 있는 GPU 만 — 한쪽만 있으면 사용률 분자·분모가 어긋난다
        List<GpuSample> withVram = collecting.stream().filter(g -> g.fbTotalMb() != null).toList();
        Double usedMb = sum(withVram, GpuSample::fbUsedMb);
        Double totalMb = sum(withVram, GpuSample::fbTotalMb);
        Double vramPercent = usedMb == null || totalMb == null || totalMb <= 0 ? null : usedMb / totalMb * 100;
        return new GpuGroupSummaryResponse(
                gpus.size(),
                round1(vramPercent),
                mbToGb(usedMb),
                mbToGb(totalMb),
                round1(sum(collecting, GpuSample::powerWatts)),
                round1(max(collecting, GpuSample::powerWatts)),
                round1(max(collecting, GpuSample::temperatureC)));
    }

    /** 그룹 상태 요약 — 이상 감지 배너 + 수집 상태/ECC DBE/PCIe Replay/Clocks Event 카드 */
    public GpuHealthSummaryResponse getGroupHealth(String nodeId) {
        MetricSelector selector = nodeAccess.dcgmSelector(nodeId);
        Map<String, GpuHealth> health = health(selector);
        return healthSummary(devices(selector).stream().map(g -> judge(g, health.get(g.uuid()))).toList(), false);
    }

    /** 그룹 개요 GPU 목록 — 현재/최근 1시간 평균 Util, VRAM, 온도, 전력. 페이징(1부터)·컬럼 정렬 */
    public PageResponse<GpuDeviceListItemResponse> getDevices(String nodeId,
                                                              int page, int size, String sort) {
        Comparator<GpuDeviceListItemResponse> comparator = SORT.comparator(sort);   // 미지원 필드는 조회 전에 400
        List<GpuDeviceListItemResponse> rows = devices(nodeAccess.dcgmSelector(nodeId)).stream()
                .map(ResourceExplorerNodeGpuService::row)
                .sorted(comparator)
                .toList();
        return PageResponse.of(rows, page, size);
    }

    /** 그룹 모니터링 차트 — GPU 별 분해 시계열 */
    public GpuMetricTrendResponse getGroupMetricTrend(String nodeId, GpuMetric metric,
                                                      Instant from, Instant to, String step) {
        return trend(nodeId, null, metric, from, to, step);
    }

    // ── 개별 GPU (트리에서 GPU 장치 선택) ───────────────────────

    /** 개별 GPU 개요 KPI 카드 6종 — Util / VRAM Util / VRAM 사용·전체 / Power / 온도 / Fan. 지금 수집되지 않는 값은 null */
    public GpuDeviceSummaryResponse getDeviceSummary(String nodeId, String gpuId) {
        return GpuPanelAssembler.deviceSummary(requireDevice(nodeId, gpuId));
    }

    /** 개별 GPU 상태 요약 — 그룹과 동일 구조, 해당 GPU 1장 기준 */
    public GpuHealthSummaryResponse getDeviceHealth(String nodeId, String gpuId) {
        MetricSelector selector = nodeAccess.dcgmSelector(nodeId);
        GpuSample gpu = requireDevice(selector, gpuId);
        return healthSummary(List.of(judge(gpu, health(selector).get(gpu.uuid()))), true);
    }

    /** 개별 GPU 정보 표 — 모델/UUID/PCI/드라이버/수집 상태/마지막 수집. CUDA·NVML·VBIOS·P-State 는 수집 항목에 없어 null */
    public GpuDeviceInfoResponse getDeviceInfo(String nodeId, String gpuId) {
        GpuSample gpu = requireDevice(nodeId, gpuId);
        return new GpuDeviceInfoResponse(
                gpu.model(),
                gpu.uuid(),
                gpu.pciBusId(),
                null,
                gpu.driverVersion(),
                null,
                null,
                null,
                GpuPanelAssembler.collectStatus(gpu),
                toOffsetDateTime(gpu.lastCollectedAt()));
    }

    /** 개별 GPU 모니터링 차트 — 해당 GPU 시계열 */
    public GpuMetricTrendResponse getDeviceMetricTrend(String nodeId, String gpuId, GpuMetric metric,
                                                       Instant from, Instant to, String step) {
        return trend(nodeId, gpuId, metric, from, to, step);
    }

    // ── 상태 요약 카드 상세 패널 ────────────────────────────────

    /** 수집 상태 상세 (그룹) — 요약 표 + GPU별 수집 상태 카드 (HEALTHY/FAILED) */
    public GpuCollectStatusResponse getCollectStatus(String nodeId) {
        List<GpuSample> gpus = devices(nodeAccess.dcgmSelector(nodeId));
        int total = HostGpuMetricsQueryService.EXPECTED_METRICS.size();
        List<GpuCollectStatus> cards = gpus.stream()
                .map(g -> new GpuCollectStatus(g.uuid(), g.displayName(), g.model(), state(g),
                        toOffsetDateTime(g.lastCollectedAt()), collectedCount(g), total))
                .toList();
        int healthy = (int) cards.stream().filter(c -> c.state() == GpuCollectState.HEALTHY).count();
        Instant last = gpus.stream().map(GpuSample::lastCollectedAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        return new GpuCollectStatusResponse(gpus.size(), healthy, gpus.size() - healthy, toOffsetDateTime(last),
                HostGpuMetricsQueryService.COLLECT_INTERVAL_SECONDS, cards);
    }

    /** 수집 상태 상세 (개별 GPU) — 요약 표 + 기대 지표별 지금 수집 여부 체크리스트 */
    public GpuDeviceCollectStatusResponse getDeviceCollectStatus(String nodeId, String gpuId) {
        GpuSample gpu = requireDevice(nodeId, gpuId);
        List<MetricCollectStatus> checklist = HostGpuMetricsQueryService.EXPECTED_METRICS.stream()
                .map(name -> new MetricCollectStatus(name, gpu.collectedMetrics().contains(name)))
                .toList();
        return new GpuDeviceCollectStatusResponse(gpu.uuid(), gpu.displayName(), gpu.model(), state(gpu),
                toOffsetDateTime(gpu.lastCollectedAt()), HostGpuMetricsQueryService.COLLECT_INTERVAL_SECONDS,
                collectedCount(gpu), checklist.size(), checklist);
    }

    /** XID Errors 상세 — 발생 이벤트 + 컨텍스트 + XID 지식 베이스 해석. gpuId 미지정 시 그룹 전체 */
    public GpuXidErrorsResponse getXidErrors(String nodeId, String gpuId) {
        // TODO:: XID 이벤트 수집 경로(dmesg/DCGM 이벤트) 확정 — 발생 시점 워크로드 스냅샷 적재 포함
        // TODO:: XID 번호별 해석 지식 베이스(제목·원인 태그·조치 안내) 구축 — NVIDIA XID 문서 기반
        throw dataSourceNotImplemented();
    }

    /** PCIe Replay Counter 상세 — GPU별 누적/1h/24h 증가와 최근 24시간 증가 추이(회/h). gpuId 미지정 시 그룹 전체 */
    public GpuPcieReplayResponse getPcieReplay(String nodeId, String gpuId) {
        MetricSelector selector = nodeAccess.dcgmSelector(nodeId);
        List<GpuSample> gpus = targets(selector, gpuId);
        Map<String, GpuHealth> health = health(selector);
        List<GpuCounter> counters = gpus.stream().map(g -> {
            GpuHealth h = health.get(g.uuid());
            return h == null
                    ? new GpuCounter(g.uuid(), g.displayName(), null, null, null)
                    : new GpuCounter(g.uuid(), g.displayName(),
                            count(h.pcieReplayTotal()), count(h.pcieReplay1h()), count(h.pcieReplay24h()));
        }).toList();

        Instant to = Instant.now();
        List<TrendSeries> trend = selector == null || gpus.isEmpty() ? List.of()
                : metrics.pcieReplayTrend(selector, blankToNull(gpuId), to.minus(PCIE_TREND_WINDOW), to).stream()
                        .map(t -> new TrendSeries(t.uuid(), gpuName(t.index(), t.uuid()), t.points()))
                        .toList();
        return new GpuPcieReplayResponse(counters, trend);
    }

    /** Clocks Event Reasons 상세 — GPU별 활성 이벤트와 안내 텍스트. gpuId 미지정 시 그룹 전체, 지표가 없으면 activeReasons null */
    public GpuClocksEventsResponse getClocksEvents(String nodeId, String gpuId) {
        MetricSelector selector = nodeAccess.dcgmSelector(nodeId);
        List<GpuSample> gpus = targets(selector, gpuId);
        Map<String, GpuHealth> health = health(selector);
        return new GpuClocksEventsResponse(gpus.stream().map(g -> {
            GpuHealth h = health.get(g.uuid());
            if (h == null || h.clocksMask() == null) {
                return new GpuClocksEvents(g.uuid(), g.displayName(), g.model(), null, null,
                        "Clock 이벤트 지표(DCGM_FI_DEV_CLOCKS_EVENT_REASONS)가 수집되지 않습니다.");
            }
            List<ClocksEventReason> reasons = ClocksEventReason.fromMask(h.clocksMask());
            return new GpuClocksEvents(g.uuid(), g.displayName(), g.model(), clocksLevel(reasons), reasons,
                    clocksGuide(reasons));
        }).toList());
    }

    // ── 조회 대상 ───────────────────────────────────────────────

    private List<GpuSample> devices(MetricSelector selector) {
        return selector == null ? List.of() : metrics.devices(selector);
    }

    private Map<String, GpuHealth> health(MetricSelector selector) {
        return selector == null ? Map.of() : metrics.health(selector);
    }

    /** 상세 패널 대상 — gpuId 가 없으면 노드의 GPU 전체, 있으면 그 GPU 1장(없으면 404) */
    private List<GpuSample> targets(MetricSelector selector, String gpuId) {
        return blankToNull(gpuId) == null ? devices(selector) : List.of(requireDevice(selector, gpuId));
    }

    private GpuSample requireDevice(String nodeId, String gpuId) {
        return requireDevice(nodeAccess.dcgmSelector(nodeId), gpuId);
    }

    /** 최근 24시간 안에 수집된 GPU 만 개별 패널을 연다 — dcgm_exporter 가 없거나 해당 UUID 가 없으면 404 */
    private GpuSample requireDevice(MetricSelector selector, String gpuId) {
        return GpuPanelAssembler.requireDevice(devices(selector), gpuId);
    }

    private GpuMetricTrendResponse trend(String nodeId, String gpuId, GpuMetric metric,
                                         Instant from, Instant to, String step) {
        if (!from.isBefore(to)) {
            throw BusinessException.badRequest("조회 시작 시각은 종료 시각보다 앞서야 합니다.");
        }
        MetricSelector selector = nodeAccess.dcgmSelector(nodeId);
        List<Series> series = selector == null ? List.of()
                : GpuPanelAssembler.series(metrics.trend(metric, selector, gpuId, from, to, step));
        return new GpuMetricTrendResponse(metric, metric.unit(), from.getEpochSecond(), to.getEpochSecond(), step, series);
    }

    // ── 행·정렬 ─────────────────────────────────────────────────

    static GpuDeviceListItemResponse row(GpuSample g) {
        return new GpuDeviceListItemResponse(
                g.uuid(),
                g.displayName(),
                g.model(),
                round1(g.utilization()),
                round1(g.utilizationAvg()),
                round1(g.vramPercent()),
                round1(g.vramPercentAvg()),
                mbToGb(g.fbUsedMb()),
                mbToGb(g.fbTotalMb()),
                round1(g.temperatureC()),
                round1(g.powerWatts()),
                null);
    }

    // ── 상태 요약 판정 ──────────────────────────────────────────

    /** GPU 1장의 카드별 단계 — 지표가 수집되지 않는 카드는 null */
    record Judgement(GpuSample gpu, GpuHealth health, GpuHealthLevel collect, GpuHealthLevel ecc,
                     GpuHealthLevel pcie, GpuHealthLevel clocks) {
        GpuHealthLevel worst() {
            return ResourceExplorerNodeGpuService.worst(Arrays.asList(collect, ecc, pcie, clocks));
        }
    }

    /** 수집: 7종 전부 NORMAL·일부 WARNING·전부 끊김 CRITICAL / ECC DBE 1건 이상 CRITICAL / PCIe 1시간 증가 WARNING / Clock 이벤트는 사유별 단계 */
    static Judgement judge(GpuSample g, GpuHealth h) {
        int collected = collectedCount(g);
        GpuHealthLevel collect = collected == HostGpuMetricsQueryService.EXPECTED_METRICS.size() ? GpuHealthLevel.NORMAL
                : collected == 0 ? GpuHealthLevel.CRITICAL : GpuHealthLevel.WARNING;
        GpuHealthLevel ecc = h == null || h.eccDbe() == null ? null
                : count(h.eccDbe()) > 0 ? GpuHealthLevel.CRITICAL : GpuHealthLevel.NORMAL;
        GpuHealthLevel pcie = h == null || h.pcieReplay1h() == null ? null
                : count(h.pcieReplay1h()) > 0 ? GpuHealthLevel.WARNING : GpuHealthLevel.NORMAL;
        GpuHealthLevel clocks = h == null || h.clocksMask() == null ? null
                : clocksLevel(ClocksEventReason.fromMask(h.clocksMask()));
        return new Judgement(g, h, collect, ecc, pcie, clocks);
    }

    /** 카드·배너 조립 — 그룹은 GPU 별 단계 중 가장 나쁜 것, 개별은 그 GPU 1장 */
    private static GpuHealthSummaryResponse healthSummary(List<Judgement> judgements, boolean single) {
        List<Judgement> anomalies = judgements.stream().filter(j -> j.worst() != GpuHealthLevel.NORMAL).toList();
        GpuHealthLevel anomalyLevel = worst(judgements.stream().map(Judgement::worst).toList());

        HealthCard collectCard;
        if (judgements.isEmpty()) {
            collectCard = new HealthCard(null, null);
        } else if (judgements.stream().allMatch(j -> j.collect() == GpuHealthLevel.CRITICAL)) {
            collectCard = new HealthCard(GpuHealthLevel.CRITICAL, null);
        } else if (judgements.stream().allMatch(j -> j.collect() == GpuHealthLevel.NORMAL)) {
            collectCard = new HealthCard(GpuHealthLevel.NORMAL, null);
        } else {
            collectCard = new HealthCard(GpuHealthLevel.WARNING, null);
        }
        HealthCard eccCard = card(judgements, Judgement::ecc, j -> count(j.health().eccDbe()));
        HealthCard pcieCard = card(judgements, Judgement::pcie, j -> count(j.health().pcieReplay1h()));
        HealthCard clocksCard = card(judgements, Judgement::clocks, j -> j.clocks() == GpuHealthLevel.NORMAL ? 0L : 1L);

        return new GpuHealthSummaryResponse(anomalyLevel, anomalies.size(),
                anomalies.isEmpty() ? null : anomalyMessage(anomalies, single),
                collectCard, eccCard, pcieCard, clocksCard);
    }

    /** 지표가 수집되는 GPU 만으로 카드 1개 — 하나도 없으면 미수집(level·count null) */
    private static HealthCard card(List<Judgement> judgements, Function<Judgement, GpuHealthLevel> level,
                                   Function<Judgement, Long> count) {
        List<Judgement> measured = judgements.stream().filter(j -> level.apply(j) != null).toList();
        if (measured.isEmpty()) {
            return new HealthCard(null, null);
        }
        long total = measured.stream().mapToLong(count::apply).sum();
        return new HealthCard(worst(measured.stream().map(level).toList()), (int) Math.min(total, Integer.MAX_VALUE));
    }

    /** 배너 문구 — 그룹 "GPU 2장에서 이상 징후 감지 — ECC DBE 오류 1장, PCIe Replay 증가 1장", 개별은 사유만 */
    private static String anomalyMessage(List<Judgement> anomalies, boolean single) {
        List<String> parts = new ArrayList<>();
        addPart(parts, anomalies, Judgement::collect, "수집 끊김", single);
        addPart(parts, anomalies, Judgement::ecc, "ECC DBE 오류", single);
        addPart(parts, anomalies, Judgement::pcie, "PCIe Replay 증가", single);
        addPart(parts, anomalies, Judgement::clocks, "Clock 제한", single);
        String head = single ? "이상 징후 감지" : "GPU " + anomalies.size() + "장에서 이상 징후 감지";
        return head + " — " + String.join(", ", parts);
    }

    private static void addPart(List<String> parts, List<Judgement> anomalies,
                                Function<Judgement, GpuHealthLevel> level, String label, boolean single) {
        long n = anomalies.stream().map(level).filter(l -> l != null && l != GpuHealthLevel.NORMAL).count();
        if (n > 0) {
            parts.add(single ? label : label + " " + n + "장");
        }
    }

    /** 활성 사유 중 가장 나쁜 단계 — 사유가 없으면 NORMAL */
    private static GpuHealthLevel clocksLevel(List<ClocksEventReason> reasons) {
        return worst(reasons.stream().map(ClocksEventReason::level).toList());
    }

    /** 주의·위험 사유의 안내를 줄바꿈으로 — 그런 사유가 없으면 상태 한 줄 */
    private static String clocksGuide(List<ClocksEventReason> reasons) {
        List<String> guides = reasons.stream().filter(r -> r.level() != GpuHealthLevel.NORMAL)
                .map(ClocksEventReason::guide).toList();
        if (!guides.isEmpty()) {
            return String.join("\n", guides);
        }
        return reasons.isEmpty() ? "활성 Clock 이벤트가 없습니다." : "정상 범위의 Clock 이벤트만 있습니다.";
    }

    /** null 은 건너뛰고 CRITICAL > WARNING > NORMAL 중 가장 나쁜 단계(GpuHealthLevel 선언 순서). 전부 null 이면 NORMAL */
    static GpuHealthLevel worst(List<GpuHealthLevel> levels) {
        return levels.stream().filter(Objects::nonNull)
                .max(Comparator.comparingInt(GpuHealthLevel::ordinal))
                .orElse(GpuHealthLevel.NORMAL);
    }

    // ── 판정·단위 ───────────────────────────────────────────────

    /** 누적·증가 카운터 값을 건수로 — increase() 의 외삽 소수는 반올림. null 은 null */
    private static Long count(Double value) {
        return value == null ? null : Math.max(0L, Math.round(value));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static GpuCollectState state(GpuSample g) {
        return collectedCount(g) == HostGpuMetricsQueryService.EXPECTED_METRICS.size()
                ? GpuCollectState.HEALTHY : GpuCollectState.FAILED;
    }

    private static int collectedCount(GpuSample g) {
        return (int) HostGpuMetricsQueryService.EXPECTED_METRICS.stream().filter(g.collectedMetrics()::contains).count();
    }

    private static Double sum(List<GpuSample> gpus, Function<GpuSample, Double> value) {
        List<Double> values = gpus.stream().map(value).filter(Objects::nonNull).toList();
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).sum();
    }

    private static Double max(List<GpuSample> gpus, Function<GpuSample, Double> value) {
        return gpus.stream().map(value).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }

    private static BusinessException dataSourceNotImplemented() {
        return BusinessException.notImplemented("GPU 지표 데이터와 자원 탐색기 GPU 패널의 매핑 구현 전입니다.");
    }
}
