package com.innogrid.aims.scheduler.service;

import java.time.Duration;
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
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
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

/**
 * 클러스터 전력 최적화 진단.
 *
 * <p>노드 하나의 효율은 {@link PowerEfficiencyService}가 답한다. 이 서비스는 한 층 위에서
 * <b>클러스터 전체로 무엇을 할 수 있는지</b>를 답한다 — 몇 대가 놀고 있고 얼마를 반납할 수 있는가.
 * {@code PlacementContext.reclaimableIdleWatts}가 배치 근거 문자열에만 찍히고 있었는데, 이 진단이
 * 그 값의 출구다.</p>
 *
 * <p>새 수식을 만들지 않는다. {@code PowerModel}과 {@code PlacementContext}가 이미 가진 계산을
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

    /** 노드 전력 사양 원장. null이면(직접 조립한 테스트 경로) 정격은 설정값. */
    private final NodePowerSpecRepository powerSpecRepository;

    /** 익스포터 instance 해석. 원장 없이 조립하면 호스트명 일치와 그대로만 쓴다. */
    private final ExporterInstanceResolver exporterResolver;

    @Autowired
    public PowerOptimizationService(SchedulingService schedulingService,
                                    PowerModel powerModel,
                                    SchedulerProperties properties,
                                    SchedulerMetricsGateway metrics,
                                    MetricsQueryGuard guard,
                                    NodePowerSpecRepository powerSpecRepository,
                                    ExporterInstanceResolver exporterResolver) {
        this.schedulingService = schedulingService;
        this.powerModel = powerModel;
        this.properties = properties;
        this.props = properties.getPower();
        this.metricsClient = metrics;
        this.guard = guard;
        this.powerSpecRepository = powerSpecRepository;
        this.exporterResolver = exporterResolver != null ? exporterResolver
                : new ExporterInstanceResolver(metrics, guard, properties);
    }

    /** 원장 없이 조립하는 형태 — 추세의 정격은 설정값, 익스포터 해석은 호스트명 일치와 그대로만. */
    public PowerOptimizationService(SchedulingService schedulingService,
                                    PowerModel powerModel,
                                    SchedulerProperties properties,
                                    SchedulerMetricsGateway metrics,
                                    MetricsQueryGuard guard,
                                    NodePowerSpecRepository powerSpecRepository) {
        this(schedulingService, powerModel, properties, metrics, guard, powerSpecRepository, null);
    }

    /** 원장 없이 조립하는 형태 — 추세의 정격은 설정값. */
    public PowerOptimizationService(SchedulingService schedulingService,
                                    PowerModel powerModel,
                                    SchedulerProperties properties,
                                    SchedulerMetricsGateway metrics,
                                    MetricsQueryGuard guard) {
        this(schedulingService, powerModel, properties, metrics, guard, null, null);
    }

    /** 클라이언트를 직접 받는 형태(테스트용) — 기본 게이트웨이로 감싼다. null이면 추세를 쓸 수 없다. */
    public PowerOptimizationService(SchedulingService schedulingService,
                                    PowerModel powerModel,
                                    SchedulerProperties properties,
                                    VictoriaMetricsClient metricsClient,
                                    MetricsQueryGuard guard) {
        this(schedulingService, powerModel, properties, VictoriaMetricsGateway.of(metricsClient), guard);
    }

    /** 스프링 없이 조립하는 형태(단위 테스트용) — 현재 시점 진단만 쓸 수 있고 추세는 저장소가 없어 호출할 수 없다. */
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
     * 그러지 않으면 {@code totalNodes}가 조용히 줄어 "10대 중 1대 제외"가 "9대 클러스터"로 보인다.</p>
     */
    public PowerOptimizationResponse diagnose(List<NodeResource> nodes, List<ExcludedNode> excluded) {
        return diagnose(nodes, excluded, MdcPowerBudgets.EMPTY);
    }

    /**
     * 예산 원장까지 반영해 진단한다. MDC별 요약에 예산·남은 여유·초과 여부가 함께 나간다.
     * 원장이 비어 있으면 요약은 나가되 예산 칸이 null이다 — MDC별 현황 자체는 예산과 무관하게 쓸모 있다.
     */
    public PowerOptimizationResponse diagnose(List<NodeResource> nodes, List<ExcludedNode> excluded,
                                              MdcPowerBudgets budgets) {
        OffsetDateTime now = OffsetDateTime.now();
        List<NodeResource> safe = nodes == null ? List.of() : nodes;
        List<ExcludedNode> safeExcluded = excluded == null ? List.of() : excluded;

        boolean measured = safe.stream().anyMatch(this::hasPower);
        Cluster cluster = cluster(safe, measured, safeExcluded);

        // 전력을 하나도 수집하지 못했으면 진단할 근거가 없다. 빈 배열과 사유를 돌려준다 —
        // 0 건이라고 "문제 없음"으로 읽히면 안 되므로 사유를 클러스터에 함께 담는다.
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
     * 클러스터 전력 최적화 추세. {@code /optimization/power}가 현재 시점에 계산하는 것을 메트릭 저장소의 구간 질의로
     * <b>과거 시각마다 다시 계산</b>한다. 저장하지 않는다 — 원시 시계열은 Thanos에 이미 있고 우리는 조회 시점에 정제한다.
     *
     * <p>질의는 노드 수와 무관하게 <b>2회</b>다: CPU 사용률(스냅샷 수집과 같은 식, 대상 job)과 IPMI 전력을 instance별로
     * 받는다. 시각마다 instance별 임시 노드를 만들어 현재 진단과 같은 {@link PowerModel}(가동 판정·유휴 전력)과
     * PEI(CPU% ÷ W) 식을 적용한다 — 새 수식은 없다. 전력이 있는 노드가 없는 시각은 {@code measured=false}이고 전력
     * 지표는 null이다(0으로 채우지 않는다). 저장소가 응답하지 않으면 예외를 올리지 않고 사유를 담은 빈 결과다.</p>
     */
    public PowerOptimizationTrendResponse trend(OffsetDateTime from, OffsetDateTime to, String step) {
        return trend(from, to, step, null);
    }

    /**
     * MDC 하나로 좁힌 추세. {@code mdcId}가 비면 클러스터 전체 추세와 같다(질의 문자열까지 같다).
     *
     * <p>MDC는 CPU 구간 시리즈의 {@code mdc_id} 라벨로 고른다 — 수집기가 노드의 MDC를 읽는 라벨과 같고, 없으면
     * {@code unknown}이다. 식별자는 앞뒤 공백을 빼고 대소문자를 구분해 정확히 맞춘다(MDC 링크 목록과 같은 규칙).
     * 질의문에 넣지 않고 받은 시리즈를 자바에서 거른다.</p>
     *
     * <p>전력은 <b>노드 전체로</b> 다시 키 매긴 뒤 CPU 쪽만 거른다. 같은 IPMI 시리즈를 가리킨 노드 둘을 빼는 판정이
     * 클러스터 추세와 같은 범위에서 돌아야 MDC 추세를 더한 값이 클러스터와 맞는다 — MDC 안에서만 판정하면 MDC를 넘어
     * 시리즈를 공유한 노드가 MDC마다 한 번씩 더해진다. 지점·에너지는 CPU 맵의 노드로만 돌므로 전력 맵은 따로 거르지 않는다.</p>
     */
    public PowerOptimizationTrendResponse trend(OffsetDateTime from, OffsetDateTime to, String step, String mdcId) {
        if (metricsClient == null) throw new IllegalStateException("메트릭 저장소 없이 조립된 서비스는 추세를 낼 수 없다");
        OffsetDateTime end = to != null ? to : OffsetDateTime.now();
        OffsetDateTime start = from != null ? from : end.minusHours(props.getTrendDefaultHours());
        String interval = (step == null || step.isBlank()) ? props.getTrendDefaultStep() : step.trim();
        TrendRangeSupport.validate(start, end, interval, props.getTrendMaxPoints());
        String mdc = mdcId == null || mdcId.isBlank() ? null : mdcId.trim();

        try {
            List<Map<String, Object>> cpuResults = rangeResults(String.format(mdc == null
                            ? "100 - (avg by (instance) (irate(node_cpu_seconds_total{mode=\"idle\",job=\"%s\"}[5m])) * 100)"
                            : "100 - (avg by (instance, mdc_id) (irate(node_cpu_seconds_total{mode=\"idle\",job=\"%s\"}[5m])) * 100)",
                    properties.getNodeJob()), start, end, interval);
            Map<String, Map<Long, Double>> allCpu = toSeries(cpuResults, metric -> true);
            // 전력 시계열의 키는 IPMI 시리즈의 instance라 노드 익스포터와 다를 수 있다(실장비: 중앙 IPMI 주소).
            // 원장 → 호스트명 일치 → 그대로 순으로 노드 instance에 다시 키 매긴다. 구간 결과의 키가 곧
            // "저장소에 있는 IPMI instance"라 발견 질의를 따로 던지지 않는다.
            Map<String, Map<Long, Double>> rawPower = seriesByInstance("ipmi_dcmi_power_consumption_watts", start, end, interval);
            Map<String, Map<Long, Double>> power = rekeyToNodes(rawPower, allCpu.keySet());

            if (allCpu.isEmpty()) {
                return PowerOptimizationTrendResponse.empty(start, end, interval,
                        "해당 구간에 대상 노드의 CPU 메트릭이 없습니다");
            }
            Map<String, Map<Long, Double>> cpu = mdc == null ? allCpu
                    : toSeries(cpuResults, metric -> mdc.equals(mdcOf(metric)));
            if (cpu.isEmpty()) {
                return PowerOptimizationTrendResponse.empty(start, end, interval,
                        "해당 구간에 그 MDC 로 수집된 노드의 CPU 메트릭이 없습니다 — MDC 식별자가 노드 목록의 mdcId 와 다르거나"
                                + "(대소문자 구분) 그 구간에 수집이 없었습니다");
            }
            TreeSet<Long> timestamps = new TreeSet<>();
            cpu.values().forEach(series -> timestamps.addAll(series.keySet()));

            // 정격 전력 원장은 시각마다가 아니라 추세 1회당 한 번만 읽는다.
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
            return new PowerOptimizationTrendResponse(start, end, interval, points, message,
                    energyOf(cpu, power, specs, interval));

        } catch (RuntimeException e) {
            // 조회 실패로 500을 내면 화면이 통째로 깨진다. 사유만 담아 빈 결과로 돌려준다(전력 효율 추세와 같은 원칙).
            log.warn("전력 최적화 추세 조회 실패: reason={}", e.getMessage());
            return PowerOptimizationTrendResponse.empty(start, end, interval, "메트릭 저장소를 조회하지 못했습니다: " + e.getMessage());
        }
    }

    /**
     * 구간 전체의 에너지 요약.
     *
     * <p><b>지점 배열을 적분하지 않는다.</b> 그 배열은 전력을 못 읽은 노드를 0으로 채워 만든 것이라, 적분하면
     * 결손이 "0 W를 썼다"가 되어 조용히 적게 나온다. 대신 <b>0을 채우기 전의 원시 시계열</b>을 노드별로
     * 적분한다 — 그 맵에는 애초에 결손 자리가 없고, 지점 배열과 달리 이미 받아 둔 값이라 질의도 늘지 않는다.</p>
     *
     * <p>에너지 계산이 터져도 추세는 그대로 낸다. 곁들이는 요약이지 추세의 일부가 아니다.</p>
     */
    private PowerOptimizationTrendResponse.Energy energyOf(
            Map<String, Map<Long, Double>> cpu, Map<String, Map<Long, Double>> power,
            NodePowerSpecs specs, String interval) {
        try {
            // 허용 간격은 조회 간격에서 낸다. 하한 5분을 그대로 쓰면 step을 크게 준 조회가 통째로 잘린다.
            long gapLimit = IntervalEnergy.gapLimitFor(TrendRangeSupport.stepSeconds(interval));
            Map<String, Map<Long, IntervalEnergy.Sample>> reclaimable = new LinkedHashMap<>();
            for (Map.Entry<String, Map<Long, Double>> e : cpu.entrySet()) {
                String instance = e.getKey();
                double capacity = specs.capacityOr(instance, powerModel::capacityOf);
                Map<Long, IntervalEnergy.Sample> series = new LinkedHashMap<>();
                for (Map.Entry<Long, Double> point : e.getValue().entrySet()) {
                    long ts = point.getKey();
                    Double watts = power.getOrDefault(instance, Map.of()).get(ts);
                    // 노드 한 대를 그 시각의 상태로 세운다. 회수 대상이 아니면 그 시각 기여는 0이다.
                    NodeResource node = new NodeResource("-", instance, 0, 0, 0, point.getValue(), 0, 0,
                            watts == null ? 0 : watts, capacity, 0, 0, 0);
                    if (powerModel.isActive(node)) continue;
                    series.put(ts, new IntervalEnergy.Sample(powerModel.wakeupPower(node), watts == null));
                }
                if (series.size() >= 2) reclaimable.put(instance, series);
            }

            IntervalEnergy.Result result = IntervalEnergy.integrate(reclaimable, gapLimit);
            IntervalEnergy.Result consumedResult = IntervalEnergy.integrate(consumedSeries(cpu, power), gapLimit);
            String reason = energyReason(result, consumedResult.kwh());

            return new PowerOptimizationTrendResponse.Energy(
                    consumedResult.kwh(), result.kwh(), result.estimatedKwh(),
                    epochToTime(result.fromEpoch()), epochToTime(result.toEpoch()),
                    result.present() ? result.hours() : null,
                    result.present() ? result.nodes() : null,
                    result.gaps(), reason);

        } catch (RuntimeException e) {
            log.warn("구간 에너지 계산 실패(추세는 그대로 낸다): reason={}", e.getMessage());
            return new PowerOptimizationTrendResponse.Energy(null, null, null, null, null, null, null, 0,
                    "구간 에너지를 계산하지 못했습니다: " + e.getMessage());
        }
    }

    /**
     * 소비 에너지에 쓸 표본 — <b>0을 채우기 전의</b> 원시 전력 시계열 그대로다.
     *
     * <p>지점 배열이 아니라 이 맵을 쓰는 것이 요점이다. 지점은 전력을 못 읽은 노드를 0으로 채워 만들지만
     * 이 맵에는 그 자리가 아예 없다 — 결손이 "0 W"로 둔갑하지 않는다.</p>
     *
     * <p>저장소에 {@code avg_over_time}을 묻는 방법도 있었으나 쓰지 않는다. 즉시 질의는 <b>언제나 현재
     * 시각에서</b> 평가되어, 어제 구간을 물어도 오늘 구간의 평균이 돌아온다. 시각을 지정할 통로가 클라이언트에
     * 없으므로, 이미 받아 둔 구간 시계열을 적분하는 편이 옳고 질의도 늘지 않는다.</p>
     */
    private static Map<String, Map<Long, IntervalEnergy.Sample>> consumedSeries(
            Map<String, Map<Long, Double>> cpu, Map<String, Map<Long, Double>> power) {
        Map<String, Map<Long, IntervalEnergy.Sample>> out = new LinkedHashMap<>();
        power.forEach((instance, series) -> {
            // 전력 질의에는 job 필터가 없다. 지점 배열이 CPU 시계열을 기준으로 도는 것과 모집단을 맞춘다 —
            // 그러지 않으면 추세에 한 줄도 나오지 않는 노드의 전력이 에너지에만 섞인다.
            if (!cpu.containsKey(instance)) return;
            Map<Long, IntervalEnergy.Sample> samples = new LinkedHashMap<>();
            series.forEach((ts, watts) -> samples.put(ts, new IntervalEnergy.Sample(watts, false)));
            if (samples.size() >= 2) out.put(instance, samples);
        });
        return out;
    }

    /** 값이 비거나 좁아진 까닭. 정상이면 null. */
    private static String energyReason(IntervalEnergy.Result result, Double consumed) {
        List<String> reasons = new ArrayList<>();
        if (consumed == null) reasons.add("전력을 실측한 노드가 없어 소비 에너지를 낼 수 없습니다");
        if (!result.present()) reasons.add("적분할 표본이 모자라 회수 가능 에너지를 낼 수 없습니다");
        else if (result.gaps() > 0) reasons.add(String.format(
                "이어지지 않은 구간이 %d곳 있습니다 — 표본이 없었거나 그 시각 노드가 가동 중이었습니다. "
                        + "실제로 적분한 시간은 integratedHours 입니다", result.gaps()));
        if (result.present() && result.estimatedKwh() != null && result.estimatedKwh() > 0) reasons.add(
                "회수 가능 에너지에 미계측 노드의 정격 추정분이 섞여 있습니다 — reclaimableEstimatedKwh 를 보십시오");
        return reasons.isEmpty() ? null : String.join(" / ", reasons);
    }

    private static OffsetDateTime epochToTime(Long epochSeconds) {
        return epochSeconds == null ? null : OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }

    /** 한 시각의 클러스터 상태 — 현재 진단의 {@link #cluster}와 같은 식. */
    PowerOptimizationTrendResponse.Point pointOf(long epochSeconds, List<NodeResource> nodes) {
        int active = (int) nodes.stream().filter(powerModel::isActive).count();
        List<NodeResource> powered = nodes.stream().filter(this::hasPower).toList();
        boolean measured = !powered.isEmpty();
        Double total = measured ? round(nodes.stream().mapToDouble(NodeResource::getPowerWatts).sum()) : null;
        Double reclaimable = measured
                ? round(nodes.stream().filter(n -> !powerModel.isActive(n)).mapToDouble(powerModel::wakeupPower).sum()) : null;
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

    /** PEI(CPU% ÷ W)는 0.0067 같은 작은 값이라 전력 효율 요약처럼 소수 4자리로 둔다 — 2자리로 자르면 0.01로 뭉개진다. */
    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /**
     * IPMI 시계열을 노드 instance로 다시 키 매긴다 — 지점 배열·에너지 적분이 CPU 시계열 기준으로 도는 것과 모집단을 맞춘다.
     * 어느 노드에도 이어지지 않은 IPMI 시리즈는 버린다(추세에 한 줄도 나오지 않는 노드의 전력이 섞이면 안 된다).
     */
    private Map<String, Map<Long, Double>> rekeyToNodes(Map<String, Map<Long, Double>> rawPower, java.util.Set<String> nodeInstances) {
        com.innogrid.aims.scheduler.model.ExporterInstances exporters = exporterResolver.resolve(java.util.Set.of(), rawPower.keySet());
        // 노드 전체를 한 번에 — 같은 IPMI 시리즈를 가리킨 노드가 둘이면 둘 다 빠진다. 한 시계열을 두 노드에 넣으면 합·에너지가 그 배수로 부푼다.
        Map<String, String> ipmiOfNode = exporters.ipmiFor(nodeInstances);
        Map<String, Map<Long, Double>> out = new LinkedHashMap<>();
        for (String node : nodeInstances) {
            Map<Long, Double> series = rawPower.get(ipmiOfNode.getOrDefault(node, node));
            if (series != null && !series.isEmpty()) out.put(node, series);
        }
        return out;
    }

    /** 구간 질의 결과를 instance → (시각 → 값)으로 편다. 저장소 응답 형태: [{metric:{instance}, values:[[초,"값"],…]}, …] */
    private Map<String, Map<Long, Double>> seriesByInstance(String promQL, OffsetDateTime start, OffsetDateTime end, String step) {
        return toSeries(rangeResults(promQL, start, end, step), metric -> true);
    }

    private List<Map<String, Object>> rangeResults(String promQL, OffsetDateTime start, OffsetDateTime end, String step) {
        return guard.call(() -> metricsClient.queryRangeResults(
                promQL, start.toInstant(), end.toInstant(), step), properties.getMetrics().getQueryTimeoutMs());
    }

    /** 시리즈 라벨의 MDC — 수집기와 같이 라벨이 없으면 {@code unknown}. */
    private static String mdcOf(Map<?, ?> metric) {
        Object mdc = metric.get("mdc_id");
        return mdc == null ? "unknown" : mdc.toString();
    }

    /** 라벨이 {@code keep}을 만족하는 시리즈만 instance → (시각 → 값)으로 편다. */
    private static Map<String, Map<Long, Double>> toSeries(List<Map<String, Object>> results, Predicate<Map<?, ?>> keep) {
        Map<String, Map<Long, Double>> out = new LinkedHashMap<>();
        for (Map<String, Object> series : results) {
            Object metric = series.get("metric");
            String instance = metric instanceof Map<?, ?> m && m.get("instance") != null && keep.test(m)
                    ? m.get("instance").toString() : null;
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
                .mapToDouble(powerModel::wakeupPower).sum();

        return new Cluster(nodes.size(), active, nodes.size() - active,
                round(total), round(measured ? reclaimable : 0),
                measured, measured ? null : NO_POWER_METRIC,
                excluded.size(), excluded.stream().map(ExcludedNodeResponse::from).toList());
    }

    // ── MDC별 현황 ────────────────────────────────────────────

    /**
     * 노드를 MDC로 묶어 전력 현황을 낸다. MDC 식별자 오름차순이라 응답이 결정적이다.
     *
     * <p>전력을 <b>실측</b>과 <b>실효</b> 두 값으로 낸다. 실측은 클러스터 합계와 같은 기준이고, 실효는 예약분까지
     * 반영해 <b>C6이 예산과 비교하는 기준선과 같다.</b> 남은 여유·초과 판정은 실효로 낸다 — 실측으로 내면
     * "여유가 있다"고 보여 주고도 실제 배치는 막히는 어긋남이 생긴다.</p>
     *
     * <p>계측 판정은 <b>C6과 같은 술어</b>(실측 전력 &gt; 0)를 쓴다. 진단의 {@link #hasPower}는 정격까지 요구하는데,
     * 그건 정격으로 나누는 진단 항목에 필요한 조건이지 "전력을 계측했는가"의 조건이 아니다. 두 술어가 갈리면
     * 지금 고치는 것과 같은 종류의 어긋남이 다시 생긴다.</p>
     *
     * <p>라벨이 없는 노드는 수집기가 {@code unknown}으로 묶으므로 여기서도 한 그룹으로 나온다.</p>
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
                    .filter(n -> n.getPowerWatts() > 0)     // C6과 같은 술어
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

        double idle = powerModel.wakeupPower(node);
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
     * 절감 항목이 아니라 <b>배치 제약</b>이라 {@code estimatedSavingWatts}를 비운다 —
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

    /** 총 전력이 0이면 비율을 낼 수 없다. 0%로 채우면 "절감 없음"으로 읽힌다. */
    static Double percentOf(double value, double total) {
        if (total <= 0) return null;
        return round(value / total * 100);
    }

    static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
