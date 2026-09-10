package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.benchmark.BaselinePlacers;
import com.innogrid.aims.scheduler.benchmark.BenchmarkClusters;
import com.innogrid.aims.scheduler.benchmark.BenchmarkContexts;
import com.innogrid.aims.scheduler.benchmark.BenchmarkMetric;
import com.innogrid.aims.scheduler.benchmark.BenchmarkPolicies;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules;
import com.innogrid.aims.scheduler.benchmark.BenchmarkWorkloads;
import com.innogrid.aims.scheduler.benchmark.GroundTruthOracle;
import com.innogrid.aims.scheduler.benchmark.GroundTruthOracle.PowerControl;
import com.innogrid.aims.scheduler.benchmark.PlacementStrategy;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.Assumptions;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.Board;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.Cell;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.EngineConfigSnapshot;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.IdleGap;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.MetricValue;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.PolicySnapshot;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.PowerKnobs;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.StrategyResult;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.ThermalKnobs;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.ThermalRiseScale;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.WakeupReconstruction;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.WorkloadClassShare;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.SchedulingEngine;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.metrics.SyntheticCluster;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.NodeResource.PowerSource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;

/**
 * 배치 전략 비교 하네스 — 우리 엔진과 대조군을 <b>같은 판·같은 워크로드·같은 제약</b>에서 돌린다.
 *
 * <p>이 서비스는 <b>아무것도 저장하지 않는다.</b> 워크로드는 파일에 선언된 분포에서 뽑은 비영속
 * 객체이고, 정책은 코드 기본값의 사본이며, 노드는 클러스터 파일에서 만든 스냅샷이다. 저장소를
 * 하나도 주입받지 않으므로 저장할 경로 자체가 없다.</p>
 *
 * <p><b>왜 저장소를 쓰지 않는가.</b> 저장된 워크로드를 쓰면 예약 적용 경로가 같은 워크로드를 예약으로
 * 한 번, 배치 대상으로 또 한 번 세어 요구량이 두 배가 된다. 활성 정책 조회 경로는 정책이 없으면
 * 기본 정책을 <b>새로 넣는</b> 부작용이 있어 진단 호출이 데이터를 만든다. 둘 다 응답만 봐서는
 * 드러나지 않는 종류의 오염이다.</p>
 *
 * <p><b>대조군은 엔진이 아니다.</b> {@link PlacementStrategy} 는 {@link SchedulingEngine} 을 구현하지
 * 않는 record 라 엔진 레지스트리가 수집할 수 없다. 대조군이 빈이 되면 엔진 식별자 검증이 통째로
 * 열려 스케줄이 대조군으로 <b>실배치</b>하고 지우지 않는 이력에 남는다.</p>
 *
 * <p><b>전원 상태 두 판은 배치를 두 번 돌리지 않는다.</b> 전원 제어 가정은 전략의 <b>입력이 아니다</b> —
 * 엔진도 대조군도 그것을 보지 않는다. 배치 결정은 한 번만 하고, 같은 결정을 두 가정으로 <b>회계</b>한다.
 * 두 번 돌리면 같은 결정이 나온다는 사실을 우연에 맡기게 되고 계산량도 두 배가 된다.</p>
 */
@Service
public class PlacementBenchmarkService {

    /** 워크로드 분포 두 종. 순위가 두 분포 사이에서 뒤집히면 그 사실 자체가 결과다. */
    public static final String HEAVYTAIL = "heavytail";
    public static final String UNIFORM = "uniform";

    private static final Map<String, String> DISTRIBUTION_FILES = Map.of(
            HEAVYTAIL, "classpath:scheduler/benchmark-workloads-heavytail.yml",
            UNIFORM, "classpath:scheduler/benchmark-workloads-uniform.yml");

    /**
     * 대조군의 순서. 응답 열 순서가 실행마다 바뀌면 표를 견줄 수 없다.
     *
     * <p>{@link #strategies()} 가 이 목록과 구현 맵의 <b>키 집합이 같은지</b> 검사한다 — 목록에만 있으면 null 이
     * 담겨 한참 뒤 NPE 로 죽고, 맵에만 있으면 열 하나가 <b>조용히</b> 사라진 채 응답은 정상이다. 둘 다 여기서
     * 크게 실패하게 한다. 시험이 같은 패키지에서 이 목록을 읽는다.</p>
     */
    static final List<String> BASELINE_ORDER =
            List.of("ffd-size", "ffd-priority-size", "pabfd", "bfd", "coolest-first", "random");

    /** 무작위 전략의 식별자. 이것만 시드를 여러 개 돌린다. */
    private static final String RANDOM_ID = "random";

    /**
     * 격차 지표의 <b>분모가 되는 대조군</b>. 문헌 기준선이라 이 열이 기준이고, 이 열 자신의 격차는 0 이 아니라
     * 값이 없다. 시험 전용 이음매가 대조군 부분 집합을 줄 수 있으므로 <b>이 실행에 없을 수도 있다</b>.
     */
    private static final String PABFD_ID = "pabfd";

    private final EngineRegistry engineRegistry;
    private final SchedulerProperties properties;
    private final BenchmarkClusters clusters;

    /** 상태가 없는 모델이라 그대로 쓴다 — 엔진이 믿은 비용이 운영과 같은 설정에서 나와야 한다. */
    private final PowerModel powerModel;

    /**
     * 경고 온도 기본값 하나만 쓴다. <b>온도 값 계산에는 쓰지 않는다</b> — 엔진의 열관성 가정으로 지표를
     * 재면 열 엔진이 자기 가정으로 채점받는다. 온도는 {@link GroundTruthOracle} 이 생성식으로 낸다.
     */
    private final ThermalModel thermalModel;

    /** 이 실행이 비교에 넣는 대조군 순서. 운영은 항상 {@link #BASELINE_ORDER} 다. */
    private final List<String> baselineOrder;

    /** 스프링이 쓰는 생성자 — 생성자가 둘이라 어느 것인지 표시해야 한다. 대조군은 항상 {@link #BASELINE_ORDER} 전량. */
    @Autowired
    public PlacementBenchmarkService(EngineRegistry engineRegistry, SchedulerProperties properties,
                                    BenchmarkClusters clusters,
                                    PowerModel powerModel, ThermalModel thermalModel) {
        this(engineRegistry, properties, clusters, powerModel, thermalModel, BASELINE_ORDER);
    }

    /**
     * 대조군 집합을 갈아 끼우는 이음매 — <b>시험 전용</b>이라 패키지 밖에서 보이지 않는다.
     *
     * <p>대조군 하나를 넣고 빼면서 "기존 열의 어느 숫자가 움직여도 되는가"를 고정하려면 같은 판을 두 집합으로
     * 돌려야 한다. 스프링은 공개 생성자만 쓰므로 운영 경로가 이 인자를 볼 길은 없다.</p>
     */
    PlacementBenchmarkService(EngineRegistry engineRegistry, SchedulerProperties properties,
                             BenchmarkClusters clusters, PowerModel powerModel, ThermalModel thermalModel,
                             List<String> baselineOrder) {
        this.engineRegistry = engineRegistry;
        this.properties = properties;
        this.clusters = clusters;
        this.powerModel = powerModel;
        this.thermalModel = thermalModel;
        this.baselineOrder = List.copyOf(baselineOrder);
    }

    // ── 진입점 ───────────────────────────────────────────────

    /**
     * 판 하나를 부하율 네 지점 × 전원 상태 두 가정으로 비교한다.
     *
     * @param boardId      {@code main}(주 결과) · {@code adversarial}(반증) · {@code sparse}(계측 공백) ·
     *                     {@code convex}(볼록 생성식) · {@code misrated}(정격 어긋남) · {@code convex-misrated}(복합·판정)
     * @param distribution {@code heavytail} 또는 {@code uniform}
     */
    public PlacementBenchmarkResponse compare(String boardId, String distribution) {
        String board = blank(boardId) ? BenchmarkClusters.MAIN : boardId;
        String dist = blank(distribution) ? HEAVYTAIL : distribution;

        String location = DISTRIBUTION_FILES.get(dist);
        if (location == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "모르는 워크로드 분포입니다: " + dist
                    + " (쓸 수 있는 분포: " + new TreeSet<>(DISTRIBUTION_FILES.keySet()) + ")");
        }
        SyntheticCluster cluster = resolveBoard(board);
        BenchmarkWorkloads workloads = BenchmarkWorkloads.load(location);

