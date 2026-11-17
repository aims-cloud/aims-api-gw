package com.innogrid.aims.scheduler.benchmark;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.SchedulingEngine;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.entity.Workload.WorkloadPriority;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 대조군들이 <b>서로 다른 답</b>을 낸다는 것을 픽스처로 고정한다.
 *
 * <p>대조군이 실제로 다른 규칙인지 확인하지 않으면, 표에 열을 늘어놓고 "여러 전략과 비교했다"고
 * 말하면서 실은 같은 배치를 여러 번 적은 것일 수 있다. 그래서 확인은 <b>선택된 nodeId</b>로 한다 —
 * 와트 총합은 정격이 같은 노드끼리는 잘 겹쳐서 구분자로 약하다.</p>
 *
 * <p>모든 픽스처는 손으로 검산할 수 있게 잡았다. 유휴 비율 0.3이므로 정격 500W 노드의 동적 범위는
 * 350W이고, 8코어에 2코어를 얹으면 고전 한계전력이 87.5W다.</p>
 */
class BaselinePlacerTest {

    private final PowerModel powerModel = new PowerModel(new SchedulerProperties());

    // ── 가장 중요한 제약: 레지스트리에 수집될 수 없다 ──────────────

    /**
     * {@code EngineRegistry}는 {@code List<SchedulingEngine>}을 받아 모든 엔진 빈을 자동 수집하고,
     * 그 목록이 입력 검증의 허용 집합이 된다. 대조군이 빈이 되면 검증이 열리고 cron이 대조군으로
     * 실배치해 지우지 않는 이력에 남는다. 그래서 <b>타입도 애너테이션도</b> 걸려서는 안 된다.
     */
    @DisplayName("대조군은 엔진 레지스트리가 수집할 수 없는 타입이고 스프링 빈도 아니다")
    @Test
    void baselines_cannot_be_collected_by_the_engine_registry() {
        assertFalse(SchedulingEngine.class.isAssignableFrom(PlacementStrategy.class),
                "PlacementStrategy 가 SchedulingEngine 을 구현하면 EngineRegistry 가 대조군을 수집한다");

        for (Class<?> type : List.of(PlacementStrategy.class, BaselinePlacers.class, BenchmarkPolicies.class)) {
            for (Annotation annotation : type.getAnnotations()) {
                assertFalse(annotation.annotationType().getName().startsWith("org.springframework"),
                        type.getSimpleName() + " 에 스프링 애너테이션이 붙었다: " + annotation);
            }
        }
    }

    // ── 선착순(FFD) ───────────────────────────────────────────

    /**
     * A는 CPU·메모리 70%로 여유가 적고 B는 10%로 넉넉하다. 둘 다 적격이므로 점수를 보는 엔진은
     * B를 고르지만, 선착순은 <b>뒤를 보지 않고</b> nodeId가 앞선 A를 집는다.
     */
    @DisplayName("선착순은 점수가 아니라 첫 적격 노드를 집는다")
    @Test
    void ffd_takes_first_eligible_not_best_scored() {
        Workload workload = workload("w", 1, 2048);

        SchedulingResult ffd = place(BaselinePlacers.ffdBySize(), workload, firstFitNodes());

        assertEquals("A", ffd.selectedNodeId());
        assertEquals(1.0, ffd.finalScore(), 1e-9, "순번 0 → 1/(1+0)");
        assertEquals(0.0, detail(ffd, "A", "순번"), 1e-9);

        // 우리 엔진은 여유가 큰 쪽을 고른다 — 두 열이 실제로 다른 답을 낸다는 근거
        assertEquals("B", new WeightedScoreScheduler()
                        .schedule(workload("w", 1, 2048), firstFitNodes(), policy()).selectedNodeId(),
                "점수를 보는 엔진과 선착순이 같은 답을 내면 이 대조군은 열을 하나 늘리기만 한다");
    }

    /**
     * 선착순은 <b>본래</b> 후보 목록의 순서에 결과가 좌우된다. 그래서 저장소 응답 순서가 바뀌면
     * 배치도 바뀌고, 그러면 측정 대상이 알고리즘이 아니라 입력 순서가 된다. 그 위험이 실재함을
     * 먼저 보이고(정규화 없는 선착순은 두 순서에서 다른 답), 우리 구현이 nodeId 오름차순으로
     * 정규화해 그것을 막는다는 것을 고정한다.
     */
    @DisplayName("목록을 뒤집으면 정규화 없는 선착순은 선택이 바뀌지만 nodeId 정규화는 고정된다")
    @Test
    void ffd_pick_changes_when_candidate_order_reversed() {
        Workload workload = workload("w", 1, 2048);
        List<NodeResource> forward = firstFitNodes();
        List<NodeResource> reversed = new ArrayList<>(firstFitNodes());
        Collections.reverse(reversed);

        // 정규화가 없다면 "목록의" 첫 적격 노드를 집는다 — 두 순서가 다른 답을 낸다
        assertEquals("A", firstEligibleInListOrder(forward, workload));
        assertEquals("B", firstEligibleInListOrder(reversed, workload),
                "목록 순서만 바꿨는데 답이 바뀐다 — 이것이 정규화가 필요한 이유다");

        // 우리 구현은 각 placer 안에서 nodeId 오름차순으로 세우므로 두 순서가 같은 답을 낸다
        assertEquals("A", place(BaselinePlacers.ffdBySize(), workload, forward).selectedNodeId());
        assertEquals("A", place(BaselinePlacers.ffdBySize(), workload, reversed).selectedNodeId());
    }

