package com.innogrid.aims.scheduler.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.scheduler.metrics.SchedulerMetricsGateway;
import com.innogrid.aims.scheduler.model.MdcPowerBudgets;
import com.innogrid.aims.scheduler.model.NodePowerSpecs;
import com.innogrid.aims.scheduler.repository.NodePowerSpecRepository;
import com.innogrid.aims.scheduler.metrics.VictoriaMetricsGateway;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.ExcludedNodeResponse;
import com.innogrid.aims.scheduler.dto.PowerOptimizationResponse;
import com.innogrid.aims.scheduler.dto.PowerOptimizationResponse.Cluster;
import com.innogrid.aims.scheduler.dto.PowerOptimizationResponse.Finding;
import com.innogrid.aims.scheduler.dto.PowerOptimizationResponse.Mdc;
import com.innogrid.aims.scheduler.dto.PowerOptimizationTrendResponse;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.model.ExcludedNode;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;

/**
 * 클러스터 전력 최적화 진단.
 *
 * <p>노드 하나의 효율은 {@link PowerEfficiencyService} 가 답한다. 이 서비스는 한 층 위에서
 * <b>클러스터 전체로 무엇을 할 수 있는지</b>를 답한다 — 몇 대가 놀고 있고 얼마를 반납할 수 있는가.
 * PR ② 에서 만든 {@code reclaimableIdleWatts} 가 배치 근거 문자열에만 찍히고 있었는데, 이 진단이
 * 그 값의 출구다.</p>
 *
 * <p>새 수식을 만들지 않는다. {@code PowerModel} 과 {@code PlacementContext} 가 이미 가진 계산을
 * 조합해 사람이 읽을 수 있는 진단으로 바꾼다.</p>
 */
@Service
public class PowerOptimizationService {

    private static final Logger log = LoggerFactory.getLogger(PowerOptimizationService.class);

    /** 이상 전력 곡선을 이 비율 이상 넘기면 낭비로 본다. */
    private static final double WASTE_THRESHOLD = 0.8;

    /** 전력 여유가 이보다 적으면 추가 배치가 어렵다고 본다(%). */
    private static final double LOW_HEADROOM_PERCENT = 20.0;

    static final String NO_POWER_METRIC =
            "전력 메트릭이 없어 최적화 진단을 낼 수 없습니다. IPMI exporter 연동을 확인하세요";

    private final SchedulingService schedulingService;
    private final PowerModel powerModel;
    private final SchedulerProperties properties;
    private final SchedulerProperties.Power props;
    private final SchedulerMetricsGateway metricsClient;
    private final MetricsQueryGuard guard;

    /** 노드 전력 사양 원장. null 이면(직접 조립한 테스트 경로) 정격은 설정값. */
    private final NodePowerSpecRepository powerSpecRepository;

    @Autowired
    public PowerOptimizationService(SchedulingService schedulingService,
                                    PowerModel powerModel,
                                    SchedulerProperties properties,
                                    SchedulerMetricsGateway metrics,
                                    MetricsQueryGuard guard,
                                    NodePowerSpecRepository powerSpecRepository) {
        this.schedulingService = schedulingService;
        this.powerModel = powerModel;
        this.properties = properties;
        this.props = properties.getPower();
        this.metricsClient = metrics;
        this.guard = guard;
        this.powerSpecRepository = powerSpecRepository;
    }

    /** 원장 없이 조립하는 형태 — 추세의 정격은 설정값. */
    public PowerOptimizationService(SchedulingService schedulingService,
                                    PowerModel powerModel,
                                    SchedulerProperties properties,
                                    SchedulerMetricsGateway metrics,
                                    MetricsQueryGuard guard) {
        this(schedulingService, powerModel, properties, metrics, guard, null);
    }

    /** 클라이언트를 직접 받는 형태(기존 테스트) — 기본 게이트웨이로 감싼다. null 이면 추세를 쓸 수 없다. */
    public PowerOptimizationService(SchedulingService schedulingService,
                                    PowerModel powerModel,
                                    SchedulerProperties properties,
                                    VictoriaMetricsClient metricsClient,
                                    MetricsQueryGuard guard) {
        this(schedulingService, powerModel, properties, VictoriaMetricsGateway.of(metricsClient), guard);
    }

    /** 스프링 없이 조립하는 형태(기존 단위 테스트) — 현재 시점 진단만 쓸 수 있고 추세는 저장소가 없어 호출할 수 없다. */
    public PowerOptimizationService(SchedulingService schedulingService,
                                    PowerModel powerModel,
                                    SchedulerProperties properties) {
        this(schedulingService, powerModel, properties, (SchedulerMetricsGateway) null, new MetricsQueryGuard(1));
    }

