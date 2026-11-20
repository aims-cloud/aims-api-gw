package com.innogrid.aims.scheduler.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.innogrid.aims.scheduler.benchmark.BaselinePlacers;
import com.innogrid.aims.scheduler.benchmark.BenchmarkContexts;
import com.innogrid.aims.scheduler.benchmark.BenchmarkLinks;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules;
import com.innogrid.aims.scheduler.benchmark.BenchmarkWorkloads;
import com.innogrid.aims.scheduler.benchmark.GroundTruthOracle;
import com.innogrid.aims.scheduler.benchmark.PlacementStrategy;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.model.ExclusionReason;
import com.innogrid.aims.scheduler.model.LinkMatrix;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.metrics.SyntheticCluster;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.NodeResource.PowerSource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;
import com.innogrid.aims.scheduler.service.BenchmarkColumns.EngineVariant;

import static com.innogrid.aims.scheduler.service.BenchmarkColumns.RANDOM_ID;

/**
 * 배치 전략 비교 서비스에서 떼어 낸 <b>시각 지점 하나의 전 전략 배치</b> — 그 시각의 노드 스냅샷과 표본 워크로드를 만들고,
 * 비교 전략마다 같은 입력으로 배치해 결과를 모은다. 무작위 대조군은 사전 등록된 시드 전부를 돌려 중앙 표본을 고른다.
 *
 * <p>스프링 빈이 아니다. 서비스가 생성자에서 한 번 만들고, 생성자는 참조만 저장한다.</p>
 */
final class BenchmarkPointRunner {

    private final SchedulerProperties properties;

    /** 상태가 없는 모델이라 그대로 쓴다 — 엔진이 믿은 비용이 운영과 같은 설정에서 나와야 한다. */
    private final PowerModel powerModel;

    /**
     * 경고 온도 기본값 하나만 쓴다. <b>온도 값 계산에는 쓰지 않는다</b> — 엔진의 열관성 가정으로 지표를
     * 재면 열 엔진이 자기 가정으로 평가받는다. 온도는 {@link GroundTruthOracle}이 생성식으로 낸다.
     */
    private final ThermalModel thermalModel;

    private final BenchmarkColumns columns;

    BenchmarkPointRunner(SchedulerProperties properties, PowerModel powerModel, ThermalModel thermalModel,
                         BenchmarkColumns columns) {
        this.properties = properties;
        this.powerModel = powerModel;
        this.thermalModel = thermalModel;
        this.columns = columns;
    }

    /** 한 시각 지점의 전 전략 결과. 배치 결정은 여기서 끝나고, 전원 상태 회계는 뒤에서 붙는다. */
    record PointOutcome(double headroomCores, int requestedWorkloads, double requestedCores,
                                Map<String, Double> cpuBeforeByNode, Set<String> activeBefore,
                                int activeCountBefore, int measuredNodeCount,
                                Map<String, String> probeExclusions,
                                Map<String, Placement> byStrategy, boolean dataMdcAssigned) {
    }

    /**
     * 전략 하나가 한 시각 지점에서 낸 배치 결과. {@code headroomAfterCores} · {@code fragmentedHeadroomCores}는 배치를 마친 뒤
     * 노드 여유의 합과 그 가운데 분포의 가장 큰 계층을 하나 더 담지 못하는 자투리의 합이다(둘 다 기준 정책 상한까지 · 코어).
     * {@code placedCores}는 배치한 워크로드의 요구 코어 합이다. {@code thermalExclusions} · {@code powerExclusions}는 시도마다 열 · 전력 까닭으로
     * 빠진 후보 노드 수의 합이다.
     */
    record Placement(PlacementStrategy strategy, int placed, int requested,
                             double engineBelievedCostW, Set<String> usedNodes,
                             Map<String, Double> cpuAfterByNode, int unmeasuredPlacements,
                             int thermalExclusions, int powerExclusions, int activeCountAfter,
                             double measuredTempStdDev, int warnZoneNodes,
                             boolean temperatureGated, boolean powerGated,
                             int dataMdcPlacements, int remoteMdcPlacements,
                             double headroomAfterCores, double fragmentedHeadroomCores, double placedCores) {
    }