    /**
     * 노드 선택 규칙이 같아도 <b>워크로드 순서</b>가 배치 맵을 바꾼다. LOW·대형과 CRITICAL·소형을
     * 두고 한 노드에 하나만 들어가게 잡으면 두 변종의 답이 정확히 뒤바뀐다.
     */
    @DisplayName("크기 순과 우선순위 순 두 변종은 배치 맵이 다르다")
    @Test
    void ffd_size_and_priority_variants_differ() {
        List<Workload> workloads = List.of(
                workload("big", 4, 16384, WorkloadPriority.LOW),
                workload("small", 1, 4096, WorkloadPriority.CRITICAL));

        Map<String, String> bySize = run(BaselinePlacers.ffdBySize(), workloads, orderNodes());
        Map<String, String> byPriority = run(BaselinePlacers.ffdByPriorityThenSize(), workloads, orderNodes());

        assertEquals(Map.of("big", "p1", "small", "p2"), bySize,
                "크기 순은 대형을 먼저 넣어 p1 을 다 쓴다");
        assertEquals(Map.of("small", "p1", "big", "p2"), byPriority,
                "우선순위 순은 CRITICAL 소형이 p1 을 먼저 차지해 대형이 밀린다");
        assertNotEquals(bySize, byPriority);
    }

    // ── 대조군이 갈린다 ────────────────────────────────────────

    /**
     * 같은 판·같은 워크로드에서 네 가지 노드 선택 규칙이 <b>서로 다른 노드</b>를 고른다.
     * (선착순 두 변종은 노드 선택 규칙이 같고 워크로드 순서만 달라, 단건에서는 같은 노드가 나온다 —
     * 그 차이는 {@link #ffd_size_and_priority_variants_differ}가 본다.)
     *
     * <p>무작위 시드 3은 <b>이 픽스처를 위해 고른 값</b>이다 — 첫 추첨이 남은 노드 n3에 떨어져
     * 네 규칙이 네 노드로 갈리는 시드를 사전 등록 목록에서 찾았다. 무작위 규칙이 다른 세 규칙과
     * 다른 노드를 고를 <b>수 있다</b>는 것을 보이는 것이 이 테스트의 뜻이고, 어떤 시드에서나 그렇다는
     * 뜻은 아니다. 시드별 흩어짐은 {@link #declared_seeds_spread_the_first_draw}가 따로 본다.</p>
     */
    @DisplayName("같은 판에서 네 가지 노드 선택 규칙이 서로 다른 노드를 고른다")
    @Test
    void baselines_disagree_on_the_same_fixture() {
        Workload workload = workload("w", 2, 8192);

        SchedulingResult ffd = place(BaselinePlacers.ffdBySize(), workload, disagreeNodes());
        SchedulingResult bfd = place(BaselinePlacers.bfd(), workload, disagreeNodes());
        SchedulingResult pabfd = place(BaselinePlacers.pabfd(powerModel), workload, disagreeNodes());
        SchedulingResult random = place(BaselinePlacers.random(3L), workload, disagreeNodes());
        SchedulingResult coolest = place(BaselinePlacers.coolestFirst(), workload, disagreeNodes());

        assertEquals("n1", ffd.selectedNodeId(), "nodeId 순 첫 적격 노드");
        assertEquals("n2", bfd.selectedNodeId(), "잔여 CPU 3.2코어로 가장 빡빡하다");
        assertEquals("n4", pabfd.selectedNodeId(), "한계전력 52.5W 로 가장 적다");
        assertEquals("n3", random.selectedNodeId(),
                "시드 3 의 첫 추첨이 인덱스 2 → 적격 목록의 세 번째 노드");
        // 무작위와 같은 노드지만 이유가 다르다 — 35℃로 가장 시원하다. 우연의 일치라 아래 구분 집합에는 넣지 않는다.
        assertEquals("n3", coolest.selectedNodeId(), "실측 온도 35℃ 로 가장 시원하다");
        assertEquals(0.65, coolest.finalScore(), 1e-9, "1 − 35/100");
        assertEquals(35.0, detail(coolest, "n3", "온도C"), 1e-9);
        assertEquals(1.0, detail(coolest, "n3", "온도계측"), 1e-9);

        // Set.of는 중복이면 예외를 던져 실패 메시지를 가린다 — 겹쳤을 때 무엇이 겹쳤는지 보이게 한다
        List<String> picks = List.of(ffd.selectedNodeId(), bfd.selectedNodeId(),
                pabfd.selectedNodeId(), random.selectedNodeId());
        assertEquals(4, new HashSet<>(picks).size(), "네 규칙이 모두 다른 노드를 골라야 한다: " + picks);

        // 각 규칙의 점수식도 함께 고정한다 — 표의 숫자가 어디서 왔는지 되짚을 수 있어야 한다
        assertEquals(1.0, ffd.finalScore(), 1e-9, "1/(1+0)");
        assertEquals(0.6, bfd.finalScore(), 1e-9, "1 − 3.2/8");
        assertEquals(0.825, pabfd.finalScore(), 1e-9, "1 − 52.5/300");
        assertEquals(0.0, random.finalScore(), 1e-9, "무작위에는 좋다고 말할 근거가 없다");

        assertEquals(52.5, detail(pabfd, "n4", "한계전력W"), 1e-9);
        assertEquals(56.875, detail(pabfd, "n3", "한계전력W"), 1e-9, "정격 1300W · 32코어 중 2코어");
    }