    /** 현재 노드 상태로 진단한다. 수집 단계에서 제외된 노드도 응답에 표기한다. */
    public PowerOptimizationResponse diagnoseCurrent() {
        PlacementContext snapshot = schedulingService.nodeSnapshot();
        return diagnose(snapshot.nodes(), snapshot.excludedNodes(), snapshot.mdcBudgets());
    }

    /**
     * 주어진 노드 스냅샷으로 진단한다(제외 노드 없음).
     * 테스트가 합성 클러스터를 넣을 수 있도록 열어 둔다 — 배포 환경은 노드가 1대뿐이라
     * 여러 노드가 섞인 판단을 실환경에서 검증할 수 없다.
     */
    public PowerOptimizationResponse diagnose(List<NodeResource> nodes) {
        return diagnose(nodes, List.of(), MdcPowerBudgets.EMPTY);
    }

    /**
     * 주어진 노드 스냅샷과 제외 노드 목록으로 진단한다.
     *
     * <p>제외 노드는 판단에 쓰지 않는다 — 판단할 값이 없어서 뺀 노드다. 하지만 응답에는 싣는다.
     * 그러지 않으면 {@code totalNodes} 가 조용히 줄어 "10대 중 1대 제외"가 "9대 클러스터"로 보인다.</p>
     */
    public PowerOptimizationResponse diagnose(List<NodeResource> nodes, List<ExcludedNode> excluded) {
        return diagnose(nodes, excluded, MdcPowerBudgets.EMPTY);
    }

    /**
     * 예산 원장까지 반영해 진단한다. MDC 별 요약에 예산·남은 여유·초과 여부가 함께 나간다.
     * 원장이 비어 있으면 요약은 나가되 예산 칸이 null 이다 — MDC 별 현황 자체는 예산과 무관하게 쓸모 있다.
     */
    public PowerOptimizationResponse diagnose(List<NodeResource> nodes, List<ExcludedNode> excluded,
                                              MdcPowerBudgets budgets) {
        OffsetDateTime now = OffsetDateTime.now();
        List<NodeResource> safe = nodes == null ? List.of() : nodes;
        List<ExcludedNode> safeExcluded = excluded == null ? List.of() : excluded;

        boolean measured = safe.stream().anyMatch(this::hasPower);
        Cluster cluster = cluster(safe, measured, safeExcluded);

        // 전력을 하나도 수집하지 못했으면 진단할 근거가 없다. 빈 배열과 사유를 돌려준다 —
        // 0 건이라고 "문제 없음" 으로 읽히면 안 되므로 사유를 클러스터에 함께 담는다.
        List<Finding> findings = measured ? findings(safe) : List.of();

        double saving = findings.stream()
                .map(Finding::estimatedSavingWatts)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).sum();

