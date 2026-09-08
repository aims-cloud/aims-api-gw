package com.innogrid.aims.scheduler.benchmark;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.metrics.SyntheticCluster;
import com.innogrid.aims.scheduler.metrics.SyntheticCluster.Node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 비교판 세 벌의 <b>성질</b>을 고정한다 — 파일을 고치면 여기가 깨진다.
 *
 * <p>판을 잘 읽는지가 아니라 판이 <b>의도한 성질</b>을 갖는지를 본다. 주 결과판의 유휴 비율이 어느 날
 * 전부 0.3 이 되면 그 판은 대조군이 아니라 엔진 가정을 확인하는 판이 되고, 반증판의 유휴 비율이 올라가면
 * 그 판은 더 이상 우리 엔진이 지는 판이 아니다. 두 경우 모두 결과가 조용히 좋아지는 방향의 사고라 계약으로 막는다.</p>
 *
 * <p>스프링을 띄우지 않는다 — 이 클래스는 파일만 읽으므로 컨텍스트가 필요 없다.</p>
 */
class BenchmarkClustersTest {

    private static final long MEMORY_MB_PER_CORE = 4096L;

    private final BenchmarkClusters clusters = new BenchmarkClusters();

    @DisplayName("세 판이 모두 읽힌다")
    @Test
    void three_boards_load() {
        assertEquals(List.of(BenchmarkClusters.MAIN, BenchmarkClusters.ADVERSARIAL, BenchmarkClusters.SPARSE),
                clusters.boardIds(), "판 순서는 고정 — 표의 열 순서가 호출마다 달라지면 두 실행을 대조할 수 없다");
        assertEquals(3, clusters.boardIds().size());
        for (String boardId : clusters.boardIds()) {
            SyntheticCluster board = clusters.get(boardId);
            assertTrue(board.nodes().size() > 0, boardId + " 판에 노드가 없다");
            assertNotNull(board.name(), boardId + " 판에 이름이 없다");
            assertTrue(clusters.roleOf(boardId).length() > 20, boardId + " 판의 역할 설명이 비었다");
        }
    }

    @DisplayName("주 결과판은 열네 대 전부 계측이다")
    @Test
    void main_board_has_fourteen_measured_nodes() {
        List<Node> nodes = clusters.get(BenchmarkClusters.MAIN).nodes();
        assertEquals(14, nodes.size());
        assertEquals(0, nodes.stream().filter(n -> !n.ipmi()).count(),
                "미계측 노드가 하나라도 있으면 온도·전력을 모르니 중립이라는 배출구가 주 결과의 순위에 섞인다");
    }

    @DisplayName("주 결과판에는 유휴 노드가 다섯 대다")
    @Test
    void main_board_has_five_idle_nodes() {
        double activeCpu = new SchedulerProperties().getPower().getActiveCpuPercent();   // 5.0
        List<Node> nodes = clusters.get(BenchmarkClusters.MAIN).nodes();
        List<Node> idle = nodes.stream().filter(n -> peakCpu(n) <= activeCpu).toList();
        assertEquals(5, idle.size(), "유휴 5대 · 가동 9대 — 통합의 이득을 잴 빈 노드가 있어야 한다");
        // 기준 사용률이 낮다는 것으로는 부족하다. 진폭까지 더한 최고 사용률이 가동 판정 아래여야 한 주기 내내 유휴다.
        for (Node n : idle) {
            assertTrue(peakCpu(n) <= activeCpu,
                    n.nodeId() + " 최고 사용률 " + fmt(peakCpu(n)) + "% 가 가동 판정 " + activeCpu + "% 를 넘는다");
        }
        assertEquals(9, nodes.size() - idle.size(), "나머지는 가동 노드");
        for (Node n : nodes) {
            if (peakCpu(n) > activeCpu) {
                assertTrue(n.cpuUsage() >= 20 && n.cpuUsage() <= 70,
                        n.nodeId() + " 가동 노드의 기준 사용률은 20~70% 로 둔다: " + n.cpuUsage());
            }
        }
    }

    @DisplayName("주 결과판의 메모리는 코어당 4기가다")
    @Test
    void main_board_memory_is_four_gigabytes_per_core() {
        for (Node n : clusters.get(BenchmarkClusters.MAIN).nodes()) {
            assertEquals(n.cores() * MEMORY_MB_PER_CORE, n.memoryMb(),
                    n.nodeId() + " — 워크로드 생성이 코어당 4096MB 를 쓰므로 비율이 어긋나면 부하율의 분모가 흔들린다");
        }
    }