    // ── 자원 최밀집(BFD) ──────────────────────────────────────

    @DisplayName("최밀집은 잔여 용량이 가장 적은 노드를 집는다")
    @Test
    void bfd_takes_the_tightest_fit() {
        Workload workload = workload("w", 2, 8192);

        SchedulingResult bfd = place(BaselinePlacers.bfd(), workload, disagreeNodes());

        assertEquals("n2", bfd.selectedNodeId());
        assertEquals(3.2, detail(bfd, "n2", "잔여CPU"), 1e-9);
        assertEquals(14.4, detail(bfd, "n1", "잔여CPU"), 1e-9, "가장 넉넉한 후보를 고르지 않았다");
        assertEquals(30.4, detail(bfd, "n3", "잔여CPU"), 1e-9);

        // 첫 후보를 집는 규칙이 아님을 보인다 — 선착순은 같은 판에서 n1을 고른다
        assertEquals("n1", place(BaselinePlacers.ffdBySize(), workload, disagreeNodes()).selectedNodeId());
    }

    /**
     * 잔여 CPU가 정확히 같으면 메모리가 더 빡빡한 쪽을 집는다. nodeId 순이라면 m-a가 나오므로,
     * m-b가 나오는 것이 동률 기준이 실제로 동작한다는 증거다.
     */
    @DisplayName("최밀집의 동률은 메모리로 풀린다")
    @Test
    void bfd_breaks_ties_by_memory_then_nodeid() {
        Workload workload = workload("w", 2, 8192);

        SchedulingResult bfd = place(BaselinePlacers.bfd(), workload, tieNodes());

        assertEquals(4.0, detail(bfd, "m-a", "잔여CPU"), 1e-9, "동률이 아니면 이 시험은 의미가 없다");
        assertEquals(4.0, detail(bfd, "m-b", "잔여CPU"), 1e-9);
        assertEquals("m-b", bfd.selectedNodeId(), "잔여 메모리 16384MB < 32768MB");
    }

    // ── 무작위 ───────────────────────────────────────────────

    @DisplayName("무작위는 부적격 노드를 절대 뽑지 않는다")
    @Test
    void random_never_picks_ineligible() {
        Workload workload = workload("w", 2, 8192);

        for (long seed : BenchmarkRules.RANDOM_SEEDS) {
            SchedulingResult result = place(BaselinePlacers.random(seed), workload, screenNodes());

            assertTrue(result.success(), "시드 " + seed);
            assertTrue(Set.of("s1", "s3").contains(result.selectedNodeId()),
                    "시드 " + seed + " 가 부적격 노드를 뽑았다: " + result.selectedNodeId());
        }
    }

    /**
     * 같은 시드는 같은 선택 열을 낸다. 열을 상수로 못 박는다 —
     * {@code BenchmarkRules.randomFor(1)}의 {@code nextInt(4)}는 1, 1, 2, 3, 3이므로
     * r2, r2, r3, r4, r4다. 픽스처는 다섯 건을 다 받아도 네 노드가 모두 적격으로 남도록 넉넉히
     * 잡아, 추첨 범위가 계속 4다.
     */
    @DisplayName("같은 시드는 같은 선택 열을 낸다")
    @Test
    void random_same_seed_same_sequence() {
        List<Workload> workloads = fiveSmallWorkloads();

        Map<String, String> first = run(BaselinePlacers.random(1L), workloads, randomNodes());
        Map<String, String> second = run(BaselinePlacers.random(1L), workloads, randomNodes());

        assertEquals(first, second, "같은 시드로 두 번 돌렸는데 열이 다르다 — Random 이 인스턴스 필드가 아닐 수 있다");
        assertEquals(Map.of("w1", "r2", "w2", "r2", "w3", "r3", "w4", "r4", "w5", "r4"), first);
    }