        return new PowerOptimizationResponse(now, cluster, mdcs(safe, budgets), findings,
                new PowerOptimizationResponse.Summary(
                        findings.size(), round(saving), percentOf(saving, cluster.totalPowerWatts())));
    }

    // ── 추세 — 과거 구간을 조회 시점에 계산 ─────────────────────────

    /**
     * 클러스터 전력 최적화 추세. {@code /optimization/power} 가 현재 시점에 계산하는 것을 메트릭 저장소의 구간 질의로
     * <b>과거 시각마다 다시 계산</b>한다. 저장하지 않는다 — 원시 시계열은 Thanos 에 이미 있고 우리는 조회 시점에 정제한다.
     *
     * <p>질의는 노드 수와 무관하게 <b>2회</b>다: CPU 사용률(스냅샷 수집과 같은 식, 대상 job)과 IPMI 전력을 instance 별로
     * 받는다. 시각마다 instance 별 임시 노드를 만들어 현재 진단과 같은 {@link PowerModel}(가동 판정·유휴 전력)과
     * PEI(CPU% ÷ W) 식을 적용한다 — 새 수식은 없다. 전력이 있는 노드가 없는 시각은 {@code measured=false} 이고 전력
     * 지표는 null 이다(0 으로 채우지 않는다). 저장소가 응답하지 않으면 예외를 올리지 않고 사유를 담은 빈 결과다.</p>
     */
    public PowerOptimizationTrendResponse trend(OffsetDateTime from, OffsetDateTime to, String step) {
        if (metricsClient == null) throw new IllegalStateException("메트릭 저장소 없이 조립된 서비스는 추세를 낼 수 없다");
        OffsetDateTime end = to != null ? to : OffsetDateTime.now();
        OffsetDateTime start = from != null ? from : end.minusHours(props.getTrendDefaultHours());
        String interval = (step == null || step.isBlank()) ? props.getTrendDefaultStep() : step.trim();
        TrendRangeSupport.validate(start, end, interval, props.getTrendMaxPoints());

        try {
            Map<String, Map<Long, Double>> cpu = seriesByInstance(String.format(
                    "100 - (avg by (instance) (irate(node_cpu_seconds_total{mode=\"idle\",job=\"%s\"}[5m])) * 100)",
                    properties.getNodeJob()), start, end, interval);
            // 전력은 job 라벨이 다를 수 있어 instance 로만 맞춘다(스냅샷 수집과 같은 방식). CPU 시계열에 없는 instance 는 무시.
            Map<String, Map<Long, Double>> power = seriesByInstance("ipmi_dcmi_power_consumption_watts", start, end, interval);

            if (cpu.isEmpty()) {
                return PowerOptimizationTrendResponse.empty(start, end, interval,
                        "해당 구간에 대상 노드의 CPU 메트릭이 없습니다");
            }
            TreeSet<Long> timestamps = new TreeSet<>();
            cpu.values().forEach(series -> timestamps.addAll(series.keySet()));

            // 정격 전력 원장은 시각마다가 아니라 추세 1회당 한 번만 읽는다(단계 20).
            NodePowerSpecs specs = powerSpecRepository == null
                    ? NodePowerSpecs.EMPTY : NodePowerSpecs.of(powerSpecRepository.findAll());

            List<PowerOptimizationTrendResponse.Point> points = new ArrayList<>();
            boolean anyPower = false;
            for (long ts : timestamps) {
                List<NodeResource> nodes = new ArrayList<>();
                for (Map.Entry<String, Map<Long, Double>> e : cpu.entrySet()) {
                    Double usage = e.getValue().get(ts);
                    if (usage == null) continue;
                    String instance = e.getKey();
                    double watts = power.getOrDefault(instance, Map.of()).getOrDefault(ts, 0.0);
                    nodes.add(new NodeResource("-", instance, 0, 0, 0, usage, 0, 0,
                            watts, specs.capacityOr(instance, powerModel::capacityOf), 0, 0, 0));
                }
                PowerOptimizationTrendResponse.Point point = pointOf(ts, nodes);
                anyPower |= point.measured();
                points.add(point);
            }
            String message = anyPower ? null
                    : "전력 메트릭이 없어 전력 지표를 계산하지 못했습니다. 가동/유휴 노드 수만 제공합니다 — IPMI exporter 연동을 확인하세요";
            return new PowerOptimizationTrendResponse(start, end, interval, points, message);

        } catch (RuntimeException e) {
            // 조회 실패로 500 을 내면 화면이 통째로 깨진다. 사유만 담아 빈 결과로 돌려준다(전력 효율 추세와 같은 원칙).
            log.warn("전력 최적화 추세 조회 실패: reason={}", e.getMessage());
            return PowerOptimizationTrendResponse.empty(start, end, interval, "메트릭 저장소를 조회하지 못했습니다: " + e.getMessage());
        }
    }

    /** 한 시각의 클러스터 상태 — 현재 진단의 {@link #cluster} 와 같은 식. */
    PowerOptimizationTrendResponse.Point pointOf(long epochSeconds, List<NodeResource> nodes) {
        int active = (int) nodes.stream().filter(powerModel::isActive).count();
        List<NodeResource> powered = nodes.stream().filter(this::hasPower).toList();
        boolean measured = !powered.isEmpty();
        Double total = measured ? round(nodes.stream().mapToDouble(NodeResource::getPowerWatts).sum()) : null;
        Double reclaimable = measured
                ? round(nodes.stream().filter(n -> !powerModel.isActive(n)).mapToDouble(powerModel::idlePower).sum()) : null;
        DoubleSummaryStatistics pei = powered.stream()
                .mapToDouble(n -> n.getCpuUsagePercent() / n.getPowerWatts()).summaryStatistics();
        return new PowerOptimizationTrendResponse.Point(
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC),
                nodes.size(), active, nodes.size() - active, total, reclaimable,
                measured ? round4(pei.getAverage()) : null,
                measured ? round4(pei.getMin()) : null,
                measured ? round4(pei.getMax()) : null,
                powered.size(), measured);
    }

    /** PEI(CPU% ÷ W)는 0.0067 같은 작은 값이라 전력 효율 요약처럼 소수 4자리로 둔다 — 2자리로 자르면 0.01 로 뭉개진다. */
    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** 구간 질의 결과를 instance → (시각 → 값) 으로 편다. 저장소 응답 형태: [{metric:{instance}, values:[[초,"값"],…]}, …] */
    private Map<String, Map<Long, Double>> seriesByInstance(String promQL, OffsetDateTime start, OffsetDateTime end, String step) {
        List<Map<String, Object>> results = guard.call(() -> metricsClient.queryRangeResults(
                promQL, start.toInstant(), end.toInstant(), step), properties.getMetrics().getQueryTimeoutMs());
        Map<String, Map<Long, Double>> out = new LinkedHashMap<>();
        for (Map<String, Object> series : results) {
            Object metric = series.get("metric");
            String instance = metric instanceof Map<?, ?> m && m.get("instance") != null ? m.get("instance").toString() : null;
            if (instance == null) continue;
            Object raw = series.get("values");
            if (!(raw instanceof List<?> values)) continue;
            Map<Long, Double> points = out.computeIfAbsent(instance, k -> new LinkedHashMap<>());
            for (Object point : values) {
                if (!(point instanceof List<?> pair) || pair.size() < 2) continue;
                try {
                    double v = Double.parseDouble(pair.get(1).toString());
                    if (Double.isNaN(v)) continue;
                    points.put((long) Double.parseDouble(pair.get(0).toString()), v);
                } catch (NumberFormatException ignored) {
                    // 저장소가 NaN 같은 값을 보낼 수 있다. 그 지점만 건너뛴다.
                }
            }
        }
        return out;
    }

    // ── 클러스터 현황 ───────────────────────────────────────────

    private Cluster cluster(List<NodeResource> nodes, boolean measured, List<ExcludedNode> excluded) {
        int active = (int) nodes.stream().filter(powerModel::isActive).count();
        double total = nodes.stream().mapToDouble(NodeResource::getPowerWatts).sum();
        double reclaimable = nodes.stream()
                .filter(n -> !powerModel.isActive(n))
                .mapToDouble(powerModel::idlePower).sum();

        return new Cluster(nodes.size(), active, nodes.size() - active,
                round(total), round(measured ? reclaimable : 0),
                measured, measured ? null : NO_POWER_METRIC,
                excluded.size(), excluded.stream().map(ExcludedNodeResponse::from).toList());
    }

    // ── MDC 별 현황 ────────────────────────────────────────────

    /**
     * 노드를 MDC 로 묶어 전력 현황을 낸다. MDC 식별자 오름차순이라 응답이 결정적이다.
     *
     * <p>전력을 <b>실측</b>과 <b>실효</b> 두 값으로 낸다. 실측은 클러스터 합계와 같은 기준이고, 실효는 예약분까지
     * 반영해 <b>C6 이 예산과 비교하는 기준선과 같다.</b> 남은 여유·초과 판정은 실효로 낸다 — 실측으로 내면
     * "여유가 있다"고 보여 주고도 실제 배치는 막히는 어긋남이 생긴다.</p>
     *
     * <p>계측 판정은 <b>C6 과 같은 술어</b>(실측 전력 &gt; 0)를 쓴다. 진단의 {@link #hasPower} 는 정격까지 요구하는데,
     * 그건 정격으로 나누는 진단 항목에 필요한 조건이지 "전력을 계측했는가"의 조건이 아니다. 두 술어가 갈리면
     * 지금 고치는 것과 같은 종류의 어긋남이 다시 생긴다.</p>
     *
     * <p>라벨이 없는 노드는 수집기가 {@code unknown} 으로 묶으므로 여기서도 한 그룹으로 나온다.</p>
     */
    private List<Mdc> mdcs(List<NodeResource> nodes, MdcPowerBudgets budgets) {
        Map<String, List<NodeResource>> byMdc = new java.util.TreeMap<>();
        for (NodeResource node : nodes) {
            byMdc.computeIfAbsent(node.getMdcId() == null ? "unknown" : node.getMdcId(),
                    k -> new ArrayList<>()).add(node);
        }

        List<Mdc> result = new ArrayList<>();
        for (Map.Entry<String, List<NodeResource>> entry : byMdc.entrySet()) {
            List<NodeResource> measuredNodes = entry.getValue().stream()
                    .filter(n -> n.getPowerWatts() > 0)     // C6 과 같은 술어
                    .toList();
            double total = measuredNodes.stream().mapToDouble(NodeResource::getPowerWatts).sum();
            double effective = measuredNodes.stream().mapToDouble(powerModel::effectivePowerWatts).sum();

            Double budget = budgets.budgetOf(entry.getKey()).stream().boxed().findFirst().orElse(null);
            Double remaining = budget == null ? null : round(budget - effective);
            result.add(new Mdc(entry.getKey(), entry.getValue().size(), measuredNodes.size(),
                    round(total), round(effective),
                    budget, remaining, remaining != null && remaining < 0));
        }
        return result;
    }

    // ── 진단 항목 ──────────────────────────────────────────────

    private List<Finding> findings(List<NodeResource> nodes) {
        List<Finding> found = new ArrayList<>();
        for (NodeResource node : nodes) {
            if (!hasPower(node)) continue;

            idleNode(node).ifPresent(found::add);
            powerWaste(node).ifPresent(found::add);
            lowHeadroom(node).ifPresent(found::add);
        }
        // 절감이 큰 항목이 위로 오게 한다. 화면이 무엇부터 봐야 할지 정해 준다.
        found.sort(Comparator.comparingDouble(
                (Finding f) -> f.estimatedSavingWatts() == null ? 0 : f.estimatedSavingWatts()).reversed());
        return found;
    }

    /** 놀고 있는데 유휴 전력을 계속 쓰는 노드. 반납하면 그 전력을 통째로 아낀다. */
    private java.util.Optional<Finding> idleNode(NodeResource node) {
        if (powerModel.isActive(node)) return java.util.Optional.empty();

        double idle = powerModel.idlePower(node);
        return java.util.Optional.of(new Finding(
                "IDLE_NODE", "HIGH", node.getMdcId(), node.getNodeId(),
                "유휴 노드가 전력을 소모합니다",
                String.format("CPU %.1f%% 로 사실상 비어 있으나 %.0fW 를 소모합니다",
                        node.getCpuUsagePercent(), node.getPowerWatts()),
                round(idle),
                "워크로드를 통합하고 노드를 반납하세요"));
    }

    /** 부하에 비해 전력을 과하게 쓰는 노드. 이상 곡선을 넘긴 만큼이 낭비다. */
    private java.util.Optional<Finding> powerWaste(NodeResource node) {
        double waste = powerModel.wasteScore(node);
        if (waste >= WASTE_THRESHOLD) return java.util.Optional.empty();

        double over = (1.0 - waste) * node.getPowerCapacityWatts();
        return java.util.Optional.of(new Finding(
                "POWER_WASTE", over > node.getPowerCapacityWatts() * 0.3 ? "HIGH" : "MEDIUM",
                node.getMdcId(), node.getNodeId(),
                "부하에 비해 전력을 많이 씁니다",
                String.format("CPU %.1f%% 인데 %.0fW 를 소모해 이상 곡선을 %.0fW 넘겼습니다",
                        node.getCpuUsagePercent(), node.getPowerWatts(), over),
                round(over),
                "전원 설정과 유휴 전력을 점검하세요"));
    }

    /**
     * 전력 여유가 부족한 노드.
     * 절감 항목이 아니라 <b>배치 제약</b>이라 {@code estimatedSavingWatts} 를 비운다 —
     * 여기서 숫자를 채우면 절감 합계가 부풀려진다.
     */
    private java.util.Optional<Finding> lowHeadroom(NodeResource node) {
        double headroom = node.powerHeadroomPercent();
        if (headroom >= LOW_HEADROOM_PERCENT) return java.util.Optional.empty();

        return java.util.Optional.of(new Finding(
                "LOW_HEADROOM", "HIGH", node.getMdcId(), node.getNodeId(),
                "전력 여유가 부족합니다",
                String.format("용량 %.0fW 중 %.0fW 를 써 여유가 %.1f%% 뿐입니다",
                        node.getPowerCapacityWatts(), node.getPowerWatts(), headroom),
                null,
                "추가 배치를 제한하고 워크로드를 분산하세요"));
    }

    // ── 공통 ───────────────────────────────────────────────────

    boolean hasPower(NodeResource node) {
        return node.getPowerWatts() > 0 && node.getPowerCapacityWatts() > 0;
    }

    /** 총 전력이 0 이면 비율을 낼 수 없다. 0% 로 채우면 "절감 없음" 으로 읽힌다. */
    static Double percentOf(double value, double total) {
        if (total <= 0) return null;
        return round(value / total * 100);
    }

    static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