    PointOutcome runPoint(SyntheticCluster cluster, BenchmarkWorkloads workloads,
                                  int loadRatio, long t, GroundTruthOracle oracle) {
        SchedulingPolicy basePolicy = BenchmarkColumns.baseline();
        int largestCores = largestClassCores(workloads);
        List<NodeResource> snapshot = nodesAt(cluster, t);

        double headroom = headroomCores(snapshot, basePolicy);
        List<Workload> sampled = workloads.sample(headroom * loadRatio / 100.0);
        assignDataMdc(sampled, cluster);
        assignRequiredBandwidth(sampled, cluster);
        LinkMatrix links = BenchmarkLinks.matrixOf(cluster);
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
            // 프로브도 전략과 같은 링크 행렬을 본다 — 전 전략 입력 동일성은 프로브까지 포함한다
            probe = BenchmarkContexts.exclusions(sampled.get(0),
                    BenchmarkContexts.copyOf(snapshot, basePolicy, probeEvaluator, links));
        }

        Map<String, Placement> byStrategy = new LinkedHashMap<>();
        Map<String, EngineVariant> variants = new LinkedHashMap<>();
        for (EngineVariant v : columns.engineVariants()) variants.put(v.strategy().id(), v);
        for (PlacementStrategy strategy : columns.strategies()) {
            EngineVariant v = variants.get(strategy.id());
            byStrategy.put(strategy.id(), run(strategy, sampled, snapshot, cluster, oracle, links,
                    v == null ? powerModel : v.believedCostModel(), v == null ? Map.of() : v.override(),
                    basePolicy, largestCores));
        }