    /**
     * 시드가 다르면 <b>첫 뽑기부터</b> 갈린다. 이 확인이 시드 목록의 표본 수를 지킨다.
     *
     * <p>{@code java.util.Random}이었다면 여기가 성립하지 않는다 — 작은 시드에서 첫
     * {@code next(31)}의 상위 비트가 거의 같아 {@code nextInt(4)}의 첫 값이 시드 1~31 전부 2다.
     * 그러면 배치 건수가 적은 판에서 "무작위 31회"가 사실상 1회가 된다. 난수원을
     * {@link BenchmarkRules#randomFor(long)}로 받는 이유이고, 그 선택이 되돌아가면 여기가 빨간불이 된다.</p>
     */
    @DisplayName("다른 시드는 첫 뽑기부터 다른 노드를 고른다")
    @Test
    void random_different_seed_differs() {
        List<Workload> workloads = fiveSmallWorkloads();

        Map<String, String> seedOne = run(BaselinePlacers.random(1L), workloads, randomNodes());
        Map<String, String> seedTwo = run(BaselinePlacers.random(2L), workloads, randomNodes());

        assertNotEquals(seedOne, seedTwo, seedOne + " vs " + seedTwo);
        assertNotEquals(seedOne.get("w1"), seedTwo.get("w1"),
                "첫 뽑기가 시드에 따라 갈려야 배치 건수가 적은 판에서도 시드 목록이 표본을 늘린다");
    }

    /**
     * 사전 등록된 시드 목록이 <b>실제로</b> 서로 다른 열을 낸다. 목록의 길이가 곧 표본 수라는
     * 주장은 난수원이 시드를 제대로 섞을 때만 참이다.
     */
    @DisplayName("사전 등록된 시드 서른한 개가 첫 뽑기에서 여러 노드로 흩어진다")
    @Test
    void declared_seeds_spread_the_first_draw() {
        Workload workload = workload("w", 2, 8192);

        Set<String> firstPicks = new java.util.LinkedHashSet<>();
        for (long seed : BenchmarkRules.RANDOM_SEEDS) {
            firstPicks.add(place(BaselinePlacers.random(seed), workload, randomNodes()).selectedNodeId());
        }

        assertTrue(firstPicks.size() >= 3,
                "시드 " + BenchmarkRules.RANDOM_SEEDS.size() + "개의 첫 뽑기가 " + firstPicks
                        + " 뿐이다 — 난수원이 시드를 섞지 않으면 시드를 늘려도 표본이 늘지 않는다");
    }

    // ── 최저 온도 우선 ────────────────────────────────────────

    /**
     * 실측 온도가 가장 낮은 적격 노드를 고른다. 자원 여유·전력·nodeId 순서가 어떻든 온도만 본다 —
     * 이 픽스처는 가장 시원한 노드가 <b>nodeId 순 마지막이고 정격도 가장 크게</b> 잡아 선착순·PABFD와
     * 다른 답이 나오게 했다(최밀집과는 겹친다 — 그 둘의 기제가 같다는 사실은 한계 문구가 밝힌다).
     */
    @DisplayName("최저 온도 우선은 실측 온도가 가장 낮은 적격 노드를 고른다")
    @Test
    void coolest_first_picks_the_lowest_measured_temperature() {
        Workload workload = workload("w", 2, 8192);
        SchedulingResult result = place(BaselinePlacers.coolestFirst(), workload, thermalNodes());

        assertEquals("c4", result.selectedNodeId(), "31℃ 가 가장 낮다 — nodeId 마지막·정격 최대라도");
        assertTrue(result.reason().contains("31.0°C"), result.reason());
        assertEquals(0.69, result.finalScore(), 1e-9, "1 − 31/100");
    }

    /** 온도가 같으면 nodeId 순 앞 노드가 남는다 — pabfd·bfd와 같은 strict 비교 관용구다. */
    @DisplayName("최저 온도 우선의 동률은 nodeId 순으로 풀린다")
    @Test
    void coolest_first_breaks_ties_by_node_id() {
        Workload workload = workload("w", 2, 8192);
        List<NodeResource> nodes = List.of(
                node("e-b", 8, 65536, 50, 50, 250, 500, 42),
                node("e-a", 16, 65536, 10, 10, 200, 800, 42));

        assertEquals("e-a", place(BaselinePlacers.coolestFirst(), workload, nodes).selectedNodeId(),
                "목록 순서가 아니라 nodeId 순 앞이어야 한다");
    }

