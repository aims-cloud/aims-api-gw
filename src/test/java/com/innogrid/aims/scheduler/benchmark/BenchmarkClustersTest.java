package com.innogrid.aims.scheduler.benchmark;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToDoubleBiFunction;
import java.util.stream.Collectors;

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
 * 비교판의 <b>성질</b>을 고정한다 — 파일을 고치면 여기가 깨진다.
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

    @DisplayName("등록된 판이 모두 읽힌다 — 순서 고정")
    @Test
    void six_boards_load() {
        assertEquals(List.of(BenchmarkClusters.MAIN, BenchmarkClusters.ADVERSARIAL, BenchmarkClusters.SPARSE,
                        BenchmarkClusters.CONVEX, BenchmarkClusters.MISRATED, BenchmarkClusters.CONVEX_MISRATED),
                clusters.boardIds(), "판 순서는 고정 — 표의 열 순서가 호출마다 달라지면 두 실행을 대조할 수 없다");
        // 판 수는 여기 다시 적지 않는다 — 순서·파일·역할이 등록부 한 레코드 목록에 있어 어긋날 수 없다
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
        double activeCpu = BenchmarkRules.POWERED_ON_CPU_PERCENT;   // 하네스의 회계 문턱 — 엔진 노브가 아니다
        // 아래는 기준 사용률만 본다. 진폭을 더한 실효 하한은 the_powered_on_threshold_separates_idle_from_busy 가 본다.
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

    /**
     * 통전 문턱이 유휴와 가동을 <b>진폭까지 더한 실효값</b>으로 가른다.
     *
     * <p>{@link BenchmarkRules#POWERED_ON_CPU_PERCENT} javadoc 이 "이 문턱이 두 무리 사이에 있다"고
     * 주장하는데, 그 주장이 참인 구간은 <b>유휴 상한 &lt; 문턱 ≤ 가동 하한</b> 뿐이다. 판 파일의
     * {@code swing} 을 한 칸만 키우면 가동 하한이 문턱 아래로 내려가 그 문단이 조용히 거짓이 된다 —
     * 그때 여기가 빨간불이 되어야 한다. 주 결과판의 실제 여유는 3.0~5.0% 로 <b>문턱이 상단 경계에 붙어 있다.</b></p>
     */
    @DisplayName("통전 문턱이 유휴 상한과 가동 하한 사이에 있다 — 진폭까지 더한 실효값으로")
    @Test
    void the_powered_on_threshold_separates_idle_from_busy() {
        double threshold = BenchmarkRules.POWERED_ON_CPU_PERCENT;

        for (String boardId : clusters.boardIds()) {
            List<Node> nodes = clusters.get(boardId).nodes();
            double idleCeiling = nodes.stream().filter(n -> peakCpu(n) <= threshold)
                    .mapToDouble(BenchmarkClustersTest::peakCpu).max().orElseThrow();
            double busyFloor = nodes.stream().filter(n -> peakCpu(n) > threshold)
                    .mapToDouble(BenchmarkClustersTest::troughCpu).min().orElseThrow();

            assertTrue(idleCeiling < threshold, boardId + ": 유휴 노드 최고 " + fmt(idleCeiling)
                    + "% 가 문턱 " + threshold + "% 이상이라 유휴가 가동으로 셈해지는 시각이 있다");
            assertTrue(threshold <= busyFloor, boardId + ": 가동 노드 최저 " + fmt(busyFloor)
                    + "% 가 문턱 " + threshold + "% 아래라, 가동 노드가 유휴로 빠지는 시각이 있다 — "
                    + "판 파일의 swing 을 줄이거나 기준 사용률을 올려야 한다");
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
        assertEquals(mainBoard.powerExponent(), sparseBoard.powerExponent(),
                "생성식 지수가 다르면 두 판의 세계가 다르다 — 계측 플래그만 다르다는 주장이 거짓이 된다");
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
            assertEquals(a.believedCapacityWatts(), b.believedCapacityWatts(),
                    "believedCapacityWatts" + at + " — 엔진에 알려 준 정격까지 같아야 차이를 센서 공백으로 돌릴 수 있다");
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
        double activeCpu = BenchmarkRules.POWERED_ON_CPU_PERCENT;
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

    @DisplayName("등록된 판 모두 설정된 job 라벨을 쓴다")
    @Test
    void all_boards_use_the_configured_job_label() {
        String job = new SchedulerProperties().getNodeJob();
        for (String boardId : clusters.boardIds()) {
            assertEquals(job, clusters.get(boardId).job(),
                    boardId + " 판의 job 라벨이 설정과 다르면 수집기가 빈 결과를 받고 비교는 전 전략 0점으로 평평해진다");
        }
    }

    /**
     * 등록된 판이 같은 주기를 쓴다. 한 주기를 훑는 계산은 이 값으로 창을 정하므로, 판마다 주기가
     * 다르면 같은 시각 지점이 판마다 다른 위상을 가리켜 판 간 비교가 어긋난다.
     */
    @DisplayName("등록된 판의 파동 주기가 육십 분으로 같다")
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
        assertEquals(12, main.length(), "표에 그대로 실을 12자");
        assertEquals(main, clusters.digest(BenchmarkClusters.MAIN), "같은 파일이면 같은 값");
        assertEquals(clusters.digest(BenchmarkClusters.SPARSE),
                new BenchmarkClusters().digest(BenchmarkClusters.SPARSE), "인스턴스가 달라도 같은 값");

        // 판 쌍마다 달라야 한다 — 한 글자만 다른 판이 넷이라, 판 이름과 숫자가 짝지어졌는지는 이 값으로만 대조된다.
        List<String> ids = clusters.boardIds();
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                assertNotEquals(clusters.digest(ids.get(i)), clusters.digest(ids.get(j)),
                        ids.get(i) + " 와 " + ids.get(j) + " 의 다이제스트가 같다 — 두 판의 표를 대조할 수 없다");
            }
        }
    }

    // ── 볼록·정격 어긋남 판 ───────────────────────────────────

    /**
     * 요인 하나만 다르다는 것이 이 판들의 존재 이유다. 노드 값이 한 칸이라도 함께 움직이면 판 사이의 차이를
     * 그 요인으로 돌릴 수 없고, 부록판은 귀속이 아니라 또 다른 판이 된다.
     */
    @DisplayName("볼록 판은 주 결과판과 생성식 지수만 다르다")
    @Test
    void convex_board_differs_from_main_only_by_the_exponent() {
        SyntheticCluster main = clusters.get(BenchmarkClusters.MAIN);
        SyntheticCluster convex = clusters.get(BenchmarkClusters.CONVEX);

        assertEquals(main.nodes(), convex.nodes(),
                "노드 값(믿은 정격 포함)이 하나라도 다르면 두 판의 차이를 지수로 돌릴 수 없다");
        assertEquals(BenchmarkRules.CONVEX_POWER_EXPONENT, convex.powerExponent(), 1e-9,
                "판 파일의 지수가 사전 등록된 상수와 달라지면 등록이 판을 가리키지 못한다");
        assertEquals(1.0, main.powerExponent(), 1e-9, "주 결과판은 선형이어야 두 판의 차이가 곧 볼록성이다");
        assertSamePhase(main, convex);
    }

    @DisplayName("정격 어긋남 판은 주 결과판과 엔진에 알려 준 정격만 다르다")
    @Test
    void misrated_board_differs_from_main_only_by_the_believed_capacity() {
        SyntheticCluster main = clusters.get(BenchmarkClusters.MAIN);
        SyntheticCluster misrated = clusters.get(BenchmarkClusters.MISRATED);

        assertEquals(withoutBelieved(main.nodes()), withoutBelieved(misrated.nodes()),
                "믿은 정격 말고 다른 값이 다르면 두 판의 차이를 정격 오차로 돌릴 수 없다");
        assertEquals(main.powerExponent(), misrated.powerExponent(), 1e-9,
                "이 판은 선형이어야 정격 오차 하나만 걸린 판이다");
        assertNotEquals(main.nodes(), misrated.nodes(), "믿은 정격이 실제로 달라야 판이 성립한다");
        assertSamePhase(main, misrated);
    }

    @DisplayName("복합 판은 두 요인을 함께 건다")
    @Test
    void convex_misrated_board_carries_both_factors() {
        SyntheticCluster main = clusters.get(BenchmarkClusters.MAIN);
        SyntheticCluster both = clusters.get(BenchmarkClusters.CONVEX_MISRATED);
        SyntheticCluster misrated = clusters.get(BenchmarkClusters.MISRATED);

        assertEquals(withoutBelieved(main.nodes()), withoutBelieved(both.nodes()),
                "두 요인 밖의 값이 다르면 2×2 요인 설계가 성립하지 않는다");
        assertEquals(misrated.nodes(), both.nodes(),
                "정격 어긋남 판과 믿은 정격이 같아야 두 요인이 직교한다");
        assertEquals(BenchmarkRules.CONVEX_POWER_EXPONENT, both.powerExponent(), 1e-9);
        assertSamePhase(main, both);
    }

    /**
     * 판정 셀이 <b>실제로 존재하는 판</b>을 가리키는지 본다. 사전 등록은 판이 생기기 전에 쓰였으므로,
     * 이 확인이 없으면 등록된 판 id 가 오타여도 아무도 모른 채 다른 판에서 판정하게 된다.
     */
    @DisplayName("사전 등록된 판정 셀의 판이 실제로 등록된 판이다")
    @Test
    void the_judgment_board_is_one_of_the_registered_boards() {
        assertTrue(clusters.boardIds().contains(BenchmarkRules.JUDGMENT_BOARD),
                "판정 셀의 판 " + BenchmarkRules.JUDGMENT_BOARD + " 가 판 목록에 없다 — 그 셀은 영원히 비어 있다");
        assertNotNull(clusters.get(BenchmarkRules.JUDGMENT_BOARD));
    }

    @DisplayName("정격 어긋남의 크기가 노드마다 정확히 사전 등록 비율이다")
    @Test
    void the_misrating_magnitude_is_exactly_the_registered_ratio() {
        for (String boardId : List.of(BenchmarkClusters.MISRATED, BenchmarkClusters.CONVEX_MISRATED)) {
            for (Node n : clusters.get(boardId).nodes()) {
                double delta = n.believedCapacityWatts() / n.capacityWatts() - 1.0;
                assertEquals(BenchmarkRules.CAPACITY_MISMATCH_RATIO, Math.abs(delta), 1e-9,
                        boardId + " / " + n.nodeId() + " 의 어긋남이 " + fmt(delta)
                                + " — 크기가 노드마다 다르면 부호의 효과와 크기의 효과를 가를 수 없다");
            }
        }
    }

    /**
     * 부호를 <b>파일만 보고 다시 계산</b>해 파일의 부호와 맞춘다. 손으로 고른 배치는 연구자 자유도이고,
     * 그 자유도가 판정 셀의 결론을 만든다. 규칙은 층마다 한 가지 양을 최소로 하는 분할이다 —
     * 유휴 층(깨우기 벌점이 붙는 유일한 층)은 벌점 평균의 편향, 가동 층은 정격 합의 편중.
     * 동률이면 과대 믿음 집합의 {@code nodeId} 열을 사전순으로 비교해 앞선 것.
     *
     * <p>층 분리는 판의 회계 규칙({@link BenchmarkRules#POWERED_ON_CPU_PERCENT})으로 한다 — 엔진 노브로
     * 고르면 노브를 돌리는 것만으로 부호 규칙이 달라진다. 그리고 해석적 상한(기준 + 진폭)이 아니라
     * <b>12지점 표본</b>으로 가른다 — 응답의 역산 점검표가 고르는 행이 그 표본이라, 해석적 상한으로
     * 가르면 표본이 닿지 않는 마루 때문에 두 층 정의가 조용히 갈릴 수 있다.</p>
     */
    @DisplayName("정격 어긋남의 부호가 층별 최소 편향 분할과 일치한다 — 파일만 보고 재계산")
    @Test
    void the_misrating_signs_match_the_recomputed_split() {
        for (String boardId : List.of(BenchmarkClusters.MISRATED, BenchmarkClusters.CONVEX_MISRATED)) {
            SyntheticCluster cluster = clusters.get(boardId);
            List<Node> nodes = cluster.nodes();
            List<Node> idleTier = nodes.stream().filter(n -> staysIdleAcrossSweep(cluster, n)).toList();
            List<Node> busyTier = nodes.stream().filter(n -> !staysIdleAcrossSweep(cluster, n)).toList();
            assertTrue(idleTier.size() >= 2 && busyTier.size() >= 2, boardId + ": 층이 갈리지 않는다");

            Set<String> idleOver = overBelieved(idleTier);
            Set<String> busyOver = overBelieved(busyTier);

            assertEquals(bestSplit(idleTier, BenchmarkClustersTest::penaltyBias), idleOver,
                    boardId + ": 유휴 층의 과대 믿음 집합이 벌점 편향 최소 분할과 다르다 — 부호를 손으로 골랐다는 뜻이다");
            assertEquals(bestSplit(busyTier, BenchmarkClustersTest::capacitySkew), busyOver,
                    boardId + ": 가동 층의 과대 믿음 집합이 정격 합 편중 최소 분할과 다르다");

            // 수용 기준 — 규칙을 따랐다는 것과 그 결과가 쓸 만하다는 것은 다른 주장이다.
            double bias = penaltyBias(idleOver, idleTier);
            double skew = capacitySkew(busyOver, busyTier);
            assertTrue(bias <= 0.01, boardId + ": 유휴 층 벌점 편향 " + fmt(bias)
                    + " 가 1% 를 넘는다 — 깨우기 벌점이 한쪽으로 기울어 판정 셀이 통합 쪽으로 쏠린다");
            assertTrue(skew <= 0.05, boardId + ": 가동 층 정격 합 편중 " + fmt(skew)
                    + " 가 5% 를 넘는다 — 전력 제약 여유가 함께 움직여 어긋남의 효과와 갈리지 않는다");

            // 실측값 핀 — 이 두 숫자는 판 설명과 판 파일 머리말에 글자로 적혀 있다. 파일을 고치면
            // 규칙은 여전히 통과하지만 적어 둔 숫자가 낡으므로, 여기서 먼저 빨간불이 되게 한다.
            assertEquals(0.000367, bias, 1e-5, boardId
                    + ": 유휴 층 벌점 편향이 " + fmt(bias) + " 로 바뀌었다 — 판 파일 머리말의 0.037% 가 낡았다");
            assertEquals(50.0 / 5850.0, skew, 1e-9, boardId
                    + ": 가동 층 정격 합 편중이 " + fmt(skew) + " 로 바뀌었다 — 판 파일 머리말의 0.85% 가 낡았다");
        }
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

    /**
     * 한 주기의 <b>12지점 표본</b>이 전부 통전 문턱 아래에 머무는 계측 노드인가 — 이것이 판의 회계 규칙이
     * 정의하는 유휴 층이다. 지점 식 {@code 주기초 × i / 지점수} 는 서비스의 {@code sweepPoints} 와 같으며,
     * 패키지가 달라 그 메서드를 부를 수 없어 여기서 재계산한다.
     */
    private static boolean staysIdleAcrossSweep(SyntheticCluster cluster, Node node) {
        if (!node.ipmi()) return false;   // 미계측 노드는 깨우기 벌점을 잴 근거가 없어 유휴 층이 아니다
        long period = cluster.periodMinutes() * 60L;
        for (int i = 0; i < BenchmarkRules.SWEEP_POINTS; i++) {
            if (cluster.cpuUsage(node, period * i / BenchmarkRules.SWEEP_POINTS)
                    > BenchmarkRules.POWERED_ON_CPU_PERCENT) {
                return false;
            }
        }
        return true;
    }

    /** 사용률 하한 = 기준 − 진폭. {@link #peakCpu} 의 대칭이다 — 통전 문턱이 두 무리 사이에 있는지 잴 때 쓴다. */
    private static double troughCpu(Node node) {
        assertNotNull(node.swing(), "노드마다 swing 을 파일에 적어 둔다: " + node.nodeId());
        return Math.max(0.0, node.cpuUsage() - node.swing());
    }

    /** 최고 온도 = 기준 온도 + 0.3 × 최고 사용률 ({@code SyntheticCluster.temperature} 와 같은 식). */
    private static double peakTemperature(Node node) {
        return node.baseTemperature() + 0.3 * peakCpu(node);
    }

    /** 코어당 생성식 전력 — 정격에서 유휴를 뺀 동적 전력을 코어로 나눈 값. 생성식 기준으로 낮을수록 좋은 노드다. */
    private static double generatorWattsPerCore(Node node) {
        return (node.capacityWatts() - node.idleWatts()) / node.cores();
    }

    /**
     * 믿은 정격을 생성식 정격으로 되돌린 사본 — "믿은 정격 말고는 같다"를 record 비교 한 줄로 보게 한다.
     * 필드를 손으로 열거해 비교하면 나중에 늘어난 필드가 조용히 검사에서 빠진다.
     */
    private static List<Node> withoutBelieved(List<Node> nodes) {
        return nodes.stream()
                .map(n -> new Node(n.mdcId(), n.nodeId(), n.cores(), n.memoryMb(), n.gpus(),
                        n.cpuUsage(), n.memoryUsage(), n.diskTotalGb(), n.diskUsage(), n.ipmi(),
                        n.idleWatts(), n.capacityWatts(), n.capacityWatts(), n.baseTemperature(),
                        n.networkRxBps(), n.networkTxBps(), n.trait(), n.swing()))
                .toList();
    }

    /** 두 판이 같은 시각에 같은 사용률을 낸다 — seed·주기·위상까지 같아야 판 사이의 차이가 한 요인으로 좁혀진다. */
    private static void assertSamePhase(SyntheticCluster a, SyntheticCluster b) {
        assertEquals(a.job(), b.job(), "job 라벨");
        assertEquals(a.periodMinutes(), b.periodMinutes(), "주기");
        assertNotEquals(a.name(), b.name(), "판 이름까지 같으면 응답이 어느 판인지 스스로 말하지 못한다");
        long at = 1_800_000_000L;
        for (int i = 0; i < a.nodes().size(); i++) {
            assertEquals(a.cpuUsage(a.nodes().get(i), at), b.cpuUsage(b.nodes().get(i), at), 1e-9,
                    a.nodes().get(i).nodeId() + " 같은 시각의 사용률 — seed 까지 같아야 한다");
        }
    }

    /** 파일이 실제로 과대 믿음을 준 노드. */
    private static Set<String> overBelieved(List<Node> tier) {
        return tier.stream().filter(n -> n.believedCapacityWatts() > n.capacityWatts())
                .map(Node::nodeId).collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * 깨우기 벌점 평균의 편향 {@code |Σ r/(1+δ) − Σ r| / Σ r} — 엔진에 <b>실제로 들어가는 양</b>이다.
     * 벌점이 {@code 깨우기/정격} 형이라 개수나 정격 합을 맞춰도 {@code 1/(1+δ)} 의 비선형으로 평균이 기운다.
     */
    private static double penaltyBias(Set<String> over, List<Node> tier) {
        double declared = 0;
        double believed = 0;
        for (Node n : tier) {
            double r = n.idleWatts() / n.capacityWatts();
            double delta = over.contains(n.nodeId())
                    ? BenchmarkRules.CAPACITY_MISMATCH_RATIO : -BenchmarkRules.CAPACITY_MISMATCH_RATIO;
            declared += r;
            believed += r / (1 + delta);
        }
        return Math.abs(believed - declared) / declared;
    }

    /** 정격 합의 편중 {@code |Σ₊cap − Σ₋cap| / Σcap} — 전력 제약의 여유와 전력 인지 대조군이 이 양을 본다. */
    private static double capacitySkew(Set<String> over, List<Node> tier) {
        double plus = 0;
        double minus = 0;
        for (Node n : tier) {
            if (over.contains(n.nodeId())) plus += n.capacityWatts();
            else minus += n.capacityWatts();
        }
        return Math.abs(plus - minus) / (plus + minus);
    }

    /**
     * 한 층의 절반 분할 가운데 목적량이 최소인 것. 동률이면 과대 믿음 집합의 {@code nodeId} 열을
     * 사전순으로 비교해 앞선 것 — 동률 규칙이 없으면 "규칙을 따랐다"가 여러 답을 허용한다.
     */
    private static Set<String> bestSplit(List<Node> tier, ToDoubleBiFunction<Set<String>, List<Node>> objective) {
        List<String> ids = tier.stream().map(Node::nodeId).sorted().toList();
        int n = ids.size();
        List<Set<String>> candidates = new ArrayList<>();
        for (int size : new TreeSet<>(List.of(n / 2, (n + 1) / 2))) {
            combinations(ids, size, 0, new ArrayList<>(), candidates);
        }
        return candidates.stream()
                .min(Comparator.comparingDouble((Set<String> c) -> objective.applyAsDouble(c, tier))
                        .thenComparing(c -> String.join(",", new TreeSet<>(c))))
                .orElseThrow();
    }

    private static void combinations(List<String> ids, int size, int from, List<String> picked,
                                     List<Set<String>> out) {
        if (picked.size() == size) {
            out.add(new TreeSet<>(picked));
            return;
        }
        for (int i = from; i < ids.size(); i++) {
            picked.add(ids.get(i));
            combinations(ids, size, i + 1, picked, out);
            picked.remove(picked.size() - 1);
        }
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
