package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.benchmark.BenchmarkClusters;
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
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.TemperatureLimitCheck;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.ThermalRiseScale;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.WakeupReconstruction;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.WorkloadClassShare;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.SchedulingEngine;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.metrics.SyntheticCluster;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;

/**
 * 배치 전략 비교 — 우리 엔진과 대조군을 <b>같은 판, 같은 워크로드, 같은 제약</b>에서 돌린다.
 *
 * <p>이 서비스는 <b>저장소에는 아무것도 쓰지 않는다.</b> 워크로드는 파일에 선언된 분포에서 뽑은 비영속 객체,
 * 정책은 코드 기본값의 사본, 노드는 클러스터 파일의 스냅샷이다. 저장소를 하나도 주입받지 않으므로 저장할 경로
 * 자체가 없다. 저장된 워크로드를 쓰면 예약 적용 경로가 같은 워크로드를 예약으로 한 번, 배치 대상으로 또 한 번
 * 세어 요구량이 두 배가 되고, 활성 정책 조회 경로는 정책이 없으면 기본 정책을 <b>새로 넣어</b> 진단 호출이
 * 데이터를 만든다. 둘 다 응답만 봐서는 드러나지 않는다. 같은 입력의 응답 본문은 프로세스 메모리에 보관하며
 * 재시작하면 사라진다(아래 {@code cache}).</p>
 *
 * <p><b>대조군은 엔진이 아니다.</b> {@link PlacementStrategy}는 {@link SchedulingEngine}을 구현하지 않는 record라
 * 엔진 레지스트리가 수집할 수 없다. 대조군이 빈이 되면 엔진 식별자 검증이 열려 스케줄이 대조군으로 <b>실배치</b>하고
 * 지우지 않는 이력에 남는다.</p>
 *
 * <p><b>전원 상태 두 가정에서 배치를 두 번 돌리지 않는다.</b> 전원 제어 가정은 전략의 입력이 아니므로 결정은 한 번만
 * 하고 같은 결정을 두 가정으로 <b>회계</b>한다(docs/14 2.2절). 두 번 돌리면 계산량도 두 배가 된다.</p>
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
     * 대조군의 순서 — 정의는 {@link BenchmarkColumns#BASELINE_ORDER}. 같은 패키지의 테스트가 이 이름으로 읽어 별칭을 남긴다.
     */
    static final List<String> BASELINE_ORDER = BenchmarkColumns.BASELINE_ORDER;

    /** 엔진 쪽 변종 열의 순서 — 정의는 {@link BenchmarkColumns#ENGINE_VARIANT_ORDER}. 테스트가 이 이름으로 읽는다. */
    static final List<String> ENGINE_VARIANT_ORDER = BenchmarkColumns.ENGINE_VARIANT_ORDER;

    private final SchedulerProperties properties;
    private final BenchmarkClusters clusters;

    /** 비교 전략 목록 · 정책 사본 · 설정 스냅샷을 조립한다. 호출할 때마다 다시 조립한다. */
    private final BenchmarkColumns columns;

    /** 시각 지점 하나에서 전 전략을 배치한다. */
    private final BenchmarkPointRunner runner;

    /** 시각 지점 결과를 셀(결과 표 한 칸)로 조립한다. */
    private final BenchmarkCellAssembler assembler;

    /** 상태가 없는 모델이라 그대로 쓴다 — 엔진이 믿은 비용이 운영과 같은 설정에서 나와야 한다. */
    private final PowerModel powerModel;

    /**
     * 순수성 캐시 — 키는 {@link #CACHE_AXES} 순서의 {@code 판|분포|판 파일 다이제스트|워크로드 파일 다이제스트|엔진 설정 다이제스트|규칙 축 다이제스트}.
     *
     * <p>응답은 {@code evaluatedAt}을 빼면 입력의 순수 함수다(docs/13 8장, 결정론 테스트가 확인한다). 그런데 한 번
     * 호출이 판 하나에 약 2,000회 배치 시뮬레이션이고 배포 서버는 2 vCPU를 스케줄러 실행 루프와 나눠 쓴다. 같은 입력을
     * 다시 계산할 이유가 없으므로 본문을 키로 보관하고, 적중 시 <b>응답 시각만 새로 찍어</b> 돌려준다. 판 파일이나 설정이
     * 바뀌면 키가 바뀌어 자연히 빗나간다. 상한을 넘으면 WARN을 남기고 전부 비운다 — 판 10 × 분포 2 = 20 조합(설정·규칙은 프로세스
     * 안에서 고정, seed는 판 파일 값이라 요청 축이 아니다)이라 상한 25에는 닿지 않으며, 닿았다면 키 축 계산이 틀렸다는 신호다.</p>
     */
    private final Map<String, PlacementBenchmarkResponse> cache = new ConcurrentHashMap<>();
    private static final int CACHE_LIMIT = 25;

    /** 캐시 키의 입력 축 이름 — 순서가 키 순서다. 축을 더하면 이 목록과 테스트의 리터럴이 함께 바뀌어야 한다. */
    static final List<String> CACHE_AXES = List.of("board", "distribution", "boardDigest", "workloadDigest", "configDigest", "rulesDigest");
    /** 판정 규칙 축 전부의 다이제스트 — 규칙 상수(시드·지점 수·문턱)가 바뀌면 옛 표가 캐시에서 나가지 않게 키에 넣는다. 프로세스 안에서 상수다. */
    private static final String RULES_DIGEST = BenchmarkClusters.digestOf(String.join("|", BenchmarkRules.ruleAxes().values()));

    /**
     * 동시 실행 1건 — 두 번째 호출은 기다리지 않고 429로 돌려보낸다.
     *
     * <p>기다리게 하면 요청 스레드가 묶이고 호출자는 응답이 늦는 이유를 모른다. 지금 계산 중인 것과 같은 입력이면 곧 캐시에
     * 실릴 테니 잠시 뒤 다시 부르면 되고, 다른 입력이면 순서대로 부르는 편이 CPU를 공유하는 배포 서버에 안전하다.</p>
     */
    private final Semaphore running = new Semaphore(1);
    private static final Logger log = LoggerFactory.getLogger(PlacementBenchmarkService.class);

    /** 스프링이 쓰는 생성자 — 생성자가 둘이라 어느 것인지 표시해야 한다. 대조군은 항상 {@link #BASELINE_ORDER} 전량. */
    @Autowired
    public PlacementBenchmarkService(EngineRegistry engineRegistry, SchedulerProperties properties,
                                    BenchmarkClusters clusters,
                                    PowerModel powerModel, ThermalModel thermalModel) {
        this(engineRegistry, properties, clusters, powerModel, thermalModel, BASELINE_ORDER);
    }

    /**
     * 대조군 집합을 바꿔 넣는 생성자 — <b>테스트 전용</b>이라 패키지 밖에서 보이지 않는다.
     *
     * <p>대조군 하나를 넣고 빼면서 "기존 열의 어느 숫자가 움직여도 되는가"를 고정하려면 같은 판을 두 집합으로
     * 돌려야 한다. 스프링은 공개 생성자만 쓰므로 운영 경로가 이 인자를 볼 길은 없다.</p>
     */
    PlacementBenchmarkService(EngineRegistry engineRegistry, SchedulerProperties properties,
                             BenchmarkClusters clusters, PowerModel powerModel, ThermalModel thermalModel,
                             List<String> baselineOrder) {
        this.properties = properties;
        this.clusters = clusters;
        this.powerModel = powerModel;
        this.columns = new BenchmarkColumns(engineRegistry, properties, powerModel, thermalModel, baselineOrder);
        this.runner = new BenchmarkPointRunner(properties, powerModel, thermalModel, columns);
        this.assembler = new BenchmarkCellAssembler(columns);
    }

    // ── 진입점 ───────────────────────────────────────────────

    /**
     * 판 하나를 부하율 네 지점 × 전원 상태 두 가정으로 비교한다.
     *
     * @param boardId      {@link BenchmarkClusters#boardIds()}의 판 이름. 비우면 {@link BenchmarkClusters#MAIN}이고,
     *                     모르는 이름은 400으로 거절한다
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
        EngineConfigSnapshot config = engineConfig();
        String key = cacheKey(board, dist, clusters.digest(board), BenchmarkWorkloads.digestOf(location), config.configDigest(), RULES_DIGEST);

        PlacementBenchmarkResponse hit = cache.get(key);
        if (hit != null) return restamped(hit);

        if (!running.tryAcquire()) {
            log.warn("배치 비교 동시 호출 거절(429): board={} distribution={}", board, dist);
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                    "배치 비교가 이미 실행 중입니다(동시 1건). 앞 계산이 끝난 뒤(보통 5~10초, 오래 걸리는 판은 20초까지) 다시 호출하세요 — 같은 입력이면 그때 캐시에서 바로 나옵니다");
        }
        try {
            hit = cache.get(key);   // 첫 조회가 빗나간 뒤 슬롯을 잡기 전 같은 키가 끝났을 수 있다(좁은 경합 창)
            if (hit != null) return restamped(hit);
            PlacementBenchmarkResponse fresh = computeFresh(board, dist, cluster, config);
            if (cache.size() >= CACHE_LIMIT) {
                log.warn("배치 비교 캐시 상한 초과 — 전부 비운다(키 축 계산 점검 필요): size={} limit={}", cache.size(), CACHE_LIMIT);
                cache.clear();
            }
            cache.put(key, fresh);
            return fresh;
        } finally {
            running.release();
        }
    }

    /**
     * 캐시 키 — 응답을 결정하는 입력 축 전부를 {@link #CACHE_AXES} 순서로 {@code |}로 잇는다. 축이 하나라도 빠지면 다른
     * 입력이 같은 표를 돌려받으므로 축 이름 목록은 테스트가 리터럴로 확인한다.
     */
    static String cacheKey(String... values) {
        if (values.length != CACHE_AXES.size()) throw new IllegalStateException("캐시 축 수 불일치: " + values.length + " ≠ " + CACHE_AXES);
        return String.join("|", values);
    }

    /**
     * 캐시·동시 제한을 거치지 않는 계산 — <b>테스트 전용</b>(패키지 밖에서 보이지 않는다). 결정론 테스트가 "두 번 계산해도 같은
     * 숫자"를 확인할 때 캐시 적중이 그 확인을 무의미하게 만들지 않도록 이 경로를 쓴다.
     */
    PlacementBenchmarkResponse compareFresh(String boardId, String distribution) {
        String board = blank(boardId) ? BenchmarkClusters.MAIN : boardId;
        String dist = blank(distribution) ? HEAVYTAIL : distribution;
        return computeFresh(board, dist, resolveBoard(board), engineConfig());
    }

    private PlacementBenchmarkResponse computeFresh(String board, String dist, SyntheticCluster cluster,
                                                    EngineConfigSnapshot config) {
        BenchmarkWorkloads workloads = BenchmarkWorkloads.load(DISTRIBUTION_FILES.get(dist));
        // seed마다 Board 하나 — 서로 독립 복제. legacy 판은 seed가 하나라 길이 1 그대로다
        List<Board> boards = new ArrayList<>(cluster.seeds().size());
        for (long seed : cluster.seeds()) {
            boards.add(runBoard(board, cluster.withSeed(seed), workloads));   // 원본도 사본으로 — seeds 길이 비대칭을 두지 않는다
        }
        return new PlacementBenchmarkResponse(
                OffsetDateTime.now(),
                assumptions(workloads, cluster),
                config,
                BenchmarkRules.METRICS,
                boards,
                BenchmarkRules.LIMITATION);
    }

    /** 캐시 적중 응답 — 본문은 그대로, 응답 시각만 지금으로. 캐시가 응답 시각을 과거로 만들지 않는다. */
    private static PlacementBenchmarkResponse restamped(PlacementBenchmarkResponse cached) {
        return new PlacementBenchmarkResponse(OffsetDateTime.now(), cached.assumptions(), cached.engineConfig(),
                cached.metrics(), cached.boards(), cached.limitation());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** 판 이름이 틀린 것은 사용자 입력 오류라 400으로 돌린다 — 500이면 원인이 로그에만 남는다. */
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
                BenchmarkRules.RANDOM_SEEDS.size(), Assumptions.POLICY_BASIS, dataMdcAssignment(cluster),
                bandwidthAssignment(cluster));
    }

    // ── 판 하나 ──────────────────────────────────────────────

    private Board runBoard(String boardId, SyntheticCluster cluster, BenchmarkWorkloads workloads) {
        GroundTruthOracle oracle = new GroundTruthOracle(cluster);
        List<Long> sweep = sweepPoints(cluster);
        List<Cell> cells = new ArrayList<>();

        for (int loadRatio : BenchmarkRules.LOAD_RATIOS) {
            // 한 부하율의 배치를 한 번만 돌리고, 같은 결정을 전원 상태 두 가정으로 회계한다.
            List<BenchmarkPointRunner.PointOutcome> points = new ArrayList<>(sweep.size());
            for (long t : sweep) {
                points.add(runner.runPoint(cluster, workloads, loadRatio, t, oracle));
            }
            for (PowerControl mode : PowerControl.values()) {
                cells.add(assembler.cell(loadRatio, mode, points, oracle));
            }
        }

        long measured = cluster.nodes().stream().filter(SyntheticCluster.Node::ipmi).count();
        return new Board(boardId, clusters.roleOf(boardId), cluster.name(), clusters.digest(boardId),
                cluster.phaseMode().name(), cluster.seed(),
                cluster.nodes().size(), (int) measured, cluster.powerExponent(),
                idleGaps(oracle), thermalRiseScale(oracle), wakeupReconstruction(cluster),
                temperatureLimitCheck(cluster, loweredTemperatureLimit(), policyTemperatureLimit()), cells);
    }

    /**
     * 한 주기를 고르게 훑는 시각 지점. <b>벽시계를 쓰지 않는다</b> — 절대 시각에 기대면 같은 요청이
     * 두 번 다른 숫자를 내고, 그 차이가 알고리즘 차이와 구분되지 않는다.
     *
     * <p>{@code private}이 아닌 이유는 같은 패키지의 테스트가 <b>같은 지점 목록</b>으로 역산을 다시 계산하기
     * 때문이다. 테스트가 사본을 두면 두 목록이 갈라져도 테스트가 통과한다.</p>
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
     * 노드별 코어당 열 상승 세 계수. {@link #idleGaps}와 같은 자리 — 전략 루프 밖에서 한 번, 판 전제 블록에.
     * 격차·비율은 만들지 않는다. 그것을 만드는 순간 "생성식이 옳다"를 전제하게 되고, 단위를 바꾸면 부호가 뒤집힌다.
     */
    private List<ThermalRiseScale> thermalRiseScale(GroundTruthOracle oracle) {
        return oracle.thermalRiseScaleCheck(properties.getThermal().getInertiaK()).stream()
                .map(r -> new ThermalRiseScale(r.nodeId(), r.cores(), r.baseTemperatureC(),
                        r.engineRisePerCoreC(), r.engineRisePerCoreIfKelvin(), r.generatorRisePerCoreC()))
                .toList();
    }

    /**
     * 유휴 노드의 깨우기 비용 역산 점검표. {@link #idleGaps}와 같은 자리 — 전략 루프 밖에서 한 번, 판 전제 블록에.
     * 판정·비율은 만들지 않는다. 값이 대체값과 같은지는 {@code engineFallbackWatts} 열을 나란히 실어 독자가 본다.
     *
     * <p>행은 <b>판의 회계 규칙</b>으로 고른다 — 시각 12지점 내내 {@link BenchmarkRules#POWERED_ON_CPU_PERCENT}
     * 아래에 머무는 노드 전부이고, 계측 여부는 열로 밝힌다. 엔진 노브({@code activeCpuPercent})로 고르면
     * 노브를 돌리는 것만으로 표의 행이 달라져, 판이 엔진 설정의 함수가 된다.</p>
     *
     * <p>지점마다 <b>배치 전 스냅샷</b>을 새로 만들어 재는 이유는 엔진의 가동 판정이 예약분까지 반영한 실효
     * CPU를 보기 때문이다. 배치가 얹힌 노드로 재면 같은 시각의 (P, u) 쌍이 아니게 되어 역산의 전제가 깨진다.</p>
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

    /**
     * 노드별 온도 상한 대조표. {@link #wakeupReconstruction}과 같은 자리 — 전략 루프 밖에서 한 번, 판 전제 블록에.
     *
     * <p>지점 수는 엔진이 보는 배치 전 스냅샷({@link #nodesAt})의 온도로 센다. 미계측 노드는 0℃로 실려 넘는 지점이 없고,
     * 상한과 같은 온도는 넘지 않은 것으로 센다(엔진의 현재 온도 검사와 같은 비교). 범위는 계측 여부와 무관하게 생성식 온도로 잰다.
     * 행은 nodeId 오름차순이다.</p>
     *
     * <p>{@code private}이 아닌 이유는 같은 패키지의 테스트가 임시 판을 직접 만들어 부르고, 판마다 다시 훑은 값과 견주기 때문이다.</p>
     */
    static List<TemperatureLimitCheck> temperatureLimitCheck(SyntheticCluster cluster, double loweredLimit, double policyLimit) {
        List<Long> sweep = sweepPoints(cluster);
        List<Map<String, NodeResource>> snapshots = new ArrayList<>(sweep.size());
        for (long t : sweep) {
            Map<String, NodeResource> snapshot = new LinkedHashMap<>();
            for (NodeResource node : nodesAt(cluster, t)) {
                snapshot.put(node.getNodeId(), node);
            }
            snapshots.add(snapshot);
        }

        List<SyntheticCluster.Node> nodes = cluster.nodes().stream()
                .sorted(Comparator.comparing(SyntheticCluster.Node::nodeId))
                .toList();
        List<TemperatureLimitCheck> rows = new ArrayList<>(nodes.size());
        for (SyntheticCluster.Node n : nodes) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            int aboveLowered = 0;
            int abovePolicy = 0;
            for (int i = 0; i < sweep.size(); i++) {
                double generated = cluster.temperature(n, sweep.get(i));
                double seen = snapshots.get(i).get(n.nodeId()).getTemperatureCelsius();
                min = Math.min(min, generated);
                max = Math.max(max, generated);
                if (seen > loweredLimit) aboveLowered++;
                if (seen > policyLimit) abovePolicy++;
            }
            rows.add(new TemperatureLimitCheck(n.nodeId(), n.ipmi(), min, max, aboveLowered, abovePolicy));
        }
        return rows;
    }

    /** 온도 상한 열이 실제로 돌린 정책의 온도 상한 — 그 열의 override를 얹은 정책 사본에서 읽는다(응답의 engineConfigOverride와 같은 출처). */
    private double loweredTemperatureLimit() {
        for (BenchmarkColumns.EngineVariant v : columns.engineVariants()) {
            if (BenchmarkRules.TEMPERATURE_LIMIT_COLUMN.equals(v.strategy().id())) {
                return BenchmarkColumns.policyFor(v.strategy(), v.override()).getMaxTemperatureCelsius();
            }
        }
        throw new IllegalStateException("온도 상한 열이 없다: " + BenchmarkRules.TEMPERATURE_LIMIT_COLUMN);
    }

    /** 기준 정책의 온도 상한 — 응답 {@code engineConfig.policies}의 BASE 행과 같은 출처. */
    private static double policyTemperatureLimit() {
        return BenchmarkColumns.baseline().getMaxTemperatureCelsius();
    }

    // ── 전략과 정책 ──────────────────────────────────────────

    /**
     * 이 실행이 본 엔진 설정과 정책 사본 전량 — 조립은 {@link BenchmarkColumns#engineConfig()}. 다이제스트 테스트가
     * 판을 계산하지 않고 스냅샷만 읽도록 패키지 공개로 둔다.
     */
    EngineConfigSnapshot engineConfig() {
        return columns.engineConfig();
    }

    /** 클러스터 파일의 노드를 한 시각의 노드 스냅샷으로 — 정의는 {@link BenchmarkPointRunner#nodesAt}. 같은 패키지의 테스트가 이 이름으로 부른다. */
    static List<NodeResource> nodesAt(SyntheticCluster cluster, long t) {
        return BenchmarkPointRunner.nodesAt(cluster, t);
    }

    /** 열 때문에 후보가 막힌 횟수 — 정의는 {@link BenchmarkPointRunner#countThermalExclusions}. 테스트가 이 이름으로 부른다. */
    static int countThermalExclusions(SchedulingResult result) {
        return BenchmarkPointRunner.countThermalExclusions(result);
    }

    /** 데이터 MDC 순환 배정 — 정의는 {@link BenchmarkPointRunner#assignDataMdc}. 테스트가 이 이름으로 부른다. */
    static void assignDataMdc(List<Workload> sampled, SyntheticCluster cluster) {
        BenchmarkPointRunner.assignDataMdc(sampled, cluster);
    }

    /** 요구 대역폭 순환 배정 — 정의는 {@link BenchmarkPointRunner#assignRequiredBandwidth}. 테스트가 이 이름으로 부른다. */
    static void assignRequiredBandwidth(List<Workload> sampled, SyntheticCluster cluster) {
        BenchmarkPointRunner.assignRequiredBandwidth(sampled, cluster);
    }

    /** 지점별 값 — 정의는 {@link BenchmarkCellAssembler#pointSeries}. 같은 패키지의 테스트가 이 이름으로 부른다. */
    static List<Double> pointSeries(List<Double> raw, int sweepPoints) {
        return BenchmarkCellAssembler.pointSeries(raw, sweepPoints);
    }

    /** 배정 규칙을 응답 전제로 밝힌다. */
    private static String dataMdcAssignment(SyntheticCluster cluster) {
        List<String> ids = cluster.workloadDataMdcIds();
        return ids.isEmpty() ? "없음 — 워크로드에 dataMdcId 를 부여하지 않아 링크 게이트는 꺼져 있다"
                : "표본 순서대로 " + ids + " 순환 배정 — 게이트를 켠 열(weighted-affinity)만 링크 점수를 섞는다";
    }

    /** 요구 대역폭 배정 규칙을 응답 전제로 밝힌다. */
    private static String bandwidthAssignment(SyntheticCluster cluster) {
        List<Double> values = cluster.workloadBandwidthMbps();
        return values.isEmpty() ? "없음 — 워크로드에 요구 대역폭을 부여하지 않아 링크 비용의 부족분 항은 전부 0 이다"
                : "표본 순서대로 " + values + " 순환 배정(0 은 요구 없음) — 링크 대역폭에 못 미치는 만큼만 링크 점수를 깎는다";
    }

}