    /**
     * 미계측(0℃) 노드는 <b>가장 시원한 노드가 아니라 후순위</b>다. 문헌은 전수 계측을 전제하고, 0을 온도로 읽으면
     * 우리가 엔진에서 제거한 편향(미계측 만점)을 대조군에 심게 된다. 후보 집합은 좁히지 않는다 — 계측 노드가
     * 전부 부적격일 때는 미계측 노드가 선택되고, 그 사유가 그렇게 적힌다.
     */
    @DisplayName("미계측 노드는 후순위지만 후보에서 빠지지는 않는다")
    @Test
    void coolest_first_puts_unmeasured_nodes_last_but_keeps_them_eligible() {
        Workload small = workload("w", 2, 8192);
        List<NodeResource> nodes = List.of(
                node("u1", 32, 131072, 5, 5, 0, 1300, 0),      // 미계측 — 자원은 가장 넉넉하다
                node("u2", 8, 32768, 60, 60, 300, 500, 58));    // 계측 — 뜨겁지만 유일한 계측 노드

        SchedulingResult withMeasured = place(BaselinePlacers.coolestFirst(), small, nodes);
        assertEquals("u2", withMeasured.selectedNodeId(), "0℃ 를 가장 시원하다고 읽으면 미계측이 이긴다");
        assertEquals(0.0, detail(withMeasured, "u1", "온도계측"), 1e-9, "미계측 표시가 있어야 온도 0 이 측정값으로 읽히지 않는다");
        assertEquals(0.0, detail(withMeasured, "u1", "온도C"), 1e-9);
        assertEquals(0.0, score(withMeasured, "u1").totalScore(), 1e-9, "미계측에는 '시원하다'는 근거가 없다");

        Workload big = workload("w", 6, 24576);                  // u2는 8코어 중 3.2 코어가 남아 6코어를 못 받는다
        SchedulingResult onlyUnmeasuredFits = place(BaselinePlacers.coolestFirst(), big, nodes);
        assertEquals("u1", onlyUnmeasuredFits.selectedNodeId(), "계측 노드가 전부 부적격이면 미계측으로 간다 — 후보에서 빼지 않는다");
        assertTrue(onlyUnmeasuredFits.reason().contains("전부 부적격"), onlyUnmeasuredFits.reason());

        // 계측 노드가 하나도 없는 판에서는 "전부 부적격"이 거짓이다 — 사유가 그 사실을 그대로 적어야 한다
        List<NodeResource> noneMeasured = List.of(
                node("v2", 16, 65536, 10, 10, 0, 800, 0),
                node("v1", 16, 65536, 10, 10, 0, 800, 0));
        SchedulingResult blind = place(BaselinePlacers.coolestFirst(), small, noneMeasured);
        assertEquals("v1", blind.selectedNodeId(), "온도 정보가 없으면 nodeId 순 첫 적격 노드가 남는다");
        assertTrue(blind.reason().contains("계측 노드가 없어"), blind.reason());
        assertFalse(blind.reason().contains("전부 부적격"), "계측 노드가 없는데 부적격이라고 쓰면 거짓이다: " + blind.reason());
    }

    /**
     * 예약은 실측 온도를 바꾸지 않으므로 여유가 있는 한 <b>같은 노드에 쌓인다</b> — 문헌 TASA의 재평가 단계가
     * 없다는 성질이다. 이것을 고정하는 이유는 이 대조군이 열을 분산시키는 전략으로 오독되지 않게 하기 위해서다.
     * 분산은 우리 엔진의 기여이고, 대조군이 조용히 그것을 얻으면 비교가 아니다.
     */
    @DisplayName("최저 온도 우선은 재평가가 없어 여유가 있는 한 같은 노드에 쌓인다")
    @Test
    void coolest_first_stacks_on_the_coolest_node_without_re_evaluation() {
        List<Workload> workloads = List.of(workload("w1", 2, 8192), workload("w2", 2, 8192));
        Map<String, String> placed = run(BaselinePlacers.coolestFirst(), workloads, thermalNodes());

        assertEquals(Set.of("c4"), new HashSet<>(placed.values()),
                "두 건이 전부 가장 시원한 노드에 쌓여야 한다 — 흩어졌다면 어딘가에서 재평가를 하고 있다: " + placed);
    }

    /**
     * TASA의 "가장 뜨거운 작업 먼저"다 — 발열의 대리로 요구 코어를 쓰므로 큰 작업이 먼저 가장 시원한 노드를 받는다.
     * 도착 순서로 바꾸면 작은 작업이 시원한 노드를 선점하고 큰 작업이 밀려난다 — 픽스처의 c4는 4코어분만 남아 있어
     * 두 순서가 정확히 반대 배치를 낸다.
     */
    @DisplayName("최저 온도 우선은 큰 작업부터 놓는다 — 도착 순서가 아니다")
    @Test
    void coolest_first_places_the_largest_workload_first() {
        List<Workload> arrivedSmallFirst = List.of(workload("small", 2, 8192), workload("big", 4, 16384));
        Map<String, String> placed = run(BaselinePlacers.coolestFirst(), arrivedSmallFirst, thermalNodes());

        assertEquals("c4", placed.get("big"), "큰 작업이 가장 시원한 노드를 받아야 한다: " + placed);
        assertEquals("c2", placed.get("small"), "작은 작업은 그 다음 시원한 노드로 밀려야 한다: " + placed);
        assertFalse(BaselinePlacers.coolestFirst().usesArrivalOrder(), "도착 순서면 순서 효과가 온도 효과에 섞인다");
    }