    @DisplayName("주 결과판의 유휴 비율은 노드마다 다르다")
    @Test
    void main_board_idle_ratio_varies() {
        List<Double> ratios = clusters.get(BenchmarkClusters.MAIN).nodes().stream()
                .map(n -> n.idleWatts() / n.capacityWatts()).toList();
        double min = ratios.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        double max = ratios.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        assertTrue(max - min >= 0.15, "유휴 비율이 " + fmt(min) + "~" + fmt(max)
                + " 로 좁다 — 전부 0.3 이면 엔진 가정(power.idle-ratio)과 같아져 대조가 아니다");
        double engineAssumption = new SchedulerProperties().getPower().getIdleRatio();    // 0.3
        assertTrue(min < engineAssumption && max > engineAssumption,
                "엔진 가정 " + engineAssumption + " 이 실제 분포의 양쪽에 있어야 과대 추정과 과소 추정이 함께 드러난다");
    }

    @DisplayName("주 결과판에는 경고 구간에 드는 노드가 있다")
    @Test
    void main_board_has_a_warn_zone_node() {
        double warn = new SchedulerProperties().getThermal().getDefaultWarningCelsius();               // 65
        double limit = new SchedulingPolicy("benchmark", "임계 확인용").getMaxTemperatureCelsius();     // 75
        List<Node> nodes = clusters.get(BenchmarkClusters.MAIN).nodes();
        long warned = nodes.stream().filter(n -> peakTemperature(n) >= warn).count();
        assertTrue(warned >= 1, "경고 구간(" + warn + "℃ 이상)에 드는 노드가 없으면 열 점수가 판을 가르지 못한다");
        assertTrue(warned <= 2, "경고 구간 노드가 " + warned + "대 — 1~2대로 둔다. 절반이 뜨거우면 열이 판 전체를 좌우한다");
        // 임계를 넘긴 노드는 배치 후보에서 아예 제외된다. 경고 구간 노드는 감점만 받고 후보로 남아야 비교가 성립한다.
        for (Node n : nodes) {
            assertTrue(peakTemperature(n) < limit, n.nodeId() + " 최고 온도 " + fmt(peakTemperature(n))
                    + "℃ 가 임계 " + limit + "℃ 를 넘어 배치 후보에서 빠진다");
        }
    }

    @DisplayName("반증판의 유휴 비율은 낮다")
    @Test
    void adversarial_board_has_low_idle_ratio() {
        for (Node n : clusters.get(BenchmarkClusters.ADVERSARIAL).nodes()) {
            double ratio = n.idleWatts() / n.capacityWatts();
            assertTrue(ratio <= 0.12, n.nodeId() + " 유휴 비율 " + fmt(ratio)
                    + " — 깨우기가 싸야 통합의 이득이 사라지고 흩뿌리기가 유리해진다");
        }
    }

    @DisplayName("반증판은 정격이 큰 노드가 효율적이다")
    @Test
    void adversarial_board_inverts_capacity_efficiency() {
        List<Node> nodes = clusters.get(BenchmarkClusters.ADVERSARIAL).nodes();
        double r = correlation(nodes.stream().mapToDouble(Node::capacityWatts).toArray(),
                nodes.stream().mapToDouble(BenchmarkClustersTest::generatorWattsPerCore).toArray());
        assertTrue(r <= -0.5, "정격과 코어당 생성식 W 의 상관이 " + fmt(r)
                + " — 음의 상관이어야 큰 노드를 피하는 판단이 곧 손해가 된다");
        // 주 결과판은 반대다: 양방향으로 섞어 두어 상관이 0 근처다(그 판에서는 이 편향이 결과를 만들지 않는다).
        List<Node> main = clusters.get(BenchmarkClusters.MAIN).nodes();
        double mainR = correlation(main.stream().mapToDouble(Node::capacityWatts).toArray(),
                main.stream().mapToDouble(BenchmarkClustersTest::generatorWattsPerCore).toArray());
        assertTrue(Math.abs(mainR) < 0.5, "주 결과판의 같은 상관은 " + fmt(mainR) + " — 0 근처여야 양방향이다");
    }