        return new PlacementBenchmarkResponse(
                OffsetDateTime.now(),
                assumptions(workloads, cluster),
                engineConfig(),
                BenchmarkRules.METRICS,
                List.of(runBoard(board, cluster, workloads)),
                BenchmarkRules.LIMITATION);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** 판 이름이 틀린 것은 사용자 입력 오류라 400 으로 돌린다 — 500 이면 원인이 로그에만 남는다. */
    private SyntheticCluster resolveBoard(String board) {
        try {
            return clusters.get(board);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private Assumptions assumptions(BenchmarkWorkloads workloads, SyntheticCluster cluster) {
        List<WorkloadClassShare> shares = workloads.classes().stream()
                .map(c -> new WorkloadClassShare(c.name(), c.weight(), c.cores(), c.gpus(), c.priority().name()))
                .toList();

        return new Assumptions(workloads.name(), workloads.seed(), shares,
                BenchmarkRules.LOAD_RATIOS, BenchmarkRules.HEADROOM_DEFINITION,
                BenchmarkRules.SWEEP_POINTS, cluster.periodMinutes(),
                BenchmarkRules.COOLDOWN_MINUTES_FOR_BENCHMARK, BenchmarkRules.MEMORY_MB_PER_CORE,
                properties.getPower().getIdleRatio(), Assumptions.GENERATOR_FORMULA, Assumptions.POWER_CONTROL_NOTE,
                BenchmarkRules.RANDOM_SEEDS.size(), Assumptions.POLICY_BASIS);
    }

    // ── 판 하나 ──────────────────────────────────────────────

    private Board runBoard(String boardId, SyntheticCluster cluster, BenchmarkWorkloads workloads) {
        GroundTruthOracle oracle = new GroundTruthOracle(cluster);
        List<Long> sweep = sweepPoints(cluster);
        List<Cell> cells = new ArrayList<>();

        for (int loadRatio : BenchmarkRules.LOAD_RATIOS) {
            // 한 부하율의 배치를 한 번만 돌리고, 같은 결정을 전원 상태 두 가정으로 회계한다.
            List<PointOutcome> points = new ArrayList<>(sweep.size());
            for (long t : sweep) {
                points.add(runPoint(cluster, workloads, loadRatio, t, oracle));
            }
            for (PowerControl mode : PowerControl.values()) {
                cells.add(cell(loadRatio, mode, points, oracle));
            }
        }

        long measured = cluster.nodes().stream().filter(SyntheticCluster.Node::ipmi).count();
        return new Board(boardId, clusters.roleOf(boardId), cluster.name(), clusters.digest(boardId),
                cluster.nodes().size(), (int) measured, cluster.powerExponent(),
                idleGaps(oracle), thermalRiseScale(oracle), wakeupReconstruction(cluster), cells);
    }

    /**
     * 한 주기를 고르게 훑는 시각 지점. <b>벽시계를 쓰지 않는다</b> — 절대 시각에 기대면 같은 요청이
     * 두 번 다른 숫자를 내고, 그 차이가 알고리즘 차이와 구분되지 않는다.
     *
     * <p>{@code private} 이 아닌 이유는 같은 패키지의 시험이 <b>같은 지점 목록</b>으로 역산을 다시 계산하기
     * 때문이다. 시험이 사본을 두면 두 목록이 갈라져도 초록불이 된다.</p>
     */
    static List<Long> sweepPoints(SyntheticCluster cluster) {
        long period = cluster.periodMinutes() * 60L;
        List<Long> points = new ArrayList<>(BenchmarkRules.SWEEP_POINTS);
        for (int i = 0; i < BenchmarkRules.SWEEP_POINTS; i++) {
            points.add(period * i / BenchmarkRules.SWEEP_POINTS);
        }
        return points;
    }

    private List<IdleGap> idleGaps(GroundTruthOracle oracle) {
        return oracle.idleAssumptionGaps(properties.getPower().getIdleRatio()).stream()
                .map(g -> new IdleGap(g.nodeId(), g.capacityWatts(), g.engineBelievedCapacityWatts(),
                        g.declaredIdleWatts(), g.engineAssumedIdleWatts(), g.absoluteGapWatts(),
                        g.declaredIdleRatio(), g.generatorWattsPerCore()))
                .toList();
    }

    /**
     * 노드별 코어당 열 상승 세 계수. {@link #idleGaps} 와 같은 자리 — 전략 루프 밖에서 한 번, 판 전제 블록에.
     * 격차·비율은 만들지 않는다. 그것을 만드는 순간 "생성식이 옳다"를 전제하게 되고, 단위를 바꾸면 부호가 뒤집힌다.
     */
    private List<ThermalRiseScale> thermalRiseScale(GroundTruthOracle oracle) {
        return oracle.thermalRiseScaleCheck(properties.getThermal().getInertiaK()).stream()
                .map(r -> new ThermalRiseScale(r.nodeId(), r.cores(), r.baseTemperatureC(),
                        r.engineRisePerCoreC(), r.engineRisePerCoreIfKelvin(), r.generatorRisePerCoreC()))
                .toList();
    }

    /**
     * 유휴 노드의 깨우기 비용 역산 점검표. {@link #idleGaps} 와 같은 자리 — 전략 루프 밖에서 한 번, 판 전제 블록에.
     * 판정·비율은 만들지 않는다. 값이 대체값과 같은지는 {@code engineFallbackWatts} 열을 나란히 실어 독자가 본다.
     *
     * <p>행은 <b>판의 회계 규칙</b>으로 고른다 — 시각 12지점 내내 {@link BenchmarkRules#POWERED_ON_CPU_PERCENT}
     * 아래에 머무는 노드 전부이고, 계측 여부는 열로 밝힌다. 엔진 노브({@code activeCpuPercent})로 고르면
     * 노브를 돌리는 것만으로 표의 행이 달라져, 판이 엔진 설정의 함수가 된다.</p>
     *
     * <p>지점마다 <b>배치 전 스냅샷</b>을 새로 만들어 재는 이유는 엔진의 가동 판정이 예약분까지 반영한 실효
     * CPU 를 보기 때문이다. 배치가 얹힌 노드로 재면 같은 시각의 (P, u) 쌍이 아니게 되어 역산의 전제가 깨진다.</p>
     */
    private List<WakeupReconstruction> wakeupReconstruction(SyntheticCluster cluster) {
        List<Long> sweep = sweepPoints(cluster);
        List<SyntheticCluster.Node> rows = cluster.nodes().stream()
                .filter(n -> sweep.stream()
                        .allMatch(t -> cluster.cpuUsage(n, t) <= BenchmarkRules.POWERED_ON_CPU_PERCENT))
                .toList();
        if (rows.isEmpty()) return List.of();

        Map<String, double[]> range = new LinkedHashMap<>();       // nodeId → { 최솟값, 최댓값 }
        for (long t : sweep) {
            Map<String, NodeResource> snapshot = new LinkedHashMap<>();
            for (NodeResource node : nodesAt(cluster, t)) {
                snapshot.put(node.getNodeId(), node);
            }
            for (SyntheticCluster.Node n : rows) {
                double watts = powerModel.wakeupPower(snapshot.get(n.nodeId()));
                double[] minMax = range.computeIfAbsent(n.nodeId(),
                        id -> new double[] { Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY });
                minMax[0] = Math.min(minMax[0], watts);
                minMax[1] = Math.max(minMax[1], watts);
            }
        }

        double idleRatio = properties.getPower().getIdleRatio();
        List<WakeupReconstruction> rowsOut = new ArrayList<>(rows.size());
        for (SyntheticCluster.Node n : rows) {
            double[] minMax = range.get(n.nodeId());
            rowsOut.add(new WakeupReconstruction(n.nodeId(), n.ipmi(), n.idleWatts(),
                    n.believedCapacityWatts() * idleRatio, minMax[0], minMax[1]));
        }
        return rowsOut;
    }

    // ── 시각 지점 하나 ────────────────────────────────────────

    /** 한 시각 지점의 전 전략 결과. 배치 결정은 여기서 끝나고, 전원 상태 회계는 뒤에서 붙는다. */
    private record PointOutcome(double headroomCores, int requestedWorkloads, double requestedCores,
                                Map<String, Double> cpuBeforeByNode, Set<String> activeBefore,
                                int activeCountBefore, int measuredNodeCount,
                                Map<String, String> probeExclusions,
                                Map<String, Placement> byStrategy) {
    }

    /** 전략 하나가 한 시각 지점에서 낸 배치 결과. */
    private record Placement(PlacementStrategy strategy, int placed, int requested,
                             double engineBelievedCostW, Set<String> usedNodes,
                             Map<String, Double> cpuAfterByNode, int unmeasuredPlacements,
                             int thermalExclusions, int activeCountAfter,
                             double measuredTempStdDev, int warnZoneNodes,
                             boolean temperatureGated, boolean powerGated) {
    }

    private PointOutcome runPoint(SyntheticCluster cluster, BenchmarkWorkloads workloads,
                                  int loadRatio, long t, GroundTruthOracle oracle) {
        SchedulingPolicy basePolicy = baseline();
        List<NodeResource> snapshot = nodesAt(cluster, t);

        double headroom = headroomCores(snapshot, basePolicy);
        List<Workload> sampled = workloads.sample(headroom * loadRatio / 100.0);
        double requestedCores = sampled.stream().mapToInt(Workload::getRequiredCpu).sum();

        Map<String, Double> cpuBefore = new LinkedHashMap<>();
        Set<String> activeBefore = new LinkedHashSet<>();
        int measuredNodes = 0;
        for (NodeResource node : snapshot) {
            cpuBefore.put(node.getNodeId(), node.effectiveCpuUsagePercent());
            if (poweredOn(node)) activeBefore.add(node.getNodeId());
            if (node.getTemperatureCelsius() > 0) measuredNodes++;
        }

        // 이 판이 왜 막히는지 한 번만 재 본다. 표본 첫 워크로드를 프로브로 쓰고 전용 평가기를 준다 —
        // 전략이 쓰는 평가기로 재면 그 측정 자체가 전략의 판정을 건드린다.
        Map<String, String> probe = Map.of();
        if (!sampled.isEmpty()) {
            ConstraintEvaluator probeEvaluator = BenchmarkContexts.freshEvaluator(properties);
            probe = BenchmarkContexts.exclusions(sampled.get(0),
                    BenchmarkContexts.copyOf(snapshot, basePolicy, probeEvaluator));
        }

        Map<String, Placement> byStrategy = new LinkedHashMap<>();
        for (PlacementStrategy strategy : strategies()) {
            byStrategy.put(strategy.id(), run(strategy, sampled, snapshot, cluster, oracle));
        }

        return new PointOutcome(headroom, sampled.size(), requestedCores,
                cpuBefore, activeBefore, activeBefore.size(), measuredNodes, probe, byStrategy);
    }

    /**
     * 전략 하나를 한 시각 지점에서 돌린다.
     *
     * <p>무작위 전략은 <b>사전 등록된 시드 전부</b>를 돌려 중앙 표본을 쓴다. 시드 하나는 표본 1개라
     * 그 값이 좋았는지 나빴는지 말할 수 없다.</p>
     */
    private Placement run(PlacementStrategy strategy, List<Workload> sampled,
                          List<NodeResource> snapshot, SyntheticCluster cluster,
                          GroundTruthOracle oracle) {
        if (!RANDOM_ID.equals(strategy.id())) {
            return runOnce(strategy, sampled, snapshot, cluster, oracle);
        }

        List<Placement> draws = new ArrayList<>(BenchmarkRules.RANDOM_SEEDS.size());
        for (long seed : BenchmarkRules.RANDOM_SEEDS) {
            draws.add(runOnce(BaselinePlacers.random(seed), sampled, snapshot, cluster, oracle));
        }
        return medianDraw(draws);
    }

    private Placement runOnce(PlacementStrategy strategy, List<Workload> sampled,
                              List<NodeResource> snapshot, SyntheticCluster cluster,
                              GroundTruthOracle oracle) {
        SchedulingPolicy policy = policyFor(strategy);
        ConstraintEvaluator evaluator = BenchmarkContexts.freshEvaluator(properties);
        PlacementContext ctx = BenchmarkContexts.copyOf(snapshot, policy, evaluator);

        List<Workload> ordered = strategy.usesArrivalOrder()
                ? sampled
                : sampled.stream().sorted(strategy.workloadOrder()).toList();

        Set<String> unmeasuredNodes = unmeasuredNodeIds(cluster);
        Set<String> used = new LinkedHashSet<>();
        int placed = 0;
        int unmeasured = 0;
        int thermal = 0;
        double believedCost = 0;

        for (Workload workload : ordered) {
            SchedulingResult result = strategy.placer().place(workload, ctx);
            thermal += countThermalExclusions(result);
            if (!result.success()) continue;

            String nodeId = result.selectedNodeId();
            NodeResource node = ctx.node(nodeId).orElseThrow(() -> new IllegalStateException(
                    "전략 " + strategy.id() + " 가 판에 없는 노드를 골랐다: " + nodeId));

            // 엔진이 믿은 비용은 배치 직전 상태에서 재야 한다 — 예약을 잡은 뒤에는 다른 값이 된다.
            believedCost += powerModel.realMarginalPower(node, workload.getRequiredCpu());

            ctx.reserve(nodeId, workload);
            used.add(nodeId);
            placed++;
            if (unmeasuredNodes.contains(nodeId)) unmeasured++;
        }

        Map<String, Double> cpuAfter = new LinkedHashMap<>();
        for (NodeResource node : ctx.nodes()) {
            cpuAfter.put(node.getNodeId(), node.effectiveCpuUsagePercent());
        }

        return new Placement(strategy, placed, ordered.size(), believedCost, used, cpuAfter,
                unmeasured, thermal, ctx.activeNodeCount(BenchmarkRules.POWERED_ON_CPU_PERCENT),
                measuredTempStdDev(ctx, oracle), warnZoneNodes(ctx, policy, oracle),
                policy.isTemperatureAwareEnabled(), policy.isPowerAwareEnabled());
    }

    /**
     * 무작위 시드들의 중앙 표본.
     *
     * <p>지표별로 따로 중앙값을 내지 않고 <b>뽑기 하나를 고른다</b> — 지표마다 다른 뽑기의 값을 섞으면
     * 어느 뽑기에서도 실제로 일어나지 않은 조합이 나온다(전력은 A 의 값, 성공률은 B 의 값).</p>
     *
     * <p><b>정렬 기준에 지표를 쓰지 않는다.</b> 전력이든 엔진이 믿은 비용이든, 어떤 목적 함수로 중앙을
     * 고르면 그 함수에 대해 무작위 대조군이 "중간쯤 하는" 전략으로 다듬어진다 — 하한선이어야 하는 것이
     * 하한선이 아니게 된다. 그래서 <b>구조</b>로만 정렬한다: 배치 건수 → 쓴 노드 수 → 쓴 노드 식별자.
     * 어느 것도 좋고 나쁨을 뜻하지 않으므로 중앙 표본이 특정 지표에 유리해질 길이 없다.</p>
     */
    private static Placement medianDraw(List<Placement> draws) {
        List<Placement> sorted = draws.stream()
                .sorted(Comparator.comparingInt(Placement::placed)
                        .thenComparingInt((Placement d) -> d.usedNodes().size())
                        .thenComparing(PlacementBenchmarkService::usedNodeKey))
                .toList();
        return sorted.get(sorted.size() / 2);
    }

    /** 쓴 노드 집합을 정렬 가능한 문자열로. 좋고 나쁨과 무관한 순서라 중앙 고르기를 왜곡하지 않는다. */
    private static String usedNodeKey(Placement draw) {
        return String.join(",", new TreeSet<>(draw.usedNodes()));
    }

    // ── 셀 조립 ──────────────────────────────────────────────

    private Cell cell(int loadRatio, PowerControl mode, List<PointOutcome> points, GroundTruthOracle oracle) {
        List<String> strategyIds = List.copyOf(points.get(0).byStrategy().keySet());

        Map<String, Map<String, List<Double>>> samples = new LinkedHashMap<>();
        for (String id : strategyIds) samples.put(id, new LinkedHashMap<>());

        // 총량 비교는 시각 지점마다 짝짓는다. 어떤 지점에서 전 전략의 배치 건수가 같다면 그 지점의
        // 총량은 견줄 수 있다 — 다른 지점이 어긋났다는 이유로 그 지점까지 버리면 쓸 수 있는 짝을 통째로
        // 잃고 주 지표가 중부하 이상에서 사라진다. 대신 몇 곳을 썼는지 밝힌다.
        int usablePoints = 0;
        Map<String, Integer> mismatchExample = Map.of();

        for (PointOutcome point : points) {
            Map<String, Double> unmeasuredRates = new LinkedHashMap<>();
            Map<String, Integer> placedHere = new LinkedHashMap<>();
            for (String id : strategyIds) {
                placedHere.put(id, point.byStrategy().get(id).placed());
            }
            boolean pointComparable = BenchmarkRules.totalsComparable(placedHere.values());

            // 생성식 총전력은 지점마다 전략당 한 번 내어 두 열이 같은 값을 읽는다. 총량 열은 짝이 아닌
            // 지점을 null 로 가리지만(아래), 격차 열은 이 행과 기준 전략 둘만 짝지으므로 가리는 조건이
            // 다르다 — 같은 오라클 호출을 두 조건으로 쓰는 것이지 값을 두 번 계산하는 것이 아니다.
            Map<String, Double> powerByStrategy = new LinkedHashMap<>();
            for (String id : strategyIds) {
                powerByStrategy.put(id, power(oracle, point, point.byStrategy().get(id), mode));
            }
            Integer placedBaseline = placedHere.get(PABFD_ID);
            Double powerBaseline = powerByStrategy.get(PABFD_ID);
            if (pointComparable) {
                usablePoints++;
            } else {
                mismatchExample = placedHere;
            }

            for (String id : strategyIds) {
                Placement p = point.byStrategy().get(id);
                Map<String, List<Double>> into = samples.get(id);

                add(into, BenchmarkRules.PLACEMENT_RATE,
                        p.requested() == 0 ? null : round(100.0 * p.placed() / p.requested()));
                add(into, BenchmarkRules.PER_WORKLOAD_POWER, perWorkload(oracle, point, p, mode));
                add(into, BenchmarkRules.ENGINE_BELIEVED_COST, round(p.engineBelievedCostW()));
                // 개입 횟수는 시도당 계수다 — 전 전략이 같은 워크로드를 한 번씩 시도하므로 배치 건수와
                // 무관하게 지점마다 센다. 아래 짝짓기 조건을 여기에 걸면 없는 결함을 고치는 것이다.
                add(into, BenchmarkRules.C3_INTERVENTIONS, (double) p.thermalExclusions());

                // 총량 계열과 온도 분포 계열은 건수가 짝지어진 지점에서만 표본에 넣는다. 적게 배치하고 전력을
                // 덜 쓰는 것이 승리가 아닌 것처럼, 적게 배치해 CPU 를 덜 얹은 노드가 덜 뜨거운 것도 열 성능이
                // 아니다 — 생성식 온도는 CPU 의 함수라 배치량이 다르면 열이 아니라 배치량을 재게 된다.
                // 짝이 아닌 지점의 값은 아예 세지 않는다.
                add(into, BenchmarkRules.GENERATOR_POWER,
                        pointComparable ? powerByStrategy.get(id) : null);
                // 격차는 전 전략 짝을 요구하지 않는다 — 이 행과 기준 전략의 건수만 같으면 낸다.
                add(into, BenchmarkRules.GAP_VS_PABFD,
                        gapVsBaseline(id, placedHere.get(id), powerByStrategy.get(id),
                                placedBaseline, powerBaseline));
                add(into, BenchmarkRules.ACTIVE_NODE_DELTA, pointComparable
                        ? (double) (p.activeCountAfter() - point.activeCountBefore()) : null);
                add(into, BenchmarkRules.WARN_ZONE_NODES,
                        pointComparable ? (double) p.warnZoneNodes() : null);
                add(into, BenchmarkRules.TEMP_STDDEV,
                        pointComparable && point.measuredNodeCount() >= 2 ? round(p.measuredTempStdDev()) : null);

                Double rate = p.placed() == 0 ? null : round(100.0 * p.unmeasuredPlacements() / p.placed());
                add(into, BenchmarkRules.UNMEASURED_RATE, rate);
                if (rate != null) unmeasuredRates.put(id, rate);
            }

            // 미계측 격차는 전략 사이의 값이라 지점마다 한 번 계산해 전 전략에 같은 값으로 싣는다.
            Double spread = spreadOf(unmeasuredRates);
            for (String id : strategyIds) {
                add(samples.get(id), BenchmarkRules.UNMEASURED_SPREAD, spread);
            }
        }

        boolean comparable = usablePoints == points.size();
        String incomparable = comparable ? null
                : pairingNote(usablePoints, points.size(), mismatchExample);

        List<StrategyResult> results = new ArrayList<>(strategyIds.size());
        for (String id : strategyIds) {
            Placement any = points.get(0).byStrategy().get(id);
            results.add(new StrategyResult(id, any.strategy().label(), any.strategy().kind().name(),
                    any.strategy().policy().name(),
                    metricValues(samples.get(id), any, usablePoints, points)));
        }

        return new Cell(loadRatio, mode.name(),
                median(points.stream().map(p -> (double) p.requestedWorkloads()).toList()),
                median(points.stream().map(PointOutcome::requestedCores).toList()),
                median(points.stream().map(PointOutcome::headroomCores).toList()),
                median(points.stream().map(p -> (double) p.activeCountBefore()).toList()),
                comparable, incomparable,
                unmeasuredCaution(samples, strategyIds),
                probeReasonCounts(points), results);
    }

    /**
     * 이 행의 <b>기준 전략 대비</b> 총전력 격차(%). 기준 전략 자신과 기준이 없는 실행에서는 값을 내지 않는다 —
     * 자기 자신의 0 은 "격차가 없다"가 아니라 값이 아니고, 그 사유는 {@code invalidReason} 이 적는다.
     *
     * <p>짝 조건과 분모 가드는 {@link BenchmarkRules#pairedGapPercent} 가 들고 있다 — 판정에 쓰는 식이라
     * 결과 코드보다 먼저 등록된 자리에 있어야 하고, 여기서는 반올림만 한다.</p>
     */
    private static Double gapVsBaseline(String id, Integer placed, Double power,
                                        Integer placedBaseline, Double powerBaseline) {
        if (PABFD_ID.equals(id) || placed == null || power == null
                || placedBaseline == null || powerBaseline == null) {
            return null;
        }
        Double gap = BenchmarkRules.pairedGapPercent(placed, placedBaseline, power, powerBaseline);
        return gap == null ? null : round(gap);
    }

    /**
     * 총량 지표를 몇 곳에서 냈는지 밝히는 문장. 셀에 실어 두면 "몇 곳의 중앙값" 이라는 사실이 숫자와
     * 같은 자리에서 읽힌다 — 각주로 밀어 두면 인용될 때 떨어져 나간다.
     */
    /**
     * 짝짓기 문장에 함께 붙는 격차 열의 단서. 이 문장이 없으면 <b>같은 셀 안에서 응답이 모순으로 읽힌다</b> —
     * 총량 열은 "비교할 수 없다"고 적혀 있는데 격차 열에는 값이 있기 때문이다. 두 열의 짝 조건이 다르다는
     * 사실은 지표 목록과 API 설명에만 있었고, 셀만 읽는 사람에게는 닿지 않았다.
     */
    private static final String GAP_PAIRING_NOTE =
            "총전력 격차 열은 이 행과 기준 전략 둘의 건수만 맞으면 내므로 이 셀에서도 값이 있을 수 있다";

    private static String pairingNote(int usable, int total, Map<String, Integer> mismatchExample) {
        if (usable == 0) {
            return "전 전략의 배치 건수가 같은 시각 지점이 없어 총량과 온도 분포를 비교할 수 없다 — "
                    + BenchmarkRules.totalsIncomparableReason(mismatchExample) + ". " + GAP_PAIRING_NOTE;
        }
        return String.format(
                "시각 지점 %d곳 중 %d곳에서만 전 전략의 배치 건수가 같아 총량·온도 분포 지표는 그 %d곳으로 냈다 "
                        + "(짝이 아닌 지점의 예: %s). %s",
                total, usable, usable, BenchmarkRules.totalsIncomparableReason(mismatchExample),
                GAP_PAIRING_NOTE);
    }

    private static List<MetricValue> metricValues(Map<String, List<Double>> samples, Placement any,
                                                  int usablePoints, List<PointOutcome> points) {
        int measuredNodes = points.get(0).measuredNodeCount();
        String strategyId = any.strategy().id();
        boolean placedAnywhere = points.stream()
                .anyMatch(p -> p.byStrategy().get(strategyId).placed() > 0);
        boolean baselinePresent = points.get(0).byStrategy().containsKey(PABFD_ID);
        List<MetricValue> values = new ArrayList<>(BenchmarkRules.METRICS.size());

        for (BenchmarkMetric metric : BenchmarkRules.METRICS) {
            List<Double> raw = samples.getOrDefault(metric.key(), List.of());
            List<Double> series = raw.stream().filter(Objects::nonNull).toList();
            // 지점별 값은 지점 대조가 필요한 지표만 싣는다. 필터 전 리스트라 인덱스가 시각 지점이고,
            // 짝이 아닌 지점은 null 로 남는다.
            List<Double> pointValues = BenchmarkRules.GAP_VS_PABFD.equals(metric.key())
                    ? pointSeries(raw, points.size()) : null;

            String invalid = invalidReason(metric, any, usablePoints, measuredNodes, placedAnywhere,
                    baselinePresent, !series.isEmpty());
            if (series.isEmpty()) {
                values.add(new MetricValue(metric.key(), null, null, null, 0, pointValues, false,
                        invalid != null ? invalid : "이 셀에서 값이 나오지 않았다"));
                continue;
            }
            if (invalid != null) {
                // 순위 근거가 될 수 없다고 판정한 지표에 중앙값을 실으면, valid 플래그를 보지 않은
                // 인용자에게는 그냥 숫자로 읽힌다. 값은 지우고 표본 수는 남긴다 — "지점이 없어 무효"와
                // "지점은 있었지만 판정이 무효"가 응답에서 구분되어야 한다.
                values.add(new MetricValue(metric.key(), null, null, null, series.size(), pointValues,
                        false, invalid));
                continue;
            }
            values.add(new MetricValue(metric.key(), median(series),
                    round(series.stream().mapToDouble(Double::doubleValue).min().orElseThrow()),
                    round(series.stream().mapToDouble(Double::doubleValue).max().orElseThrow()),
                    series.size(), pointValues, true, null));
        }
        return values;
    }

    /**
     * 지점별 값 — 길이는 <b>항상</b> 스윕 지점 수이고 인덱스가 시각 지점이다.
     *
     * <p>지점 루프가 지표마다 지점당 한 번씩 담으므로 운영 경로에서는 이미 길이가 맞는다 — 길이를 채우는
     * 가지는 <b>방어</b>이고, 지금은 시험이 직접 부를 때만 지난다. 어떤 키가 조건부로만 담기게 되는 날
     * 길이 불변식이 조용히 깨지는 것을 막는 자리다. 값만 모아 압축하면 두 실행을 지점별로 대조할 때 정렬이 어긋나 짝 비교가
     * 성립하지 않는다 — 짝이 아닌 지점이 몇 번째였는지가 응답에 남아야 한다.</p>
     *
     * <p>응답만 보고는 이 성질을 확인할 수 없어 같은 패키지의 시험이 직접 부른다. 길이·표본 수·중앙값은
     * 값을 앞으로 당겨 모아도 그대로라서, 정렬이 깨진 것을 응답 수준 단언이 잡지 못한다.</p>
     */
    static List<Double> pointSeries(List<Double> raw, int sweepPoints) {
        List<Double> series = new ArrayList<>(Math.max(raw.size(), sweepPoints));
        series.addAll(raw);
        while (series.size() < sweepPoints) series.add(null);
        return series;
    }

    /**
     * 지표가 이 전략·이 셀에서 순위 근거가 될 수 없는 사유. 없으면 null.
     *
     * <p>총량 계열은 <b>짝지어진 지점이 하나도 없을 때만</b> 무효다. 일부 지점만 짝이었다면 그 지점들의
     * 중앙값은 정당한 비교이고, 몇 곳이었는지는 {@code samples} 와 셀의 짝짓기 문장이 밝힌다.</p>
     *
     * <p>건당 전력은 <b>전 시각 지점에서 배치가 0건일 때만</b> 무효다. 첫 지점 하나로 판정하면 t=0 에
     * 0건이고 다른 지점에 배치한 전략의 정당한 중앙값이 통째로 사라진다 — 그래서 {@code placedAnywhere}
     * 는 셀 전체를 보고 온다. 온도 게이트·계측 노드 수는 셀 안에서 상수라 첫 지점으로 판정해도 된다.</p>
     */
    private static String invalidReason(BenchmarkMetric metric, Placement any,
                                        int usablePoints, int measuredNodes, boolean placedAnywhere,
                                        boolean baselinePresent, boolean paired) {
        String key = metric.key();
        if (BenchmarkRules.GAP_VS_PABFD.equals(key)) {
            // 대조군 집합을 갈아 끼운 실행에는 기준 열이 없을 수 있다. 그때 0 이나 빈 값을 내면 "격차가
            // 없다"로 읽히므로, 전 행이 사유를 달고 무효로 나간다.
            if (!baselinePresent) {
                return "기준 전략(전력 인지 최적적합)이 이 실행에 없다";
            }
            if (PABFD_ID.equals(any.strategy().id())) {
                return "기준 전략 자신 — 0 은 값이 아니다";
            }
            // 같은 행의 총량 열은 전용 사유를 다는데 격차만 "값이 나오지 않았다"로 나가면 원인을 되짚을 수
            // 없다. 이 열이 비는 이유는 전 전략이 아니라 기준 전략 하나와 어긋났다는 것이다.
            if (!paired) {
                return "기준 전략과 배치 건수가 같은 시각 지점이 없다";
            }
        }
        if ((BenchmarkRules.GENERATOR_POWER.equals(key) || BenchmarkRules.ACTIVE_NODE_DELTA.equals(key))
                && usablePoints == 0) {
            return "전 전략의 배치 건수가 같은 시각 지점이 없다 — 적게 배치하고 전력을 덜 쓰는 것은 승리가 아니다";
        }
        if ((BenchmarkRules.TEMP_STDDEV.equals(key) || BenchmarkRules.WARN_ZONE_NODES.equals(key))
                && usablePoints == 0) {
            return "전 전략의 배치 건수가 같은 시각 지점이 없다 — 적게 배치해 CPU 를 덜 얹은 노드가 덜 뜨거운 것은 "
                    + "열 성능이 아니다";
        }
        if (BenchmarkRules.PER_WORKLOAD_POWER.equals(key) && !placedAnywhere) {
            return "전 시각 지점에서 배치 0건이라 건당 값을 낼 수 없다";
        }
        if (BenchmarkRules.C3_INTERVENTIONS.equals(key) && !any.temperatureGated()) {
            return "이 전략은 온도 제약이 꺼져 있어 항상 0 이다 — 켜진 전략과 비교하지 않는다";
        }
        // 제약을 끈 변종은 막히는 자리가 없어 성공률이 정의상 최고가 된다. 개입 횟수(위)에만 가드를 걸고
        // 성공률에 안 걸면, 반칙으로 나빠 보이는 칸만 가리고 반칙으로 좋아 보이는 칸은 그대로 두는 셈이다.
        // 온도·전력 게이트를 따로 본다 — 둘 중 하나만 끈 변종이 생기면 온도 플래그 하나로는 못 걸러진다.
        if (BenchmarkRules.PLACEMENT_RATE.equals(key) && !(any.temperatureGated() && any.powerGated())) {
            return "이 전략은 온도·전력 제약이 꺼져 있어 막히는 자리가 없다 — 제약을 받는 전략과 성공률을 "
                    + "비교하지 않는다. 이 열은 경쟁자가 아니라 '제약이 없었다면 얼마나 배치됐을까'의 천장이다";
        }
        if ((BenchmarkRules.TEMP_STDDEV.equals(key) || BenchmarkRules.WARN_ZONE_NODES.equals(key))
                && measuredNodes < 2) {
            return "계측 노드가 둘 미만이라 온도 분포를 말할 수 없다";
        }
        return null;
    }

    // ── 지표 계산 ────────────────────────────────────────────

    /**
     * 생성식 기준 총전력.
     *
     * <p>{@code AVAILABLE} 판에서 통전된 노드는 <b>배치 전부터 부하가 있던 노드와 이번에 배치한 노드</b>다 —
     * 부하가 도는 노드를 끌 수는 없으므로 "안 쓰는 노드는 꺼져 있다"를 그 노드에까지 적용하면 없는
     * 절감을 만들어 낸다.</p>
     */
    private static Double power(GroundTruthOracle oracle, PointOutcome point, Placement p, PowerControl mode) {
        return round(oracle.clusterPowerWatts(p.cpuAfterByNode(), powered(point, p), mode));
    }

    /** 배치 1건당 늘어난 생성식 기준 전력. 깨우기 비용은 {@code AVAILABLE} 판에서만 붙는다. */
    private static Double perWorkload(GroundTruthOracle oracle, PointOutcome point,
                                      Placement p, PowerControl mode) {
        if (p.placed() == 0) return null;
        double after = oracle.clusterPowerWatts(p.cpuAfterByNode(), powered(point, p), mode);
        double before = oracle.clusterPowerWatts(point.cpuBeforeByNode(), point.activeBefore(), mode);
        return round((after - before) / p.placed());
    }

    /**
     * 통전 판정 — {@link BenchmarkRules#POWERED_ON_CPU_PERCENT} 로만 본다.
     *
     * <p>{@code PowerModel.isActive} 를 쓰지 않는 이유는 그 메서드가 엔진 설정을 읽기 때문이다. 판정식
     * 자체는 같다({@code 실효 CPU% > 문턱}) — {@code PlacementContext.activeNodeCount} 도 같은 부등호를
     * 쓰므로 배치 전(여기)과 배치 후의 셈이 어긋나지 않는다. 경계값을 포함하는 방향까지 같아야 한다.</p>
     */
    private static boolean poweredOn(NodeResource node) {
        return node.effectiveCpuUsagePercent() > BenchmarkRules.POWERED_ON_CPU_PERCENT;
    }

    private static Set<String> powered(PointOutcome point, Placement p) {
        Set<String> on = new LinkedHashSet<>(point.activeBefore());
        on.addAll(p.usedNodes());
        return on;
    }

    /**
     * 계측 노드의 <b>생성식 온도</b> 표준편차. 계측 노드가 둘 미만이면 0(무효 사유는 따로 붙는다).
     *
     * <p>엔진의 {@code effectiveTemperature}(= 실측 × (1 + inertiaK × 예약비율))를 쓰지 않는다. 그 식은
     * <b>엔진의 열관성 가정</b>이고 {@code inertiaK} 는 엔진 튜닝 노브다 — 그것으로 재면 열 엔진이 자기
     * 가정으로 채점받고, 노브를 돌리면 세계는 그대로인데 측정값이 움직인다. 대신 배치가 만든 CPU 분포를
     * 생성식에 넣어 같은 배치를 <b>평가자의 식</b>으로 다시 계산한다. 예약분은
     * {@code effectiveCpuUsagePercent} 에 이미 들어 있으므로 누락되지 않는다.</p>
     *
     * <p>바뀌는 것은 온도의 출처가 아니라 <b>예약 상승분 모델 하나</b>다 — 노드에 실린 온도 자체가 이미
     * 생성식 값이라 예약 0 이면 두 계산은 같다. 엔진 {@code T×(1+0.15r)} 대 생성식 {@code T+30r} 의 차이가
     * 이 지표를 움직이는 전부이고, 그 격차는 판의 {@code thermalRiseScaleCheck} 가 밝힌다.</p>
     *
     * <p>미계측 노드를 빼는 것은 그대로다 — 오라클은 미계측 노드의 온도도 알지만, 이 지표의 사전 등록 문구가
     * "계측 노드만"이고 계측 공백판의 뜻이 거기 있다. 필터를 풀면 sparse 판과 main 판의 온도 열이 같아진다.</p>
     */
    private static double measuredTempStdDev(PlacementContext ctx, GroundTruthOracle oracle) {
        List<Double> temps = ctx.nodes().stream()
                .filter(n -> n.getTemperatureCelsius() > 0)
                .map(n -> oracle.nodeTemperatureC(n.getNodeId(), n.effectiveCpuUsagePercent()))
                .toList();
        if (temps.size() < 2) return 0;

        double mean = temps.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = temps.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / temps.size();
        return Math.sqrt(variance);
    }

    /**
     * 경고 구간에 든 <b>계측</b> 노드 수 — 온도는 생성식, 경계는 정책(엔진 노브)이다.
     * 미계측 노드는 온도 판단 근거가 없어 셈에서 뺀다. 반은 생성식·반은 엔진 노브라는 사실은 지표의
     * 라벨과 유효성 문구가 함께 밝힌다 — 경계를 바꾸면 세계가 그대로인데 숫자가 움직인다.
     */
    private int warnZoneNodes(PlacementContext ctx, SchedulingPolicy policy, GroundTruthOracle oracle) {
        double warning = policy.getWarningTemperatureCelsius() != null
                ? policy.getWarningTemperatureCelsius()
                : thermalModel.defaultWarningCelsius();

        return (int) ctx.nodes().stream()
                .filter(n -> n.getTemperatureCelsius() > 0)
                .filter(n -> oracle.nodeTemperatureC(n.getNodeId(), n.effectiveCpuUsagePercent()) >= warning)
                .count();
    }

    /**
     * 열 때문에 후보가 막힌 횟수 — 사유 문구로 센다.
     *
     * <p>제약 종류를 코드가 돌려주지 않아 문구를 볼 수밖에 없다. 온도 계열 사유 넷이 모두 "온도" 를
     * 담고 냉각 대기만 "냉각" 을 담으므로 둘을 함께 본다. 사유 문구가 바뀌면 이 셈이 조용히 0 이 되므로
     * 시험이 실제 제약을 걸어 값을 확인한다.</p>
     */
    private static int countThermalExclusions(SchedulingResult result) {
        int count = 0;
        for (NodeScore score : result.candidateScores()) {
            String reason = score.exclusionReason();
            if (reason != null && (reason.contains("온도") || reason.contains("냉각"))) count++;
        }
        return count;
    }

    private static Double spreadOf(Map<String, Double> rates) {
        if (rates.size() < 2) return null;
        double max = rates.values().stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        double min = rates.values().stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        return round(max - min);
    }

    /** 미계측 비율이 전략 간에 크게 갈리면 셀이 스스로 경고 문장을 만든다 — 각주에 의존하지 않는다. */
    private static String unmeasuredCaution(Map<String, Map<String, List<Double>>> samples,
                                            List<String> strategyIds) {
        Map<String, Double> medianRate = new LinkedHashMap<>();
        for (String id : strategyIds) {
            List<Double> series = samples.get(id)
                    .getOrDefault(BenchmarkRules.UNMEASURED_RATE, List.of())
                    .stream().filter(Objects::nonNull).toList();
            Double m = median(series);
            if (m != null) medianRate.put(id, m);
        }
        Double spread = spreadOf(medianRate);
        if (spread == null || spread < BenchmarkRules.UNMEASURED_SPREAD_CAUTION_PP) return null;

        String highest = medianRate.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElseThrow();
        String lowest = medianRate.entrySet().stream()
                .min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElseThrow();
        return BenchmarkRules.unmeasuredCaution(spread, highest, lowest);
    }

    private static Map<String, Integer> probeReasonCounts(List<PointOutcome> points) {
        Map<String, Integer> counts = new TreeMap<>();
        for (PointOutcome point : points) {
            for (String reason : point.probeExclusions().values()) {
                counts.merge(shortenReason(reason), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** 사유에서 숫자를 떼어 종류만 남긴다 — 숫자를 남기면 지점마다 다른 키가 되어 셀 수 없다. */
    private static String shortenReason(String reason) {
        int paren = reason.indexOf(" (");
        return paren > 0 ? reason.substring(0, paren) : reason;
    }

    // ── 전략과 정책 ──────────────────────────────────────────

    /**
     * 비교할 전략 전부 — 운영 엔진 전량 + 자원전용 두 변종 + 대조군({@link #BASELINE_ORDER}).
     *
     * <p>엔진은 레지스트리가 밝히는 목록에서 가져온다 — 여기에 이름을 적어 두면 엔진이 늘 때 조용히
     * 빠진다. 순서는 식별자로 정렬해 응답 열 순서를 빈 등록 순서에서 떼어 놓는다.</p>
     */
    private List<PlacementStrategy> strategies() {
        List<PlacementStrategy> all = new ArrayList<>();

        List<String> engineIds = engineRegistry.describe().stream()
                .map(m -> m.get("id")).sorted().toList();
        for (String id : engineIds) {
            all.add(PlacementStrategy.ofEngine(engineRegistry.resolve(id)));
        }

        // 자원전용 두 변종은 기본 엔진에 정책만 갈아 끼운 것이다 — 가중치를 안 봐도 제약이 막아 주는지,
        // 제약까지 없으면 얼마나 나빠지는지를 갈라 본다.
        SchedulingEngine weighted = engineRegistry.resolve(EngineRegistry.DEFAULT_ENGINE_ID);
        all.add(new PlacementStrategy("resource-only-gated", "자원전용(전력·온도 제약 유지)",
                PlacementStrategy.Kind.ENGINE, PlacementStrategy.PolicyVariant.RESOURCE_ONLY_GATED,
                null, weighted::schedule));
        all.add(new PlacementStrategy("resource-only-ungated", "자원전용(제약까지 해제)",
                PlacementStrategy.Kind.ENGINE, PlacementStrategy.PolicyVariant.RESOURCE_ONLY_UNGATED,
                null, weighted::schedule));

        Map<String, PlacementStrategy> baselines = new LinkedHashMap<>();
        baselines.put("ffd-size", BaselinePlacers.ffdBySize());
        baselines.put("ffd-priority-size", BaselinePlacers.ffdByPriorityThenSize());
        baselines.put(PABFD_ID, BaselinePlacers.pabfd(powerModel));
        baselines.put("bfd", BaselinePlacers.bfd());
        baselines.put("coolest-first", BaselinePlacers.coolestFirst());
        baselines.put(RANDOM_ID, BaselinePlacers.random(BenchmarkRules.RANDOM_SEEDS.get(0)));

        // 순서 목록과 구현 맵은 서로를 모른다 — 한쪽에만 있는 id 는 여기서 크게 실패해야 한다. 목록에만 있으면
        // null 이 담겨 한참 뒤 NPE, 맵에만 있으면 열이 조용히 빠진 채 응답은 200 이다. 시험 전용 이음매로 부분 집합을
        // 줄 수는 있으므로 검사는 "목록 ⊆ 맵" 과 "각 id 의 record id 일치" 다.
        for (String id : baselineOrder) {
            PlacementStrategy strategy = baselines.get(id);
            if (strategy == null) {
                throw new IllegalStateException("대조군 순서 목록에 구현이 없는 id 가 있다: " + id
                        + " (구현: " + baselines.keySet() + ")");
            }
            if (!id.equals(strategy.id())) {
                throw new IllegalStateException("대조군 맵의 키와 전략 id 가 다르다: " + id + " ≠ " + strategy.id());
            }
            all.add(strategy);
        }
        // 참조 비교(==)가 아니라 equals 다 — List.copyOf 가 같은 인스턴스를 돌려주는 것은 구현 노트일 뿐이라,
        // 참조로 걸면 어느 날 이 검사가 조용히 영구히 꺼진다. 그것이 바로 이 검사가 막으려는 사고다.
        if (BASELINE_ORDER.equals(baselineOrder) && !baselines.keySet().equals(Set.copyOf(BASELINE_ORDER))) {
            throw new IllegalStateException("구현은 있는데 순서 목록에 없는 대조군이 있다 — 열이 조용히 빠진다: "
                    + baselines.keySet() + " vs " + BASELINE_ORDER);
        }

        return all;
    }

    /** 이 전략이 볼 정책. 어떤 경우에도 <b>영속되지 않는 사본</b>이다. */
    private static SchedulingPolicy policyFor(PlacementStrategy strategy) {
        return policyFor(strategy.policy());
    }

    /** 변종 하나의 정책 사본. 설정 스냅샷도 이 메서드로 만든다 — 응답이 밝히는 정책과 돌린 정책이 갈리지 않게. */
    private static SchedulingPolicy policyFor(PlacementStrategy.PolicyVariant variant) {
        SchedulingPolicy base = baseline();
        return switch (variant) {
            case BASE -> base;
            case RESOURCE_ONLY_GATED -> BenchmarkPolicies.resourceOnly(base, true);
            case RESOURCE_ONLY_UNGATED -> BenchmarkPolicies.resourceOnly(base, false);
        };
    }

    /**
     * 이 실행이 본 엔진 설정과 정책 사본 전량.
     *
     * <p>정책은 전략이 실제로 쓰는 {@link #policyFor(PlacementStrategy.PolicyVariant)} 로 만든다 — 스냅샷을 따로
     * 조립하면 언젠가 두 벌이 갈려 응답이 밝히는 정책과 돌린 정책이 달라진다. 다이제스트의 정규화 문자열은
     * record 의 {@code toString} 이 아니라 <b>명시적 이름=값 목록</b>이다 — 필드 순서를 바꾸는 리팩터링에
     * 어제 낸 표와 오늘 낸 표가 "다른 설정"으로 보이면 안 된다.</p>
     */
    private EngineConfigSnapshot engineConfig() {
        SchedulerProperties.Power p = properties.getPower();
        SchedulerProperties.Thermal t = properties.getThermal();

        List<String> engineIds = engineRegistry.describe().stream().map(m -> m.get("id")).sorted().toList();
        PowerKnobs power = new PowerKnobs(p.getIdleRatio(), p.getActiveCpuPercent(),
                BenchmarkRules.POWERED_ON_CPU_PERCENT, p.getCurveAlpha(), p.getPeiWeight(),
                p.getWasteWeight(), p.getMarginalWeight(), p.getPeiNormalizer(),
                p.getResourceShare(), p.getDefaultCapacityWatts(),
                p.getCapacityByNode() == null ? Map.of() : new TreeMap<>(p.getCapacityByNode()),
                p.isIdleFromMeasurement(), p.getMarginalScaleWatts());
        ThermalKnobs thermal = new ThermalKnobs(t.getInertiaK(), t.getDefaultWarningCelsius(),
                t.getCooldownMinutes(), BenchmarkRules.COOLDOWN_MINUTES_FOR_BENCHMARK);

        List<PolicySnapshot> policies = new ArrayList<>();
        List<String> canonical = new ArrayList<>();
        canonical.add("engines=" + engineIds);
        canonical.add("idleRatio=" + power.idleRatio());
        canonical.add("activeCpuPercent=" + power.activeCpuPercent());
        canonical.add("poweredOn=" + power.benchmarkPoweredOnCpuPercent());
        canonical.add("curveAlpha=" + power.curveAlpha());
        canonical.add("peiWeight=" + power.peiWeight());
        canonical.add("wasteWeight=" + power.wasteWeight());
        canonical.add("marginalWeight=" + power.marginalWeight());
        canonical.add("peiNormalizer=" + power.peiNormalizer());
        canonical.add("resourceShare=" + power.resourceShare());
        canonical.add("defaultCapacityWatts=" + power.defaultCapacityWatts());
        canonical.add("capacityByNode=" + new TreeMap<>(power.capacityByNode()));
        // 모델 형태 노브 — 값이 아니라 식이 바뀌는 자리라, 빠뜨리면 어제 표와 오늘 표가 같은 설정으로 보인다
        canonical.add("idleFromMeasurement=" + power.idleFromMeasurement());
        canonical.add("marginalScaleWatts=" + power.marginalScaleWatts());
        canonical.add("inertiaK=" + thermal.inertiaK());
        canonical.add("defaultWarningCelsius=" + thermal.defaultWarningCelsius());
        canonical.add("cooldownMinutes=" + thermal.cooldownMinutes());
        canonical.add("benchmarkCooldown=" + thermal.benchmarkCooldownMinutes());
        for (PlacementStrategy.PolicyVariant variant : PlacementStrategy.PolicyVariant.values()) {
            SchedulingPolicy policy = policyFor(variant);
            PolicySnapshot snap = new PolicySnapshot(variant.name(),
                    policy.getCpuWeight(), policy.getMemoryWeight(), policy.getPowerWeight(),
                    policy.getTemperatureWeight(), policy.getNetworkWeight(),
                    policy.getMaxCpuUsagePercent(), policy.getMaxMemoryUsagePercent(),
                    policy.getMaxTemperatureCelsius(), policy.getMaxPowerUsagePercent(),
                    policy.getWarningTemperatureCelsius(),
                    policy.isPowerAwareEnabled(), policy.isTemperatureAwareEnabled(), policy.isAffinityEnabled());
            policies.add(snap);
            canonical.add(variant.name() + "=" + snap.cpuWeight() + "," + snap.memoryWeight() + ","
                    + snap.powerWeight() + "," + snap.temperatureWeight() + "," + snap.networkWeight() + ","
                    + snap.maxCpuUsagePercent() + "," + snap.maxMemoryUsagePercent() + ","
                    + snap.maxTemperatureCelsius() + "," + snap.maxPowerUsagePercent() + ","
                    + snap.warningTemperatureCelsius() + "," + snap.powerAwareEnabled() + ","
                    + snap.temperatureAwareEnabled() + "," + snap.affinityEnabled());
        }

        return new EngineConfigSnapshot(BenchmarkClusters.digestOf(String.join("|", canonical)),
                engineIds, power, thermal, policies);
    }

    /**
     * 비교의 기준 정책 — 코드에 선언된 기본값.
     *
     * <p>활성 정책을 읽지 않는다. 조회 경로가 정책이 없으면 기본 정책을 <b>새로 넣기</b> 때문에 진단
     * 호출이 데이터를 만들게 된다. 그 사실은 응답의 {@code policyBasis} 가 밝힌다.</p>
     */
    private static SchedulingPolicy baseline() {
        return new SchedulingPolicy("benchmark-base", "배치 비교용 기준 정책 (저장되지 않음)");
    }

    // ── 판 만들기 ────────────────────────────────────────────

    /**
     * 클러스터 파일의 노드를 한 시각의 {@link NodeResource} 로 만든다.
     *
     * <p>{@code private} 이 아닌 이유는 {@link #sweepPoints} 와 같다 — 같은 패키지의 시험이 이 스냅샷의
     * 전력이 오라클의 같은 시각 값과 같은지를 본다. 시험이 사본을 두면 두 세계가 갈라져도 초록불이 된다.</p>
     *
     * <p><b>정격 전력은 계측 여부와 무관하게 싣는다.</b> 운영에서도 정격은 원장에서 오고 IPMI 와 별개다 —
     * 계측이 없는 노드는 "지금 몇 W 인지"를 모르는 것이고 "몇 W 까지 쓸 수 있는지"를 모르는 게 아니다.
     * 이 구분이 계측 공백판의 뜻이다.</p>
     */
    static List<NodeResource> nodesAt(SyntheticCluster cluster, long t) {
        List<NodeResource> nodes = new ArrayList<>(cluster.nodes().size());
        for (SyntheticCluster.Node n : cluster.nodes()) {
            NodeResource node = new NodeResource(n.mdcId(), n.nodeId(), n.cores(), n.memoryMb(), n.gpus(),
                    cluster.cpuUsage(n, t), cluster.memoryUsage(n, t), cluster.diskUsage(n, t),
                    n.ipmi() ? cluster.powerWatts(n, t) : 0,
                    n.believedCapacityWatts(),   // 판 경로에서는 파일이 원장이다 — 엔진이 믿는 정격은 생성식 정격과 다를 수 있다
                    n.ipmi() ? cluster.temperature(n, t) : 0,
                    cluster.networkRx(n, t), cluster.networkTx(n, t))
                    .withDiskCapacityGb((long) n.diskTotalGb());
            node.setPowerSource(n.ipmi() ? PowerSource.IPMI : PowerSource.NONE);
            nodes.add(node);
        }
        return nodes;
    }

    private static Set<String> unmeasuredNodeIds(SyntheticCluster cluster) {
        Set<String> ids = new HashSet<>();
        for (SyntheticCluster.Node n : cluster.nodes()) {
            if (!n.ipmi()) ids.add(n.nodeId());
        }
        return ids;
    }

    /**
     * 부하율의 분모 — 총 코어가 아니라 <b>정책 상한까지의 여유</b>다.
     *
     * <p>{@link BenchmarkRules#HEADROOM_DEFINITION} 을 그대로 계산한다. 노드마다 구속 자원이 달라
     * (어떤 노드는 메모리가, 어떤 노드는 CPU 가 먼저 막힌다) 두 축의 최솟값을 쓴다. 총 코어를 쓰면
     * 90% 부하율이 실제로는 수용 불가가 되어 성공률이 전 전략에서 함께 무너지고, 그러면 부하율 축이
     * 전략을 구분하지 못한다.</p>
     */
    private static double headroomCores(List<NodeResource> nodes, SchedulingPolicy policy) {
        double total = 0;
        for (NodeResource node : nodes) {
            double byCpu = node.getTotalCpu()
                    * (policy.getMaxCpuUsagePercent() - node.effectiveCpuUsagePercent()) / 100.0;
            double byMemory = node.getTotalMemoryMb()
                    * (policy.getMaxMemoryUsagePercent() - node.effectiveMemoryUsagePercent()) / 100.0
                    / BenchmarkRules.MEMORY_MB_PER_CORE;
            total += Math.max(0, Math.min(byCpu, byMemory));
        }
        return total;
    }

    // ── 숫자 다루기 ──────────────────────────────────────────

    private static void add(Map<String, List<Double>> into, String key, Double value) {
        into.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }

    /** 중앙값. 빈 목록이면 null. */
    private static Double median(List<Double> values) {
        List<Double> sorted = values.stream().filter(Objects::nonNull).sorted().toList();
        if (sorted.isEmpty()) return null;
        int mid = sorted.size() / 2;
        double m = sorted.size() % 2 == 1
                ? sorted.get(mid)
                : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
        return round(m);
    }

    /** NaN·무한대는 <b>0 이 아니라 null</b> 로 낸다 — 0 은 "가장 좋은 값"으로 읽힌다. */
    private static Double round(double value) {
        if (!Double.isFinite(value)) return null;
        return Math.round(value * 10000.0) / 10000.0;
    }
}