    /**
     * 선택 기준은 <b>실측 온도</b>지 예약을 반영한 실효 온도가 아니다. 실효 온도({@code T × (1 + k·r)})는 엔진의 열관성
     * 가정이라 그것으로 고르면 대조군이 우리 모델을 조금 바꾼 것이 된다. 픽스처는 첫 배치로 x1의 예약비가 0.5가
     * 되어 실효 온도(40 × 1.075 = 43℃)가 x2(41℃)를 넘게 잡았다 — 실효 온도로 고르면 두 번째 건이 x2로 가고,
     * 실측 온도로 고르면 x1에 남는다.
     */
    @DisplayName("최저 온도 우선은 예약이 쌓여도 실측 온도로 고른다 — 엔진의 실효 온도가 아니다")
    @Test
    void coolest_first_reads_measured_temperature_not_the_engine_effective_temperature() {
        List<NodeResource> nodes = List.of(
                node("x1", 8, 65536, 10, 10, 200, 500, 40),
                node("x2", 64, 262144, 10, 10, 400, 900, 41));
        List<Workload> workloads = List.of(workload("w1", 4, 8192), workload("w2", 2, 8192));

        Map<String, String> placed = run(BaselinePlacers.coolestFirst(), workloads, nodes);

        assertEquals("x1", placed.get("w1"));
        assertEquals("x1", placed.get("w2"),
                "예약이 실측 온도를 바꾸지 않으므로 두 번째 건도 x1 이어야 한다 — x2 로 갔다면 실효 온도(엔진 모델)로 고른 것이다: " + placed);
    }

    // ── 공통 계약 ────────────────────────────────────────────

    /**
     * 후보 전원에 제약 파이프라인을 적용하고 전원의 점수를 싣는다. 게다가 <b>제외 사유 맵이 전략마다
     * 같아야</b> 한다 — 대조군이 나쁘게 나온 이유가 "제약을 덜 받았기 때문"이면 비교가 아니라 반칙이다.
     */
    @DisplayName("대조군 전부가 후보 전원을 채점하고 같은 제외 사유를 남긴다")
    @Test
    void every_baseline_scores_all_candidates() {
        Workload workload = workload("w", 2, 8192);
        Map<String, String> sharedReasons = null;

        for (PlacementStrategy strategy : baselines()) {
            SchedulingResult result = place(strategy, workload, screenNodes());

            assertEquals(3, result.candidateScores().size(), strategy.id() + ": 후보 전원이 아니다");
            assertEquals(Set.of("s1", "s2", "s3"), result.candidateScores().stream()
                    .map(NodeScore::nodeId).collect(Collectors.toSet()), strategy.id());

            for (NodeScore score : result.candidateScores()) {
                assertEquals("mdc-01", score.mdcId(), strategy.id() + ": " + score.nodeId());
                assertEquals(score.exclusionReason() == null, score.eligible(),
                        strategy.id() + ": 적격 여부와 사유가 어긋난다 — " + score.nodeId());
                assertFalse(score.details().isEmpty(),
                        strategy.id() + ": 판단 근거가 비었다 — " + score.nodeId());
                assertEquals(1.0, score.details().get(BaselinePlacers.UNSCORED_DIMENSIONS), 1e-9,
                        strategy.id() + ": 전력·열·네트워크 칸의 0 이 측정값으로 읽힐 수 있다");
                assertEquals(0.0, score.powerScore(), 1e-9, strategy.id() + ": 대조군은 전력을 채점하지 않는다");
                assertEquals(0.0, score.temperatureScore(), 1e-9, strategy.id());
                assertEquals(0.0, score.networkScore(), 1e-9, strategy.id());
            }

            NodeScore blocked = score(result, "s2");
            assertFalse(blocked.eligible(), strategy.id());
            assertNotNull(blocked.exclusionReason(), strategy.id());
            assertTrue(blocked.exclusionReason().contains("자원 부족"),
                    strategy.id() + ": " + blocked.exclusionReason());
            assertNull(score(result, "s1").exclusionReason(), strategy.id());

            Map<String, String> reasons = exclusionReasons(result);
            if (sharedReasons == null) {
                sharedReasons = reasons;
            } else {
                assertEquals(sharedReasons, reasons,
                        strategy.id() + ": 전략마다 제외 사유가 다르면 같은 제약을 받았다고 말할 수 없다");
            }
        }
    }