    @DisplayName("계측 공백판은 주 결과판과 계측 플래그만 다르다")
    @Test
    void sparse_board_differs_from_main_only_by_ipmi() {
        SyntheticCluster mainBoard = clusters.get(BenchmarkClusters.MAIN);
        SyntheticCluster sparseBoard = clusters.get(BenchmarkClusters.SPARSE);
        List<Node> main = mainBoard.nodes();
        List<Node> sparse = sparseBoard.nodes();
        assertEquals(main.size(), sparse.size(), "두 판의 노드 수가 다르면 결과 차이를 센서 공백으로 돌릴 수 없다");
        int ipmiDiff = 0;
        for (int i = 0; i < main.size(); i++) {
            Node a = main.get(i);
            Node b = sparse.get(i);
            String at = " (" + a.nodeId() + ")";
            assertEquals(a.nodeId(), b.nodeId(), "노드 순서" + at);
            assertEquals(a.mdcId(), b.mdcId(), "mdcId" + at);
            assertEquals(a.cores(), b.cores(), "cores" + at);
            assertEquals(a.memoryMb(), b.memoryMb(), "memoryMb" + at);
            assertEquals(a.gpus(), b.gpus(), "gpus" + at);
            assertEquals(a.cpuUsage(), b.cpuUsage(), "cpuUsage" + at);
            assertEquals(a.memoryUsage(), b.memoryUsage(), "memoryUsage" + at);
            assertEquals(a.idleWatts(), b.idleWatts(), "idleWatts" + at);
            assertEquals(a.capacityWatts(), b.capacityWatts(), "capacityWatts" + at);
            assertEquals(a.baseTemperature(), b.baseTemperature(), "baseTemperature" + at);
            assertEquals(a.diskTotalGb(), b.diskTotalGb(), "diskTotalGb" + at);
            assertEquals(a.diskUsage(), b.diskUsage(), "diskUsage" + at);
            assertEquals(a.swing(), b.swing(), "swing" + at);
            if (a.ipmi() != b.ipmi()) {
                ipmiDiff++;
            }
        }
        assertEquals(5, ipmiDiff, "계측 플래그만 5대 다르다 — 그 5대가 곧 센서 공백의 크기다");
        assertEquals(14, main.stream().filter(Node::ipmi).count(), "주 결과판은 전수 계측");
        assertEquals(9, sparse.stream().filter(Node::ipmi).count(), "계측 공백판은 9대만 계측");
        // 위상은 nodeId 해시 + seed 로 정해진다. 같은 시각에 같은 사용률이 나와야 차이가 센서 하나로 좁혀진다.
        long at = 1_800_000_000L;
        for (int i = 0; i < main.size(); i++) {
            assertEquals(mainBoard.cpuUsage(main.get(i), at), sparseBoard.cpuUsage(sparse.get(i), at), 1e-9,
                    main.get(i).nodeId() + " 같은 시각의 사용률 — seed·periodMinutes 까지 같아야 한다");
        }
    }

    @DisplayName("계측 공백판의 미계측 노드에 유휴와 가동이 섞여 있다")
    @Test
    void sparse_board_mixes_idle_and_active_unmeasured() {
        double activeCpu = new SchedulerProperties().getPower().getActiveCpuPercent();
        List<Node> unmeasured = clusters.get(BenchmarkClusters.SPARSE).nodes().stream()
                .filter(n -> !n.ipmi()).toList();
        assertEquals(5, unmeasured.size());
        long idle = unmeasured.stream().filter(n -> peakCpu(n) <= activeCpu).count();
        long active = unmeasured.size() - idle;
        assertTrue(idle >= 1, "미계측에 유휴 노드가 없으면 반납 후보의 절감 잠재량을 못 잰다는 손실이 드러나지 않는다");
        assertTrue(active >= 1, "미계측에 가동 노드가 없으면 뜨겁고 바쁜 노드가 안 보인다는 손실이 드러나지 않는다");
        double warn = new SchedulerProperties().getThermal().getDefaultWarningCelsius();
        assertTrue(unmeasured.stream().anyMatch(n -> peakTemperature(n) >= warn),
                "경고 구간 노드 하나는 미계측으로 둔다 — 계측판에서 감점받던 노드가 여기서는 중립이 되는 것이 공백의 값이다");
    }

    @DisplayName("세 판 모두 설정된 job 라벨을 쓴다")
    @Test
    void all_boards_use_the_configured_job_label() {
        String job = new SchedulerProperties().getNodeJob();
        for (String boardId : clusters.boardIds()) {
            assertEquals(job, clusters.get(boardId).job(),
                    boardId + " 판의 job 라벨이 설정과 다르면 수집기가 빈 결과를 받고 비교는 전 전략 0점으로 평평해진다");
        }
    }