        return new PointOutcome(headroom, sampled.size(), requestedCores,
                cpuBefore, activeBefore, activeBefore.size(), measuredNodes, probe, byStrategy,
                !cluster.workloadDataMdcIds().isEmpty());
    }

    /**
     * 전략 하나를 한 시각 지점에서 돌린다.
     *
     * <p>무작위 전략은 <b>사전 등록된 시드 전부</b>를 돌려 중앙 표본을 쓴다. 시드 하나는 표본 1개라
     * 그 값이 좋았는지 나빴는지 말할 수 없다.</p>
     */
    private Placement run(PlacementStrategy strategy, List<Workload> sampled,
                          List<NodeResource> snapshot, SyntheticCluster cluster,
                          GroundTruthOracle oracle, LinkMatrix links, PowerModel believedCostModel,
                          Map<String, String> override, SchedulingPolicy basePolicy, int largestCores) {
        if (!RANDOM_ID.equals(strategy.id())) {
            return runOnce(strategy, sampled, snapshot, cluster, oracle, links, believedCostModel, override,
                    basePolicy, largestCores);
        }

        List<Placement> draws = new ArrayList<>(BenchmarkRules.RANDOM_SEEDS.size());
        for (long seed : BenchmarkRules.RANDOM_SEEDS) {
            draws.add(runOnce(BaselinePlacers.random(seed), sampled, snapshot, cluster, oracle, links, believedCostModel, override,
                    basePolicy, largestCores));
        }
        return medianDraw(draws);
    }

    /**
     * {@code basePolicy} · {@code largestCores}는 자원 조각화 비율의 잣대다 — 열마다 같은 기준 정책 상한과 분포의 가장 큰 계층으로
     * 재야 열 사이에 견줄 수 있어, 전략의 정책({@code policy})이 아니라 시각 지점에서 한 번 정한 값을 받는다.
     */
    private Placement runOnce(PlacementStrategy strategy, List<Workload> sampled,
                              List<NodeResource> snapshot, SyntheticCluster cluster,
                              GroundTruthOracle oracle, LinkMatrix links, PowerModel believedCostModel,
                              Map<String, String> override, SchedulingPolicy basePolicy, int largestCores) {
        SchedulingPolicy policy = BenchmarkColumns.policyFor(strategy, override);
        ConstraintEvaluator evaluator = BenchmarkContexts.freshEvaluator(properties);
        PlacementContext ctx = BenchmarkContexts.copyOf(snapshot, policy, evaluator, links);

        List<Workload> ordered = strategy.usesArrivalOrder()
                ? sampled
                : sampled.stream().sorted(strategy.workloadOrder()).toList();

        Set<String> unmeasuredNodes = unmeasuredNodeIds(cluster);
        Set<String> used = new LinkedHashSet<>();
        int placed = 0;
        double placedCores = 0;
        int unmeasured = 0;
        int thermal = 0;
        int power = 0;
        int dataMdcPlaced = 0;
        int remote = 0;
        double believedCost = 0;

        for (Workload workload : ordered) {
            SchedulingResult result = strategy.placer().place(workload, ctx);
            thermal += countThermalExclusions(result);
            power += countPowerExclusions(result);
            if (!result.success()) continue;

            String nodeId = result.selectedNodeId();
            NodeResource node = ctx.node(nodeId).orElseThrow(() -> new IllegalStateException(
                    "전략 " + strategy.id() + " 가 판에 없는 노드를 골랐다: " + nodeId));

            // 엔진이 믿은 비용은 배치 직전 상태에서 재야 한다 — 예약을 잡은 뒤에는 다른 값이 된다.
            believedCost += believedCostModel.realMarginalPower(node, workload.getRequiredCpu());

            ctx.reserve(nodeId, workload);
            used.add(nodeId);
            placed++;
            placedCores += workload.getRequiredCpu();
            if (unmeasuredNodes.contains(nodeId)) unmeasured++;
            Boolean isRemote = remoteMdc(node.getMdcId(), workload.getDataMdcId());
            if (isRemote != null) {
                dataMdcPlaced++;
                if (isRemote) remote++;
            }
        }

        Map<String, Double> cpuAfter = new LinkedHashMap<>();
        double headroomAfter = 0;
        double fragmented = 0;
        for (NodeResource node : ctx.nodes()) {
            cpuAfter.put(node.getNodeId(), node.effectiveCpuUsagePercent());
            double h = nodeHeadroomCores(node, basePolicy);
            headroomAfter += h;
            fragmented += fragmentOf(h, largestCores);
        }

        return new Placement(strategy, placed, ordered.size(), believedCost, used, cpuAfter,
                unmeasured, thermal, power, ctx.activeNodeCount(BenchmarkRules.POWERED_ON_CPU_PERCENT),
                measuredTempStdDev(ctx, oracle), warnZoneNodes(ctx, policy, oracle),
                policy.isTemperatureAwareEnabled(), policy.isPowerAwareEnabled(), dataMdcPlaced, remote,
                headroomAfter, fragmented, placedCores);
    }

    /**
     * 노드 여유 하나에서 가장 큰 계층({@code largestCores} 코어)을 하나 더 담지 못하는 자투리 — {@code h − R × ⌊h / R⌋}.
     * R 코어 배치는 여유를 R만큼 줄여 자투리를 바꾸지 않으므로, 이 값을 움직이는 것은 R보다 작은 배치다.
     *
     * <p>패키지 안에서 보이게 둔 것은 테스트가 직접 부르기 위해서다.</p>
     */
    static double fragmentOf(double headroomCores, int largestCores) {
        return headroomCores - largestCores * Math.floor(headroomCores / largestCores);
    }

    /**
     * 분포 파일 계층 가운데 가장 큰 요구 코어 — 자원 조각화 비율의 기준 크기 R. 규칙 상수로 두지 않고 분포에서 읽어, 분포 파일이
     * 바뀌면 R도 함께 바뀌고 그 변경은 분포 축에서 드러난다. 코어를 요구하는 계층이 없으면 자투리가 정의되지 않아 실패한다.
     *
     * <p>패키지 안에서 보이게 둔 것은 테스트가 직접 부르기 위해서다.</p>
     */
    static int largestClassCores(BenchmarkWorkloads workloads) {
        int largest = workloads.classes().stream().mapToInt(BenchmarkWorkloads.WorkloadClass::cores).max().orElse(0);
        if (largest <= 0) {
            throw new IllegalStateException("분포 파일에 코어를 요구하는 계층이 없어 자원 조각화 비율의 기준 크기를 정할 수 없다");
        }
        return largest;
    }

    /**
     * 배치 하나가 원격인가 — 노드의 MDC가 워크로드의 데이터 MDC와 다르면 참이다. 둘 중 하나라도 없으면 원격인지 말할 수 없어
     * null이고, 그 배치는 원격 MDC 배치 비율의 분모에서 빠진다. 판 파일의 사실만 보고 엔진의 링크 점수는 보지 않는다.
     *
     * <p>패키지 안에서 보이게 둔 것은 테스트가 직접 부르기 위해서다 — 등록된 판은 노드 MDC가 모두 있어 null 가지를 응답으로는 볼 수 없다.</p>
     */
    static Boolean remoteMdc(String nodeMdcId, String dataMdcId) {
        if (nodeMdcId == null || dataMdcId == null) return null;
        return !nodeMdcId.equals(dataMdcId);
    }

    /**
     * 무작위 시드들의 중앙 표본.
     *
     * <p>지표별로 따로 중앙값을 내지 않고 <b>뽑기 하나를 고른다</b> — 지표마다 다른 뽑기의 값을 섞으면
     * 어느 뽑기에서도 실제로 일어나지 않은 조합이 나온다(전력은 A의 값, 성공률은 B의 값).</p>
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
                        .thenComparing(BenchmarkPointRunner::usedNodeKey))
                .toList();
        return sorted.get(sorted.size() / 2);
    }

    /** 쓴 노드 집합을 정렬 가능한 문자열로. 좋고 나쁨과 무관한 순서라 중앙 고르기를 왜곡하지 않는다. */
    private static String usedNodeKey(Placement draw) {
        return String.join(",", new TreeSet<>(draw.usedNodes()));
    }

    /**
     * 통전 판정 — {@link BenchmarkRules#POWERED_ON_CPU_PERCENT}로만 본다.
     *
     * <p>{@code PowerModel.isActive}를 쓰지 않는 이유는 그 메서드가 엔진 설정을 읽기 때문이다. 판정식
     * 자체는 같다({@code 실효 CPU% > 문턱}) — {@code PlacementContext.activeNodeCount}도 같은 부등호를
     * 쓰므로 배치 전(여기)과 배치 후의 셈이 어긋나지 않는다. 경계값을 포함하는 방향까지 같아야 한다.</p>
     */
    private static boolean poweredOn(NodeResource node) {
        return node.effectiveCpuUsagePercent() > BenchmarkRules.POWERED_ON_CPU_PERCENT;
    }

    /**
     * 계측 노드의 <b>생성식 온도</b> 표준편차. 계측 노드가 둘 미만이면 0(무효 사유는 따로 붙는다).
     *
     * <p>엔진의 {@code effectiveTemperature}(= 실측 × (1 + inertiaK × 예약비율))를 쓰지 않는다. 그 식은
     * <b>엔진의 열관성 가정</b>이고 {@code inertiaK}는 엔진 튜닝 노브다 — 그것으로 재면 열 엔진이 자기
     * 가정으로 평가받고, 노브를 돌리면 세계는 그대로인데 측정값이 움직인다. 대신 배치가 만든 CPU 분포를
     * 생성식에 넣어 같은 배치를 <b>평가자의 식</b>으로 다시 계산한다. 예약분은
     * {@code effectiveCpuUsagePercent}에 이미 들어 있으므로 누락되지 않는다.</p>
     *
     * <p>바뀌는 것은 온도의 출처가 아니라 <b>예약 상승분 모델 하나</b>다 — 노드에 실린 온도 자체가 이미
     * 생성식 값이라 예약 0이면 두 계산은 같다. 엔진 {@code T×(1+0.15r)} 대 생성식 {@code T+30r}의 차이가
     * 이 지표를 움직이는 전부이고, 그 격차는 판의 {@code thermalRiseScaleCheck}가 밝힌다.</p>
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
     * 경고 구간에 든 <b>계측</b> 노드 수 — 온도는 생성식, 경고 온도는 정책(엔진 노브)이다.
     * 미계측 노드는 온도 판단 근거가 없어 셈에서 뺀다. 반은 생성식·반은 엔진 노브라는 사실은 지표의
     * 라벨과 유효성 문구가 함께 밝힌다 — 경고 온도를 바꾸면 세계가 그대로인데 숫자가 움직인다.
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
     * 열 때문에 후보가 막힌 횟수 — 제외 까닭 코드로 센다.
     *
     * <p>사유 문구로 세면 문구를 한 글자만 고쳐도 셈이 조용히 0이 되므로, 온도 계열 넷을 가르는
     * {@link ExclusionReason#isThermal()}로 센다. 패키지 안에서 보이게 둔 것은 테스트가 이 셈을 직접 부르기 위해서다.
     * 등록된 판에서 이 지표의 중앙값은 모든 셀에서 0이고 최댓값만 대부분의 셀에서 0보다 커, 판 응답의 숫자만으로는
     * 무엇을 세는지 가려낼 수 없다.</p>
     */
    static int countThermalExclusions(SchedulingResult result) {
        int count = 0;
        for (NodeScore score : result.candidateScores()) {
            if (score.exclusionCode() != null && score.exclusionCode().isThermal()) count++;
        }
        return count;
    }

    /**
     * 전력 때문에 후보가 막힌 횟수 — 제외 까닭 코드로 센다({@link ExclusionReason#isPower()}). 열 막힘 셈과 같은 자리에서 시도마다 세고,
     * 앞 제약에서 먼저 걸린 노드는 그 까닭으로 남아 여기서 세지 않는다.
     *
     * <p>판에는 MDC 예산 · 워크로드 예산 · 실행 예산 입력이 없어 실제로 걸리는 까닭은 전력 여유 부족과 배치 후 예상 전력 초과 둘이다.
     * 패키지 안에서 보이게 둔 것은 테스트가 이 셈을 직접 부르기 위해서다.</p>
     */
    static int countPowerExclusions(SchedulingResult result) {
        int count = 0;
        for (NodeScore score : result.candidateScores()) {
            if (score.exclusionCode() != null && score.exclusionCode().isPower()) count++;
        }
        return count;
    }

    /**
     * 표본 워크로드에 판이 선언한 데이터 MDC를 <b>순환 배정</b>한다 — 표본 순서가 결정적이라 배정도 결정적이고, 전략 실행 전에 끝나
     * 전 열이 같은 목록을 본다. 절이 없는 판은 아무것도 하지 않는다(기존 판의 게이트는 꺼진 채다). {@code sample()}이 호출마다 새
     * 객체를 만들므로 공유 인스턴스가 오염될 길은 없다.
     */
    static void assignDataMdc(List<Workload> sampled, SyntheticCluster cluster) {
        List<String> ids = cluster.workloadDataMdcIds();
        if (ids.isEmpty()) return;
        for (int i = 0; i < sampled.size(); i++) sampled.get(i).setDataMdcId(ids.get(i % ids.size()));
    }

    /**
     * 요구 대역폭 순환 배정 — 목록의 {@code 0}은 요구 없음이라 null로 둔다(그 워크로드는 부족분 항이 꺼진다).
     *
     * <p>{@code assignDataMdc}와 같은 자리에서 같은 표본 순서로 돌린다. 표본은 {@code sample()}이 판마다 새로 만든
     * 객체라 공유 인스턴스를 오염시키지 않고, 전 열이 같은 목록을 본다.</p>
     */
    static void assignRequiredBandwidth(List<Workload> sampled, SyntheticCluster cluster) {
        List<Double> values = cluster.workloadBandwidthMbps();
        if (values.isEmpty()) return;
        for (int i = 0; i < sampled.size(); i++) {
            double v = values.get(i % values.size());
            sampled.get(i).setRequiredBandwidthMbps(v > 0 ? v : null);
        }
    }

    /**
     * 클러스터 파일의 노드를 한 시각의 {@link NodeResource}로 만든다.
     *
     * <p>{@code private}이 아닌 이유는 {@link PlacementBenchmarkService#sweepPoints}와 같다 — 같은 패키지의 테스트가 이 스냅샷의
     * 전력이 오라클의 같은 시각 값과 같은지를 본다. 테스트가 사본을 두면 두 세계가 갈라져도 통과한다.</p>
     *
     * <p><b>정격 전력은 계측 여부와 무관하게 싣는다.</b> 운영에서도 정격은 원장에서 오고 IPMI와 별개다 —
     * 계측이 없는 노드는 "지금 몇 W인지"를 모르는 것이고 "몇 W까지 쓸 수 있는지"를 모르는 게 아니다.
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
     * <p>{@link BenchmarkRules#HEADROOM_DEFINITION}을 그대로 계산한다. 노드마다 구속 자원이 달라
     * (어떤 노드는 메모리가, 어떤 노드는 CPU가 먼저 막힌다) 두 축의 최솟값을 쓴다. 총 코어를 쓰면
     * 90% 부하율이 실제로는 수용 불가가 되어 성공률이 전 전략에서 함께 무너지고, 그러면 부하율 축이
     * 전략을 구분하지 못한다.</p>
     */
    private static double headroomCores(List<NodeResource> nodes, SchedulingPolicy policy) {
        double total = 0;
        for (NodeResource node : nodes) {
            total += nodeHeadroomCores(node, policy);
        }
        return total;
    }

    /**
     * 노드 하나의 여유(코어) — {@link #headroomCores}의 노드 항. 자원 조각화 비율이 배치를 마친 노드에 같은 식을 쓰므로 떼어 냈다.
     * 식과 연산 순서는 떼어 내기 전과 같다 — 바뀌면 부하율 분모가 달라져 기존 지표가 전부 움직인다.
     *
     * <p>패키지 안에서 보이게 둔 것은 테스트가 직접 부르기 위해서다.</p>
     */
    static double nodeHeadroomCores(NodeResource node, SchedulingPolicy policy) {
        double byCpu = node.getTotalCpu()
                * (policy.getMaxCpuUsagePercent() - node.effectiveCpuUsagePercent()) / 100.0;
        double byMemory = node.getTotalMemoryMb()
                * (policy.getMaxMemoryUsagePercent() - node.effectiveMemoryUsagePercent()) / 100.0
                / BenchmarkRules.MEMORY_MB_PER_CORE;
        return Math.max(0, Math.min(byCpu, byMemory));
    }
}