    /**
     * 용량이 1건분뿐인 노드 두 대에 2건을 넣으면 서로 다른 노드로 간다.
     *
     * <p>예약을 잡는 것은 <b>호출부의 일</b>이다 — 운영 경로에서도 엔진은 점수만 내고 실행 서비스가
     * 예약을 잡는다. 대조군만 스스로 예약하면 두 경로의 의미가 달라지고 이중 예약이 생긴다. 이 테스트가
     * 고정하는 것은 "placer 가 예약된 상태를 실제로 본다"는 성질이다 — 보지 않으면 두 건이 같은
     * 노드로 몰린다.</p>
     */
    @DisplayName("배치마다 예약을 반영하면 두 건이 같은 노드로 몰리지 않는다")
    @Test
    void every_baseline_reserves_after_each_placement() {
        List<Workload> workloads = List.of(workload("w1", 4, 16384), workload("w2", 4, 16384));

        for (PlacementStrategy strategy : baselines()) {
            Map<String, String> placed = run(strategy, workloads, capacityNodes());

            assertEquals(Set.of("t1", "t2"), new HashSet<>(placed.values()),
                    strategy.id() + ": 같은 노드로 몰렸거나 배치에 실패했다 — " + placed);
        }
    }

    // ── 하네스 축소판 ─────────────────────────────────────────

    /**
     * 전략 하나로 워크로드 목록을 배치하고 이름 → 노드 맵을 돌려준다.
     *
     * <p>실제 하네스가 하는 일의 축소판이다. 순서를 전략에서 가져오고, 성공한 배치마다 예약을 잡는다.</p>
     */
    private Map<String, String> run(PlacementStrategy strategy, List<Workload> workloads,
                                    List<NodeResource> nodes) {
        PlacementContext context = PlacementContext.of(nodes, policy());

        List<Workload> ordered = new ArrayList<>(workloads);
        if (!strategy.usesArrivalOrder()) {
            ordered.sort(strategy.workloadOrder());
        }

        Map<String, String> placed = new LinkedHashMap<>();
        for (Workload workload : ordered) {
            SchedulingResult result = strategy.placer().place(workload, context);
            if (result.success()) {
                placed.put(workload.getName(), result.selectedNodeId());
                context.reserve(result.selectedNodeId(), workload);
            } else {
                placed.put(workload.getName(), "실패:" + result.reason());
            }
        }
        return placed;
    }

    private SchedulingResult place(PlacementStrategy strategy, Workload workload, List<NodeResource> nodes) {
        return strategy.placer().place(workload, PlacementContext.of(nodes, policy()));
    }

    private List<PlacementStrategy> baselines() {
        return List.of(BaselinePlacers.ffdBySize(),
                BaselinePlacers.ffdByPriorityThenSize(),
                BaselinePlacers.pabfd(powerModel),
                BaselinePlacers.bfd(),
                BaselinePlacers.coolestFirst(),
                BaselinePlacers.random(1L));
    }

    /** 정규화가 없는 선착순 — 목록 순서 그대로 첫 적격 노드. 비교 대상으로만 쓴다. */
    private String firstEligibleInListOrder(List<NodeResource> nodes, Workload workload) {
        PlacementContext context = PlacementContext.of(nodes, policy());
        return nodes.stream()
                .filter(n -> context.exclusionReason(workload, n) == null)
                .map(NodeResource::getNodeId)
                .findFirst().orElse(null);
    }

    /** nodeId → 제외 사유. 적격은 값이 null이라 맵에 담을 수 없어 문자열로 바꾼다. */
    private Map<String, String> exclusionReasons(SchedulingResult result) {
        Map<String, String> reasons = new LinkedHashMap<>();
        for (NodeScore score : result.candidateScores()) {
            reasons.put(score.nodeId(), score.exclusionReason() == null ? "적격" : score.exclusionReason());
        }
        return reasons;
    }

    private NodeScore score(SchedulingResult result, String nodeId) {
        return result.candidateScores().stream()
                .filter(s -> s.nodeId().equals(nodeId))
                .findFirst().orElseThrow();
    }

    private Double detail(SchedulingResult result, String nodeId, String key) {
        return score(result, nodeId).details().get(key);
    }

    // ── 픽스처 ───────────────────────────────────────────────

    /** A는 빡빡하고 B는 넉넉하다. 둘 다 적격이라 "첫 후보인가 최고점인가"가 갈린다. */
    private static List<NodeResource> firstFitNodes() {
        return List.of(
                node("A", 8, 16384, 70, 70, 250, 500, 50),
                node("B", 8, 16384, 10, 10, 250, 500, 50));
    }