    /**
     * 세 판이 같은 주기를 쓴다. 한 주기를 훑는 계산은 이 값으로 창을 정하므로, 판마다 주기가
     * 다르면 같은 시각 지점이 판마다 다른 위상을 가리켜 판 간 비교가 어긋난다.
     */
    @DisplayName("세 판의 파동 주기가 육십 분으로 같다")
    @Test
    void all_boards_share_the_same_period() {
        for (String boardId : clusters.boardIds()) {
            assertEquals(60, clusters.get(boardId).periodMinutes(),
                    boardId + " 의 주기가 다르다 — 시각 스윕의 창이 판마다 달라진다");
        }
    }

    @DisplayName("다이제스트는 판마다 다르고 안정적이다")
    @Test
    void digest_is_stable_and_differs_per_board() {
        String main = clusters.digest(BenchmarkClusters.MAIN);
        String adversarial = clusters.digest(BenchmarkClusters.ADVERSARIAL);
        String sparse = clusters.digest(BenchmarkClusters.SPARSE);
        assertEquals(12, main.length(), "표에 그대로 실을 12자");
        assertEquals(main, clusters.digest(BenchmarkClusters.MAIN), "같은 파일이면 같은 값");
        assertEquals(sparse, new BenchmarkClusters().digest(BenchmarkClusters.SPARSE), "인스턴스가 달라도 같은 값");
        assertNotEquals(main, adversarial);
        assertNotEquals(main, sparse, "ipmi 플래그만 달라도 파일이 다르므로 다이제스트가 달라야 한다");
        assertNotEquals(adversarial, sparse);
    }

    @DisplayName("모르는 판은 조용히 통과하지 않는다")
    @Test
    void unknown_board_fails_loudly() {
        for (String boardId : List.of("Main", "benchmark", "")) {
            assertTrue(assertThrows(IllegalArgumentException.class, () -> clusters.get(boardId))
                    .getMessage().contains(boardId), "예외 메시지에 넘긴 판 이름이 있어야 한다: " + boardId);
        }
        assertTrue(assertThrows(IllegalArgumentException.class, () -> clusters.roleOf("adversary"))
                .getMessage().contains("adversary"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> clusters.digest("sparse-board"))
                .getMessage().contains("sparse-board"));
        assertThrows(IllegalArgumentException.class, () -> clusters.get(null));
    }

    // ── 파일만 보고 계산하는 보조 ─────────────────────────────

    /**
     * 한 주기 안에서 도달할 <b>최고</b> 사용률. 기준값이 아니라 진폭을 더한 값으로 봐야
     * 한 주기 내내 유휴라고 말할 수 있다. 노드별 swing 은 파일에 적혀 있어야 한다 —
     * 클러스터 공통 진폭을 읽는 {@code swingOf} 는 metrics 패키지 밖에서 부를 수 없다.
     */
    private static double peakCpu(Node node) {
        assertNotNull(node.swing(), "노드마다 swing 을 파일에 적어 둔다 — 계약이 파일만 보고 상한을 계산한다: " + node.nodeId());
        return Math.min(100.0, node.cpuUsage() + node.swing());
    }

    /** 최고 온도 = 기준 온도 + 0.3 × 최고 사용률 ({@code SyntheticCluster.temperature} 와 같은 식). */
    private static double peakTemperature(Node node) {
        return node.baseTemperature() + 0.3 * peakCpu(node);
    }

    /** 코어당 생성식 전력 — 정격에서 유휴를 뺀 동적 전력을 코어로 나눈 값. 생성식 기준으로 낮을수록 좋은 노드다. */
    private static double generatorWattsPerCore(Node node) {
        return (node.capacityWatts() - node.idleWatts()) / node.cores();
    }

    /** 피어슨 상관. 상관을 눈으로 판정하지 않고 숫자로 고정하려고 테스트 안에 둔다. */
    private static double correlation(double[] xs, double[] ys) {
        int n = xs.length;
        double mx = 0;
        double my = 0;
        for (int i = 0; i < n; i++) {
            mx += xs[i] / n;
            my += ys[i] / n;
        }
        double cov = 0;
        double dx = 0;
        double dy = 0;
        for (int i = 0; i < n; i++) {
            cov += (xs[i] - mx) * (ys[i] - my);
            dx += (xs[i] - mx) * (xs[i] - mx);
            dy += (ys[i] - my) * (ys[i] - my);
        }
        return cov / (Math.sqrt(dx) * Math.sqrt(dy));
    }

    private static String fmt(double v) {
        return String.format("%.3f", v);
    }
}