    /**
     * 네 규칙이 서로 다른 노드를 고르도록 잡은 판.
     * 고전 한계전력(정격 × 0.7 × 요구코어/총코어, 2코어)은 n1 70.0W · n2 87.5W · n3 56.875W · n4 52.5W,
     * 잔여 CPU는 n1 14.4 · n2 3.2 · n3 30.4 · n4 6.4 코어다.
     */
    private static List<NodeResource> disagreeNodes() {
        return List.of(
                node("n1", 16, 65536, 10, 10, 200, 800, 45),
                node("n2", 8, 32768, 60, 60, 300, 500, 50),
                node("n3", 32, 131072, 5, 5, 250, 1300, 35),
                node("n4", 8, 32768, 20, 20, 150, 300, 40));
    }

    /**
     * 가장 시원한 c4(31℃)가 nodeId 순 마지막이고 정격도 가장 크다 — 선착순(c1)·PABFD와 다른 답이 나온다.
     * 잔여는 c4가 최소(4코어)라 최밀집과는 겹치므로 갈림 테스트가 아니라 온도 선택·쌓임 테스트에만 쓴다.
     * 2코어 두 건을 쌓으면 c4는 50% → 75% → 100%가 되고, 두 번째 배치 시점의 실효 사용률 75%는 상한 80% 안이라
     * 적격이다(세 번째는 100%라 막혀 다음 시원한 노드로 간다 — 그래서 쌓임 테스트는 두 건이다).
     */
    private static List<NodeResource> thermalNodes() {
        return List.of(
                node("c1", 16, 65536, 10, 10, 200, 600, 45),
                node("c2", 16, 65536, 20, 20, 220, 600, 38),
                node("c3", 32, 131072, 5, 5, 250, 900, 40),
                node("c4", 8, 32768, 50, 50, 400, 1000, 31));
    }

    /** 잔여 CPU가 정확히 4.0 코어로 같고 메모리만 다르다. */
    private static List<NodeResource> tieNodes() {
        return List.of(
                node("m-a", 8, 65536, 50, 50, 250, 500, 50),
                node("m-b", 16, 32768, 75, 50, 400, 500, 50));
    }

    /** s2는 자원 부족으로 제외된다. 제약 파이프라인 비교용. */
    private static List<NodeResource> screenNodes() {
        return List.of(
                node("s1", 8, 16384, 20, 20, 250, 500, 50),
                node("s2", 8, 16384, 95, 95, 300, 500, 55),
                node("s3", 8, 16384, 40, 40, 260, 500, 52));
    }

    /** p1은 4코어분만 남아 있어 대형·소형 중 하나만 받는다. */
    private static List<NodeResource> orderNodes() {
        return List.of(
                node("p1", 8, 32768, 50, 10, 250, 500, 50),
                node("p2", 8, 32768, 10, 10, 200, 500, 45));
    }

    /** 두 대 모두 4코어·16384MB 한 건분만 남아 있다. */
    private static List<NodeResource> capacityNodes() {
        return List.of(
                node("t1", 8, 32768, 50, 50, 250, 500, 45),
                node("t2", 8, 32768, 50, 50, 250, 500, 45));
    }

    /** 다섯 건을 다 받아도 네 대가 모두 적격으로 남는다 — 추첨 범위가 계속 4여야 열을 못 박을 수 있다. */
    private static List<NodeResource> randomNodes() {
        return List.of(
                node("r1", 32, 131072, 5, 5, 200, 1200, 40),
                node("r2", 32, 131072, 5, 5, 200, 1200, 40),
                node("r3", 32, 131072, 5, 5, 200, 1200, 40),
                node("r4", 32, 131072, 5, 5, 200, 1200, 40));
    }

    private static List<Workload> fiveSmallWorkloads() {
        return List.of(workload("w1", 1, 4096), workload("w2", 1, 4096), workload("w3", 1, 4096),
                workload("w4", 1, 4096), workload("w5", 1, 4096));
    }

    private static NodeResource node(String nodeId, int cores, long memoryMb,
                                     double cpuUsage, double memUsage,
                                     double powerWatts, double capacityWatts, double temperature) {
        // 디스크 총량은 넣지 않는다(0 = 모름) — 디스크 제약은 총량을 아는 노드에서만 걸리므로
        // 이 픽스처들은 CPU·메모리·전력·온도만으로 갈린다.
        return new NodeResource("mdc-01", nodeId, cores, memoryMb, 0,
                cpuUsage, memUsage, 40, powerWatts, capacityWatts, temperature, 1_000_000, 500_000);
    }

    private static Workload workload(String name, int cpu, int memoryMb) {
        return new Workload(name, cpu, memoryMb);
    }

    private static Workload workload(String name, int cpu, int memoryMb, WorkloadPriority priority) {
        Workload workload = new Workload(name, cpu, memoryMb);
        workload.setPriority(priority);
        return workload;
    }

    private SchedulingPolicy policy() {
        return new SchedulingPolicy("benchmark", "대조군 비교용");
    }
}
