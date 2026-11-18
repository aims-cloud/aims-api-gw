package com.innogrid.aims.scheduler.service;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.benchmark.BaselinePlacers;
import com.innogrid.aims.scheduler.benchmark.BenchmarkClusters;
import com.innogrid.aims.scheduler.benchmark.BenchmarkWorkloads;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.benchmark.BenchmarkMetric;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules;
import com.innogrid.aims.scheduler.benchmark.GroundTruthOracle;
import com.innogrid.aims.scheduler.benchmark.PlacementStrategy;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.Board;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.Cell;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.EngineConfigSnapshot;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.IdleGap;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.PolicySnapshot;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.MetricValue;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.StrategyResult;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.ThermalRiseScale;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.WakeupReconstruction;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.PowerAwareScheduler;
import com.innogrid.aims.scheduler.engine.TemperatureAwareScheduler;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.model.NetworkModel;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.metrics.SyntheticCluster;
import com.innogrid.aims.scheduler.model.ExclusionReason;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 배치 비교 서비스가 <b>비교라고 부를 수 있는 것</b>을 내는지 확인한다.
 *
 * <p>순위는 판, 부하율, 전원 상태 가정에 딸린 값이라 테스트가 확인할 대상이 아니다. 대신 <b>비교의 조건</b>을
 * 확인한다: 같은 입력이면 같은 답이 나오는가, 두 전원 가정이 같은 배치 결정을 회계하는가, 대조군이 운영 경로에
 * 새지 않는가, 평가 기준이 엔진의 목적 함수와 다른가, 그리고 숫자가 언제 의미를 갖는지 응답이 스스로 밝히는가.</p>
 *
 * <p>스프링을 띄우지 않는다. 비교 서비스는 저장소를 하나도 쓰지 않으므로 손으로 조립할 수 있고,
 * 그 사실 자체를 {@link #harness_takes_no_repository}가 확인한다.</p>
 */
class PlacementBenchmarkServiceTest {

    private final SchedulerProperties properties = new SchedulerProperties();
    private final PlacementBenchmarkService service = newService();

    private PlacementBenchmarkService newService() {
        SchedulerProperties props = new SchedulerProperties();
        return new PlacementBenchmarkService(
                new EngineRegistry(List.of(new WeightedScoreScheduler(), new PowerAwareScheduler(),
                        new TemperatureAwareScheduler())),
                props, new BenchmarkClusters(),
                new PowerModel(props), new ThermalModel(props));
    }

    // ── 통전 회계는 엔진 노브를 따르지 않는다 ─────────────────

    /**
     * 통전 회계는 <b>판정 규칙</b>이지 엔진 설정이 아니다. 엔진의 가동 판정 노브를 크게 흔들어도 가동 노드
     * 기준선과 증감이 그대로여야, 두 실행의 표를 나란히 놓을 수 있다. 값이 안 바뀌는 것은 우연이 아니라
     * 판의 구조다 — 유휴 5대는 3% 이하, 가동 9대는 20% 이상이라 문턱이 3~20% 어디에 있어도 집합이 같다.
     * 문턱을 그 범위 <b>밖</b>으로 옮기면 엔진의 배치가 실제로 달라진다 — 그건 이 테스트가 보는 것이 아니다.
     */
    @DisplayName("엔진의 가동 판정 문턱을 바꿔도 가동 노드 회계가 움직이지 않는다")
    @Test
    void the_powered_on_accounting_ignores_the_engine_threshold() {
        SchedulerProperties tuned = new SchedulerProperties();
        tuned.getPower().setActiveCpuPercent(40.0);   // 기본 5.0의 여덟 배
        PlacementBenchmarkService other = harnessWith(tuned);

        Cell base = cell(service.compare("main", "heavytail").boards().get(0), 60, "AVAILABLE");
        Cell moved = cell(other.compare("main", "heavytail").boards().get(0), 60, "AVAILABLE");

        assertTrue(base.activeNodesBefore() > 0, "가동 노드가 0 이면 이 시험이 아무것도 보지 않는다");
        assertEquals(base.activeNodesBefore(), moved.activeNodesBefore(), 1e-9,
                "기준선이 설정을 따라 움직이면 증감이 알고리즘이 아니라 설정의 함수가 된다");
        // 전력 엔진 변종 4열은 비교 코드가 설정 사본으로 새로 조립하는 엔진이라 노브를 따라 배치가 달라지므로
        // 이 테스트의 대상이 아니다
        assertEquals(mediansExcept(base, BenchmarkRules.ACTIVE_NODE_DELTA, "power-"),
                mediansExcept(moved, BenchmarkRules.ACTIVE_NODE_DELTA, "power-"), "가동 노드 증감이 엔진 노브를 따라 움직였다");
        assertEquals(mediansExcept(base, BenchmarkRules.GENERATOR_POWER, "power-"),
                mediansExcept(moved, BenchmarkRules.GENERATOR_POWER, "power-"),
                "전원 제어 판의 통전 집합이 엔진 노브를 따라 움직이면 주 지표가 설정의 함수다");
    }

    /**
     * 반대로 엔진이 <b>스스로 믿은 비용</b>은 엔진 노브를 따라 움직여야 한다 — 그건 엔진의 믿음이다.
     * 비교 코드가 그것까지 대신 정해 주면 모델 가정과 생성식의 격차를 잴 수 없다. 이 테스트가
     * {@code PowerModel.isActive}를 갈아치우는 잘못된 확대 적용을 막는다.
     */
    @DisplayName("엔진이 믿은 비용은 여전히 엔진 설정을 따른다")
    @Test
    void the_engine_belief_still_follows_the_engine_knobs() {
        SchedulerProperties tuned = new SchedulerProperties();
        tuned.getPower().setActiveCpuPercent(40.0);
        PlacementBenchmarkService other = serviceWith(tuned);

        Cell base = cell(service.compare("main", "heavytail").boards().get(0), 60, "AVAILABLE");
        Cell moved = cell(other.compare("main", "heavytail").boards().get(0), 60, "AVAILABLE");

        assertNotEquals(medians(base, BenchmarkRules.ENGINE_BELIEVED_COST),
                medians(moved, BenchmarkRules.ENGINE_BELIEVED_COST),
                "하네스가 엔진의 믿음까지 대신 정해 주면 모델 가정의 크기를 잴 수 없다");
    }

    /**
     * 비교 서비스 설정만 바꾸고 <b>엔진은 코드 기본값</b>으로 둔다. 회계 규칙이 엔진 설정을 따르지 않는지
     * 보려면 엔진을 고정한 채 비교 서비스 설정만 흔들어야 한다 — 엔진까지 함께 흔들면 배치가 달라져 회계 차이인지
     * 배치 차이인지 구분할 수 없다.
     */
    private static PlacementBenchmarkService harnessWith(SchedulerProperties props) {
        return new PlacementBenchmarkService(
                new EngineRegistry(List.of(new WeightedScoreScheduler(), new PowerAwareScheduler(),
                        new TemperatureAwareScheduler())),
                props, new BenchmarkClusters(), new PowerModel(props), new ThermalModel(props));
    }

    /**
     * 설정을 <b>엔진까지</b> 넣어 조립한다. 무인자 생성자로 만들면 엔진이 코드 기본값을 쓰므로,
     * 노브를 바꿔도 다이제스트만 달라지고 표는 그대로여서 "노브가 표를 움직인다"를 볼 수 없다.
     */
    private static PlacementBenchmarkService serviceWith(SchedulerProperties props) {
        PowerModel power = new PowerModel(props);
        ThermalModel thermal = new ThermalModel(props);
        return new PlacementBenchmarkService(
                new EngineRegistry(List.of(new WeightedScoreScheduler(power, thermal, new NetworkModel(new SchedulerProperties())),
                        new PowerAwareScheduler(power, thermal, new NetworkModel(new SchedulerProperties()), props),
                        new TemperatureAwareScheduler(thermal))),
                props, new BenchmarkClusters(), power, thermal);
    }

    // ── 응답이 자기가 본 설정을 밝힌다 ────────────────────────

    /**
     * {@code policyBasis}는 "코드 기본값으로 돌렸다"는 말이고, 이 블록은 그 말의 내용이다. 값이 없으면
     * 배포 환경의 임계값이 다른지를 읽는 사람이 확인할 수 없다.
     */
    @DisplayName("응답이 자기가 본 엔진 설정과 정책 변종 셋을 전부 밝힌다")
    @Test
    void the_response_states_the_engine_configuration_it_ran_with() {
        EngineConfigSnapshot config = service.compare("main", "heavytail").engineConfig();

        assertEquals(12, config.configDigest().length(), "판 다이제스트와 같은 규칙이어야 대조할 수 있다");
        assertEquals(List.of("power", "temperature", "weighted"), config.engineIds(),
                "레지스트리가 자동 수집하는 엔진 목록이 응답에 있어야 열이 늘었는지 알 수 있다");
        assertEquals(properties.getPower().getIdleRatio(), config.power().idleRatio(), 1e-9);
        assertEquals(properties.getPower().getActiveCpuPercent(), config.power().activeCpuPercent(), 1e-9);
        assertEquals(BenchmarkRules.POWERED_ON_CPU_PERCENT, config.power().benchmarkPoweredOnCpuPercent(), 1e-9,
                "회계 문턱과 엔진 문턱을 나란히 실어야 둘이 갈렸는지 보인다");
        assertEquals(properties.getThermal().getInertiaK(), config.thermal().inertiaK(), 1e-9);
        assertEquals(0, config.thermal().benchmarkCooldownMinutes(),
                "하네스가 실제로 쓴 냉각 대기가 설정값과 다르다는 사실이 보여야 한다");
        assertEquals(List.of("BASE", "RESOURCE_ONLY_GATED", "RESOURCE_ONLY_UNGATED"),
                config.policies().stream().map(PolicySnapshot::variant).toList(),
                "전략이 볼 수 있는 정책 변종 전량이어야 한다");
    }

    /** 스냅샷은 전략이 실제로 쓰는 정책이어야 한다 — 두 벌이 갈리면 응답이 거짓말을 한다. */
    @DisplayName("자원전용 변종은 전력·온도·네트워크 가중치가 0 으로 실린다")
    @Test
    void the_resource_only_variants_are_reported_with_zero_power_and_thermal_weights() {
        EngineConfigSnapshot config = service.compare("main", "heavytail").engineConfig();
        PolicySnapshot base = variant(config, "BASE");
        PolicySnapshot gated = variant(config, "RESOURCE_ONLY_GATED");
        PolicySnapshot ungated = variant(config, "RESOURCE_ONLY_UNGATED");

        for (PolicySnapshot p : List.of(gated, ungated)) {
            assertEquals(0.0, p.powerWeight(), 1e-9, p.variant());
            assertEquals(0.0, p.temperatureWeight(), 1e-9, p.variant());
            assertEquals(0.0, p.networkWeight(), 1e-9, p.variant());
            assertEquals(base.cpuWeight(), p.cpuWeight(), 1e-9,
                    p.variant() + ": 자원 가중치까지 0 이면 총점이 전부 같아져 선착순과 구분되지 않는다");
        }
        assertTrue(gated.temperatureAwareEnabled(), "제약 유지 변종인데 게이트가 꺼졌다");
        assertFalse(ungated.temperatureAwareEnabled());
        assertFalse(ungated.powerAwareEnabled());
    }

    /** 설정이 달라지면 다이제스트가 달라져야, 두 표가 같은 설정에서 나온 것인지 눈으로 갈린다. */
    @DisplayName("설정이 다르면 설정 다이제스트가 다르고, 판·분포가 달라도 설정이 같으면 같다")
    @Test
    void a_different_configuration_gets_a_different_digest() {
        SchedulerProperties tuned = new SchedulerProperties();
        tuned.getThermal().setInertiaK(0.42);
        PlacementBenchmarkService other = serviceWith(tuned);

        String mine = service.compare("main", "heavytail").engineConfig().configDigest();
        assertNotEquals(mine, other.compare("main", "heavytail").engineConfig().configDigest(),
                "열관성 계수가 다른데 다이제스트가 같으면 설정을 지목하지 못한다");
        assertEquals(mine, service.compare("sparse", "uniform").engineConfig().configDigest(),
                "판·분포는 설정이 아니다 — 다르면 다이제스트가 입력까지 섞어 지목하는 것이다");
    }

    /**
     * 모델 <b>형태</b>를 바꾸는 노브도 다이제스트에 들어가야 한다. 노브 값만 정규화하면 유휴 전력을 실측 역산으로
     * 바꿔도 다이제스트가 같아, 어제 표(대체값)와 오늘 표(역산)가 같은 설정의 표로 보인다 — 사전 등록을 사후 검증할 수 없다.
     */
    @DisplayName("유휴 역산 플래그가 다르면 다이제스트가 다르고 설정 스냅샷이 그 값을 밝힌다")
    @Test
    void the_idle_estimation_flag_is_part_of_the_digest() {
        SchedulerProperties off = new SchedulerProperties();
        off.getPower().setIdleFromMeasurement(false);
        EngineConfigSnapshot mine = service.compare("main", "heavytail").engineConfig();
        EngineConfigSnapshot other = serviceWith(off).compare("main", "heavytail").engineConfig();

        assertTrue(mine.power().idleFromMeasurement(), "기본값은 켜짐이어야 한다 — 판이 평가한 엔진이 출하되는 엔진이다");
        assertFalse(other.power().idleFromMeasurement());
        assertNotEquals(mine.configDigest(), other.configDigest(),
                "모델 형태가 다른데 다이제스트가 같다 — 두 표를 같은 설정으로 오독하게 된다");
    }

    /**
     * W 눈금은 <b>모델 형태</b>를 바꾸는 노브다 — 다이제스트에 들어가지 않으면 눈금이 다른 두 표가
     * 같은 설정의 표로 보인다. 기본값은 계약 상한보다 커야 판에서 점수가 0으로 포화하지 않는다.
     */
    @DisplayName("W 눈금은 다이제스트 성분이고 기본값은 계약 상한보다 크다")
    @Test
    void the_marginal_scale_is_part_of_the_digest_and_clears_the_contract_bound() {
        SchedulerProperties tuned = new SchedulerProperties();
        tuned.getPower().setMarginalScaleWatts(700.0);
        EngineConfigSnapshot mine = service.compare("main", "heavytail").engineConfig();
        EngineConfigSnapshot other = serviceWith(tuned).compare("main", "heavytail").engineConfig();

        assertEquals(500.0, mine.power().marginalScaleWatts(), 1e-9, "출하 기본값이 바뀌면 판정 기준선도 무효다");
        assertTrue(mine.power().marginalScaleWatts() >= BenchmarkRules.MAX_REAL_MARGINAL_WATTS_AT_JUDGMENT,
                "기본 눈금이 판정 시점 실질 한계 전력 최댓값보다 작으면 점수가 0 으로 포화해 다른 결함이 섞인다");
        assertTrue(mine.power().marginalScaleWatts() >= BenchmarkRules.MAX_REAL_MARGINAL_WATTS,
                "현 등록 판의 실측 최댓값이 기본 눈금을 넘었다 — 포화가 기본 설정에서 발현하므로 그 판의 도입을 보류한다");
        assertEquals(700.0, other.power().marginalScaleWatts(), 1e-9);
        assertNotEquals(mine.configDigest(), other.configDigest(),
                "눈금이 다른데 다이제스트가 같다 — 두 표를 같은 설정으로 오독하게 된다");
    }

    /**
     * 다이제스트 성분 <b>전량</b>이 값 변경에 반응하는지 본다.
     *
     * <p>canonical 목록은 지역 변수라 반사로 볼 수 없다. 대신 노브 레코드의 성분마다 값을 하나씩
     * 바꿔 다이제스트가 달라지는지 확인하고, 성분 목록을 반사로 읽어 이 표에 빠진 성분이 없는지
     * 확인한다. 새 노브를 더하고 canonical에 넣지 않으면 두 번째 확인이 잡는다.</p>
     */
    @DisplayName("전력 노브의 모든 성분이 다이제스트를 움직이고, 표에 빠진 성분이 없다")
    @Test
    void every_power_knob_component_moves_the_digest() {
        Map<String, java.util.function.Consumer<SchedulerProperties.Power>> tweaks = new LinkedHashMap<>();
        tweaks.put("idleRatio", pw -> pw.setIdleRatio(0.4));
        tweaks.put("activeCpuPercent", pw -> pw.setActiveCpuPercent(7.5));
        tweaks.put("curveAlpha", pw -> pw.setCurveAlpha(1.3));
        tweaks.put("peiWeight", pw -> pw.setPeiWeight(0.31));
        tweaks.put("wasteWeight", pw -> pw.setWasteWeight(0.26));
        tweaks.put("marginalWeight", pw -> pw.setMarginalWeight(0.36));
        tweaks.put("peiNormalizer", pw -> pw.setPeiNormalizer(3.5));
        tweaks.put("resourceShare", pw -> pw.setResourceShare(0.15));
        tweaks.put("defaultCapacityWatts", pw -> pw.setDefaultCapacityWatts(650.0));
        tweaks.put("capacityByNode", pw -> pw.setCapacityByNode(Map.of("bm-01:9100", 700.0)));
        tweaks.put("idleFromMeasurement", pw -> pw.setIdleFromMeasurement(false));
        tweaks.put("marginalScaleWatts", pw -> pw.setMarginalScaleWatts(700.0));

        String base = service.engineConfig().configDigest();   // 스냅샷만 — 판 계산 없이 다이제스트를 읽는다
        tweaks.forEach((name, tweak) -> {
            SchedulerProperties props = new SchedulerProperties();
            tweak.accept(props.getPower());
            assertNotEquals(base, serviceWith(props).engineConfig().configDigest(),
                    name + " 을 바꿨는데 다이제스트가 그대로다 — 설정 다이제스트에서 빠진 성분이다");
        });

        Set<String> covered = new java.util.HashSet<>(tweaks.keySet());
        covered.add("benchmarkPoweredOnCpuPercent");   // 사전 등록 상수라 설정으로 바꿀 수 없다
        Set<String> components = java.util.Arrays.stream(
                        PlacementBenchmarkResponse.PowerKnobs.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).collect(Collectors.toSet());
        assertEquals(components, covered,
                "노브 성분과 민감도 표가 어긋났다 — 새 노브를 더했으면 canonical 과 이 표에도 넣는다");
    }

    /**
     * 네트워크 노브 성분 <b>전량</b>이 다이제스트에 반응하고 응답 레코드에 노출된다 — 전력 노브와 같은 규약.
     * 링크를 선언한 판에서 이 노브가 결과에 반영되므로, 빠지면 같은 다이제스트로 다른 표가 나온다.
     */
    @DisplayName("네트워크 노브 성분 전량이 다이제스트를 움직이고 응답 레코드와 성분 집합이 같다")
    @Test
    void every_network_knob_component_moves_the_digest() {
        Map<String, java.util.function.Consumer<SchedulerProperties.Network>> tweaks = new LinkedHashMap<>();
        tweaks.put("latencyScaleMs", n -> n.setLatencyScaleMs(80.0));
        tweaks.put("degradedPenalty", n -> n.setDegradedPenalty(0.4));
        tweaks.put("linkWeight", n -> n.setLinkWeight(0.6));
        tweaks.put("unknownLinkScore", n -> n.setUnknownLinkScore(0.4));

        String base = service.engineConfig().configDigest();
        tweaks.forEach((name, tweak) -> {
            SchedulerProperties props = new SchedulerProperties();
            tweak.accept(props.getNetwork());
            assertNotEquals(base, serviceWith(props).engineConfig().configDigest(),
                    name + " 을 바꿨는데 다이제스트가 그대로다 — 설정 다이제스트에서 빠진 성분이다");
        });
        Set<String> components = java.util.Arrays.stream(
                        PlacementBenchmarkResponse.NetworkKnobs.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).collect(Collectors.toSet());
        assertEquals(components, tweaks.keySet(), "네트워크 노브 성분과 민감도 표가 어긋났다");
        assertEquals(0.5, service.engineConfig().network().unknownLinkScore(), 1e-9);
    }

    /** 위상 독립 판은 seed마다 Board 하나 — 서로 독립 복제라 값이 다르고, legacy 판은 Board 하나다. */
    @DisplayName("위상 독립 판은 seeds 수만큼 Board 를 내고 seed 별 값이 서로 다르며, legacy 판은 Board 하나다")
    @Test
    void independent_boards_yield_one_board_per_seed() {
        var main = service.compare("main", "heavytail");
        assertEquals(1, main.boards().size());
        assertEquals("LEGACY", main.boards().get(0).phaseMode());
        assertEquals(7L, main.boards().get(0).phaseSeed());

        var ind = service.compare("main-independent", "heavytail");
        assertEquals(List.of(7L, 11L, 13L), ind.boards().stream().map(b -> b.phaseSeed()).toList());
        assertTrue(ind.boards().stream().allMatch(b -> "INDEPENDENT".equals(b.phaseMode())));
        assertEquals(1, ind.boards().stream().map(b -> b.digest()).distinct().count(), "같은 판 파일 — 다이제스트 하나");
        assertEquals(3, ind.boards().stream().map(PlacementBenchmarkServiceTest::flattenBoard).distinct().count(),
                "seed 별 Board 의 값이 전부 같다 — 위상이 seed 에 반응하지 않는다");
    }

    /**
     * 설정 다이제스트 리터럴 — 기본 설정·엔진 3·override 열 6의 canonical 문자열.
     * 이 값이 바뀌면 docs/13 10장에 옛 → 새 행을 적는다.
     * 방법론 변경 이전(위생 정비 PR까지)의 값은 {@code 79315785b762}, 온도 상한 열 추가 전의 값은 {@code 1487c10f4e97}이었다.
     */
    @DisplayName("기본 설정의 configDigest 리터럴이 고정된다 — 바뀌면 계약 §10 행이 따른다")
    @Test
    void the_default_config_digest_is_pinned() {
        assertEquals("b8f222c123d1", service.compare("main", "heavytail").engineConfig().configDigest(),
                "설정 다이제스트가 바뀌었다 — canonical 성분·열·노브가 바뀐 것이며 계약 §10 에 옛 → 새 행을 적는다");
    }

    /** 링크 게이트 열은 링크 판에서만 weighted와 갈린다 — 링크 없는 판에서는 게이트가 켜질 수 없어 같은 값이다(구조적 사실). */
    @DisplayName("weighted-affinity 는 링크 없는 판에서 weighted 와 값이 같고 링크 판에서만 다르다")
    @Test
    void the_affinity_column_differs_from_weighted_only_on_the_link_board() {
        for (String board : new BenchmarkClusters().boardIds()) {
            if (BenchmarkClusters.LINK.equals(board)) continue;
            for (var b : service.compare(board, "heavytail").boards()) {
                assertEquals(columnOf(b, "weighted"), columnOf(b, "weighted-affinity"), board + " seed " + b.phaseSeed() + " — 데이터 MDC 를 배정하지 않은 판인데 두 열이 다르다");
            }
        }
        var link = service.compare("link", "heavytail").boards().get(0);
        assertNotEquals(columnOf(link, "weighted"), columnOf(link, "weighted-affinity"), "링크 판에서 게이트 on/off 열이 같다 — netScore 경로가 작동하지 않는다");
    }

    /**
     * 판 실행 경로가 요구 대역폭을 실제로 배정하는지 — 배정 함수 단독 테스트는 호출이 사라져도 통과한다.
     *
     * <p>링크 판 30% 두 셀의 게이트 열 격차 중앙값을 사전 등록 리터럴로 고정한다. 요구 배정이 빠지면
     * a-c 경로(1000Mbps)의 부족분이 사라져 이 값이 움직인다.</p>
     */
    @DisplayName("링크 판 실행이 요구 대역폭을 배정해 게이트 열 격차가 사전 등록 리터럴과 같다")
    @Test
    void the_link_board_run_applies_the_required_bandwidth() {
        var link = service.compare("link", "heavytail").boards().get(0);
        Map<String, Double> gaps = new LinkedHashMap<>();
        for (var cell : link.cells()) {
            for (var s : cell.strategies()) {
                if (!s.strategyId().equals("weighted-affinity")) continue;
                for (var m : s.metrics()) {
                    if (m.key().equals(BenchmarkRules.GAP_VS_PABFD) && m.median() != null) {
                        gaps.put(cell.loadRatioPercent() + ":" + cell.powerControl(), m.median());
                    }
                }
            }
        }
        assertEquals(24.7883, gaps.get("30:AVAILABLE"), BenchmarkRules.JUDGMENT_POINT_IGNORE_PP,
                "요구 대역폭 배정이 빠지면 부족분이 사라져 이 값이 움직인다");
        assertEquals(7.8664, gaps.get("30:UNAVAILABLE"), BenchmarkRules.JUDGMENT_POINT_IGNORE_PP);
        assertEquals(26.7045, gaps.get("60:AVAILABLE"), BenchmarkRules.JUDGMENT_POINT_IGNORE_PP);
    }

    /**
     * 민감도 3열은 실제로 다른 눈금으로 돈다 — 표기만 다르고 값이 같으면 열이 죽어 있는 것이다. 주 결과판에서 250W 눈금은
     * 실질 한계 전력 최댓값 아래라 포화가 발현해 1000W 열과 갈라져야 하고, 정격 분모(0) 열도 W 눈금 열과 갈라져야 한다.
     */
    @DisplayName("민감도 3열의 값이 서로 다르다 — 표기만 다른 열이 아니다")
    @Test
    void the_sensitivity_columns_actually_differ() {
        var main = service.compare("main", "heavytail").boards().get(0);
        Map<String, String> s250 = columnOf(main, "power-s250"), s1000 = columnOf(main, "power-s1000"), s0 = columnOf(main, "power-s0");
        assertNotEquals(s250, s1000, "250W 눈금 열이 1000W 열과 같다 — 포화가 발현하지 않거나 눈금이 적용되지 않았다");
        assertNotEquals(s0, s1000, "정격 분모 열이 1000W 열과 같다 — 구 방식이 적용되지 않았다");
        assertNotEquals(columnOf(main, "power-idle-fixed"), columnOf(main, "power"), "역산 차단 열이 power 와 같다 — 계측 유휴 노드가 있는 판에서는 역산이 값을 바꿔야 한다");
    }

    /**
     * 열을 더해도 기존 열의 값이 바뀌지 않는다 — 짝 조건은 기준 집합으로만 정한다. 배포 검증에서 반증판 90%·전원 제어 셀의 power
     * 총전력 표본이 5 → 1로 줄어든 것이 이 규칙의 계기다. 리터럴의 출처는 두 겹이다: 처음은 변종 열 도입 전(위생 정비 PR jar) 응답의
     * 표본 5 · 6429.0022 · 32.3241이었고, 배치 점수 PEI·비낭비 항의 기저를 실효로 바꾼 판정 회차 1에서 power 열의 배치가 달라져
     * 기준 집합 짝 지점이 5 → 6으로 늘고 두 중앙값이 6412.2625 · 32.2913으로 움직였으며, 열 항의 기저를 실효 온도로 바꾼 다음 회차에서
     * 반증판 짝 지점이 6 → 7, 총전력 중앙값이 6403.1272로 다시 움직였다(계측 공백판 32.2913은 그대로). 노드 배치 스코어 PEI 항에
     * 코어 비율을 곱한 회차에서 반증판 짝 지점이 7 → 4, 총전력 중앙값이 6234.1715, 계측 공백판 미계측 격차가 34.4749로 움직였다
     * — 지금 리터럴은 맨 뒤 값이다.
     * 값의 이동은 배치 점수 변경에 귀속된다. 현 리터럴은 변종 열이 있는 실행에서 온 값이라 override 열 독립성의 교차 증거는
     * 첫 출처에서 소모됐고, 이 테스트는 이후 회차에 대해 "값이 조용히 움직이지 않는다"를 지킨다.
     */
    @DisplayName("변종 열을 더해도 기준 집합 열의 총량 표본 수와 값이 사전 등록 리터럴과 같다")
    @Test
    void adding_override_columns_does_not_move_core_column_totals() {
        Cell adversarial = cell(service.compare("adversarial", "heavytail").boards().get(0), 90, "AVAILABLE");
        MetricValue power = strategy(adversarial, "power").metrics().stream()
                .filter(m -> m.key().equals(BenchmarkRules.GENERATOR_POWER)).findFirst().orElseThrow();
        assertEquals(4, power.samples(), "짝 지점 수가 사전 등록 리터럴(4)과 다르다 — 짝 조건이 override 열까지 보고 있거나 배치가 또 움직였다");
        // 허용 오차는 판정 규칙의 지점 비교 무시 폭(0.0002, 4자리 두 칸)과 같은 근거 — 실수 항등인 변경이 마지막 자리를 한 칸 뒤집을 수 있어 한 칸(1e-4)은 여유가 0이다
        assertEquals(6234.1715, power.median(), BenchmarkRules.JUDGMENT_POINT_IGNORE_PP);
        Cell sparse = cell(service.compare("sparse", "heavytail").boards().get(0), 90, "AVAILABLE");
        MetricValue spread = strategy(sparse, "power").metrics().stream()
                .filter(m -> m.key().equals(BenchmarkRules.UNMEASURED_SPREAD)).findFirst().orElseThrow();
        assertEquals(34.4749, spread.median(), BenchmarkRules.JUDGMENT_POINT_IGNORE_PP, "미계측 격차가 변종 열까지 포함해 계산됐다");
    }

    /** 순환 배정 — 표본 순서대로 a,b,c,a,b,c… 링크 판만 부여하고 링크 없는 판은 아무것도 하지 않는다. */
    @DisplayName("데이터 MDC 는 표본 순서대로 순환 배정되고 링크 없는 판은 부여하지 않는다")
    @Test
    void data_mdc_is_assigned_round_robin_only_on_the_link_board() {
        BenchmarkClusters clusters = new BenchmarkClusters();
        List<Workload> sample = BenchmarkWorkloads.load("classpath:scheduler/benchmark-workloads-heavytail.yml").sample(200);
        PlacementBenchmarkService.assignDataMdc(sample, clusters.get(BenchmarkClusters.LINK));
        List<String> ids = clusters.get(BenchmarkClusters.LINK).workloadDataMdcIds();
        for (int i = 0; i < sample.size(); i++) assertEquals(ids.get(i % ids.size()), sample.get(i).getDataMdcId(), "표본 " + i);
        assertTrue(sample.size() > ids.size(), "표본이 배정 목록보다 길어야 순환이 검증된다");

        List<Workload> plain = BenchmarkWorkloads.load("classpath:scheduler/benchmark-workloads-heavytail.yml").sample(200);
        PlacementBenchmarkService.assignDataMdc(plain, clusters.get(BenchmarkClusters.MAIN));
        assertTrue(plain.stream().allMatch(w -> w.getDataMdcId() == null), "링크 없는 판은 dataMdcId 를 부여하지 않는다");
    }

    /** 순환 배정 — 0은 요구 없음이라 null로 남고, 요구 대역폭 절이 없는 판은 아무것도 부여하지 않는다. */
    @DisplayName("요구 대역폭은 표본 순서대로 순환 배정되고 0 은 요구 없음으로 남는다")
    @Test
    void required_bandwidth_is_assigned_round_robin_with_zero_meaning_none() {
        BenchmarkClusters clusters = new BenchmarkClusters();
        List<Workload> sample = BenchmarkWorkloads.load("classpath:scheduler/benchmark-workloads-heavytail.yml").sample(200);
        PlacementBenchmarkService.assignRequiredBandwidth(sample, clusters.get(BenchmarkClusters.LINK));
        List<Double> values = clusters.get(BenchmarkClusters.LINK).workloadBandwidthMbps();
        assertTrue(sample.size() > values.size(), "표본이 배정 목록보다 길어야 순환이 검증된다");
        for (int i = 0; i < sample.size(); i++) {
            double v = values.get(i % values.size());
            Double got = sample.get(i).getRequiredBandwidthMbps();
            if (v > 0) assertEquals(v, got, "표본 " + i);
            else assertNull(got, "표본 " + i + " — 0 은 요구 없음이라 null 이어야 부족분 항이 꺼진다");
        }
        assertTrue(sample.stream().anyMatch(w -> w.getRequiredBandwidthMbps() == null), "요구 없음 갈래가 실제로 돌아야 한다");
        assertTrue(sample.stream().anyMatch(w -> w.getRequiredBandwidthMbps() != null), "요구 있음 갈래도 실제로 돌아야 한다");

        List<Workload> plain = BenchmarkWorkloads.load("classpath:scheduler/benchmark-workloads-heavytail.yml").sample(200);
        PlacementBenchmarkService.assignRequiredBandwidth(plain, clusters.get(BenchmarkClusters.MAIN));
        assertTrue(plain.stream().allMatch(w -> w.getRequiredBandwidthMbps() == null), "요구 대역폭 절이 없는 판은 부여하지 않는다");
    }

    // ── 원격 MDC 배치 비율 ─────────────────────────────────────

    /** 링크 판은 워크로드에 데이터 MDC를 배정하는 유일한 판이라 전 셀 · 전 열이 시각 지점마다 값을 낸다. 0~100 밖이면 분모나 분자를 잘못 센 것이다. */
    @DisplayName("링크 판의 원격 MDC 배치 비율은 두 분포의 전 셀 · 전 열에서 0~100 사이 값이다")
    @Test
    void remote_mdc_rate_is_valid_on_every_link_board_cell() {
        for (String distribution : List.of("heavytail", "uniform")) {
            for (var c : service.compare("link", distribution).boards().get(0).cells()) {
                for (var s : c.strategies()) {
                    MetricValue m = metric(s, BenchmarkRules.REMOTE_MDC_RATE);
                    String where = distribution + " " + c.loadRatioPercent() + ":" + c.powerControl() + " " + s.strategyId();
                    assertTrue(m.valid(), where + " — " + m.invalidReason());
                    assertTrue(m.median() >= 0 && m.median() <= 100, where + " 값 " + m.median());
                    assertEquals(BenchmarkRules.SWEEP_POINTS, m.samples(), where + " — 시각 지점마다 표본 하나");
                }
            }
        }
    }

    /** 데이터 MDC가 없는 판에서 0을 내면 "원격 배치가 없었다"로 읽힌다 — 값도 표본도 없이 사유를 단 무효여야 한다. */
    @DisplayName("데이터 MDC 를 배정하지 않은 판은 원격 MDC 배치 비율이 0 이 아니라 사유를 단 무효다")
    @Test
    void remote_mdc_rate_is_invalid_with_a_reason_on_boards_without_data_mdc() {
        for (String board : new BenchmarkClusters().boardIds()) {
            if (BenchmarkClusters.LINK.equals(board)) continue;
            for (String distribution : List.of("heavytail", "uniform")) {
                for (var b : service.compare(board, distribution).boards()) {
                    for (var c : b.cells()) {
                        for (var s : c.strategies()) {
                            MetricValue m = metric(s, BenchmarkRules.REMOTE_MDC_RATE);
                            String where = board + " " + distribution + " seed " + b.phaseSeed() + " " + c.loadRatioPercent() + ":"
                                    + c.powerControl() + " " + s.strategyId();
                            assertNull(m.median(), where);
                            assertFalse(m.valid(), where);
                            assertEquals(0, m.samples(), where + " — 데이터 MDC 가 없으면 표본도 없다");
                            assertEquals("이 판은 워크로드에 데이터 MDC 를 배정하지 않아 원격 배치가 정의되지 않는다", m.invalidReason(), where);
                        }
                    }
                }
            }
        }
    }

    /**
     * 링크 판의 링크 게이트 끈 열(weighted)과 켠 열(weighted-affinity)의 원격 MDC 배치 비율 — 첫 실행에서 관측한 값을 리터럴로 고정한다(회귀 고정).
     * 두 전원 상태 셀은 배치가 같아 값이 같다. 허용 오차는 판정 규칙의 지점 비교 무시 폭과 같은 근거다.
     */
    @DisplayName("링크 판의 원격 MDC 배치 비율이 두 분포 · 네 부하율에서 관측 리터럴과 같다")
    @Test
    void remote_mdc_rate_on_the_link_board_matches_the_observed_literals() {
        // 분포:부하율 → { weighted, weighted-affinity }
        Map<String, double[]> expected = Map.of(
                "heavytail:30", new double[]{57.1573, 21.5436}, "heavytail:60", new double[]{62.069, 17.0953},
                "heavytail:90", new double[]{65.5259, 16.2791}, "heavytail:110", new double[]{65.3846, 17.5614},
                "uniform:30", new double[]{47.3684, 21.0526}, "uniform:60", new double[]{58.6771, 27.9872},
                "uniform:90", new double[]{62.1475, 26.5852}, "uniform:110", new double[]{62.3316, 24.7642});
        int checked = 0;
        for (String distribution : List.of("heavytail", "uniform")) {
            for (var c : service.compare("link", distribution).boards().get(0).cells()) {
                double[] want = expected.get(distribution + ":" + c.loadRatioPercent());
                String where = distribution + " " + c.loadRatioPercent() + ":" + c.powerControl();
                assertEquals(want[0], metric(strategy(c, "weighted"), BenchmarkRules.REMOTE_MDC_RATE).median(),
                        BenchmarkRules.JUDGMENT_POINT_IGNORE_PP, where + " weighted");
                assertEquals(want[1], metric(strategy(c, "weighted-affinity"), BenchmarkRules.REMOTE_MDC_RATE).median(),
                        BenchmarkRules.JUDGMENT_POINT_IGNORE_PP, where + " weighted-affinity");
                checked++;
            }
        }
        assertEquals(expected.size() * 2, checked, "두 분포 · 네 부하율 · 두 전원 상태 셀을 모두 봐야 한다");
    }

    /** 분모는 요청 건수도 배치 건수도 아니고 원격 여부를 말할 수 있는 배치 건수다 — 등록된 판에서는 둘이 늘 같아 응답으로는 가려낼 수 없다. */
    @DisplayName("원격 MDC 배치 비율은 데이터 MDC 가 있는 배치 건수로 나누고 그 배치가 0건이면 0 이 아니라 null 이다")
    @Test
    void remote_mdc_rate_divides_by_placements_with_a_data_mdc() {
        assertEquals(75.0, BenchmarkCellAssembler.remoteMdcRate(placementWithDataMdc(5, 4, 3)), 1e-9, "배치 건수(5)로 나누면 60 이 된다");
        assertEquals(33.3333, BenchmarkCellAssembler.remoteMdcRate(placementWithDataMdc(3, 3, 1)), 1e-9);
        assertNull(BenchmarkCellAssembler.remoteMdcRate(placementWithDataMdc(5, 0, 0)), "원격 여부를 말할 수 있는 배치가 없으면 비율은 정의되지 않는다");
    }

    /** 등록된 판은 노드 MDC가 모두 있어 응답으로는 빠지는 가지를 볼 수 없다. */
    @DisplayName("원격 여부는 노드 MDC 와 데이터 MDC 가 다를 때만 참이고 둘 중 하나라도 없으면 셈에서 빠진다")
    @Test
    void remote_mdc_is_counted_only_when_both_mdc_ids_are_known() {
        assertEquals(Boolean.FALSE, BenchmarkPointRunner.remoteMdc("bm-mdc-a", "bm-mdc-a"), "같은 MDC 는 원격이 아니다");
        assertEquals(Boolean.TRUE, BenchmarkPointRunner.remoteMdc("bm-mdc-b", "bm-mdc-a"));
        assertNull(BenchmarkPointRunner.remoteMdc(null, "bm-mdc-a"), "노드 MDC 가 없으면 원격인지 말할 수 없다");
        assertNull(BenchmarkPointRunner.remoteMdc("bm-mdc-a", null), "데이터 MDC 가 없는 워크로드는 셈에서 빠진다");
    }

    private static BenchmarkPointRunner.Placement placementWithDataMdc(int placed, int dataMdcPlacements, int remoteMdcPlacements) {
        return new BenchmarkPointRunner.Placement(BaselinePlacers.ffdBySize(), placed, placed, 0, Set.of(), Map.of(), 0, 0, 0, 0, 0, 0,
                true, true, dataMdcPlacements, remoteMdcPlacements, 0, 0, 0);
    }

    // ── 자원 조각화 비율 ───────────────────────────────────────

    /** 부하율 분모와 같은 식이어야 조각화 비율의 여유가 표본 크기를 정한 여유와 같은 뜻이 된다 — 메모리가 묶는 노드 · CPU가 묶는 노드 · 상한을 넘은 노드로 본다. */
    @DisplayName("노드 여유는 기준 정책 상한까지의 CPU 와 메모리 환산 코어 중 작은 쪽이고 예약분을 빼며 음수는 0 이다")
    @Test
    void node_headroom_is_the_smaller_of_cpu_and_memory_and_never_negative() {
        SchedulingPolicy base = BenchmarkColumns.baseline();
        NodeResource memoryBound = new NodeResource("bm", "n-mem", 16, 16384, 0, 0, 0, 0, 0, 500, 0, 0, 0);
        double byCpu = 16 * base.getMaxCpuUsagePercent() / 100.0;
        double byMemory = 16384 * base.getMaxMemoryUsagePercent() / 100.0 / BenchmarkRules.MEMORY_MB_PER_CORE;
        assertTrue(byMemory < byCpu, "이 노드는 메모리가 여유를 묶어야 한다");
        assertEquals(byMemory, BenchmarkPointRunner.nodeHeadroomCores(memoryBound, base), 1e-9);

        memoryBound.reserve(2, 4096, 0);
        assertEquals(16384 * (base.getMaxMemoryUsagePercent() - 25.0) / 100.0 / BenchmarkRules.MEMORY_MB_PER_CORE,
                BenchmarkPointRunner.nodeHeadroomCores(memoryBound, base), 1e-9, "배치를 마친 노드의 여유라 예약분도 뺀다");

        NodeResource cpuBound = new NodeResource("bm", "n-cpu", 4, 65536, 0, 50, 0, 0, 0, 500, 0, 0, 0);
        assertEquals(4 * (base.getMaxCpuUsagePercent() - 50) / 100.0, BenchmarkPointRunner.nodeHeadroomCores(cpuBound, base), 1e-9);

        NodeResource over = new NodeResource("bm", "n-over", 8, 32768, 0, 95, 0, 0, 0, 500, 0, 0, 0);
        assertEquals(0.0, BenchmarkPointRunner.nodeHeadroomCores(over, base), 1e-9, "정책 상한을 이미 넘은 노드의 여유는 0 이다");
    }

    /** 기준 크기는 사전 등록 값(두 분포 모두 4)이고, 더 작은 계층이 있어야 기준 크기를 잘못 고른 것이 드러난다. */
    @DisplayName("조각화 비율의 기준 크기는 분포 파일의 가장 큰 계층 코어이고 자투리는 그 배수를 뺀 나머지다")
    @Test
    void fragment_is_what_is_left_after_whole_largest_classes() {
        for (String distribution : List.of("heavytail", "uniform")) {
            BenchmarkWorkloads workloads = BenchmarkWorkloads.load("classpath:scheduler/benchmark-workloads-" + distribution + ".yml");
            assertEquals(4, BenchmarkPointRunner.largestClassCores(workloads), distribution);
            assertTrue(workloads.classes().stream().anyMatch(c -> c.cores() < 4), distribution + " — 4코어보다 작은 계층이 있어야 한다");
        }
        assertEquals(1.5, BenchmarkPointRunner.fragmentOf(9.5, 4), 1e-9);
        assertEquals(3.0, BenchmarkPointRunner.fragmentOf(3.0, 4), 1e-9, "기준 크기보다 작은 여유는 전부 자투리다");
        assertEquals(0.0, BenchmarkPointRunner.fragmentOf(8.0, 4), 1e-9, "기준 크기의 배수인 여유에는 자투리가 없다");
        assertEquals(0.0, BenchmarkPointRunner.fragmentOf(0.0, 4), 1e-9);
    }

    /**
     * 짝 지점이 있는 셀은 전 열이 0~100 사이 값이고 표본 수가 총량 지표(가동 노드 증감)의 표본 수와 같다. 짝 지점이 없는 고부하 셀은 값 없이
     * 사유를 단 무효다 — 짝 조건이 빠지면 적게 배치한 열이 남긴 여유로 고부하 셀에 값이 생긴다. 반증판에는 기본 설정을 바꾼 열이 자기 배치
     * 건수가 기준 집합과 다른 지점을 빼는 셀이 있어(90% heavytail의 power-s250 · power-s0 · power-s1000) 그 열의 짝 조건까지 본다.
     */
    @DisplayName("주 결과판 · 반증판의 자원 조각화 비율은 짝 지점에서만 표본을 내고 짝 지점이 없는 셀은 사유를 단 무효다")
    @Test
    void fragmented_headroom_is_valid_only_where_points_are_paired() {
        int valid = 0;
        int unpaired = 0;
        int overrideDropped = 0;
        for (String board : List.of(BenchmarkClusters.MAIN, BenchmarkClusters.ADVERSARIAL)) {
            for (String distribution : List.of("heavytail", "uniform")) {
                for (var c : service.compare(board, distribution).boards().get(0).cells()) {
                    for (var s : c.strategies()) {
                        MetricValue m = metric(s, BenchmarkRules.FRAGMENTED_HEADROOM);
                        MetricValue totals = metric(s, BenchmarkRules.ACTIVE_NODE_DELTA);
                        String where = board + " " + distribution + " " + c.loadRatioPercent() + ":" + c.powerControl() + " " + s.strategyId();
                        assertEquals(totals.samples(), m.samples(), where + " — 표본은 짝 지점에서만 나온다");
                        if (m.samples() > 0) {
                            assertTrue(m.valid(), where + " — " + m.invalidReason());
                            assertTrue(m.median() >= 0 && m.median() <= 100, where + " 값 " + m.median());
                            valid++;
                        } else if (outsideBaseSet(s)
                                && metric(strategy(c, "power"), BenchmarkRules.ACTIVE_NODE_DELTA).samples() > 0) {
                            assertNull(m.median(), where);
                            assertFalse(m.valid(), where);
                            assertTrue(m.invalidReason().startsWith("기준 집합과 배치 건수가 다른 지점뿐이라 조각화 비율을 낼 수 없다"),
                                    where + " — " + m.invalidReason());
                            overrideDropped++;
                        } else {
                            assertNull(m.median(), where);
                            assertFalse(m.valid(), where);
                            assertEquals("기본 설정 열 전부의 배치 건수가 같은 시각 지점이 없다 — 적게 배치해 남긴 여유는 조각화 비교 대상이 아니다",
                                    m.invalidReason(), where);
                            unpaired++;
                        }
                        if (c.loadRatioPercent() == 110) assertEquals(0, m.samples(), where + " — 110% 셀은 짝 지점이 없다");
                    }
                }
            }
        }
        assertTrue(valid > 0 && unpaired > 0 && overrideDropped > 0,
                "값이 있는 셀 · 짝 지점이 없는 셀 · 자기 건수가 달라 표본이 전부 빠진 기본 설정을 바꾼 열을 모두 봐야 한다");
    }

    /**
     * 주 결과판 heavytail의 다섯 열 자원 조각화 비율 — 첫 실행에서 관측한 값을 리터럴로 고정한다(회귀 고정). 두 전원 상태 셀은 배치가 같아 값이 같다.
     * 110% 셀은 짝 지점이 없어 위 테스트가 본다. 허용 오차는 판정 규칙의 지점 비교 무시 폭과 같은 근거다.
     */
    @DisplayName("주 결과판 heavytail 의 자원 조각화 비율이 세 부하율에서 관측 리터럴과 같다")
    @Test
    void fragmented_headroom_on_the_main_board_matches_the_observed_literals() {
        List<String> columns = List.of("ffd-size", "ffd-priority-size", "bfd", "weighted", "power");
        Map<Integer, double[]> expected = Map.of(
                30, new double[]{16.2046, 16.2046, 11.6147, 23.0101, 25.5453},
                60, new double[]{11.6981, 11.6981, 6.8479, 37.8524, 27.2332},
                90, new double[]{18.2917, 20.3239, 7.2408, 51.6563, 40.3181});
        int checked = 0;
        for (var c : service.compare("main", "heavytail").boards().get(0).cells()) {
            double[] want = expected.get(c.loadRatioPercent());
            if (want == null) continue;
            for (int i = 0; i < columns.size(); i++) {
                assertEquals(want[i], metric(strategy(c, columns.get(i)), BenchmarkRules.FRAGMENTED_HEADROOM).median(),
                        BenchmarkRules.JUDGMENT_POINT_IGNORE_PP, c.loadRatioPercent() + ":" + c.powerControl() + " " + columns.get(i));
            }
            checked++;
        }
        assertEquals(expected.size() * 2, checked, "세 부하율 · 두 전원 상태 셀을 모두 봐야 한다");
    }

    // ── 코어 기준 배치 성공률 ───────────────────────────────────

    /**
     * 두 성공률은 같은 배치를 건수와 코어로 센 값이라, 전부 배치했을 때만 100이 되는 것도 같다. 유효 여부 · 표본 수 · 사유도 배치 성공률과 같다 —
     * 제약을 끈 변종은 두 지표 모두 천장 사유를 단 무효다. 코어 누적이 배치 성공과 무관하게 늘면 이 동치가 깨진다.
     */
    @DisplayName("주 결과판 · 반증판의 코어 기준 배치 성공률은 배치 성공률과 유효 여부가 같고 둘 다 100 인 열도 같다")
    @Test
    void core_placement_rate_is_valid_where_the_placement_rate_is_and_full_together() {
        int compared = 0;
        int ceiling = 0;
        for (String board : List.of(BenchmarkClusters.MAIN, BenchmarkClusters.ADVERSARIAL)) {
            for (String distribution : List.of("heavytail", "uniform")) {
                for (var c : service.compare(board, distribution).boards().get(0).cells()) {
                    for (var s : c.strategies()) {
                        MetricValue core = metric(s, BenchmarkRules.CORE_PLACEMENT_RATE);
                        MetricValue count = metric(s, BenchmarkRules.PLACEMENT_RATE);
                        String where = board + " " + distribution + " " + c.loadRatioPercent() + ":" + c.powerControl() + " " + s.strategyId();
                        assertEquals(count.valid(), core.valid(), where);
                        assertEquals(count.samples(), core.samples(), where + " — 전 시각 지점에서 낸다");
                        assertEquals(count.invalidReason(), core.invalidReason(), where);
                        if (core.valid()) {
                            assertEquals(count.median() == 100.0, core.median() == 100.0,
                                    where + " — 건수 " + count.median() + " · 코어 " + core.median());
                            compared++;
                        } else {
                            assertNull(core.median(), where);
                            assertTrue(core.invalidReason().contains("천장"), where + " — " + core.invalidReason());
                            ceiling++;
                        }
                    }
                }
            }
        }
        assertTrue(compared > 0 && ceiling > 0, "값이 있는 열과 제약을 끈 천장 열을 모두 봐야 한다");
    }

    /** 짝 지점이 없는 고부하 셀에도 값이 있어야 이 지표가 있는 까닭이 선다 — 110% 셀에는 전부 배치하지 못한 열이 있다. */
    @DisplayName("주 결과판의 90 · 110% 셀에서 코어 기준 배치 성공률은 0~100 사이 값이고 110% 셀에는 100 이 아닌 열이 있다")
    @Test
    void core_placement_rate_has_values_in_the_high_load_cells() {
        int cells = 0;
        for (String distribution : List.of("heavytail", "uniform")) {
            for (var c : service.compare("main", distribution).boards().get(0).cells()) {
                if (c.loadRatioPercent() < 90) continue;
                boolean below = false;
                for (var s : c.strategies()) {
                    MetricValue core = metric(s, BenchmarkRules.CORE_PLACEMENT_RATE);
                    if (!core.valid()) continue;
                    String where = distribution + " " + c.loadRatioPercent() + ":" + c.powerControl() + " " + s.strategyId();
                    assertTrue(core.median() >= 0 && core.median() <= 100, where + " 값 " + core.median());
                    if (core.median() < 100) below = true;
                }
                if (c.loadRatioPercent() == 110) assertTrue(below, distribution + " 110:" + c.powerControl() + " — 전부 배치하지 못한 열이 있어야 한다");
                cells++;
            }
        }
        assertEquals(8, cells, "두 분포 × 90 · 110% × 두 전원 상태 셀을 모두 봐야 한다");
    }

    /**
     * 주 결과판 110% 셀의 일곱 열 코어 기준 배치 성공률 — 첫 실행에서 관측한 값을 리터럴로 고정한다(회귀 고정). 두 전원 상태 셀은 배치가 같아 값이 같다.
     * 허용 오차는 판정 규칙의 지점 비교 무시 폭과 같은 근거다.
     */
    @DisplayName("주 결과판 110% 셀의 코어 기준 배치 성공률이 두 분포에서 관측 리터럴과 같다")
    @Test
    void core_placement_rate_on_the_main_board_matches_the_observed_literals() {
        List<String> columns = List.of("ffd-size", "ffd-priority-size", "pabfd", "bfd", "weighted", "power", "coolest-first");
        Map<String, double[]> expected = Map.of(
                "heavytail", new double[]{97.1098, 97.1098, 97.1098, 96.5805, 96.8614, 96.8614, 94.2197},
                "uniform", new double[]{79.4179, 70.114, 76.0136, 80.5874, 80.5874, 80.5874, 80.5874});
        int checked = 0;
        for (var e : expected.entrySet()) {
            for (var c : service.compare("main", e.getKey()).boards().get(0).cells()) {
                if (c.loadRatioPercent() != 110) continue;
                for (int i = 0; i < columns.size(); i++) {
                    assertEquals(e.getValue()[i], metric(strategy(c, columns.get(i)), BenchmarkRules.CORE_PLACEMENT_RATE).median(),
                            BenchmarkRules.JUDGMENT_POINT_IGNORE_PP, e.getKey() + " 110:" + c.powerControl() + " " + columns.get(i));
                }
                checked++;
            }
        }
        assertEquals(expected.size() * 2, checked, "두 분포 · 두 전원 상태 셀을 모두 봐야 한다");
    }

    /** 열 하나의 셀×지표 값 — median만이 아니라 samples·valid까지 비교 키에 넣는다(표본 축소·유효성 차이도 잡는다). */
    private static Map<String, String> columnOf(PlacementBenchmarkResponse.Board board, String strategyId) {
        Map<String, String> out = new LinkedHashMap<>();
        for (var cell : board.cells()) for (var s : cell.strategies()) if (s.strategyId().equals(strategyId)) for (var m : s.metrics()) {
            out.put(cell.loadRatioPercent() + "/" + cell.powerControl() + "/" + m.key(), m.median() + "|" + m.samples() + "|" + m.valid());
        }
        return out;
    }

    /**
     * override 열이 기준 집합과 건수가 다른 지점에서 총량을 내지 못하면 그 사실이 응답에 보인다 — PAIRING_RULE의 표기 의무.
     * 등록 판 전부를 훑어 그런 셀을 찾고, 있으면 셀 문장에 열 이름과 지점 수가 적혀 있는지 본다(없으면 규칙이 검증 불가다).
     */
    @DisplayName("override 열의 총량 표본이 기준 집합보다 적은 셀은 그 사실을 셀 문장에 적는다")
    @Test
    void dropped_override_points_are_reported_in_the_cell() {
        int found = 0;
        for (String board : new BenchmarkClusters().boardIds()) {
            for (var b : service.compare(board, "heavytail").boards()) {
                for (var cell : b.cells()) {
                    int core = strategy(cell, "power").metrics().stream().filter(m -> m.key().equals(BenchmarkRules.GENERATOR_POWER)).findFirst().orElseThrow().samples();
                    for (var s : cell.strategies()) {
                        if (!outsideBaseSet(s)) continue;
                        int mine = s.metrics().stream().filter(m -> m.key().equals(BenchmarkRules.GENERATOR_POWER)).findFirst().orElseThrow().samples();
                        if (mine < core) {
                            found++;
                            assertNotNull(cell.totalsIncomparableReason(), board + " " + cell.loadRatioPercent() + " " + s.strategyId() + " — 표본이 줄었는데 셀 문장이 없다");
                            assertTrue(cell.totalsIncomparableReason().contains(s.strategyId() + " " + (core - mine) + "곳 제외(표본 " + mine + ")"),
                                    board + " " + cell.loadRatioPercent() + " — 셀 문장에 열과 지점 수가 없다: " + cell.totalsIncomparableReason());
                        }
                    }
                }
            }
        }
        assertTrue(found > 0, "표본이 줄어든 override 열이 한 곳도 없다 — 이 시험이 아무것도 보지 않는다(판 구성이 바뀌었으면 시험을 다시 본다)");
    }

    private static Map<String, Double> flattenBoard(PlacementBenchmarkResponse.Board board) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (var cell : board.cells()) for (var s : cell.strategies()) for (var m : s.metrics()) {
            out.put(cell.loadRatioPercent() + "/" + cell.powerControl() + "/" + s.strategyId() + "/" + m.key(), m.median());
        }
        return out;
    }

    /** 엔진 쪽 변종 열의 순서 리터럴 — 열을 더하면 이 목록과 응답 열 수 확인이 함께 바뀐다. */
    @DisplayName("엔진 변종 열 순서가 고정되고 기본 설정 열은 override 가 비어 있다")
    @Test
    void engine_variant_order_is_pinned_and_default_columns_have_no_override() {
        assertEquals(List.of("resource-only-gated", "resource-only-ungated", "weighted-affinity",
                "power-s250", "power-s1000", "power-s0", "power-idle-fixed",
                "weighted-priority-age", "weighted-priority-size", "weighted-temp-65"), PlacementBenchmarkService.ENGINE_VARIANT_ORDER);
        Map<String, Map<String, String>> expected = Map.of(
                "weighted-affinity", Map.of("affinityEnabled", "true"),
                "power-s250", Map.of("marginalScaleWatts", "250.0"),
                "power-s1000", Map.of("marginalScaleWatts", "1000.0"),
                "power-s0", Map.of("marginalScaleWatts", "0.0"),
                "power-idle-fixed", Map.of("idleFromMeasurement", "false"),
                "weighted-temp-65", Map.of("maxTemperatureCelsius", "65.0"));
        var cell = service.compare("main", "heavytail").boards().get(0).cells().get(0);
        for (var s : cell.strategies()) {
            assertEquals(expected.getOrDefault(s.strategyId(), Map.of()), s.engineConfigOverride(), s.strategyId());
        }
    }

    /**
     * 눈금 전환의 점수 차가 닫힌 형태와 같다 — 판의 모든 노드·지점·요구 코어에서.
     *
     * <p>{@code score_S = 1 − rm/S}, {@code score_pMax = 1 − rm/pMax}이므로
     * {@code score_S − score_pMax = rm·(1/pMax − 1/S)}다. 이 등식이 성립하면 바뀐 것은 분모 하나뿐이고,
     * {@code rm}(실질 한계 전력)은 그대로라는 뜻이다. 포화 구간은 등식이 성립하지 않으므로 제외하고,
     * 제외된 수가 0인 것도 함께 확인한다 — 기본 눈금에서는 판에 포화가 없어야 한다.</p>
     */
    @DisplayName("판 전체에서 눈금 전환의 점수 차가 rm·(1/정격 − 1/눈금) 과 같다")
    @Test
    void the_score_shift_equals_the_closed_form_on_every_board() {
        Set<Integer> requiredCores = new TreeSet<>();
        for (String location : List.of("classpath:scheduler/benchmark-workloads-heavytail.yml",
                "classpath:scheduler/benchmark-workloads-uniform.yml")) {
            BenchmarkWorkloads.load(location).classes().forEach(c -> requiredCores.add(c.cores()));
        }
        double scale = new SchedulerProperties().getPower().getMarginalScaleWatts();
        PowerModel scaled = new PowerModel(scaleProps(scale));
        PowerModel legacy = new PowerModel(scaleProps(0.0));   // 0 이하면 정격 분모(구 방식)
        BenchmarkClusters clusters = new BenchmarkClusters();
        int checked = 0;
        int saturatedByScale = 0;
        int saturatedByNameplate = 0;

        for (String boardId : clusters.boardIds()) {
            SyntheticCluster cluster = clusters.get(boardId);
            for (long t : PlacementBenchmarkService.sweepPoints(cluster)) {
                for (NodeResource n : PlacementBenchmarkService.nodesAt(cluster, t)) {
                    double pMax = n.getPowerCapacityWatts();
                    if (pMax <= 0) continue;
                    for (int cores : requiredCores) {
                        double rm = scaled.realMarginalPower(n, cores);
                        assertEquals(rm, legacy.realMarginalPower(n, cores), 1e-9,
                                "눈금이 실질 한계 전력(W)까지 바꿨다 — 분모만 바뀌어야 한다");
                        if (rm >= scale) { saturatedByScale++; continue; }
                        if (rm >= pMax) { saturatedByNameplate++; continue; }
                        assertEquals(rm * (1.0 / pMax - 1.0 / scale),
                                scaled.marginalScore(n, cores) - legacy.marginalScore(n, cores), 1e-9,
                                boardId + " " + n.getNodeId() + " " + cores + "코어 t=" + t + "s");
                        checked++;
                    }
                }
            }
        }
        assertEquals(0, saturatedByScale,
                "출하 눈금 " + scale + "W 에서 포화한 지점이 있다 — 판이 실제로 쓰는 요구 코어에서 실질 한계 "
                        + "전력이 " + BenchmarkRules.MAX_REAL_MARGINAL_WATTS + "W 를 넘지 않아야 한다");
        assertEquals(0, saturatedByNameplate,
                "정격 분모 쪽에서 포화한 지점이 있다 — 구 방식과의 비교가 성립하지 않는 자리다");
        int expected = clusters.boardIds().stream()
                .mapToInt(id -> PlacementBenchmarkService.nodesAt(clusters.get(id), 0L).size()).sum()
                * BenchmarkRules.SWEEP_POINTS * requiredCores.size();
        assertEquals(expected, checked,
                "훑은 조합 수가 판·지점·노드·요구 코어의 곱과 다르다 — 판이나 코어가 줄면 여기서 잡는다");
    }

    /**
     * <b>대조군의 총전력은 눈금과 무관하다</b> — 격차 지표의 분모가 우리 변경에 흔들리지 않는다는 뜻이고,
     * 그것이 "격차가 줄었다"를 우리 엔진의 변화로 읽을 수 있는 근거다.
     *
     * <p>구조상으로는 자명하다(대조군 어느 것도 한계 전력 점수를 부르지 않는다). 그러나 자명함은
     * 테스트가 아니다 — 나중에 대조군이 우리 점수를 쓰게 되면 이 전제가 조용히 깨지고, 그때 격차의
     * 해석이 통째로 바뀐다. 눈금을 크게 벌린 두 설정으로 같은 판을 돌려 대조군 열이 한 자리도
     * 움직이지 않는지 본다. 견주는 두 설정은 W 눈금과 정격 분모다.</p>
     */
    @DisplayName("눈금을 바꿔도 대조군 총전력은 한 자리도 움직이지 않는다")
    @Test
    void the_baselines_do_not_move_with_the_scale() {
        Cell mine = cell(service.compare("main", "heavytail").boards().get(0), 60, "AVAILABLE");
        Cell other = cell(serviceWith(scaleProps(0.0)).compare("main", "heavytail").boards().get(0), 60, "AVAILABLE");

        int compared = 0;
        for (StrategyResult s : mine.strategies()) {
            // 전력 엔진 계열(power·눈금 변종·역산 차단)만 움직여야 한다 — 변종 열은 기본 설정 사본이라 눈금을 고정하지 않은 열은 따라간다
            if (s.strategyId().equals("power") || s.strategyId().startsWith("power-")) continue;
            StrategyResult t = other.strategies().stream()
                    .filter(x -> x.strategyId().equals(s.strategyId())).findFirst()
                    .orElseThrow(() -> new AssertionError(s.strategyId() + " 열이 사라졌다"));
            assertEquals(median(s, BenchmarkRules.GENERATOR_POWER), median(t, BenchmarkRules.GENERATOR_POWER),
                    s.strategyId() + " 총전력이 눈금을 따라 움직였다 — 격차의 분모가 흔들린다");
            compared++;
        }
        assertTrue(compared >= 5, "대조군 열이 " + compared + " 개뿐이다 — 비교가 성립하지 않는다");
        assertNotEquals(median(strategy(mine, "power"), BenchmarkRules.GENERATOR_POWER),
                median(strategy(other, "power"), BenchmarkRules.GENERATOR_POWER),
                "우리 전력 엔진 열까지 그대로면 분모 교체가 아무 일도 하지 않은 것이다");
    }

    private static SchedulerProperties scaleProps(double scaleWatts) {
        SchedulerProperties p = new SchedulerProperties();
        p.getPower().setMarginalScaleWatts(scaleWatts);
        return p;
    }

    private static PolicySnapshot variant(EngineConfigSnapshot config, String name) {
        return config.policies().stream().filter(p -> p.variant().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " 변종이 스냅샷에 없다"));
    }

    // ── 열 상승 척도 점검이 판 전제로 실린다 ─────────────────

    /**
     * 판이 노드 전량의 코어당 열 상승 세 계수를 전제로 싣고, 행 순서가 유휴 전력 격차 표와 같다.
     * 격차·비율·판정 필드가 없는 것은 의도다 — 두 엔진 값이 다르다는 사실 자체가 척도 의존의 증거고,
     * 어느 쪽이 옳은지는 이 표가 말하지 않는다.
     */
    @DisplayName("판이 노드 전량의 코어당 열 상승 세 계수를 전제로 싣는다")
    @Test
    void the_board_states_the_thermal_rise_scale_check_for_every_node() {
        Board board = service.compare("main", "heavytail").boards().get(0);

        assertEquals(board.nodeCount(), board.thermalRiseScaleCheck().size());
        assertEquals(board.idleAssumptionGaps().stream().map(IdleGap::nodeId).toList(),
                board.thermalRiseScaleCheck().stream().map(ThermalRiseScale::nodeId).toList(),
                "두 전제 표의 행 순서가 다르면 나란히 읽을 수 없다");
        for (ThermalRiseScale r : board.thermalRiseScaleCheck()) {
            assertTrue(Double.isFinite(r.engineRisePerCoreC()) && Double.isFinite(r.generatorRisePerCoreC()), r.nodeId());
            assertTrue(r.engineRisePerCoreIfKelvin() > r.engineRisePerCoreC(),
                    r.nodeId() + ": 같은 식이 두 눈금에서 같은 값이면 척도 의존이 없다는 뜻이라 이 표가 필요 없다");
        }
        assertEquals(properties.getThermal().getInertiaK() * board.thermalRiseScaleCheck().get(0).baseTemperatureC()
                        / board.thermalRiseScaleCheck().get(0).cores(),
                board.thermalRiseScaleCheck().get(0).engineRisePerCoreC(), 1e-9,
                "엔진 계수는 운영 설정의 inertiaK 로 계산해야 한다");
    }

    /**
     * 판에 실린 <b>엔진 쪽 계수</b>가 진짜 엔진 식과 같은지 못 박는다.
     *
     * <p>{@code GroundTruthOracle}은 엔진 식({@code inertiaK × T × 코어비율})을 <b>손으로 복제</b>해
     * 갖고 있다. 복제본과 원본을 묶는 테스트가 없으면, 나중에 {@code ThermalModel}의 상승 식을 고쳐도
     * <b>아무 테스트도 실패하지 않고</b> 응답의 그 필드만 조용히 거짓이 된다 — 존재하지 않는 식을
     * "엔진이 이렇게 잰다"고 설명하게 된다. 그래서 여기서 <b>실제 엔진 모델을 불러</b> 대조한다.</p>
     *
     * <p>엔진 식을 바꾸면 이 테스트가 먼저 터진다. 그때 할 일은 테스트를 고치는 것이 아니라
     * {@code thermalRiseScaleCheck}가 새 식을 반영하게 하는 것이다.</p>
     */
    @DisplayName("판에 실린 엔진 쪽 열 상승 계수가 실제 엔진 모델의 계산과 같다")
    @Test
    void the_reported_engine_rise_matches_what_the_engine_model_actually_computes() {
        ThermalModel engineModel = new ThermalModel(properties);
        Board board = service.compare("main", "heavytail").boards().get(0);

        for (ThermalRiseScale r : board.thermalRiseScaleCheck()) {
            // 판이 기준으로 삼은 상태(CPU 0%, 예약 0)를 그대로 노드로 만든다.
            NodeResource node = new NodeResource("bm", r.nodeId(), r.cores(), r.cores() * 4096L, 0,
                    0, 0, 0, 200, 500, r.baseTemperatureC(), 1000, 500);

            double engineRiseForOneCore =
                    engineModel.predictedTemperature(node, 1) - engineModel.effectiveTemperature(node);

            assertEquals(engineRiseForOneCore, r.engineRisePerCoreC(), 1e-9,
                    r.nodeId() + ": 응답이 밝힌 엔진 계수가 엔진이 실제로 쓰는 식과 다르다 — "
                            + "오라클의 복제식이 낡았다는 뜻이고, 그 칸은 존재하지 않는 식을 설명하고 있다");
        }
        assertFalse(board.thermalRiseScaleCheck().isEmpty(), "행이 없으면 이 시험이 아무것도 보지 않는다");
    }

    // ── 온도 지표는 엔진 노브를 따르지 않는다 ─────────────────

    /**
     * 온도 지표는 엔진의 열관성 가정이 아니라 생성식으로 낸다. 엔진 노브({@code inertiaK})를 크게 흔들어도
     * 값이 움직이지 않아야, 열 엔진이 자기 가정으로 채점받지 않는다는 것이 증명된다.
     *
     * <p>비교 대상은 <b>자원전용 제약 해제 변종 하나</b>다. 다른 전략은 전부 온도 제약 C3를 받고, C3의
     * "배치 후 예상 온도"가 바로 {@code inertiaK}를 쓰므로 노브를 돌리면 <b>배치 자체</b>가 바뀐다 —
     * 그 경우 지표가 움직이는 것은 측정이 아니라 결정이 바뀐 것이고, 그것은 옳은 동작이다(실제로
     * {@code ffd-size}는 13.42 → 11.01로 움직인다). 측정이 노브에 무관함을 보려면 배치가 노브에 무관한
     * 전략에서 봐야 하고, 그것이 게이트까지 끈 자원전용 변종이다.</p>
     */
    @DisplayName("열관성 계수를 바꿔도 온도 지표가 움직이지 않는다 — 배치가 노브에 무관한 전략에서")
    @Test
    void the_temperature_metrics_do_not_follow_the_engine_inertia_knob() {
        SchedulerProperties tuned = new SchedulerProperties();
        // 기본 0.15의 두 배. 온도 분포 지표는 총량과 같이 <b>전 전략의 배치 건수가 같은 지점</b>에서만 값이
        // 나오는데, 노브를 서른 배로 돌리면 게이트가 켜진 다른 전략이 저부하에서도 배치에 실패해 어느 셀에도
        // 짝 지점이 남지 않는다 — 그러면 비교할 값 자체가 없다. 두 배면 짝은 남고, 엔진 모델로 잰다면
        // 예약이 있는 노드의 온도가 (1+0.15r)→(1+0.30r)로 움직여 4자리 반올림한 표준편차가 달라진다.
        // 경고 구간 노드 수는 정수라 작은 흔들림에 우연히 같을 수 있으므로, 이 테스트의 힘은 표준편차 쪽에 있다.
        tuned.getThermal().setInertiaK(0.30);
        PlacementBenchmarkService other = serviceWith(tuned);

        // 어느 셀에서 짝이 사라질지는 노브 값에 따라 다르므로 셀을 하나로 못 박지 않고, 두 실행 모두에서 값이
        // 나온 첫 셀을 고른다. 그런 셀이 없으면 테스트는 아무것도 안 본 것이라 실패다.
        Board baseBoard = service.compare("main", "heavytail").boards().get(0);
        Board movedBoard = other.compare("main", "heavytail").boards().get(0);
        Cell base = null;
        Cell moved = null;
        for (Cell candidate : baseBoard.cells()) {
            Cell counterpart = cell(movedBoard, candidate.loadRatioPercent(), candidate.powerControl());
            if (median(strategy(candidate, "resource-only-ungated"), BenchmarkRules.TEMP_STDDEV) != null
                    && median(strategy(counterpart, "resource-only-ungated"), BenchmarkRules.TEMP_STDDEV) != null) {
                base = candidate;
                moved = counterpart;
                break;
            }
        }
        assertNotNull(base, "두 실행 모두에서 온도 표준편차가 나온 셀이 없다 — 값이 없으면 이 시험이 아무것도 보지 않는다");

        StrategyResult a = strategy(base, "resource-only-ungated");
        StrategyResult b = strategy(moved, "resource-only-ungated");
        // 표본 지점 집합이 같아야 중앙값 비교가 성질을 본다 — 노브가 다른 전략의 배치를 바꿔 짝 지점이 달라지면
        // 지표가 노브를 안 봐도 중앙값이 움직인다. 그 경우는 이 테스트의 전제가 깨진 것이라 따로 말한다.
        assertEquals(metric(a, BenchmarkRules.TEMP_STDDEV).samples(), metric(b, BenchmarkRules.TEMP_STDDEV).samples(),
                "두 실행의 짝 지점 수가 다르다 — 중앙값이 달라도 측정이 노브를 따른 것이 아니라 표본이 달라진 것이다");
        assertEquals(median(a, BenchmarkRules.TEMP_STDDEV), median(b, BenchmarkRules.TEMP_STDDEV),
                "열관성 계수가 온도 표준편차를 움직인다 — 평가 기준이 엔진의 가정이라는 뜻이다");
        assertEquals(median(a, BenchmarkRules.WARN_ZONE_NODES), median(b, BenchmarkRules.WARN_ZONE_NODES),
                "열관성 계수가 경고 구간 노드 수를 움직인다");
        // 성공률은 이 변종에서 무효라 median이 null이다 — null == null로 비교하면 아무것도 안 본다.
        // 배치가 노브에 무관하다는 전제는 값이 나온 지점 수로 확인한다(배치가 달라지면 표본 수가 달라진다).
        MetricValue rateBase = metric(a, BenchmarkRules.PLACEMENT_RATE);
        MetricValue rateMoved = metric(b, BenchmarkRules.PLACEMENT_RATE);
        assertFalse(rateBase.valid(), "제약 해제 변종의 성공률은 비교 대상이 아니어야 한다");
        assertEquals(rateBase.samples(), rateMoved.samples(),
                "제약 해제 변종의 배치가 노브를 따라 움직였다면 이 시험의 전제가 깨진 것이다");
        assertEquals(medians(base, BenchmarkRules.GENERATOR_POWER).size(),
                medians(moved, BenchmarkRules.GENERATOR_POWER).size(), "열 구성이 달라졌다");
    }

    /**
     * 위 테스트의 <b>짝</b>이다. 위는 "측정이 노브를 안 따른다"를 보고, 이것은 "<b>제약은 노브를 따라야 한다</b>"를 본다.
     * 둘이 함께 있어야 "노브를 바꿔도 값이 같다"가 <b>평가기가 주입 설정을 무시한 결과</b>가 아님이 증명된다.
     *
     * <p>제약 평가기가 {@code new SchedulerProperties()}(코드 기본값)로 만들어지면 운영에서 환경변수로 덮은
     * 임계값을 통째로 무시하고, 벤치마크가 <b>운영과 다른 조건</b>에서 낸 수치를 연구 근거로 쓰게 된다.
     * 응답만 봐서는 절대 드러나지 않는 종류의 오염이라 여기서 못 박는다.</p>
     *
     * <p>온도 게이트가 켜진 전략은 C3가 "배치 후 예상 온도"를 {@code inertiaK}로 계산하므로, 계수를 키우면
     * 더 많은 후보가 막혀 배치가 달라져야 한다. 안 달라지면 평가기가 주입 설정을 안 보고 있다는 뜻이다.</p>
     */
    @DisplayName("온도 게이트가 켜진 전략은 열관성 계수를 따라 배치가 달라진다 — 평가기가 주입 설정을 본다")
    @Test
    void the_gated_strategies_do_follow_the_engine_inertia_knob() {
        SchedulerProperties tuned = new SchedulerProperties();
        tuned.getThermal().setInertiaK(5.0);
        PlacementBenchmarkService other = serviceWith(tuned);

        Cell base = cell(service.compare("main", "heavytail").boards().get(0), 60, "UNAVAILABLE");
        Cell moved = cell(other.compare("main", "heavytail").boards().get(0), 60, "UNAVAILABLE");

        assertNotEquals(medians(base, BenchmarkRules.GENERATOR_POWER),
                medians(moved, BenchmarkRules.GENERATOR_POWER),
                "열관성 계수를 서른 배로 올렸는데 제약을 받는 전략들의 전력이 그대로다 — "
                        + "평가기가 주입 설정 대신 코드 기본값으로 만들어졌다는 뜻이다");
    }

    /**
     * 미계측 노드를 온도 지표에서 빼는 성질은 그대로다 — 오라클이 그 노드의 온도를 알아도. 필터를 풀면
     * 계측 공백판과 주 결과판의 온도 열이 같아져 계측 공백판이 아무것도 재지 못한다.
     */
    @DisplayName("계측 공백판의 온도 표준편차는 계측 노드 아홉 대만 본다")
    @Test
    void the_sensor_gap_board_still_measures_only_the_measured_nodes() {
        Board sparse = service.compare("sparse", "heavytail").boards().get(0);
        Board main = service.compare("main", "heavytail").boards().get(0);

        assertEquals(9, sparse.measuredNodeCount());
        assertNotEquals(medians(cell(main, 60, "UNAVAILABLE"), BenchmarkRules.TEMP_STDDEV),
                medians(cell(sparse, 60, "UNAVAILABLE"), BenchmarkRules.TEMP_STDDEV),
                "두 판의 노드 값이 같은데 표준편차가 같다면 계측 여부를 보지 않고 있다");
    }

    // ── 무효 지표는 숫자를 싣지 않는다 ─────────────────────────

    /**
     * 순위 근거가 아니라고 판정한 칸에 중앙값이 실리면 {@code valid}를 보지 않은 인용자에게는 그냥 숫자다.
     * 각주로 밀어 둔 표시는 인용될 때 빠져나가므로, 그런 지표에는 중앙값·최소·최대를 싣지 않는다.
     */
    @DisplayName("순위 근거로 쓸 수 없는 지표는 중앙값·최소·최대를 싣지 않는다")
    @Test
    void an_invalid_metric_carries_no_number() {
        int invalidSeen = 0;
        for (String board : List.of("main", "adversarial", "sparse", "convex-misrated")) {
            for (Cell c : service.compare(board, "heavytail").boards().get(0).cells()) {
                for (StrategyResult s : c.strategies()) {
                    for (MetricValue m : s.metrics()) {
                        if (m.valid()) {
                            assertNotNull(m.median(), board + " " + s.strategyId() + " " + m.key()
                                    + ": 유효한데 값이 없다");
                            assertNull(m.invalidReason(), m.key() + ": 유효한 칸에 사유가 붙었다");
                            continue;
                        }
                        invalidSeen++;
                        String where = board + " " + c.loadRatioPercent() + "% " + c.powerControl()
                                + " " + s.strategyId() + " " + m.key();
                        assertNull(m.median(), where + ": 무효인데 숫자가 실렸다 — valid 를 보지 않은 인용자에게는 그냥 값이다");
                        assertNull(m.min(), where);
                        assertNull(m.max(), where);
                        // 지점별 값도 숫자 통로다. median만 지우고 배열에 값을 남기면 무효 판정이
                        // 응답의 한쪽에서만 지켜지고, 지점 대조는 그 배열을 그대로 읽는다.
                        if (m.points() != null) {
                            assertTrue(m.points().stream().allMatch(Objects::isNull),
                                    where + ": 무효인데 지점별 값에 숫자가 남았다 — " + m.points());
                        }
                        assertNotNull(m.invalidReason(), where + ": 사유 없는 무효는 되짚을 수 없다");
                    }
                }
            }
        }
        assertTrue(invalidSeen > 0, "무효 칸이 하나도 없으면 이 시험이 아무것도 보지 않는다");
    }

    /**
     * 위 테스트는 "무효면 숫자가 없다"만 본다 — 무효여야 할 칸이 <b>유효로 둔갑</b>하면 통과해 버린다.
     * 그래서 무효여야 하는 칸 하나를 이름으로 못 박는다: 온도 제약을 끈 변종의 개입 횟수는 항상 0이고,
     * 그 0은 값이 아니라 게이트가 없다는 뜻이라 반드시 무효·null이어야 한다.
     */
    @DisplayName("온도 제약을 끈 변종의 개입 횟수는 무효이고 숫자가 없다")
    @Test
    void the_ungated_variant_has_an_invalid_intervention_count_with_no_number() {
        for (Cell c : service.compare("main", "heavytail").boards().get(0).cells()) {
            MetricValue m = metric(strategy(c, "resource-only-ungated"), BenchmarkRules.C3_INTERVENTIONS);
            String where = c.loadRatioPercent() + "% " + c.powerControl();
            assertFalse(m.valid(), where + ": 게이트가 없는 전략의 개입 0 이 유효한 숫자로 나갔다");
            assertNull(m.median(), where + ": 무효인데 0 이 실렸다 — 인용자에게는 '개입 0건'으로 읽힌다");
            assertNotNull(m.invalidReason(), where);
            assertTrue(m.samples() > 0, where + ": 값이 나온 지점은 있었다는 사실은 남아야 한다");
        }
    }

    /**
     * 건당 전력의 무효 판정이 <b>첫 시각 지점 하나</b>로 결정되던 결함. t=0에 0건이고 다른 지점에 배치한
     * 전략은 정당한 중앙값을 갖는데 "배치 0건" 사유로 통째로 버려졌다. 고친 뒤에는 값이 나온 지점이
     * 하나라도 있으면 유효, 하나도 없으면 무효 — 둘이 정확히 일치해야 한다.
     */
    @DisplayName("건당 전력의 유효성은 값이 나온 지점이 있는지와 정확히 일치한다")
    @Test
    void per_workload_power_validity_matches_whether_any_point_produced_a_value() {
        for (String board : List.of("main", "adversarial", "sparse", "convex-misrated")) {
            for (Cell c : service.compare(board, "heavytail").boards().get(0).cells()) {
                for (StrategyResult s : c.strategies()) {
                    MetricValue m = metric(s, BenchmarkRules.PER_WORKLOAD_POWER);
                    String where = board + " " + c.loadRatioPercent() + "% " + s.strategyId();
                    assertEquals(m.samples() > 0, m.valid(),
                            where + ": 값이 나온 지점 " + m.samples() + "곳인데 valid=" + m.valid()
                                    + " — 첫 지점 하나로 판정하면 이 둘이 어긋난다");
                }
            }
        }
    }

    // ── 오염 없음 ─────────────────────────────────────────────

    /**
     * 배치 비교 서비스가 저장소를 하나도 받지 않는다. 이것이 "아무것도 저장하지 않는다"의 <b>구조적</b> 근거다 —
     * 주석이나 관례가 아니라 주입할 것이 없어서 저장할 수 없다. 저장된 워크로드를 쓰면 예약 적용 경로가
     * 같은 워크로드를 두 번 세고, 활성 정책 조회 경로는 정책이 없으면 새로 넣는다.
     */
    @DisplayName("하네스는 저장소를 하나도 주입받지 않는다")
    @Test
    void harness_takes_no_repository() {
        Constructor<?>[] ctors = PlacementBenchmarkService.class.getConstructors();
        assertEquals(1, ctors.length, "생성자가 여럿이면 저장소를 받는 경로가 숨을 수 있다");

        for (Class<?> type : ctors[0].getParameterTypes()) {
            assertFalse(type.getSimpleName().endsWith("Repository"),
                    "저장소를 주입받으면 진단이 데이터를 만들 수 있다: " + type.getName());
            assertFalse(type.getSimpleName().endsWith("Service"),
                    "다른 서비스를 타면 그 서비스의 부작용을 물려받는다: " + type.getName());
        }
    }

    /**
     * 같은 입력이면 같은 답. 벽시계·해시 순서·난수원 상태가 새어 들면 여기가 깨진다.
     *
     * <p>결정론은 재현성의 조건이면서 <b>오염 검출기</b>이기도 하다 — 앞 호출이 노드 스냅샷이나 정책을
     * 변이시켰다면 두 번째 호출의 숫자가 달라진다.</p>
     */
    @DisplayName("같은 판·같은 분포를 두 번 돌리면 숫자가 같다")
    @Test
    void same_input_gives_the_same_numbers() {
        // compareFresh: 캐시를 거치지 않는다 — 공개 경로는 두 번째 호출을 캐시에서 돌려주므로 결정론을 증명하지 못한다
        Map<String, Double> first = flatten(service.compareFresh("main", "heavytail"));
        Map<String, Double> second = flatten(service.compareFresh("main", "heavytail"));

        assertEquals(first, second,
                "두 번째 호출의 숫자가 다르다 — 앞 호출이 노드 스냅샷이나 정책을 변이시켰거나 "
                        + "벽시계·난수원 상태가 결과에 새어 들었다");
        assertFalse(first.isEmpty(), "비교할 숫자가 하나도 없다");
    }

    /**
     * 공개 경로의 두 번째 호출은 <b>캐시</b>에서 나온다 — 본문(판·전제·설정·지표·한계)은 같은 객체이고 응답 시각만 새로 찍힌다.
     * 캐시가 응답 시각을 과거로 만들거나 본문을 재계산하지 않는다는 두 성질을 함께 고정한다.
     */
    @DisplayName("같은 입력의 두 번째 호출은 캐시에서 나오고 응답 시각만 새로 찍힌다")
    @Test
    void second_call_is_served_from_cache_with_a_fresh_timestamp() {
        var first = service.compare("main", "heavytail");
        var second = service.compare("main", "heavytail");

        assertSame(first.boards(), second.boards(), "본문은 같은 객체 — 재계산하지 않았다");
        assertSame(first.assumptions(), second.assumptions());
        assertEquals(first.engineConfig().configDigest(), second.engineConfig().configDigest());
        assertFalse(second.evaluatedAt().isBefore(first.evaluatedAt()), "캐시 적중이 응답 시각을 과거로 만들지 않는다");
        assertEquals(flatten(first), flatten(second));
    }

    /** 설정이 바뀌면 키가 바뀌어 캐시가 빗나간다 — 옛 설정의 표가 새 설정의 응답으로 나가지 않는다. */
    @DisplayName("엔진 설정이 바뀌면 캐시가 빗나가고 다시 계산한다")
    @Test
    void config_change_misses_the_cache() {
        SchedulerProperties props = new SchedulerProperties();
        PlacementBenchmarkService s = new PlacementBenchmarkService(
                new EngineRegistry(List.of(new WeightedScoreScheduler(), new PowerAwareScheduler(),
                        new TemperatureAwareScheduler())),
                props, new BenchmarkClusters(), new PowerModel(props), new ThermalModel(props));
        var before = s.compare("main", "heavytail");
        props.getPower().setMarginalScaleWatts(250.0);
        var after = s.compare("main", "heavytail");

        assertNotEquals(before.engineConfig().configDigest(), after.engineConfig().configDigest(), "설정이 다르면 다이제스트가 다르다");
        assertNotSame(before.boards(), after.boards(), "다른 키라 재계산했다");
    }

    /**
     * 캐시 키의 입력 축 — 이름 목록을 리터럴로 고정한다. 축 하나가 빠지면 다른 입력이 같은 표를 받는다(워크로드 파일
     * 다이제스트가 빠져도 그렇다). 요청 축이 늘어나면(예: seeds·phaseMode) 이 리터럴이 먼저 바뀌어야 한다.
     */
    @DisplayName("캐시 키는 여섯 축(판·분포·판 파일·워크로드 파일·설정·규칙 다이제스트)이고 어느 축이 바뀌어도 키가 바뀐다")
    @Test
    void cache_key_covers_every_input_axis() {
        assertEquals(List.of("board", "distribution", "boardDigest", "workloadDigest", "configDigest", "rulesDigest"),
                PlacementBenchmarkService.CACHE_AXES, "축을 더하거나 빼면 이 리터럴과 계약 §8 을 함께 고친다");
        String[] base = {"main", "heavytail", "b1", "w1", "c1", "r1"};
        String key = PlacementBenchmarkService.cacheKey(base);
        assertEquals(PlacementBenchmarkService.CACHE_AXES.size(), key.split("\\|").length);
        for (int i = 0; i < base.length; i++) {
            String[] changed = base.clone(); changed[i] = changed[i] + "x";
            assertNotEquals(key, PlacementBenchmarkService.cacheKey(changed), "축 " + PlacementBenchmarkService.CACHE_AXES.get(i) + " 가 키에 반영되지 않는다");
        }
        assertThrows(IllegalStateException.class, () -> PlacementBenchmarkService.cacheKey("main", "heavytail"), "축 수가 다르면 키를 만들지 않는다");
    }

    /** 실행 중인 호출이 있으면 두 번째 호출은 기다리지 않고 429다. */
    @DisplayName("동시 두 번째 호출은 429 로 거절된다")
    @Test
    void concurrent_second_call_is_rejected_with_429() throws Exception {
        // 실행 슬롯(세마포어)을 테스트가 먼저 점유해 "실행 중" 상태를 만든다
        PlacementBenchmarkService s = newService();
        java.lang.reflect.Field f = PlacementBenchmarkService.class.getDeclaredField("running");
        f.setAccessible(true);
        java.util.concurrent.Semaphore running = (java.util.concurrent.Semaphore) f.get(s);
        assertTrue(running.tryAcquire(), "시험이 실행 슬롯을 먼저 잡는다");
        try {
            com.innogrid.aims.common.exception.BusinessException e = assertThrows(
                    com.innogrid.aims.common.exception.BusinessException.class, () -> s.compare("main", "heavytail"));
            assertEquals(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
        } finally {
            running.release();
        }
        assertNotNull(s.compare("main", "heavytail").boards(), "슬롯이 풀리면 정상 계산");
    }

    /** 서비스 인스턴스가 달라도 같은 답이어야 한다 — 인스턴스 필드에 상태가 쌓이지 않는다는 뜻. */
    @DisplayName("새 하네스 인스턴스도 같은 숫자를 낸다")
    @Test
    void a_fresh_harness_gives_the_same_numbers() {
        assertEquals(flatten(service.compare("main", "heavytail")),
                flatten(newService().compare("main", "heavytail")),
                "인스턴스마다 답이 다르면 하네스가 상태를 들고 있다");
    }

    // ── 전원 상태 두 판 ───────────────────────────────────────

    /**
     * 전원 제어 가정은 전략의 <b>입력이 아니다</b>. 그래서 두 판은 같은 배치 결정을 다르게 회계할 뿐이고,
     * 배치 성공률·엔진이 믿은 비용·경고 구간 노드 수는 <b>글자 하나 다르지 않아야</b> 한다.
     * 여기가 갈리면 전원 가정이 배치 경로로 새어 든 것이다.
     */
    @DisplayName("전원 상태 두 판이 같은 배치 결정을 회계한다")
    @Test
    void two_power_control_boards_account_the_same_decisions() {
        Board board = service.compare("main", "heavytail").boards().get(0);
        Map<Integer, List<Cell>> byRatio = board.cells().stream()
                .collect(Collectors.groupingBy(Cell::loadRatioPercent));

        for (Map.Entry<Integer, List<Cell>> entry : byRatio.entrySet()) {
            assertEquals(2, entry.getValue().size(), "부하율 " + entry.getKey() + "% 의 판이 둘이 아니다");
            Cell available = pick(entry.getValue(), "AVAILABLE");
            Cell unavailable = pick(entry.getValue(), "UNAVAILABLE");

            assertEquals(available.requestedWorkloads(), unavailable.requestedWorkloads(),
                    "요구량이 전원 가정에 따라 달라지면 같은 판이 아니다");

            for (String key : List.of(BenchmarkRules.PLACEMENT_RATE, BenchmarkRules.ENGINE_BELIEVED_COST,
                    BenchmarkRules.WARN_ZONE_NODES, BenchmarkRules.UNMEASURED_RATE)) {
                assertEquals(medians(available, key), medians(unavailable, key),
                        key + " 가 전원 가정에 따라 달라졌다 — 그 가정이 배치 경로로 새어 들었다");
            }
        }
    }

    /**
     * 전원 제어가 없으면 유휴 노드도 계속 전력을 먹으므로 총전력이 <b>작아질 수 없다</b>.
     *
     * <p><b>같을 수는 있다.</b> 어떤 전략이 판의 모든 노드에 퍼뜨리면 꺼 둘 노드가 남지 않아 두 회계가
     * 일치한다 — 그 전략은 전원 제어로 얻을 것이 없다는 뜻이고, 결함이 아니라 결과다. 그래서 전 전략에
     * 대해서는 부등식을 느슨하게 두고, <b>적어도 한 전략에서는 실제로 벌어지는지</b>를 따로 확인한다.
     * 그것마저 같으면 회계가 한쪽만 돌고 있다는 뜻이다.</p>
     */
    @DisplayName("전원 제어가 없는 판의 총전력이 작아지지 않고 뭉치는 전략에서는 실제로 더 크다")
    @Test
    void the_board_without_power_control_never_reports_less_total_power() {
        Board board = service.compare("main", "heavytail").boards().get(0);
        Cell available = cell(board, 30, "AVAILABLE");
        Cell unavailable = cell(board, 30, "UNAVAILABLE");

        int strictlyLarger = 0;
        for (StrategyResult on : available.strategies()) {
            Double withControl = median(on, BenchmarkRules.GENERATOR_POWER);
            Double without = median(strategy(unavailable, on.strategyId()), BenchmarkRules.GENERATOR_POWER);
            assertNotNull(withControl, on.strategyId());
            assertNotNull(without, on.strategyId());

            assertTrue(without >= withControl, on.strategyId()
                    + ": 전원 제어가 없는 판(" + without + "W)이 있는 판(" + withControl
                    + "W)보다 작다 — 꺼진 노드의 전력을 세고 있다");
            if (without > withControl) strictlyLarger++;
        }
        assertTrue(strictlyLarger > 0,
                "어느 전략에서도 두 판이 갈리지 않았다 — 전원 상태 가정이 회계에 반영되지 않고 있다");

        // 노드를 뭉쳐 쓰는 전략에서는 꺼 둘 노드가 남아 두 판이 반드시 갈린다.
        Double packedWith = median(strategy(available, "ffd-size"), BenchmarkRules.GENERATOR_POWER);
        Double packedWithout = median(strategy(unavailable, "ffd-size"), BenchmarkRules.GENERATOR_POWER);
        assertTrue(packedWithout > packedWith,
                "선착순은 가동 노드에 뭉쳐 넣어 빈 노드를 남기는데 두 판이 같다: "
                        + packedWith + "W vs " + packedWithout + "W");
    }

    /**
     * 전원 제어가 있다는 가정에서도 <b>배치 전부터 돌던 노드는 끌 수 없다</b>. 그래서 그 판의 총전력은
     * 없는 판의 총전력에서 <b>유휴 노드 몫만큼만</b> 낮아질 수 있다.
     *
     * <p>경계를 응답에서 유도한다 — 유휴 노드 수는 {@code nodeCount − activeNodesBefore}이고 그 노드들이
     * 낼 수 있는 최대치는 {@code idleAssumptionGaps}의 실제 유휴 전력 중 큰 것들의 합이다. 통전 노드
     * 집합에서 "배치 전부터 돌던 노드"를 빼면 쓰지 않은 가동 노드가 통째로 사라져 <b>없는 절감</b>이
     * 만들어지고, 그때 총전력이 이 경계 아래로 내려간다.</p>
     */
    @DisplayName("전원 제어가 있어도 돌던 노드는 꺼지지 않아 총전력에 바닥이 있다")
    @Test
    void already_running_nodes_form_a_floor_under_the_total_power() {
        Board board = service.compare("main", "heavytail").boards().get(0);
        Cell available = cell(board, 30, "AVAILABLE");
        Cell unavailable = cell(board, 30, "UNAVAILABLE");

        int idleNodes = board.nodeCount() - (int) Math.round(available.activeNodesBefore());
        assertTrue(idleNodes > 0, "유휴 노드가 없으면 두 판이 같아 이 시험이 아무것도 보지 않는다");
        assertEquals(available.activeNodesBefore(), unavailable.activeNodesBefore(), 1e-9,
                "기준선이 전원 가정에 따라 달라지면 증감의 뜻이 달라진다");

        // 끌 수 있는 것은 유휴 노드뿐이고, 그중 가장 후하게 잡아도 이만큼이다.
        double mostThatCanBeTurnedOff = board.idleAssumptionGaps().stream()
                .map(IdleGap::declaredIdleWatts).sorted(java.util.Comparator.reverseOrder())
                .limit(idleNodes).mapToDouble(Double::doubleValue).sum();

        for (StrategyResult on : available.strategies()) {
            double withControl = median(on, BenchmarkRules.GENERATOR_POWER);
            double without = median(strategy(unavailable, on.strategyId()), BenchmarkRules.GENERATOR_POWER);

            assertTrue(withControl >= without - mostThatCanBeTurnedOff, on.strategyId()
                    + ": 전원 제어가 있는 판의 총전력 " + withControl + "W 가 바닥 "
                    + (without - mostThatCanBeTurnedOff) + "W 아래다 — 유휴 노드 " + idleNodes
                    + "대를 다 꺼도 " + mostThatCanBeTurnedOff + "W 밖에 줄지 않으므로, "
                    + "쓰지 않은 가동 노드까지 꺼진 것으로 세고 있다");
        }
    }

    // ── 대조군 격리 ───────────────────────────────────────────

    /**
     * 대조군 식별자가 엔진 레지스트리에 하나도 없다. 있으면 엔진 식별자 검증이 열려 스케줄이 대조군으로
     * <b>실배치</b>하고, 그 결정은 지우지 않는 이력에 남는다.
     */
    @DisplayName("대조군 식별자는 엔진 레지스트리에 없다")
    @Test
    void baseline_ids_are_not_engine_ids() {
        EngineRegistry registry = new EngineRegistry(List.of(new WeightedScoreScheduler(),
                new PowerAwareScheduler(), new TemperatureAwareScheduler()));
        Board board = service.compare("main", "heavytail").boards().get(0);

        List<String> baselines = board.cells().get(0).strategies().stream()
                .filter(s -> "BASELINE".equals(s.kind())).map(StrategyResult::strategyId).toList();

        assertEquals(PlacementBenchmarkService.BASELINE_ORDER, baselines,
                "응답의 대조군 열이 순서 목록과 같아야 한다 — 개수가 아니라 id 와 순서로 본다: " + baselines);
        for (String id : baselines) {
            assertFalse(registry.contains(id),
                    id + " 가 엔진으로 등록됐다 — 스케줄이 이 전략으로 실배치할 수 있다");
        }
    }

    /**
     * 순서 목록과 구현 맵은 서로를 모른다. 목록에만 있는 id는 {@code null}이 담겨 한참 뒤 NPE로 죽고, 맵에만 있는
     * 구현은 열이 <b>조용히</b> 빠진 채 응답이 정상이다. 둘 다 전략 목록을 만드는 자리에서 크게 실패해야 한다 —
     * 대조군을 늘리는 PR이 바로 그 자리를 건드린다.
     */
    @DisplayName("대조군 순서 목록에 구현이 없는 id 가 있으면 조용히 빠지지 않고 즉시 실패한다")
    @Test
    void a_baseline_id_without_an_implementation_fails_loudly() {
        SchedulerProperties props = new SchedulerProperties();
        PlacementBenchmarkService broken = new PlacementBenchmarkService(
                new EngineRegistry(List.of(new WeightedScoreScheduler())), props, new BenchmarkClusters(),
                new PowerModel(props), new ThermalModel(props),
                List.of("ffd-size", "no-such-baseline"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> broken.compare("main", "heavytail"));
        assertTrue(e.getMessage().contains("no-such-baseline"), e.getMessage());
        assertTrue(e.getMessage().contains("ffd-size"), "구현 목록도 함께 밝혀야 어느 쪽이 틀렸는지 보인다: " + e.getMessage());
    }

    /**
     * 대조군 하나를 넣고 빼면 기존 열의 <b>어느 숫자가 움직여도 되는가</b>를 못 박는다. 전략 실행은 서로 독립이라
     * 성공률·건당 전력·엔진이 믿은 비용·개입 횟수·미계측 비율은 비트 단위로 같아야 한다. 움직여도 되는 것은 셀 집계에서
     * <b>전 전략을 함께 보는</b> 것들뿐이다 — 짝 지점(총전력·가동 증감·온도 분포 둘)과 전략 간 미계측 격차.
     * 이 목록 밖의 키가 하나라도 움직이면 대조군이 다른 전략의 실행에 새어 들어간 것이다.
     */
    @DisplayName("대조군을 하나 빼고 돌려도 기존 열은 셀 단위로 집계되는 키 밖에서는 한 칸도 움직이지 않는다")
    @Test
    void adding_a_baseline_moves_only_the_cell_level_keys_of_the_other_columns() {
        SchedulerProperties props = new SchedulerProperties();
        List<String> without = PlacementBenchmarkService.BASELINE_ORDER.stream()
                .filter(id -> !id.equals("coolest-first")).toList();
        PlacementBenchmarkService fewer = new PlacementBenchmarkService(
                new EngineRegistry(List.of(new WeightedScoreScheduler(), new PowerAwareScheduler(),
                        new TemperatureAwareScheduler())),
                props, new BenchmarkClusters(), new PowerModel(props), new ThermalModel(props), without);

        Set<String> mayMove = Set.of(BenchmarkRules.GENERATOR_POWER, BenchmarkRules.ACTIVE_NODE_DELTA,
                BenchmarkRules.WARN_ZONE_NODES, BenchmarkRules.TEMP_STDDEV, BenchmarkRules.UNMEASURED_SPREAD);

        int spreadMoved = 0;
        for (String boardId : List.of(BenchmarkClusters.MAIN, BenchmarkClusters.ADVERSARIAL, BenchmarkClusters.SPARSE)) {
          for (String distribution : List.of(PlacementBenchmarkService.HEAVYTAIL, PlacementBenchmarkService.UNIFORM)) {
            Board full = service.compare(boardId, distribution).boards().get(0);
            Board less = fewer.compare(boardId, distribution).boards().get(0);

            for (Cell a : full.cells()) {
                Cell b = cell(less, a.loadRatioPercent(), a.powerControl());
                assertTrue(a.strategies().stream().anyMatch(s -> s.strategyId().equals("coolest-first")), boardId);
                assertFalse(b.strategies().stream().anyMatch(s -> s.strategyId().equals("coolest-first")), boardId);

                for (StrategyResult sb : b.strategies()) {
                    StrategyResult sa = strategy(a, sb.strategyId());
                    for (MetricValue mb : sb.metrics()) {
                        MetricValue ma = metric(sa, mb.key());
                        String where = boardId + " " + a.loadRatioPercent() + "% " + a.powerControl() + " "
                                + sb.strategyId() + " " + mb.key();
                        if (mayMove.contains(mb.key())) {
                            if (!Objects.equals(ma, mb)) {
                                if (BenchmarkRules.UNMEASURED_SPREAD.equals(mb.key())) spreadMoved++;
                            }
                            continue;
                        }
                        // 레코드 전체 비교 — 중앙값만 보면 min·max·사유만 움직이는 누수가 그대로 통과한다
                        assertEquals(mb, ma, where + " " + distribution + ": 대조군을 넣었다고 이 칸이 움직였다 — 전략 실행이 독립이 아니다");
                    }
                }
            }
          }
        }
        // 주 결과판에서는 새 대조군이 어느 지점의 짝도 바꾸지 않을 수 있다(전 노드 계측·건수 동일). 그래서 판을 합쳐 본다 —
        // 계측 공백판의 미계측 격차는 전략 간 값이라 대조군 하나가 빠지면 반드시 움직인다. 그 키가 움직였는지를 따로 세어야
        // "무언가 움직였다"가 아니라 "움직여야 할 것이 움직였다"를 본다.
        assertTrue(spreadMoved > 0, "계측 공백판의 미계측 격차가 한 칸도 안 움직였다 — 대조군을 뺀 실행이 같은 실행이라는 뜻이라, 이 시험이 허용 목록을 시험하지 못했다");
    }

    /** 우리 엔진은 레지스트리가 밝히는 것을 전부 담는다 — 이름을 적어 두면 엔진이 늘 때 조용히 빠진다. */
    @DisplayName("등록된 엔진 전량이 비교에 들어간다")
    @Test
    void every_registered_engine_takes_part() {
        EngineRegistry registry = new EngineRegistry(List.of(new WeightedScoreScheduler(),
                new PowerAwareScheduler(), new TemperatureAwareScheduler()));
        Set<String> engineIds = registry.describe().stream()
                .map(m -> m.get("id")).collect(Collectors.toSet());

        Set<String> inTable = service.compare("main", "heavytail").boards().get(0)
                .cells().get(0).strategies().stream()
                .filter(s -> "ENGINE".equals(s.kind())).map(StrategyResult::strategyId)
                .collect(Collectors.toSet());

        assertTrue(inTable.containsAll(engineIds),
                "빠진 엔진이 있다: " + engineIds.stream().filter(id -> !inTable.contains(id)).toList());
    }

    // ── 순환 논증이 아님 ──────────────────────────────────────

    /**
     * 엔진이 믿은 비용과 생성식 기준 전력이 <b>다른 함수</b>다. 같은 값이면 주 지표가 엔진 목적함수의
     * 재탕이고, 그 지표에서 그 목적함수를 최대화한 전략이 정의상 이긴다.
     *
     * <p>한 셀만 보면 그 셀에서 우연히 갈린 것과 구분되지 않는다 — 등록된 판 전부 × 부하율 넷 × 전원 가정 둘을
     * 전부 훑는다. 분포는 heavytail 하나만 쓴다: 분포는 요구 워크로드를 바꿀 뿐 두 값을 만드는 함수를
     * 바꾸지 않으므로, 둘째 분포를 더해도 잠기는 성질은 같고 시간만 두 배가 된다.</p>
     */
    @DisplayName("등록된 판 전 셀에서 엔진이 믿은 비용과 생성식 기준 전력은 다른 함수다")
    @Test
    void engine_belief_and_ground_truth_are_different_functions() {
        BenchmarkClusters clusters = new BenchmarkClusters();
        int checkedCells = 0;

        for (String boardId : clusters.boardIds()) {
            Board board = service.compare(boardId, "heavytail").boards().get(0);
            assertEquals(BenchmarkRules.LOAD_RATIOS.size() * 2, board.cells().size(),
                    boardId + ": 셀이 부하율 네 지점 × 전원 가정 둘이 아니다 — 잠글 자리가 빠졌다");

            for (Cell target : board.cells()) {
                String at = boardId + "/" + target.loadRatioPercent() + "%/" + target.powerControl() + "/";
                boolean checked = false;

                for (StrategyResult s : target.strategies()) {
                    Double believed = median(s, BenchmarkRules.ENGINE_BELIEVED_COST);
                    Double truth = median(s, BenchmarkRules.GENERATOR_POWER);
                    if (believed == null || truth == null) continue;   // 배치 0건 셀 — 잴 값이 없다
                    checked = true;
                    assertNotEquals(believed, truth,
                            at + s.strategyId() + ": 두 값이 같다 — 평가 기준이 엔진의 목적 함수다");
                }
                if (checked) checkedCells++;
            }
        }
        assertTrue(checkedCells > 0, "값이 있는 셀이 한 칸도 없었다 — 루프가 조용히 비면 이 시험은 아무것도 잠그지 않는다");
    }

    /**
     * 엔진이 믿은 비용에 <b>깨우는 비용이 들어 있다</b>. 이 값은 예약을 잡기 <b>전</b> 상태에서 재야
     * 한다 — 엔진의 가동 판정이 예약분을 반영한 사용률을 보기 때문에, 예약을 먼저 잡으면 그 노드가
     * 이미 가동 중으로 보여 유휴 전력 항이 통째로 사라진다.
     *
     * <p>경계를 응답에서 유도한다: 노드를 더 깨운 전략은 그 차이만큼의 유휴 전력을 더 물어야 하고,
     * 가장 인색하게 잡아도 <b>깨운 노드 수 × 최소 정격 × 엔진 유휴 비율</b>이다. 측정 순서가 뒤로
     * 밀리면 이 차이가 사라져 경계 아래로 내려간다.</p>
     */
    @DisplayName("엔진이 믿은 비용에 노드를 깨우는 값이 들어 있다")
    @Test
    void the_engine_belief_includes_the_cost_of_waking_a_node() {
        Board board = service.compare("main", "heavytail").boards().get(0);
        Cell target = cell(board, 30, "UNAVAILABLE");
        assertTrue(target.totalsComparable(), "가동 노드 증감이 무효인 셀에서는 이 시험이 성립하지 않는다");

        double minCapacity = board.idleAssumptionGaps().stream()
                .mapToDouble(IdleGap::engineBelievedCapacityWatts).min().orElseThrow();   // 엔진은 믿는 정격으로 가정한다
        double engineIdleRatio = properties.getPower().getIdleRatio();

        StrategyResult spreads = strategy(target, "temperature");
        StrategyResult packs = strategy(target, "ffd-size");

        double extraWoken = median(spreads, BenchmarkRules.ACTIVE_NODE_DELTA)
                - median(packs, BenchmarkRules.ACTIVE_NODE_DELTA);
        assertTrue(extraWoken > 0,
                "퍼뜨리는 전략이 뭉치는 전략보다 노드를 더 깨우지 않았다 — 이 판이 두 전략을 구분하지 못한다");

        double leastExtraCost = extraWoken * minCapacity * engineIdleRatio;
        double actualGap = median(spreads, BenchmarkRules.ENGINE_BELIEVED_COST)
                - median(packs, BenchmarkRules.ENGINE_BELIEVED_COST);

        assertTrue(actualGap >= leastExtraCost, "노드를 " + extraWoken
                + "대 더 깨운 전략의 믿은 비용이 " + actualGap + "W 만 크다 — 가장 인색하게 잡아도 "
                + leastExtraCost + "W 여야 한다. 깨우는 비용을 예약 뒤에 재고 있을 수 있다");
    }

    /**
     * 엔진의 유휴 전력 가정과 파일 선언값이 <b>실제로 다른</b> 노드가 있다. 전 노드가 같으면 생성식 기준값이
     * 엔진 모델과 같은 값이 되어 위 테스트가 우연히 통과할 수 있다.
     */
    @DisplayName("엔진 가정과 파일에 선언된 유휴 전력이 다른 노드가 있다")
    @Test
    void some_node_breaks_the_engine_idle_assumption() {
        Board board = service.compare("main", "heavytail").boards().get(0);

        assertEquals(board.nodeCount(), board.idleAssumptionGaps().size(), "노드 전량의 격차를 밝혀야 한다");
        double biggest = board.idleAssumptionGaps().stream()
                .mapToDouble(IdleGap::gapWatts).max().orElse(0);
        assertTrue(biggest > 10,
                "가장 큰 격차가 " + biggest + "W 뿐이다 — 생성식 기준값이 엔진 모델과 사실상 같은 값이 된다");

        double ratioSpread = board.idleAssumptionGaps().stream()
                .mapToDouble(IdleGap::declaredIdleRatio).max().orElse(0)
                - board.idleAssumptionGaps().stream()
                .mapToDouble(IdleGap::declaredIdleRatio).min().orElse(0);
        assertTrue(ratioSpread >= 0.15,
                "유휴 비율의 폭이 " + ratioSpread + " 뿐이다 — 엔진 가정이 과대·과소 양쪽으로 틀리는 판이 아니다");
    }

    // ── 응답이 스스로 밝히는 것 ────────────────────────────────

    @DisplayName("사전 등록된 지표 전량이 셀마다 실린다")
    @Test
    void every_declared_metric_appears_in_every_cell() {
        Set<String> declared = BenchmarkRules.METRICS.stream()
                .map(BenchmarkMetric::key).collect(Collectors.toSet());
        PlacementBenchmarkResponse response = service.compare("main", "heavytail");

        assertEquals(BenchmarkRules.METRICS, response.metrics(), "지표 선언을 응답이 그대로 실어야 한다");
        for (Cell c : response.boards().get(0).cells()) {
            for (StrategyResult s : c.strategies()) {
                assertEquals(declared, s.metrics().stream().map(MetricValue::key).collect(Collectors.toSet()),
                        c.loadRatioPercent() + "% " + c.powerControl() + " " + s.strategyId());
            }
        }
    }

    @DisplayName("응답에 승자를 지명하는 필드가 없다")
    @Test
    void no_field_names_a_winner() {
        for (java.lang.reflect.RecordComponent rc : PlacementBenchmarkResponse.class.getRecordComponents()) {
            assertFalse(rc.getName().toLowerCase().contains("winner"), rc.getName());
            assertFalse(rc.getName().toLowerCase().contains("best"), rc.getName());
        }
        for (java.lang.reflect.RecordComponent rc : StrategyResult.class.getRecordComponents()) {
            assertFalse(rc.getName().toLowerCase().contains("rank"), rc.getName());
            assertFalse(rc.getName().toLowerCase().contains("winner"), rc.getName());
        }
    }

    /** NaN·무한대는 0으로 새어 나가면 "가장 좋은 값"으로 읽힌다. 값이 없으면 null이어야 한다. */
    @DisplayName("응답의 숫자에 NaN 이나 무한대가 없다")
    @Test
    void no_nan_or_infinity_leaks_into_the_response() {
        for (Map.Entry<String, Double> entry : flatten(service.compare("main", "heavytail")).entrySet()) {
            assertTrue(Double.isFinite(entry.getValue()),
                    entry.getKey() + " 가 " + entry.getValue() + " 다 — 0 으로 새면 최고 점수로 읽힌다");
        }
    }

    /** 전제 블록이 비어 있으면 이 응답의 숫자는 인용될 근거가 없다. */
    @DisplayName("전제 블록이 부하율 정의와 정책 출처와 전원 가정을 밝힌다")
    @Test
    void assumptions_state_the_denominator_and_the_policy_and_the_power_note() {
        var a = service.compare("main", "heavytail").assumptions();

        assertEquals(BenchmarkRules.LOAD_RATIOS, a.loadRatioPercents());
        assertEquals(BenchmarkRules.HEADROOM_DEFINITION, a.headroomDefinition());
        assertEquals(BenchmarkRules.COOLDOWN_MINUTES_FOR_BENCHMARK, a.cooldownMinutes());
        assertEquals(BenchmarkRules.MEMORY_MB_PER_CORE, a.memoryMbPerCore());
        assertEquals(BenchmarkRules.SWEEP_POINTS, a.sweepPoints());
        assertEquals(60, a.periodMinutes(), "파동 주기를 밝히지 않으면 스윕 창의 뜻이 없다");
        assertEquals(properties.getPower().getIdleRatio(), a.engineIdleRatio(), 1e-9);
        assertTrue(a.policyBasis().contains("활성 정책"), a.policyBasis());
        assertTrue(a.powerControlNote().contains("UNAVAILABLE"), a.powerControlNote());
        assertFalse(a.workloadClasses().isEmpty(), "구성비를 밝히지 않으면 표본이 무엇인지 알 수 없다");
        assertEquals(BenchmarkRules.RANDOM_SEEDS.size(), a.randomSeedCount());
    }

    @DisplayName("판이 자기 입력 파일을 지목한다")
    @Test
    void the_board_points_at_its_own_input_file() {
        Board board = service.compare("main", "heavytail").boards().get(0);

        assertEquals("main", board.boardId());
        assertEquals(12, board.digest().length(), "다이제스트가 없으면 어느 파일로 낸 숫자인지 알 수 없다");
        assertFalse(board.role().isBlank(), "판을 만든 의도를 밝혀야 한다");
        assertEquals(14, board.nodeCount());
        assertEquals(14, board.measuredNodeCount(), "주 결과판은 전수 계측이다");
    }

    // ── 부하율 축 ─────────────────────────────────────────────

    @DisplayName("부하율이 오르면 요구량이 늘고 분모는 그대로다")
    @Test
    void a_higher_load_ratio_asks_for_more_against_the_same_denominator() {
        Board board = service.compare("main", "heavytail").boards().get(0);
        List<Cell> ascending = board.cells().stream()
                .filter(c -> "UNAVAILABLE".equals(c.powerControl()))
                .sorted(java.util.Comparator.comparingInt(Cell::loadRatioPercent)).toList();

        assertEquals(BenchmarkRules.LOAD_RATIOS.size(), ascending.size());
        for (int i = 1; i < ascending.size(); i++) {
            assertTrue(ascending.get(i).requestedCores() > ascending.get(i - 1).requestedCores(),
                    ascending.get(i).loadRatioPercent() + "% 의 요구 코어가 앞 지점보다 크지 않다");
            assertEquals(ascending.get(0).headroomCores(), ascending.get(i).headroomCores(),
                    "분모가 부하율에 따라 달라지면 부하율이 무엇의 비율인지 알 수 없다");
        }
    }

    /**
     * 분모는 총 코어가 아니라 <b>정책 상한까지의 여유</b>다. 총 코어를 쓰면 90% 지점이 실제로는
     * 수용 불가가 되어 성공률이 전 전략에서 함께 무너지고, 부하율 축이 전략을 구분하지 못한다.
     */
    @DisplayName("분모가 총 코어보다 작다")
    @Test
    void the_denominator_is_smaller_than_the_total_cores() {
        BenchmarkClusters clusters = new BenchmarkClusters();
        int totalCores = clusters.get("main").nodes().stream()
                .mapToInt(com.innogrid.aims.scheduler.metrics.SyntheticCluster.Node::cores).sum();

        double headroom = cell(service.compare("main", "heavytail").boards().get(0), 30, "UNAVAILABLE")
                .headroomCores();

        assertTrue(headroom > 0, "여유가 0 이면 아무것도 배치할 수 없다");
        assertTrue(headroom < totalCores,
                "여유 " + headroom + " 가 총 코어 " + totalCores + " 보다 작지 않다 — 정책 상한을 반영하지 않았다");
    }

    /** 저부하에서는 전 전략이 다 배치해야 한다 — 여기서 실패가 나오면 판이나 분모가 잘못됐다. */
    @DisplayName("저부하에서는 모든 전략이 전량 배치한다")
    @Test
    void every_strategy_places_everything_at_low_load() {
        Cell target = cell(service.compare("main", "heavytail").boards().get(0), 30, "UNAVAILABLE");

        int checked = 0;
        for (StrategyResult s : target.strategies()) {
            MetricValue rate = metric(s, BenchmarkRules.PLACEMENT_RATE);
            // 제약을 끈 변종은 성공률이 정의상 최고라 비교 대상이 아니다(값이 실리지 않는다).
            // 그 사실 자체는 아래에서 따로 확인하고, 여기서는 겨루는 전략만 본다.
            if (!rate.valid()) {
                assertEquals("resource-only-ungated", s.strategyId(),
                        s.strategyId() + " 의 성공률이 무효다 — 제약 해제 변종 말고는 이 셀에서 무효일 이유가 없다");
                assertNull(rate.median(), s.strategyId());
                continue;
            }
            checked++;
            assertEquals(100.0, rate.median(), 1e-9,
                    s.strategyId() + " 가 저부하에서 전량 배치하지 못했다 — 판이나 분모를 다시 봐야 한다");
        }
        assertEquals(target.strategies().size() - 1, checked, "겨루는 전략 전량을 봐야 한다");
    }

    // ── 스케줄 배치 순서 열 ─────────────────────────────────────

    /**
     * 두 열은 스케줄 실행이 고르는 비교자 객체를 그대로 쓴다 — 복사본이면 운영 순서가 바뀌어도 열이 따라가지 않는다. 스케줄 배치 순서 값이 늘면
     * 대응하는 열이 없어 실패하므로 열을 함께 더하게 된다. 생성 시각이 없는 표본에서는 마지막 기준이 동률이라 표본 순서가 등록 순 역할을 한다.
     */
    @DisplayName("스케줄 배치 순서 두 열은 스케줄 실행의 비교자 객체로 기본 엔진을 돌리고 기준 집합 밖이다")
    @Test
    void schedule_order_columns_run_the_default_engine_with_the_execution_comparators() {
        BenchmarkColumns columns = harnessColumns();
        Map<String, BenchmarkColumns.EngineVariant> variants = new LinkedHashMap<>();
        for (BenchmarkColumns.EngineVariant v : columns.engineVariants()) variants.put(v.strategy().id(), v);
        Map<Schedule.PlacementOrder, String> columnFor = Map.of(
                Schedule.PlacementOrder.PRIORITY_THEN_AGE, BenchmarkRules.SCHEDULE_ORDER_AGE_COLUMN,
                Schedule.PlacementOrder.PRIORITY_THEN_SIZE, BenchmarkRules.SCHEDULE_ORDER_SIZE_COLUMN);
        assertEquals(Schedule.PlacementOrder.values().length, BenchmarkRules.SCHEDULE_ORDER_COLUMNS.size(), "스케줄 배치 순서 값마다 열이 하나다");

        for (Schedule.PlacementOrder order : Schedule.PlacementOrder.values()) {
            String id = columnFor.get(order);
            assertNotNull(id, order + " — 이 배치 순서의 열이 없다");
            Schedule schedule = new Schedule("bm-order", "bm-order", "0 0 * * * ?");
            schedule.setPlacementOrder(order);
            BenchmarkColumns.EngineVariant v = variants.get(id);
            assertNotNull(v, id + " 열이 없다");
            assertSame(ScheduleExecutionService.orderFor(schedule), v.strategy().workloadOrder(), id + " — 운영 비교자 객체 그대로여야 한다");
            assertEquals(PlacementStrategy.Kind.ENGINE, v.strategy().kind(), id);
            assertEquals(PlacementStrategy.PolicyVariant.BASE, v.strategy().policy(), id);
            assertTrue(v.override().isEmpty(), id + " — 순서는 엔진 노브가 아니다");
            assertFalse(columns.overrides().containsKey(id), id + " — 설정 다이제스트에 들어가면 안 된다");
            assertEquals(List.of("weighted"), BenchmarkCellAssembler.baseSetIds(List.of("weighted", id), columns.overrides().keySet()),
                    id + " — 총량 짝 조건의 기준 집합 밖이어야 한다");
        }

        List<Workload> sample = List.of(orderWorkload("s1", 1, 0, Workload.WorkloadPriority.NORMAL),
                orderWorkload("g4", 4, 1, Workload.WorkloadPriority.NORMAL), orderWorkload("b4", 4, 0, Workload.WorkloadPriority.HIGH),
                orderWorkload("m2", 2, 0, Workload.WorkloadPriority.NORMAL), orderWorkload("s2", 1, 0, Workload.WorkloadPriority.NORMAL));
        assertEquals(List.of("b4", "s1", "g4", "m2", "s2"), sample.stream()
                        .sorted(variants.get(BenchmarkRules.SCHEDULE_ORDER_AGE_COLUMN).strategy().workloadOrder()).map(Workload::getName).toList(),
                "등록 순 열 — 우선순위 뒤에는 표본 순서(생성 시각 동률)");
        assertEquals(List.of("b4", "g4", "m2", "s1", "s2"), sample.stream()
                        .sorted(variants.get(BenchmarkRules.SCHEDULE_ORDER_SIZE_COLUMN).strategy().workloadOrder()).map(Workload::getName).toList(),
                "크기 순 열 — 우선순위 뒤에는 요구 CPU · 메모리 큰 순, 같으면 표본 순서");
    }

    /**
     * 열을 더하는 것만으로 기존 열의 값이 바뀌면 안 된다(BenchmarkRules.PAIRING_RULE). 같은 12지점에서 두 순서 열을 뺀 결과와, 두 순서 열의 배치
     * 건수를 일부러 요구 건수보다 크게 바꾼 결과를 견준다 — 건수를 강제로 어긋나게 하므로 관측 결과와 무관하게 두 열이 기준 집합에 섞이면 잡힌다.
     */
    @DisplayName("스케줄 배치 순서 열을 빼거나 그 열의 건수를 어긋나게 해도 기존 열의 결과는 그대로다")
    @Test
    void schedule_order_columns_do_not_change_the_existing_columns() {
        BenchmarkColumns columns = harnessColumns();
        BenchmarkCellAssembler assembler = new BenchmarkCellAssembler(columns);
        int checked = 0;
        for (Map.Entry<String, Integer> target : List.of(Map.entry(BenchmarkClusters.ADVERSARIAL, 90), Map.entry(BenchmarkClusters.MAIN, 30))) {
            List<BenchmarkPointRunner.PointOutcome> points = orderSweep(columns, target.getKey(), "heavytail", target.getValue());
            GroundTruthOracle oracle = new GroundTruthOracle(new BenchmarkClusters().get(target.getKey()));
            for (GroundTruthOracle.PowerControl mode : GroundTruthOracle.PowerControl.values()) {
                Cell without = assembler.cell(target.getValue(), mode, withoutOrderColumns(points), oracle);
                Cell shifted = assembler.cell(target.getValue(), mode, withShiftedOrderColumns(points), oracle);
                String where = target.getKey() + " heavytail " + target.getValue() + "% " + mode;
                assertEquals(without.strategies().size() + 2, shifted.strategies().size(), where);
                assertEquals(without.totalsComparable(), shifted.totalsComparable(), where);
                assertEquals(without.unmeasuredCaution(), shifted.unmeasuredCaution(), where);
                assertEquals(without.probeExclusionReasons(), shifted.probeExclusionReasons(), where);
                for (StrategyResult s : without.strategies()) {
                    assertEquals(s, strategy(shifted, s.strategyId()), where + " " + s.strategyId() + " — 기존 열의 결과가 바뀌었다");
                }
                checked++;
            }
        }
        assertEquals(4, checked, "두 셀 · 두 전원 상태를 모두 봐야 한다");
    }

    /**
     * 순서 열이 기준 집합과 건수가 다른 지점은 override 열과 따로 문장으로 적는다 — override 열 문장은 바이트 그대로이고, 순서 열 문장이 뒤에 붙는다.
     * 반증판 heavytail 90% 셀은 override 열 제외가 이미 있어 두 문장이 함께 나오는 경우를 본다.
     */
    @DisplayName("스케줄 배치 순서 열의 제외 지점은 override 열 문장 뒤에 따로 적히고 그 열의 총량은 사유를 단 무효다")
    @Test
    void schedule_order_column_drops_get_their_own_sentence() {
        BenchmarkColumns columns = harnessColumns();
        BenchmarkCellAssembler assembler = new BenchmarkCellAssembler(columns);
        boolean sawOverrideSentence = false;
        for (Map.Entry<String, Integer> target : List.of(Map.entry(BenchmarkClusters.ADVERSARIAL, 90), Map.entry(BenchmarkClusters.MAIN, 30))) {
            List<BenchmarkPointRunner.PointOutcome> points = orderSweep(columns, target.getKey(), "heavytail", target.getValue());
            GroundTruthOracle oracle = new GroundTruthOracle(new BenchmarkClusters().get(target.getKey()));
            Cell without = assembler.cell(target.getValue(), GroundTruthOracle.PowerControl.AVAILABLE, withoutOrderColumns(points), oracle);
            Cell shifted = assembler.cell(target.getValue(), GroundTruthOracle.PowerControl.AVAILABLE, withShiftedOrderColumns(points), oracle);
            String where = target.getKey() + " heavytail " + target.getValue() + "%";
            int usable = metric(strategy(without, "weighted"), BenchmarkRules.GENERATOR_POWER).samples();
            assertTrue(usable > 0, where + " — 짝 지점이 있어야 제외 문장을 볼 수 있다");
            String note = "스케줄 배치 순서 열 중 기준 집합과 배치 건수가 다른 지점을 뺀 열: "
                    + BenchmarkRules.SCHEDULE_ORDER_AGE_COLUMN + " " + usable + "곳 제외(표본 0), "
                    + BenchmarkRules.SCHEDULE_ORDER_SIZE_COLUMN + " " + usable + "곳 제외(표본 0) — 그 열의 총량 표본(samples)은 그만큼 적다";
            String before = without.totalsIncomparableReason();
            assertEquals(before == null ? note : before + ". " + note, shifted.totalsIncomparableReason(), where);
            if (before != null && before.contains("override 열 중 ")) sawOverrideSentence = true;
            for (String id : BenchmarkRules.SCHEDULE_ORDER_COLUMNS) {
                MetricValue m = metric(strategy(shifted, id), BenchmarkRules.GENERATOR_POWER);
                assertEquals(0, m.samples(), where + " " + id);
                assertEquals("기준 집합과 배치 건수가 다른 지점뿐이라 총량을 낼 수 없다(제외 " + usable + "곳)", m.invalidReason(), where + " " + id);
            }
        }
        assertTrue(sawOverrideSentence, "override 열 제외 문장이 있는 셀을 봐야 두 문장이 갈리는지 알 수 있다");
    }

    /**
     * 온도 상한 열의 정책은 기준 정책 사본에서 온도 상한 하나만 다르다 — 다른 필드가 함께 바뀌면 열 차이가 운영 스케줄 온도 상한의 효과가 아니게 된다.
     * 선언 필드 전량을 반사로 견주고(SchedulingPolicyCopyTest와 같은 방식), 다른 열의 정책은 기본 임계 그대로인지 함께 본다.
     */
    @DisplayName("온도 상한 열은 기준 정책 사본에서 온도 상한만 기본 경고 온도로 낮추고 다른 열의 정책은 그대로 둔다")
    @Test
    void temperature_limit_column_lowers_only_the_policy_limit() throws IllegalAccessException {
        BenchmarkColumns columns = harnessColumns();
        BenchmarkColumns.EngineVariant lowered = columns.engineVariants().stream()
                .filter(v -> v.strategy().id().equals(BenchmarkRules.TEMPERATURE_LIMIT_COLUMN)).findFirst().orElseThrow();
        SchedulingPolicy policy = BenchmarkColumns.policyFor(lowered.strategy(), lowered.override());
        SchedulingPolicy base = BenchmarkColumns.policyFor(lowered.strategy(), Map.of());
        List<String> differing = new ArrayList<>();
        for (java.lang.reflect.Field f : SchedulingPolicy.class.getDeclaredFields()) {
            if (f.isSynthetic() || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            if (!Objects.equals(f.get(policy), f.get(base))) differing.add(f.getName());
        }
        assertEquals(List.of("maxTemperatureCelsius"), differing, "온도 상한 말고 바뀐 필드가 있다");
        assertEquals(BenchmarkRules.LOWERED_TEMPERATURE_LIMIT_CELSIUS, policy.getMaxTemperatureCelsius());
        assertNull(policy.getWarningTemperatureCelsius(), "경고 온도는 비워 기본 경고 온도를 쓴다");
        for (BenchmarkColumns.EngineVariant other : columns.engineVariants()) {
            if (other.strategy().id().equals(BenchmarkRules.TEMPERATURE_LIMIT_COLUMN)) continue;
            assertEquals(base.getMaxTemperatureCelsius(), BenchmarkColumns.policyFor(other.strategy(), other.override()).getMaxTemperatureCelsius(),
                    other.strategy().id() + " — 다른 열은 기본 임계 그대로");
        }
    }

    /**
     * 주 결과판에서 기본 임계 75℃의 weighted 열은 온도 제약 개입이 0이고, 상한을 65℃로 낮춘 열에서는 개입이 생긴다 — 이 열이 그 제약이
     * 작동하는 자리를 보이는지의 확인이다. 개입 횟수는 현재 온도 초과 제외와 배치 후 예상 온도 초과 제외의 합이다(규칙 문장).
     */
    @DisplayName("주 결과판에서 온도 상한 열만 온도 제약 개입이 생기고 weighted 열은 0 그대로다")
    @Test
    void temperature_limit_column_engages_thermal_constraint_on_main_board() {
        int checked = 0;
        for (String distribution : List.of("heavytail", "uniform")) {
            for (Cell c : service.compare(BenchmarkClusters.MAIN, distribution).boards().get(0).cells()) {
                String where = distribution + " " + c.loadRatioPercent() + "% " + c.powerControl();
                StrategyResult lowered = strategy(c, BenchmarkRules.TEMPERATURE_LIMIT_COLUMN);
                assertEquals(Map.of("maxTemperatureCelsius", "65.0"), lowered.engineConfigOverride(), where);
                assertEquals(0.0, median(strategy(c, "weighted"), BenchmarkRules.C3_INTERVENTIONS), where + " — 기본 임계 75℃ 에서는 개입이 없다");
                assertTrue(median(lowered, BenchmarkRules.C3_INTERVENTIONS) > 0, where + " — 상한 65℃ 에서는 개입이 생긴다");
                checked++;
            }
        }
        assertEquals(BenchmarkRules.LOAD_RATIOS.size() * 2 * 2, checked, "두 분포 · 네 부하율 · 두 전원 상태를 모두 봐야 한다");
    }

    /**
     * 온도 상한 열을 더하는 것만으로 기존 열의 값이 바뀌면 안 된다(BenchmarkRules.PAIRING_RULE). 같은 12지점에서 이 열을 뺀 결과와, 이 열의 배치 건수를
     * 일부러 요구 건수보다 크게 바꾼 결과를 견준다 — 건수를 강제로 어긋나게 하므로 관측 결과와 무관하게 이 열이 기준 집합에 섞이면 잡힌다.
     */
    @DisplayName("온도 상한 열을 빼거나 그 열의 건수를 어긋나게 해도 기존 열의 결과는 그대로다")
    @Test
    void temperature_limit_column_does_not_change_the_existing_columns() {
        BenchmarkColumns columns = harnessColumns();
        BenchmarkCellAssembler assembler = new BenchmarkCellAssembler(columns);
        int checked = 0;
        for (Map.Entry<String, Integer> target : List.of(Map.entry(BenchmarkClusters.ADVERSARIAL, 90), Map.entry(BenchmarkClusters.MAIN, 30))) {
            List<BenchmarkPointRunner.PointOutcome> points = orderSweep(columns, target.getKey(), "heavytail", target.getValue());
            GroundTruthOracle oracle = new GroundTruthOracle(new BenchmarkClusters().get(target.getKey()));
            for (GroundTruthOracle.PowerControl mode : GroundTruthOracle.PowerControl.values()) {
                Cell without = assembler.cell(target.getValue(), mode, withoutTemperatureLimitColumn(points), oracle);
                Cell shifted = assembler.cell(target.getValue(), mode, withShiftedTemperatureLimitColumn(points), oracle);
                String where = target.getKey() + " heavytail " + target.getValue() + "% " + mode;
                assertEquals(without.strategies().size() + 1, shifted.strategies().size(), where);
                assertEquals(without.totalsComparable(), shifted.totalsComparable(), where);
                assertEquals(without.unmeasuredCaution(), shifted.unmeasuredCaution(), where);
                assertEquals(without.probeExclusionReasons(), shifted.probeExclusionReasons(), where);
                for (StrategyResult s : without.strategies()) {
                    assertEquals(s, strategy(shifted, s.strategyId()), where + " " + s.strategyId() + " — 기존 열의 결과가 바뀌었다");
                }
                checked++;
            }
        }
        assertEquals(4, checked, "두 셀 · 두 전원 상태를 모두 봐야 한다");
    }

    /**
     * 온도 상한 열이 기준 집합과 건수가 다른 지점은 셋째 문장으로 끝에 붙는다 — 앞의 override 열 · 스케줄 배치 순서 열 문장은 바이트 그대로다.
     * 반증판 heavytail 90% 셀은 override 열 제외가 이미 있어, 세 문장이 함께 나올 때의 순서도 본다.
     */
    @DisplayName("온도 상한 열의 제외 지점은 셋째 문장으로 끝에 붙고 그 열의 총량은 사유를 단 무효다")
    @Test
    void temperature_limit_column_drops_get_their_own_sentence() {
        BenchmarkColumns columns = harnessColumns();
        BenchmarkCellAssembler assembler = new BenchmarkCellAssembler(columns);
        boolean sawThreeSentences = false;
        for (Map.Entry<String, Integer> target : List.of(Map.entry(BenchmarkClusters.ADVERSARIAL, 90), Map.entry(BenchmarkClusters.MAIN, 30))) {
            List<BenchmarkPointRunner.PointOutcome> points = orderSweep(columns, target.getKey(), "heavytail", target.getValue());
            GroundTruthOracle oracle = new GroundTruthOracle(new BenchmarkClusters().get(target.getKey()));
            Cell without = assembler.cell(target.getValue(), GroundTruthOracle.PowerControl.AVAILABLE, withoutTemperatureLimitColumn(points), oracle);
            Cell shifted = assembler.cell(target.getValue(), GroundTruthOracle.PowerControl.AVAILABLE, withShiftedTemperatureLimitColumn(points), oracle);
            String where = target.getKey() + " heavytail " + target.getValue() + "%";
            int usable = metric(strategy(without, "weighted"), BenchmarkRules.GENERATOR_POWER).samples();
            assertTrue(usable > 0, where + " — 짝 지점이 있어야 제외 문장을 볼 수 있다");
            String note = "온도 상한을 낮춘 열 중 기준 집합과 배치 건수가 다른 지점을 뺀 열: "
                    + BenchmarkRules.TEMPERATURE_LIMIT_COLUMN + " " + usable + "곳 제외(표본 0) — 그 열의 총량 표본(samples)은 그만큼 적다";
            String before = without.totalsIncomparableReason();
            assertEquals(before == null ? note : before + ". " + note, shifted.totalsIncomparableReason(), where);
            MetricValue m = metric(strategy(shifted, BenchmarkRules.TEMPERATURE_LIMIT_COLUMN), BenchmarkRules.GENERATOR_POWER);
            assertEquals(0, m.samples(), where);
            assertEquals("기준 집합과 배치 건수가 다른 지점뿐이라 총량을 낼 수 없다(제외 " + usable + "곳)", m.invalidReason(), where);

            String all = assembler.cell(target.getValue(), GroundTruthOracle.PowerControl.AVAILABLE,
                    withShiftedTemperatureLimitColumn(withShiftedOrderColumns(points)), oracle).totalsIncomparableReason();
            int override = all.indexOf("override 열 중 ");
            int order = all.indexOf("스케줄 배치 순서 열 중 ");
            int temperature = all.indexOf("온도 상한을 낮춘 열 중 ");
            assertTrue(order >= 0 && temperature > order && (override < 0 || override < order), where + " — 문장 순서: " + all);
            if (override >= 0) sawThreeSentences = true;
        }
        assertTrue(sawThreeSentences, "override 열 제외 문장이 있는 셀을 봐야 세 문장의 순서를 알 수 있다");
    }

    /**
     * 비교는 전략마다 새 제약 평가기를 쓰므로, 상한을 낮춰 온도 제약이 걸려도 서비스에 준 운영 온도 모델에는 냉각 표시가 남지 않아야 한다 —
     * 남으면 진단 호출이 운영 배치의 냉각 대기를 만든다.
     */
    @DisplayName("온도 상한 열을 돌려도 서비스에 준 운영 온도 모델에 냉각 표시가 남지 않는다")
    @Test
    void temperature_limit_column_leaves_no_cooldown_marks_on_the_operating_thermal_model() {
        SchedulerProperties props = new SchedulerProperties();
        ThermalModel operating = new ThermalModel(props);
        PlacementBenchmarkService fresh = new PlacementBenchmarkService(
                new EngineRegistry(List.of(new WeightedScoreScheduler(), new PowerAwareScheduler(), new TemperatureAwareScheduler())),
                props, new BenchmarkClusters(), new PowerModel(props), operating);
        Cell cell = pick(fresh.compareFresh(BenchmarkClusters.MAIN, "heavytail").boards().get(0).cells().stream()
                .filter(c -> c.loadRatioPercent() == 110).toList(), "AVAILABLE");
        assertTrue(median(strategy(cell, BenchmarkRules.TEMPERATURE_LIMIT_COLUMN), BenchmarkRules.C3_INTERVENTIONS) > 0,
                "온도 제약이 걸린 실행이어야 냉각 표시가 남는지 볼 수 있다");
        assertTrue(operating.cooldownMarks().isEmpty(), "운영 온도 모델에 냉각 표시가 남았다: " + operating.cooldownMarks());
    }

    /**
     * 주 결과판의 두 순서 열 값 — 첫 실행에서 관측한 값을 리터럴로 고정한다(회귀 고정 · 결과보다 먼저 정한 값이 아니다). 짝 셀은 조각난 여유 비율,
     * 110% 셀은 건수 · 코어 두 성공률이다. 두 전원 상태 셀은 배치가 같아 같은 값과 견준다. 허용 오차는 판정 규칙의 지점 비교 무시 폭과 같은 근거다.
     */
    @DisplayName("주 결과판에서 두 스케줄 배치 순서 열의 조각난 여유 비율과 110% 셀의 두 성공률이 관측 리터럴과 같다")
    @Test
    void schedule_order_columns_on_the_main_board_match_the_observed_literals() {
        Map<String, double[]> fragmentation = new LinkedHashMap<>();   // 분포:부하율 → {등록 순, 크기 순}
        fragmentation.put("heavytail:30", new double[]{23.0101, 23.0101});
        fragmentation.put("heavytail:60", new double[]{39.3046, 41.9738});
        fragmentation.put("heavytail:90", new double[]{75.7731, 74.2111});
        fragmentation.put("uniform:30", new double[]{23.3503, 20.3357});
        fragmentation.put("uniform:60", new double[]{37.8196, 41.1259});
        Map<String, double[]> rates = new LinkedHashMap<>();           // 분포 → {등록 순 건수, 등록 순 코어, 크기 순 건수, 크기 순 코어}
        rates.put("heavytail", new double[]{95.7405, 95.3757, 88.4615, 93.0636});
        rates.put("uniform", new double[]{86.6253, 80.5874, 86.6253, 80.5874});
        List<String> mismatches = new ArrayList<>();
        int checked = 0;
        for (String distribution : List.of("heavytail", "uniform")) {
            for (Cell c : service.compare(BenchmarkClusters.MAIN, distribution).boards().get(0).cells()) {
                StrategyResult age = strategy(c, BenchmarkRules.SCHEDULE_ORDER_AGE_COLUMN);
                StrategyResult size = strategy(c, BenchmarkRules.SCHEDULE_ORDER_SIZE_COLUMN);
                String where = distribution + ":" + c.loadRatioPercent();
                double[] want = fragmentation.get(where);
                Double[] got = null;
                if (want != null) {
                    got = new Double[]{median(age, BenchmarkRules.FRAGMENTED_HEADROOM), median(size, BenchmarkRules.FRAGMENTED_HEADROOM)};
                } else if (c.loadRatioPercent() == 110) {
                    want = rates.get(distribution);
                    got = new Double[]{median(age, BenchmarkRules.PLACEMENT_RATE), median(age, BenchmarkRules.CORE_PLACEMENT_RATE),
                            median(size, BenchmarkRules.PLACEMENT_RATE), median(size, BenchmarkRules.CORE_PLACEMENT_RATE)};
                }
                if (want == null) continue;
                for (int i = 0; i < want.length; i++) {
                    if (got[i] == null || Math.abs(want[i] - got[i]) > BenchmarkRules.JUDGMENT_POINT_IGNORE_PP) {
                        mismatches.add(where + ":" + c.powerControl() + " [" + i + "] 기대 " + want[i] + " · 실제 " + got[i]);
                    }
                }
                checked++;
            }
        }
        assertTrue(mismatches.isEmpty(), "관측 리터럴과 다르다:\n  " + String.join("\n  ", mismatches));
        assertEquals((fragmentation.size() + rates.size()) * 2, checked, "짝 셀 다섯과 110% 두 셀을 두 전원 상태로 모두 봐야 한다");
    }

    /**
     * 판독 규칙(rules 커밋 본문 · docs/14)의 지점별 부호 집계를 운영 부품으로 낸다 — 지점 집합 I는 기준 집합 짝 지점 가운데 두 순서 열의 건수가
     * 기준 건수와 같은 지점, 크기 효과 d는 조각난 여유 비율(크기 순 − 등록 순), 우선순위 효과 p는 (등록 순 − weighted)다. 무시 폭 안의 차는 동률이다.
     * 값은 첫 실행 관측을 리터럴로 고정한 것이고 사전 추정은 테스트로 확인하지 않는다 — 빗나가면 노브를 돌릴 유인이 생긴다.
     */
    @DisplayName("주 결과판 짝 셀 다섯에서 두 스케줄 배치 순서 열의 같은 지점 차 부호 집계가 관측 리터럴과 같다")
    @Test
    void schedule_order_paired_sign_counts_match_the_observed_literals() {
        BenchmarkColumns columns = harnessColumns();
        Map<String, double[]> expected = new LinkedHashMap<>();   // 분포:부하율 → {|I|, d 음 · 동률 · 양, p 음 · 동률 · 양, d 중앙값, p 중앙값}
        expected.put("heavytail:30", new double[]{12, 0, 12, 0, 0, 12, 0, 0, 0});
        expected.put("heavytail:60", new double[]{12, 3, 5, 4, 1, 7, 4, 0, 0});
        expected.put("heavytail:90", new double[]{8, 4, 1, 3, 4, 0, 4, -0.7708, 6.8954});
        expected.put("uniform:30", new double[]{12, 10, 2, 0, 8, 1, 3, -3.6364, -3.6071});
        expected.put("uniform:60", new double[]{2, 0, 1, 1, 2, 0, 0, 3.3063, -3.5739});
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, double[]> e : expected.entrySet()) {
            String[] key = e.getKey().split(":");
            List<BenchmarkPointRunner.PointOutcome> points = orderSweep(columns, BenchmarkClusters.MAIN, key[0], Integer.parseInt(key[1]));
            List<String> base = BenchmarkCellAssembler.baseSetIds(points.get(0).byStrategy().keySet(), columns.overrides().keySet());
            List<Double> d = new ArrayList<>();
            List<Double> p = new ArrayList<>();
            for (BenchmarkPointRunner.PointOutcome point : points) {
                List<Integer> counts = base.stream().map(id -> point.byStrategy().get(id).placed()).toList();
                if (!BenchmarkRules.totalsComparable(counts)) continue;
                BenchmarkPointRunner.Placement age = point.byStrategy().get(BenchmarkRules.SCHEDULE_ORDER_AGE_COLUMN);
                BenchmarkPointRunner.Placement size = point.byStrategy().get(BenchmarkRules.SCHEDULE_ORDER_SIZE_COLUMN);
                if (age.placed() != counts.get(0) || size.placed() != counts.get(0)) continue;
                Double fAge = BenchmarkCellAssembler.fragmentedHeadroomRate(age);
                Double fSize = BenchmarkCellAssembler.fragmentedHeadroomRate(size);
                Double fWeighted = BenchmarkCellAssembler.fragmentedHeadroomRate(point.byStrategy().get("weighted"));
                if (fAge == null || fSize == null || fWeighted == null) continue;
                d.add(round4(fSize - fAge));
                p.add(round4(fAge - fWeighted));
            }
            double[] got = {d.size(), below(d), ties(d), above(d), below(p), ties(p), above(p), medianOf(d), medianOf(p)};
            for (int i = 0; i < got.length; i++) {
                if (Math.abs(e.getValue()[i] - got[i]) > BenchmarkRules.JUDGMENT_POINT_IGNORE_PP) {
                    mismatches.add(e.getKey() + " 기대 " + java.util.Arrays.toString(e.getValue()) + " · 실제 " + java.util.Arrays.toString(got));
                    break;
                }
            }
        }
        assertTrue(mismatches.isEmpty(), "부호 집계가 관측 리터럴과 다르다:\n  " + String.join("\n  ", mismatches));
    }

    // ── 전력 제약 개입 횟수 ─────────────────────────────────

    /**
     * 판 지표가 세는 전력 막힘 — 전력 계열 까닭 두 가지(여유 부족 · 배치 후 예상 전력 초과)를 하나씩 담은 후보에서 둘만 센다. 열 막힘(1) ·
     * 막힌 후보 전체(3) · 전력 까닭 하나(1)와 값이 갈리게 후보를 놓아, 셈이 그중 무엇을 세도 여기서 잡힌다.
     */
    @DisplayName("전력 제약 개입 셈은 전력 까닭 두 가지를 세고 열 까닭과 막힌 후보 전체는 세지 않는다")
    @Test
    void power_intervention_count_counts_both_power_codes_only() {
        SchedulingPolicy policy = new SchedulingPolicy("p", "d");
        policy.setTemperatureAwareEnabled(true);
        policy.setPowerAwareEnabled(true);
        List<NodeResource> nodes = List.of(
                new NodeResource("mdc-a", "n-hot", 64, 65536, 0, 10, 10, 15, 400, 1000, 90, 0, 0),
                new NodeResource("mdc-a", "n-headroom", 64, 65536, 0, 10, 10, 15, 1000, 1000, 30, 0, 0),
                new NodeResource("mdc-a", "n-predicted", 4, 65536, 0, 10, 10, 15, 850, 1000, 30, 0, 0),
                new NodeResource("mdc-a", "n-ok", 64, 65536, 0, 10, 10, 15, 100, 1000, 30, 0, 0));
        SchedulingResult r = new WeightedScoreScheduler().schedule(new Workload("w", 1, 512),
                PlacementContext.of(nodes, policy, ConstraintEvaluator.withDefaults()));

        Set<ExclusionReason> powerCodes = r.candidateScores().stream()
                .map(SchedulingResult.NodeScore::exclusionCode).filter(c -> c != null && c.isPower()).collect(Collectors.toSet());
        assertEquals(Set.of(ExclusionReason.POWER_HEADROOM_EXCEEDED, ExclusionReason.PREDICTED_POWER_EXCEEDED), powerCodes,
                "후보가 전력 까닭 두 가지를 하나씩 담아야 이 테스트가 두 까닭을 다 본다");
        assertEquals(3, r.candidateScores().stream().filter(s -> !s.eligible()).count(), "막힌 후보 전체는 셋 — 열 하나 · 전력 둘");
        assertEquals(1, BenchmarkPointRunner.countThermalExclusions(r), "열 막힘은 하나");
        assertEquals(2, BenchmarkPointRunner.countPowerExclusions(r),
                "전력 막힘은 둘 — 막힌 후보 전체(3) · 열 막힘(1) · 전력 까닭 하나(1)를 세면 갈린다");
    }

    /**
     * 전력 제약을 끈 변종의 개입 횟수는 항상 0이고, 그 0은 값이 아니라 게이트가 없다는 뜻이라 무효 · null이어야 한다 — 온도 제약 개입 횟수와 같은 꼴이다.
     */
    @DisplayName("전력 제약을 끈 변종의 전력 제약 개입 횟수는 무효이고 숫자가 없다")
    @Test
    void the_ungated_variant_has_an_invalid_power_intervention_count_with_no_number() {
        for (Cell c : service.compare(BenchmarkClusters.MAIN, "heavytail").boards().get(0).cells()) {
            MetricValue m = metric(strategy(c, "resource-only-ungated"), BenchmarkRules.POWER_INTERVENTIONS);
            String where = c.loadRatioPercent() + "% " + c.powerControl();
            assertFalse(m.valid(), where + ": 게이트가 없는 전략의 개입 0 이 유효한 숫자로 나갔다");
            assertNull(m.median(), where + ": 무효인데 0 이 실렸다 — 인용자에게는 '개입 0건'으로 읽힌다");
            assertEquals("이 전략은 전력 제약이 꺼져 있어 항상 0 이다 — 켜진 전략과 비교하지 않는다", m.invalidReason(), where);
            assertTrue(m.samples() > 0, where + ": 값이 나온 지점은 있었다는 사실은 남아야 한다");
        }
    }

    /**
     * 전력 제약 개입 횟수는 시도당 계수라 배치 성공률과 같은 지점 수를 센다 — 짝 지점이 없는 셀에서도 표본이 줄지 않아야 한다. 두 분포를 다 보아
     * 총량 표본이 줄어든 셀이 실제로 있었는지도 확인한다.
     */
    @DisplayName("전력 제약 개입 횟수는 짝 지점이 줄어든 셀에서도 배치 성공률과 같은 지점 수를 센다")
    @Test
    void power_intervention_count_uses_every_point_like_the_placement_rate() {
        boolean sawFewerTotals = false;
        for (String distribution : List.of("heavytail", "uniform")) {
            for (Cell c : service.compare(BenchmarkClusters.MAIN, distribution).boards().get(0).cells()) {
                for (StrategyResult s : c.strategies()) {
                    String where = distribution + " " + c.loadRatioPercent() + "% " + c.powerControl() + " " + s.strategyId();
                    MetricValue power = metric(s, BenchmarkRules.POWER_INTERVENTIONS);
                    assertEquals(BenchmarkRules.SWEEP_POINTS, power.samples(), where + ": 시도당 계수라 전 시각 지점을 센다");
                    assertEquals(metric(s, BenchmarkRules.PLACEMENT_RATE).samples(), power.samples(),
                            where + ": 배치 성공률과 같은 지점 수여야 한다");
                    if (metric(s, BenchmarkRules.GENERATOR_POWER).samples() < BenchmarkRules.SWEEP_POINTS) sawFewerTotals = true;
                }
            }
        }
        assertTrue(sawFewerTotals, "총량 표본이 줄어든 셀이 없다 — 짝 지점 조건이 걸린 셀을 보지 못해 이 테스트가 아무것도 가르지 못했다");
    }

    /**
     * 네 판(주 결과판 · 정격 어긋남판 · 오목판 · 반증판) heavytail 60 · 90 · 110% 셀의 weighted · power 전력 제약 개입 횟수 — 첫 실행에서 관측한 값을
     * 리터럴로 고정한다(결과보다 먼저 정한 값이 아니다). 두 전원 상태 셀은 배치가 같아 같은 값과 견준다. 사전 추정은 테스트로 확인하지 않는다.
     * 110% 셀은 실패한 시도가 있는 셀이라 셈이 성공 확인 앞에 있는지도 함께 본다 — 반증판은 값이 늘 0이고 나머지 세 판의 60 · 90% 셀은 모든 시도가 성공해 그 자리를 가르지 못한다.
     */
    @DisplayName("네 판 heavytail 60 · 90 · 110% 셀의 weighted · power 전력 제약 개입 횟수 중앙값 · 최댓값이 관측 리터럴과 같다")
    @Test
    void power_intervention_counts_match_the_observed_literals() {
        Map<String, double[]> want = new LinkedHashMap<>();   // 판:부하율:열 → {중앙값, 최댓값}
        want.put("main:60:weighted", new double[]{7.0, 12.0});
        want.put("main:60:power", new double[]{5.0, 13.0});
        want.put("main:90:weighted", new double[]{15.5, 20.0});
        want.put("main:90:power", new double[]{30.0, 44.0});
        want.put("main:110:weighted", new double[]{25.0, 36.0});
        want.put("main:110:power", new double[]{48.0, 65.0});
        want.put("misrated:60:weighted", new double[]{66.0, 84.0});
        want.put("misrated:60:power", new double[]{62.0, 87.0});
        want.put("misrated:90:weighted", new double[]{114.5, 154.0});
        want.put("misrated:90:power", new double[]{123.5, 185.0});
        want.put("misrated:110:weighted", new double[]{177.0, 230.0});
        want.put("misrated:110:power", new double[]{188.5, 276.0});
        want.put("concave:60:weighted", new double[]{16.0, 81.0});
        want.put("concave:60:power", new double[]{32.0, 83.0});
        want.put("concave:90:weighted", new double[]{86.5, 187.0});
        want.put("concave:90:power", new double[]{101.5, 186.0});
        want.put("concave:110:weighted", new double[]{190.0, 315.0});
        want.put("concave:110:power", new double[]{212.5, 297.0});
        want.put("adversarial:60:weighted", new double[]{0.0, 0.0});
        want.put("adversarial:60:power", new double[]{0.0, 0.0});
        want.put("adversarial:90:weighted", new double[]{0.0, 0.0});
        want.put("adversarial:90:power", new double[]{0.0, 0.0});
        want.put("adversarial:110:weighted", new double[]{0.0, 0.0});
        want.put("adversarial:110:power", new double[]{0.0, 0.0});
        List<String> mismatches = new ArrayList<>();
        int checked = 0;
        for (String board : List.of(BenchmarkClusters.MAIN, BenchmarkClusters.MISRATED, BenchmarkClusters.CONCAVE, BenchmarkClusters.ADVERSARIAL)) {
            for (Cell c : service.compare(board, "heavytail").boards().get(0).cells()) {
                for (String id : List.of("weighted", "power")) {
                    String where = board + ":" + c.loadRatioPercent() + ":" + id;
                    double[] w = want.get(where);
                    if (w == null) continue;
                    MetricValue m = metric(strategy(c, id), BenchmarkRules.POWER_INTERVENTIONS);
                    Double[] got = {m.median(), m.max()};
                    for (int i = 0; i < w.length; i++) {
                        if (got[i] == null || Math.abs(w[i] - got[i]) > BenchmarkRules.JUDGMENT_POINT_IGNORE_PP) {
                            mismatches.add(where + ":" + c.powerControl() + " [" + i + "] 기대 " + w[i] + " · 실제 " + got[i]);
                        }
                    }
                    checked++;
                }
            }
        }
        assertTrue(mismatches.isEmpty(), "관측 리터럴과 다르다:\n  " + String.join("\n  ", mismatches));
        assertEquals(want.size() * 2, checked, "네 판 세 부하율 두 열을 두 전원 상태로 모두 봐야 한다");
    }

    private BenchmarkColumns harnessColumns() {
        return new BenchmarkColumns(new EngineRegistry(List.of(new WeightedScoreScheduler(), new PowerAwareScheduler(),
                new TemperatureAwareScheduler())), properties, new PowerModel(properties), new ThermalModel(properties),
                PlacementBenchmarkService.BASELINE_ORDER);
    }

    /** 한 셀의 시각 지점 전부를 서비스와 같은 부품(열 · 지점 · 표본 · 오라클)으로 돌린다. */
    private List<BenchmarkPointRunner.PointOutcome> orderSweep(BenchmarkColumns columns, String board, String distribution, int loadRatio) {
        SyntheticCluster cluster = new BenchmarkClusters().get(board);
        BenchmarkWorkloads workloads = BenchmarkWorkloads.load("classpath:scheduler/benchmark-workloads-" + distribution + ".yml");
        BenchmarkPointRunner runner = new BenchmarkPointRunner(properties, new PowerModel(properties), new ThermalModel(properties), columns);
        GroundTruthOracle oracle = new GroundTruthOracle(cluster);
        List<BenchmarkPointRunner.PointOutcome> points = new ArrayList<>();
        for (long t : PlacementBenchmarkService.sweepPoints(cluster)) points.add(runner.runPoint(cluster, workloads, loadRatio, t, oracle));
        return points;
    }

    private static List<BenchmarkPointRunner.PointOutcome> withoutOrderColumns(List<BenchmarkPointRunner.PointOutcome> points) {
        return points.stream().map(p -> {
            Map<String, BenchmarkPointRunner.Placement> kept = new LinkedHashMap<>(p.byStrategy());
            BenchmarkRules.SCHEDULE_ORDER_COLUMNS.forEach(kept::remove);
            return withPlacements(p, kept);
        }).toList();
    }

    /** 두 순서 열의 배치 건수를 요구 건수보다 크게 바꾼다 — 기준 건수(요구 건수 이하)와 반드시 다르다. */
    private static List<BenchmarkPointRunner.PointOutcome> withShiftedOrderColumns(List<BenchmarkPointRunner.PointOutcome> points) {
        return points.stream().map(p -> {
            Map<String, BenchmarkPointRunner.Placement> shifted = new LinkedHashMap<>(p.byStrategy());
            for (String id : BenchmarkRules.SCHEDULE_ORDER_COLUMNS) {
                BenchmarkPointRunner.Placement x = shifted.get(id);
                shifted.put(id, new BenchmarkPointRunner.Placement(x.strategy(), x.requested() + 1, x.requested(), x.engineBelievedCostW(),
                        x.usedNodes(), x.cpuAfterByNode(), x.unmeasuredPlacements(), x.thermalExclusions(), x.powerExclusions(), x.activeCountAfter(),
                        x.measuredTempStdDev(), x.warnZoneNodes(), x.temperatureGated(), x.powerGated(), x.dataMdcPlacements(),
                        x.remoteMdcPlacements(), x.headroomAfterCores(), x.fragmentedHeadroomCores(), x.placedCores()));
            }
            return withPlacements(p, shifted);
        }).toList();
    }

    private static List<BenchmarkPointRunner.PointOutcome> withoutTemperatureLimitColumn(List<BenchmarkPointRunner.PointOutcome> points) {
        return points.stream().map(p -> {
            Map<String, BenchmarkPointRunner.Placement> kept = new LinkedHashMap<>(p.byStrategy());
            kept.remove(BenchmarkRules.TEMPERATURE_LIMIT_COLUMN);
            return withPlacements(p, kept);
        }).toList();
    }

    /** 온도 상한 열의 배치 건수를 요구 건수보다 크게 바꾼다 — 기준 건수(요구 건수 이하)와 반드시 다르다. */
    private static List<BenchmarkPointRunner.PointOutcome> withShiftedTemperatureLimitColumn(List<BenchmarkPointRunner.PointOutcome> points) {
        return points.stream().map(p -> {
            Map<String, BenchmarkPointRunner.Placement> shifted = new LinkedHashMap<>(p.byStrategy());
            BenchmarkPointRunner.Placement x = shifted.get(BenchmarkRules.TEMPERATURE_LIMIT_COLUMN);
            shifted.put(BenchmarkRules.TEMPERATURE_LIMIT_COLUMN, new BenchmarkPointRunner.Placement(x.strategy(), x.requested() + 1, x.requested(),
                    x.engineBelievedCostW(), x.usedNodes(), x.cpuAfterByNode(), x.unmeasuredPlacements(), x.thermalExclusions(), x.powerExclusions(),
                    x.activeCountAfter(), x.measuredTempStdDev(), x.warnZoneNodes(), x.temperatureGated(), x.powerGated(), x.dataMdcPlacements(),
                    x.remoteMdcPlacements(), x.headroomAfterCores(), x.fragmentedHeadroomCores(), x.placedCores()));
            return withPlacements(p, shifted);
        }).toList();
    }

    private static BenchmarkPointRunner.PointOutcome withPlacements(BenchmarkPointRunner.PointOutcome p,
                                                                    Map<String, BenchmarkPointRunner.Placement> byStrategy) {
        return new BenchmarkPointRunner.PointOutcome(p.headroomCores(), p.requestedWorkloads(), p.requestedCores(), p.cpuBeforeByNode(),
                p.activeBefore(), p.activeCountBefore(), p.measuredNodeCount(), p.probeExclusions(), byStrategy, p.dataMdcAssigned());
    }

    private static Workload orderWorkload(String name, int cores, int gpus, Workload.WorkloadPriority priority) {
        Workload w = new Workload(name, cores, cores * BenchmarkRules.MEMORY_MB_PER_CORE);
        w.setRequiredGpu(gpus);
        w.setPriority(priority);
        return w;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static int below(List<Double> diffs) {
        return (int) diffs.stream().filter(v -> v < -BenchmarkRules.JUDGMENT_POINT_IGNORE_PP).count();
    }

    private static int ties(List<Double> diffs) {
        return (int) diffs.stream().filter(v -> Math.abs(v) <= BenchmarkRules.JUDGMENT_POINT_IGNORE_PP).count();
    }

    private static int above(List<Double> diffs) {
        return (int) diffs.stream().filter(v -> v > BenchmarkRules.JUDGMENT_POINT_IGNORE_PP).count();
    }

    /** 중앙값 — 빈 목록이면 NaN(리터럴과 같지 않아 실패한다). */
    private static double medianOf(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        return round4(sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0);
    }

    // ── 총량 짝짓기 ───────────────────────────────────────────

    /**
     * 배치 건수가 어긋난 지점이 있으면 그 지점의 총량은 빼고, <b>몇 곳으로 냈는지</b>를 밝힌다.
     * 셀을 통째로 무효로 만들면 쓸 수 있는 짝까지 잃어 주 지표가 중부하 이상에서 사라진다.
     */
    @DisplayName("배치 건수가 어긋난 지점은 총량 표본에서 빠지고 그 사실이 셀에 적힌다")
    @Test
    void unpaired_points_drop_out_of_the_totals_and_the_cell_says_so() {
        Board board = service.compare("main", "heavytail").boards().get(0);
        List<Cell> partial = board.cells().stream().filter(c -> !c.totalsComparable()).toList();
        assertFalse(partial.isEmpty(),
                "짝이 어긋난 셀이 하나도 없다 — 수용 불가 부하 지점(110%)이 전략을 구분하지 못한다는 뜻이다");

        for (Cell c : partial) {
            assertNotNull(c.totalsIncomparableReason(), c.loadRatioPercent() + "% 사유가 없다");
            assertTrue(c.totalsIncomparableReason().contains("건"),
                    "사유에 건수가 없으면 무엇이 어긋났는지 알 수 없다: " + c.totalsIncomparableReason());

            for (StrategyResult s : c.strategies()) {
                MetricValue totals = metric(s, BenchmarkRules.GENERATOR_POWER);
                MetricValue rate = metric(s, BenchmarkRules.PLACEMENT_RATE);
                assertTrue(totals.samples() < rate.samples(), s.strategyId()
                        + ": 총량 표본이 성공률 표본보다 적지 않다 — 짝이 아닌 지점을 그냥 세고 있다");
            }
        }

        // 스케줄 배치 순서 열은 기준 집합 밖이라 자기 건수가 어긋난 지점이 있으면 그 사실만 셀에 적힌다 — 기준 집합 · override 열 문장은 없어야 한다
        for (Cell c : board.cells().stream().filter(Cell::totalsComparable).toList()) {
            String reason = c.totalsIncomparableReason();
            assertTrue(reason == null || reason.startsWith("스케줄 배치 순서 열 중 "),
                    "짝이 온전한 셀에 사유가 붙었다: " + reason);
            for (StrategyResult s : c.strategies()) {
                if (BenchmarkRules.SCHEDULE_ORDER_COLUMNS.contains(s.strategyId())) continue;
                assertEquals(BenchmarkRules.SWEEP_POINTS, metric(s, BenchmarkRules.GENERATOR_POWER).samples(),
                        s.strategyId() + ": 짝이 온전한 셀인데 표본이 전 지점이 아니다");
            }
        }
    }

    /**
     * 온도 분포 두 지표는 총량과 <b>같은 지점</b>에서만 표본에 들어가야 한다. 생성식 온도는 CPU의 함수라
     * 덜 배치한 전략은 CPU를 덜 얹어 덜 뜨겁고 분포도 덜 벌어진다 — 건수가 다른 지점을 그냥 세면 열이
     * 아니라 배치량을 재게 된다. 개입 횟수는 시도당 계수라 성공률과 같이 전 지점을 센다.
     *
     * <p>판 넷을 다 본다 — 짝이 어긋나는 셀이 어느 판에서 나올지는 판의 성질이라 하나만 보면 우연히
     * 게이트가 한 번도 작동하지 않은 채 통과할 수 있다.</p>
     */
    @DisplayName("온도 분포 두 지표는 총량과 같은 지점에서만 세고 개입 횟수는 성공률과 같이 전 지점을 센다")
    @Test
    void the_temperature_distribution_metrics_are_paired_like_the_totals() {
        boolean anyGateFired = false;

        for (String boardId : List.of(BenchmarkClusters.MAIN, BenchmarkClusters.ADVERSARIAL, BenchmarkClusters.SPARSE,
                BenchmarkClusters.CONVEX_MISRATED)) {
            Board board = service.compare(boardId, "heavytail").boards().get(0);
            assertTrue(board.measuredNodeCount() >= 2, boardId + ": 계측 노드가 둘 미만이면 이 시험이 게이트를 못 본다");

            for (Cell c : board.cells()) {
                for (StrategyResult s : c.strategies()) {
                    String where = boardId + " " + c.loadRatioPercent() + "% " + c.powerControl() + " " + s.strategyId();
                    MetricValue totals = metric(s, BenchmarkRules.GENERATOR_POWER);
                    MetricValue rate = metric(s, BenchmarkRules.PLACEMENT_RATE);

                    for (String key : List.of(BenchmarkRules.WARN_ZONE_NODES, BenchmarkRules.TEMP_STDDEV)) {
                        MetricValue thermal = metric(s, key);
                        assertEquals(totals.samples(), thermal.samples(),
                                where + " " + key + ": 표본 수가 총량과 다르다 — 짝이 아닌 지점을 세고 있다");
                        assertEquals(totals.median() == null, thermal.median() == null,
                                where + " " + key + ": 총량이 무효인 셀에서 값이 살아 있다(또는 반대)");
                    }
                    assertEquals(rate.samples(), metric(s, BenchmarkRules.C3_INTERVENTIONS).samples(),
                            where + ": 개입 횟수는 시도당 계수라 성공률과 같은 지점 수여야 한다");

                    if (totals.samples() < rate.samples()) anyGateFired = true;
                }
            }
        }
        assertTrue(anyGateFired, "네 판 어디서도 짝이 어긋난 지점이 없다 — 게이트가 한 번도 작동하지 않아 이 시험이 아무것도 보지 않았다");
    }

    /**
     * 짝 지점이 하나도 없으면 온도 분포 두 지표도 총량처럼 <b>값을 싣지 않고 사유를 적는다</b>. 값이 남아 있으면
     * valid 플래그를 보지 않은 인용자에게는 그냥 숫자다. 그런 셀이 지금 판에 없으면 이 테스트는 그 사실을 알린다 —
     * 없다는 것을 조용히 통과로 바꾸지 않는다.
     *
     * <p>override 열은 셀에 짝 지점이 있어도 자기 배치 건수가 기준 집합과 다른 지점을 빼므로, 그런 지점뿐이면 표본이 0이 되고
     * 제외 사유를 단다(노드 배치 스코어 PEI 항에 코어 비율을 곱한 회차부터 반증판 90%의 {@code power-s250}·{@code power-s0}이 그렇다).
     * 그때는 같은 셀의 {@code power} 열에 짝 지점이 있어야 한다. 그런 행은 짝 지점 0 셀이 아니라서 아래 가드의 개수에 세지 않는다.</p>
     */
    @DisplayName("짝 지점이 하나도 없는 셀에서는 온도 분포 두 지표도 값 없이 사유만 싣는다")
    @Test
    void with_no_paired_point_the_temperature_distribution_metrics_carry_a_reason_and_no_number() {
        int checked = 0;
        for (String boardId : List.of(BenchmarkClusters.MAIN, BenchmarkClusters.ADVERSARIAL, BenchmarkClusters.SPARSE,
                BenchmarkClusters.CONVEX_MISRATED)) {
            for (Cell c : service.compare(boardId, "heavytail").boards().get(0).cells()) {
                for (StrategyResult s : c.strategies()) {
                    if (metric(s, BenchmarkRules.GENERATOR_POWER).samples() > 0) continue;
                    boolean overrideColumn = outsideBaseSet(s);
                    boolean droppedOnly = false;
                    for (String key : List.of(BenchmarkRules.WARN_ZONE_NODES, BenchmarkRules.TEMP_STDDEV)) {
                        MetricValue m = metric(s, key);
                        String where = boardId + " " + c.loadRatioPercent() + "% " + s.strategyId() + " " + key;
                        assertNull(m.median(), where + ": 짝 지점이 없는데 값이 실렸다");
                        assertEquals(0, m.samples(), where);
                        assertNotNull(m.invalidReason(), where + ": 사유가 없다");
                        if (overrideColumn && m.invalidReason().startsWith("기준 집합과 배치 건수가 다른 지점뿐")) {
                            assertTrue(metric(strategy(c, "power"), BenchmarkRules.GENERATOR_POWER).samples() > 0,
                                    where + ": 제외 사유는 power 열에 짝 지점이 있는 셀에서만 나와야 한다");
                            droppedOnly = true;
                        } else {
                            assertTrue(m.invalidReason().contains("배치 건수가 같은 시각 지점이 없다"), where + ": " + m.invalidReason());
                        }
                    }
                    if (!droppedOnly) checked++;
                }
            }
        }
        // 수용 불가 부하(110%)는 판정 규칙이 일부러 넣은 지점이라 네 판 모두 짝 지점 0인 셀이 있어야 한다. 하나도 없으면
        // 위 확인이 아무것도 안 본 것이므로 통과가 아니라 실패다 — 로그로 남기면 통과가 "확인함"으로 읽힌다.
        assertTrue(checked > 0, "짝 지점 0 인 셀이 네 판 어디에도 없다 — 110% 부하가 전략을 구분하지 못한다는 뜻이고, 이 시험이 아무것도 보지 않았다");
    }

    /** 시각 지점을 훑었으므로 최솟값 ≤ 중앙값 ≤ 최댓값 이고, 폭이 0이 아닌 지표가 있어야 한다. */
    @DisplayName("지표마다 최솟값과 중앙값과 최댓값의 순서가 맞다")
    @Test
    void metric_ranges_are_ordered_and_not_degenerate() {
        Cell target = cell(service.compare("main", "heavytail").boards().get(0), 60, "UNAVAILABLE");
        boolean anySpread = false;

        for (StrategyResult s : target.strategies()) {
            for (MetricValue m : s.metrics()) {
                if (m.median() == null) continue;
                assertNotNull(m.min(), s.strategyId() + " " + m.key());
                assertTrue(m.min() <= m.median() && m.median() <= m.max(),
                        s.strategyId() + " " + m.key() + ": " + m.min() + " ≤ " + m.median() + " ≤ " + m.max());
                if (m.max() > m.min()) anySpread = true;
            }
        }
        assertTrue(anySpread,
                "모든 지표의 폭이 0 이다 — 시각을 하나로 고정한 것과 같아 오차 막대를 붙일 수 없다");
    }

    // ── 정직성 지표 ───────────────────────────────────────────

    /**
     * 계측 공백판은 주 결과판과 <b>계측 플래그만</b> 다르다. 그런데 전략마다 미계측 노드에 얹는 비율이
     * 크게 갈리면, 전력·온도 순위는 알고리즘이 아니라 <b>센서 공백</b>을 재고 있을 수 있다.
     * 그 사실을 셀이 스스로 문장으로 밝혀야 한다.
     */
    @DisplayName("계측 공백판은 미계측 배치 비율의 격차를 스스로 경고한다")
    @Test
    void the_sensor_gap_board_warns_about_its_own_blind_spot() {
        Board sparse = service.compare("sparse", "heavytail").boards().get(0);

        assertEquals(14, sparse.nodeCount());
        assertEquals(9, sparse.measuredNodeCount(), "계측 공백판은 다섯 대가 미계측이다");

        Cell target = cell(sparse, 30, "UNAVAILABLE");
        assertNotNull(target.unmeasuredCaution(),
                "미계측 비율이 전략마다 크게 갈리는데 경고가 없다 — 순위가 센서 공백의 산물일 수 있다");
        assertTrue(target.unmeasuredCaution().contains("센서"), target.unmeasuredCaution());

        double spread = median(strategy(target, "pabfd"), BenchmarkRules.UNMEASURED_SPREAD);
        assertTrue(spread >= BenchmarkRules.UNMEASURED_SPREAD_CAUTION_PP,
                "격차 " + spread + "%p 가 경고 임계 미만인데 경고가 붙었다");

        boolean anyOnUnmeasured = target.strategies().stream()
                .anyMatch(s -> median(s, BenchmarkRules.UNMEASURED_RATE) > 0);
        assertTrue(anyOnUnmeasured, "미계측 노드에 아무도 배치하지 않았다면 이 판이 아무것도 재지 못한다");
    }

    /** 전수 계측 판에서는 미계측 배치가 0이고 경고가 붙지 않는다 — 경고가 늘 붙으면 신호가 아니다. */
    @DisplayName("전수 계측 판에서는 미계측 배치가 없고 경고도 없다")
    @Test
    void the_fully_measured_board_has_no_blind_spot_and_no_warning() {
        for (Cell c : service.compare("main", "heavytail").boards().get(0).cells()) {
            assertNull(c.unmeasuredCaution(),
                    c.loadRatioPercent() + "% " + c.powerControl() + ": 전수 계측 판에 경고가 붙었다");
            for (StrategyResult s : c.strategies()) {
                assertEquals(0.0, median(s, BenchmarkRules.UNMEASURED_RATE), 1e-9, s.strategyId());
            }
        }
    }

    // ── 반증판 ────────────────────────────────────────────────

    /**
     * 반증판은 우리 엔진이 지도록 설계했다. 이 테스트는 <b>지는지</b>를 확인하지 않는다 — 그것은
     * 결과이고, 결과를 테스트로 못 박으면 결과를 고칠 때 테스트도 고치게 된다. 대신 <b>판이 설계대로
     * 심겼는지</b>를 지킨다: 유휴 비율이 엔진 가정보다 훨씬 낮아 깨우기 비용 가정이 크게 틀리는가.
     */
    @DisplayName("반증판은 엔진의 유휴 전력 가정이 크게 과대인 판이다")
    @Test
    void the_adversarial_board_makes_the_engine_overestimate_wake_up_cost() {
        Board adversarial = service.compare("adversarial", "heavytail").boards().get(0);
        double engineRatio = properties.getPower().getIdleRatio();

        assertFalse(adversarial.idleAssumptionGaps().isEmpty());
        for (IdleGap gap : adversarial.idleAssumptionGaps()) {
            assertTrue(gap.declaredIdleRatio() < engineRatio / 2, gap.nodeId()
                    + ": 실제 유휴 비율 " + gap.declaredIdleRatio() + " 가 엔진 가정 " + engineRatio
                    + " 의 절반 미만이 아니다 — 이 판에서 깨우기 비용 가정이 틀리지 않는다");
            assertTrue(gap.engineAssumedIdleWatts() > gap.declaredIdleWatts(),
                    gap.nodeId() + ": 엔진 가정이 실제보다 크지 않다");
        }
        assertTrue(adversarial.role().contains("지도록") || adversarial.role().contains("반증"),
                "이 판을 만든 의도가 응답에 적혀 있어야 한다: " + adversarial.role());
    }

    /**
     * 기존 세 판은 엔진에게 <b>생성식과 같은 정격</b>을 준다. 정격을 어긋나게 주는 판이 생겨도 이 세 판은
     * 그대로여야 하고, 그렇지 않으면 새 판과의 비교가 정격 어긋남이 아니라 판 전체의 변화를 재게 된다.
     */
    @DisplayName("기존 세 판은 엔진이 믿는 정격과 생성식 정격이 노드마다 같다")
    @Test
    void the_three_existing_boards_tell_the_engine_the_generator_capacity() {
        for (String boardId : List.of(BenchmarkClusters.MAIN, BenchmarkClusters.ADVERSARIAL, BenchmarkClusters.SPARSE)) {
            Board board = service.compare(boardId, "heavytail").boards().get(0);
            assertFalse(board.idleAssumptionGaps().isEmpty(), boardId);
            for (IdleGap gap : board.idleAssumptionGaps()) {
                assertEquals(gap.capacityWatts(), gap.engineBelievedCapacityWatts(),
                        boardId + "/" + gap.nodeId() + ": 정격을 어긋나게 주지 않는 판인데 두 값이 다르다");
            }
        }
    }

    /**
     * 판정 셀이 <b>쓸 수 있는 칸인지</b>를 본다 — 결과가 아니라 <b>판의 유효성</b>이다.
     *
     * <p>전력 제약의 문턱은 부하율에 비대칭이라 전략마다 배치 건수를 다르게 만들 수 있고, 그러면 총량 열이
     * 통째로 무효가 되어 판정 셀의 주 지표가 사라진다. 그때는 결과가 나쁜 것이 아니라 <b>판을 못 쓰는 것</b>이므로,
     * 이 테스트가 실패하면 판을 다시 떠야 한다. 90·110%에서 총량 열이 소실되는 것은 사전 등록한 예상 범위라
     * 여기서 보지 않는다.</p>
     */
    @DisplayName("판정 셀과 같은 판의 30% 셀에서 총전력 지표가 유효하고 표본이 있다")
    @Test
    void the_judgment_cell_carries_a_usable_total_power_metric() {
        Board board = service.compare(BenchmarkRules.JUDGMENT_BOARD, BenchmarkRules.JUDGMENT_DISTRIBUTION).boards().get(0);
        String powerControl = BenchmarkRules.JUDGMENT_POWER_CONTROL.name();

        for (int loadRatio : List.of(30, BenchmarkRules.JUDGMENT_LOAD_RATIO)) {
            Cell c = cell(board, loadRatio, powerControl);
            for (StrategyResult s : c.strategies()) {
                MetricValue m = metric(s, BenchmarkRules.GENERATOR_POWER);
                String where = BenchmarkRules.JUDGMENT_BOARD + " " + loadRatio + "% " + powerControl
                        + " " + s.strategyId();
                assertTrue(m.valid(), where + ": 총전력 열이 무효다 — 판정할 지표가 없으므로 판을 다시 떠야 한다: "
                        + m.invalidReason());
                assertTrue(m.samples() >= 1, where + ": 표본 지점이 하나도 없다 — 판정 셀이 비었다");
            }
        }
    }

    // ── 기준 전략 대비 격차 ───────────────────────────────────

    /**
     * 판정 셀에서 격차 열을 <b>쓸 수 있는지</b> 본다 — 판정이 읽을 자리라 여기가 비면
     * 결과가 나쁜 것이 아니라 판을 못 쓰는 것이다.
     *
     * <p>부호도 함께 못 박는다. 지금 이 판에서 우리 전력 엔진은 ΔP 단독 대조군(pabfd)보다 전력을 <b>더</b> 쓰고
     * (격차 &gt; 0), 그것이 엔진 개선이 줄이려는 값이다. 이 부호가 뒤집히면 세계가 달라진 것이거나 식의
     * 분모·부호가 바뀐 것이므로, 조용히 지나가지 않고 여기서 멈춰 테스트를 함께 고치게 한다.</p>
     */
    @DisplayName("판정 셀의 격차 열이 유효하고 표본이 있으며 기준선보다 큰 쪽에 있다")
    @Test
    void the_judgment_cell_carries_a_usable_gap_metric() {
        Cell c = cell(service.compare(BenchmarkRules.JUDGMENT_BOARD, BenchmarkRules.JUDGMENT_DISTRIBUTION).boards().get(0),
                BenchmarkRules.JUDGMENT_LOAD_RATIO, BenchmarkRules.JUDGMENT_POWER_CONTROL.name());
        MetricValue m = metric(strategy(c, "power"), BenchmarkRules.GAP_VS_PABFD);

        assertTrue(m.valid(), "판정 셀의 격차 열이 무효다 — 판정할 지표가 없으므로 판을 다시 떠야 한다: "
                + m.invalidReason());
        assertTrue(m.samples() >= 1, "표본 지점이 하나도 없다 — 판정 셀이 비었다");
        assertTrue(m.samples() >= BenchmarkRules.JUDGMENT_MIN_PAIRED_POINTS,
                "짝 지점이 " + m.samples() + "곳뿐이라 사전 등록한 하한("
                        + BenchmarkRules.JUDGMENT_MIN_PAIRED_POINTS + ")에 못 미친다 — 이 판으로는 판정 불가다");
        assertTrue(m.median() > 0,
                "우리 전력 엔진의 격차 중앙값이 " + m.median() + " 다 — 기준선보다 덜 쓰는 쪽으로 부호가 "
                        + "뒤집혔다면 세계가 달라졌거나 식의 분모·부호가 바뀐 것이다");
        assertEquals(BenchmarkRules.SWEEP_POINTS, m.points().size());
    }

    /**
     * 아래 단위 테스트는 배열을 만드는 자리만 본다 — <b>호출부가 걸러진 목록을 넘기면</b> 그 자리는 결백한 채로
     * 정렬이 깨진다. 응답에서 "빈 자리 뒤에 값이 있는" 행을 세어 그 등가 변이까지 막는다. 압축하면 값이
     * 앞으로 모이고 null이 뒤로 밀려 이런 행이 하나도 남지 않는다.
     */
    @DisplayName("응답에도 빈 자리 뒤에 값이 있는 격차 행이 남아 있다")
    @Test
    void the_response_still_shows_values_after_a_gap_in_the_point_series() {
        int interleaved = 0;
        for (String board : List.of("main", "convex-misrated")) {
            for (Cell c : service.compare(board, "heavytail").boards().get(0).cells()) {
                for (StrategyResult s : c.strategies()) {
                    for (MetricValue m : s.metrics()) {
                        if (!BenchmarkRules.GAP_VS_PABFD.equals(m.key()) || m.points() == null) continue;
                        boolean sawNull = false;
                        for (Double v : m.points()) {
                            if (v == null) sawNull = true;
                            else if (sawNull) { interleaved++; break; }
                        }
                    }
                }
            }
        }
        assertTrue(interleaved > 0,
                "빈 자리 뒤에 값이 있는 행이 하나도 없다 — 값이 앞으로 당겨졌거나 판이 바뀌어 이 시험이 "
                        + "아무것도 보지 않는다. 둘 다 정렬 보장을 잃은 것이다");
    }

    /**
     * 빈 자리를 <b>있던 자리에</b> 남기는지 — 길이·표본 수·중앙값 확인으로는 볼 수 없는 성질이다.
     *
     * <p>값을 앞으로 당겨 모으고 뒤를 null로 채워도 길이·표본 수·중앙값은 전부 그대로다. 바뀌는 것은
     * 인덱스가 가리키는 시각뿐이고, 판정 계산({@link BenchmarkRules#judgeCell})은 정확히 그 인덱스로 두 실행을 맞댄다.
     * 값을 앞으로 당기는 변이는 길이, 표본 수, 중앙값 확인을 모두 통과하므로 이 테스트를 따로 둔다.</p>
     */
    @DisplayName("지점별 값은 빈 자리를 있던 자리에 남긴다")
    @Test
    void the_point_series_keeps_the_gaps_where_they_happened() {
        List<Double> raw = new ArrayList<>();
        raw.add(1.0);
        raw.add(null);
        raw.add(2.0);

        List<Double> series = PlacementBenchmarkService.pointSeries(raw, 5);

        assertEquals(5, series.size(), "길이는 스윕 지점 수로 맞춘다");
        assertEquals(1.0, series.get(0), "첫 지점의 값이 자리를 옮겼다");
        assertNull(series.get(1), "짝이 아니던 지점이 사라지고 뒤 값이 당겨졌다 — 두 실행의 같은 인덱스가 "
                + "다른 시각을 가리키게 된다");
        assertEquals(2.0, series.get(2), "세 번째 지점의 값이 자리를 옮겼다");
        assertNull(series.get(3));
        assertNull(series.get(4));
    }

    /**
     * 지점별 값은 <b>두 실행을 지점마다 맞대는</b> 것이 목적이라 정렬이 전부다. 길이가 스윕 지점 수로
     * 고정되고 짝이 아닌 지점이 null로 남아야 인덱스가 곧 시각이고, 값만 모아 압축하면 두 실행의
     * 3번 지점이 서로 다른 시각이 된다. 표본 수·중앙값이 그 배열과 같은 것도 함께 본다 — 셋이 갈리면
     * 응답이 스스로 모순된다.
     */
    @DisplayName("격차 열만 지점별 값을 싣고 길이는 스윕 지점 수이며 표본 수·중앙값이 그 배열과 맞는다")
    @Test
    void the_gap_row_carries_one_slot_per_sweep_point_and_agrees_with_its_own_statistics() {
        int nonNullSeen = 0;
        int nullSlotsSeen = 0;

        for (String boardId : List.of(BenchmarkClusters.MAIN, BenchmarkClusters.ADVERSARIAL,
                BenchmarkClusters.SPARSE, BenchmarkClusters.CONVEX_MISRATED)) {
            for (Cell c : service.compare(boardId, "heavytail").boards().get(0).cells()) {
                for (StrategyResult s : c.strategies()) {
                    String where = boardId + " " + c.loadRatioPercent() + "% " + c.powerControl()
                            + " " + s.strategyId();
                    for (MetricValue m : s.metrics()) {
                        if (!BenchmarkRules.GAP_VS_PABFD.equals(m.key())) {
                            assertNull(m.points(), where + " " + m.key()
                                    + ": 지점 대조가 필요 없는 지표까지 지점값을 실으면 응답이 배로 커진다");
                            continue;
                        }
                        assertNotNull(m.points(), where + ": 격차 열에 지점값이 없다");
                        assertEquals(BenchmarkRules.SWEEP_POINTS, m.points().size(),
                                where + ": 지점 배열의 길이가 스윕 지점 수와 다르다 — 두 실행의 같은 인덱스가 "
                                        + "같은 시각이라는 보장이 사라진다");

                        List<Double> present = m.points().stream().filter(Objects::nonNull).toList();
                        assertEquals(present.size(), m.samples(),
                                where + ": 값이 있는 지점 " + present.size() + "곳인데 samples=" + m.samples());
                        nonNullSeen += present.size();
                        nullSlotsSeen += m.points().size() - present.size();

                        if (m.valid()) {
                            assertEquals(BenchmarkRules.median(present), m.median(), 1e-9,
                                    where + ": 중앙값이 지점 배열의 중앙값과 다르다 — 둘 중 하나가 다른 지점을 "
                                            + "보고 있다");
                        }
                    }
                }
            }
        }
        assertTrue(nonNullSeen > 0, "네 판 어디에도 격차 값이 없다 — 이 시험이 빈 배열만 보고 통과했다");
        assertTrue(nullSlotsSeen > 0,
                "짝이 아닌 지점이 하나도 없다 — null 자리를 지키는 이 시험이 아무것도 보지 않았다");
    }

    /**
     * 격차의 짝짓기는 총량 열보다 <b>느슨하다</b> — 총량 열은 전 전략의 배치 건수가 같은 지점만 쓰지만
     * 격차는 이 행과 기준 전략 <b>둘</b>만 본다. 그래서 표본 수가 총량 열 이상이어야 하고, 실제로 더 많은
     * 셀이 있어야 두 정의가 다르다는 말이 사실이 된다. 기준 전략 자신은 값을 내지 않으므로 뺀다.
     */
    @DisplayName("격차의 표본 수는 총량 열 이상이고, 더 많은 셀이 실제로 있다")
    @Test
    void the_gap_is_paired_more_loosely_than_the_totals() {
        boolean anyLooser = false;

        for (String boardId : List.of(BenchmarkClusters.MAIN, BenchmarkClusters.ADVERSARIAL,
                BenchmarkClusters.SPARSE, BenchmarkClusters.CONVEX_MISRATED)) {
            for (Cell c : service.compare(boardId, "heavytail").boards().get(0).cells()) {
                for (StrategyResult s : c.strategies()) {
                    if ("pabfd".equals(s.strategyId())) continue;
                    int gap = metric(s, BenchmarkRules.GAP_VS_PABFD).samples();
                    int totals = metric(s, BenchmarkRules.GENERATOR_POWER).samples();

                    assertTrue(gap >= totals, boardId + " " + c.loadRatioPercent() + "% " + c.powerControl()
                            + " " + s.strategyId() + ": 격차 표본 " + gap + "곳이 총량 표본 " + totals
                            + "곳보다 적다 — 두 전략만 보는 짝이 전 전략 짝보다 좁을 수는 없다");
                    if (gap > totals) anyLooser = true;
                }
            }
        }
        assertTrue(anyLooser,
                "격차 표본이 총량 표본보다 많은 셀이 하나도 없다 — 두 짝 정의가 실제로는 같게 동작한다는 뜻이고, "
                        + "그러면 유효성 문구의 '표본 수가 총량 열 이상일 수 있다' 가 근거를 잃는다");
    }

    /**
     * 기준 전략 자신의 격차는 <b>0이 아니라 값이 아니다</b>. 0을 유효한 숫자로 실으면 표에서 그 열이
     * "격차 없음"으로 1등처럼 읽히고, 무효 판정이 없으면 그 오독을 막을 자리가 응답에 없다.
     */
    @DisplayName("기준 전략 행은 자기 격차를 싣지 않고 사유를 적는다")
    @Test
    void the_baseline_row_carries_no_gap_of_its_own() {
        for (String boardId : List.of(BenchmarkClusters.MAIN, BenchmarkClusters.CONVEX_MISRATED)) {
            for (Cell c : service.compare(boardId, "heavytail").boards().get(0).cells()) {
                MetricValue m = metric(strategy(c, "pabfd"), BenchmarkRules.GAP_VS_PABFD);
                String where = boardId + " " + c.loadRatioPercent() + "% " + c.powerControl();

                assertFalse(m.valid(), where + ": 기준 전략의 0 이 유효한 격차로 나갔다 — 표에서 1등으로 읽힌다");
                assertNull(m.median(), where + ": 무효인데 숫자가 실렸다");
                assertEquals(0, m.samples(), where);
                assertNotNull(m.invalidReason(), where);
                assertTrue(m.invalidReason().contains("기준 전략 자신"), where + ": " + m.invalidReason());
                assertEquals(BenchmarkRules.SWEEP_POINTS, m.points().size(),
                        where + ": 값이 없어도 지점 배열의 길이는 지켜야 인덱스가 시각이다");
                assertTrue(m.points().stream().allMatch(Objects::isNull), where + ": " + m.points());
            }
        }
    }

    /**
     * 대조군 집합을 갈아 끼우는 이음매는 기준 전략이 <b>없는</b> 실행을 만들 수 있다. 그때 격차 열이
     * 죽거나(NPE) 0을 싣지 않고, 전 행이 사유를 달고 무효로 나가야 한다 — 기준이 없는 격차는 값이 아니다.
     */
    @DisplayName("기준 전략이 없는 실행에서는 격차 열 전부가 사유를 달고 무효다")
    @Test
    void a_run_without_the_baseline_marks_every_gap_row_invalid() {
        SchedulerProperties props = new SchedulerProperties();
        List<String> without = PlacementBenchmarkService.BASELINE_ORDER.stream()
                .filter(id -> !id.equals("pabfd")).toList();
        PlacementBenchmarkService noBaseline = new PlacementBenchmarkService(
                new EngineRegistry(List.of(new WeightedScoreScheduler(), new PowerAwareScheduler(),
                        new TemperatureAwareScheduler())),
                props, new BenchmarkClusters(), new PowerModel(props), new ThermalModel(props), without);

        for (Cell c : noBaseline.compare("main", "heavytail").boards().get(0).cells()) {
            assertFalse(c.strategies().stream().anyMatch(s -> s.strategyId().equals("pabfd")),
                    "이 실행에는 기준 전략이 없어야 이 시험이 무언가를 본다");
            for (StrategyResult s : c.strategies()) {
                MetricValue m = metric(s, BenchmarkRules.GAP_VS_PABFD);
                String where = c.loadRatioPercent() + "% " + c.powerControl() + " " + s.strategyId();

                assertFalse(m.valid(), where + ": 기준이 없는데 격차가 유효로 나갔다");
                assertNull(m.median(), where);
                assertEquals(0, m.samples(), where);
                assertNotNull(m.invalidReason(), where);
                assertTrue(m.invalidReason().contains("이 실행에 없다"), where + ": " + m.invalidReason());
                assertEquals(BenchmarkRules.SWEEP_POINTS, m.points().size(),
                        where + ": 한 번도 담기지 않은 키도 길이는 지켜야 한다");
                assertTrue(m.points().stream().allMatch(Objects::isNull), where + ": " + m.points());
            }
        }
    }

    /**
     * 정격 어긋남은 우리 엔진만 흔들지 않는다 — 문헌 대조군도 <b>판이 알려 준 정격</b>을 읽는다.
     * "대조군은 그대로"라고 읽으면 어긋남의 효과를 우리 엔진의 것으로만 돌리게 된다.
     */
    @DisplayName("정격을 어긋나게 준 판에서는 전력 인지 대조군이 믿은 비용도 달라진다")
    @Test
    void the_power_aware_baseline_reads_the_misrated_capacity() {
        String powerControl = BenchmarkRules.JUDGMENT_POWER_CONTROL.name();
        Cell main = cell(service.compare(BenchmarkClusters.MAIN, "heavytail").boards().get(0),
                BenchmarkRules.JUDGMENT_LOAD_RATIO, powerControl);
        Cell misrated = cell(service.compare(BenchmarkClusters.MISRATED, "heavytail").boards().get(0),
                BenchmarkRules.JUDGMENT_LOAD_RATIO, powerControl);

        MetricValue a = metric(strategy(main, "pabfd"), BenchmarkRules.ENGINE_BELIEVED_COST);
        MetricValue b = metric(strategy(misrated, "pabfd"), BenchmarkRules.ENGINE_BELIEVED_COST);
        assertNotNull(a.median(), "주 결과판의 대조군 믿은 비용이 비었다");
        assertNotNull(b.median(), "정격 어긋남 판의 대조군 믿은 비용이 비었다");
        assertNotEquals(a.median(), b.median(),
                "두 판에서 대조군이 믿은 비용이 같다 — 대조군이 판의 정격을 읽지 않는다는 뜻이고, "
                        + "그러면 정격 어긋남의 효과가 우리 엔진 쪽에만 걸린 것처럼 보인다");
    }

    /**
     * 유휴 격차 표의 <b>열이 이름대로</b> 실렸는지 본다. 전 필드가 {@code double}이라 열을 하나 끼워 넣으면
     * 뒤의 값들이 한 칸씩 밀려도 컴파일이 통과하고, 그러면 표는 그럴듯한 숫자로 가득 찬 채 거짓이 된다.
     * 오라클이 직접 낸 행과 응답의 행을 열마다 맞춰 본다.
     *
     * <p>주 결과판만 보면 두 정격 열이 <b>같은 값</b>이라 서로 바뀌어 실려도 통과한다 — 두 값이 노드마다
     * 다른 정격 어긋남 판을 함께 돌려 그 구멍을 막는다.</p>
     */
    @DisplayName("유휴 격차 표의 각 열이 오라클의 같은 이름 값과 같다 — 주 결과판과 정격 어긋남 판")
    @Test
    void every_idle_gap_column_carries_the_value_of_its_own_name() {
        double engineRatio = properties.getPower().getIdleRatio();

        for (String boardId : List.of(BenchmarkClusters.MAIN, BenchmarkClusters.MISRATED)) {
            Board board = service.compare(boardId, "heavytail").boards().get(0);
            Map<String, GroundTruthOracle.IdleGap> expected =
                    new GroundTruthOracle(new BenchmarkClusters().get(boardId))
                            .idleAssumptionGaps(engineRatio).stream()
                            .collect(Collectors.toMap(g -> g.nodeId(), g -> g));

            assertEquals(expected.size(), board.idleAssumptionGaps().size(), boardId);
            for (IdleGap row : board.idleAssumptionGaps()) {
                var o = Objects.requireNonNull(expected.get(row.nodeId()), row.nodeId() + " 가 오라클에 없다");
                String at = boardId + "/" + row.nodeId() + ": ";
                assertEquals(o.capacityWatts(), row.capacityWatts(), 1e-9, at + "capacityWatts");
                assertEquals(o.engineBelievedCapacityWatts(), row.engineBelievedCapacityWatts(), 1e-9,
                        at + "engineBelievedCapacityWatts");
                assertEquals(o.declaredIdleWatts(), row.declaredIdleWatts(), 1e-9, at + "declaredIdleWatts");
                assertEquals(o.engineAssumedIdleWatts(), row.engineAssumedIdleWatts(), 1e-9,
                        at + "engineAssumedIdleWatts");
                assertEquals(o.absoluteGapWatts(), row.gapWatts(), 1e-9, at + "gapWatts");
                assertEquals(o.declaredIdleRatio(), row.declaredIdleRatio(), 1e-9, at + "declaredIdleRatio");
                assertEquals(o.generatorWattsPerCore(), row.generatorWattsPerCore(), 1e-9,
                        at + "generatorWattsPerCore");
                assertEquals(row.engineBelievedCapacityWatts() * engineRatio, row.engineAssumedIdleWatts(), 1e-9,
                        at + "엔진 가정은 엔진이 믿는 정격 × 유휴 비율이다");

                if (BenchmarkClusters.MISRATED.equals(boardId)) {
                    assertNotEquals(row.capacityWatts(), row.engineBelievedCapacityWatts(),
                            at + "정격 어긋남 판인데 두 정격이 같다 — 두 열이 한 값을 두 번 싣고 있을 수 있다");
                }
            }
        }
    }

    // ── 분포 두 종 ────────────────────────────────────────────

    /** 두 분포는 서로 다른 표본을 만든다 — 같으면 분포 축이 아무것도 확인하지 못한다. */
    @DisplayName("두 워크로드 분포가 서로 다른 표본을 만든다")
    @Test
    void the_two_distributions_produce_different_samples() {
        Cell heavy = cell(service.compare("main", "heavytail").boards().get(0), 60, "UNAVAILABLE");
        Cell uniform = cell(service.compare("main", "uniform").boards().get(0), 60, "UNAVAILABLE");

        assertNotEquals(heavy.requestedWorkloads(), uniform.requestedWorkloads(),
                "같은 코어 목표를 같은 건수로 채웠다면 두 분포의 구성비가 같다는 뜻이다");
        assertEquals(heavy.headroomCores(), uniform.headroomCores(), 1e-9,
                "분포가 분모를 바꾸면 두 분포의 결과를 견줄 수 없다");
    }

    // ── 잘못된 입력 ───────────────────────────────────────────

    @DisplayName("모르는 판은 조용히 기본판으로 넘어가지 않는다")
    @Test
    void an_unknown_board_is_rejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.compare("no-such-board", "heavytail"));
        assertTrue(e.getMessage().contains("no-such-board"), e.getMessage());
    }

    @DisplayName("모르는 분포는 조용히 기본 분포로 넘어가지 않는다")
    @Test
    void an_unknown_distribution_is_rejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.compare("main", "no-such-distribution"));
        assertTrue(e.getMessage().contains("no-such-distribution"), e.getMessage());
        assertTrue(e.getMessage().contains("heavytail"), "쓸 수 있는 값을 알려줘야 한다: " + e.getMessage());
    }

    @DisplayName("판과 분포를 비우면 주 결과판과 기본 분포로 돈다")
    @Test
    void blank_parameters_fall_back_to_the_main_board() {
        PlacementBenchmarkResponse response = service.compare(null, null);

        assertEquals("main", response.boards().get(0).boardId());
        assertEquals("heavytail-4class", response.assumptions().workloadDistribution());
    }

    /**
     * 생성식 문구가 유휴 역산의 범위(계측된 유휴 노드에 한함)와 그 결과가 상한일 뿐 알고리즘
     * 개선의 증거가 아니라는 한계를 스스로 밝히는지 못 박는다. 이 문구가 빠지면 "격차 축소"가
     * 실제 개선처럼 인용될 수 있다.
     */
    @DisplayName("생성식이 유휴 역산의 범위와 그 결과가 상한일 뿐이라는 한계를 함께 밝힌다")
    @Test
    void the_generator_formula_states_the_idle_reconstruction_and_its_ceiling() {
        String formula = service.compare("main", "heavytail").assumptions().generatorFormula();

        assertTrue(formula.contains("계측된 유휴 노드에 한해"), formula);
        assertTrue(formula.contains("대체값"), formula);
        assertTrue(formula.contains("β 는 판 파일이 선언"),
                "지수의 출처가 문구에서 빠지면 β 가 엔진 노브로 읽힌다: " + formula);
        assertTrue(formula.contains("상한이며 알고리즘 개선의 증거가 아니다"), formula);
        assertTrue(formula.contains("복원하지 않"),
                "복원이 성립하지 않는 판이 생겼는데 문구가 그 사실을 말하지 않으면, 상한 문장이 모든 판에 걸린 것으로 읽힌다: "
                        + formula);
    }

    @DisplayName("응답이 실측이 아님과 노드 한 대를 스스로 밝힌다")
    @Test
    void the_response_states_its_own_limits() {
        assertEquals(BenchmarkRules.LIMITATION, service.compare("main", "heavytail").limitation());
    }

    // ── 유휴 역산 복원 점검표 ─────────────────────────────────

    /**
     * 판에서 나올 수 있는 <b>실질 한계 전력의 최댓값</b>을 재서 계약 상수에 못 박는다.
     *
     * <p>한계 전력 점수의 분모는 노드 정격에서 고정 W 눈금 S로 바뀌었다({@code PowerModel}). 그 변경이
     * "일률 편향 하나만 고친다"가 되려면 판 안에서 {@code realMarginalPower > S}인 지점이 새로
     * 생기지 않아야 한다 — 생기면 점수가 0으로 포화해 노드를 구별하지 못하는 다른 결함이 섞인다.
     * 그래서 결과를 보기 전에 최댓값을 재어 {@link BenchmarkRules#MAX_REAL_MARGINAL_WATTS}로 적고,
     * 이 테스트가 그 수가 실측과 같은지 지킨다. 판·생성식·정격 원장이 바뀌면 여기가 먼저 깨진다.</p>
     *
     * <p>요구 코어는 상상하지 않고 <b>실제 워크로드 파일</b>이 쓰는 값만 본다 — 판에 없는 요구량으로
     * 잰 최댓값은 판의 사실이 아니다.</p>
     */
    @DisplayName("등록된 판 전 지점·전 노드의 실질 한계 전력 최댓값이 계약 상수와 같다")
    @Test
    void the_maximum_real_marginal_power_on_the_boards_matches_the_contract_constant() {
        PowerModel powerModel = new PowerModel(new SchedulerProperties());
        Set<Integer> requiredCores = new TreeSet<>();
        for (String location : List.of("classpath:scheduler/benchmark-workloads-heavytail.yml",
                "classpath:scheduler/benchmark-workloads-uniform.yml")) {
            BenchmarkWorkloads.load(location).classes().forEach(c -> requiredCores.add(c.cores()));
        }
        assertFalse(requiredCores.isEmpty(), "워크로드 파일에서 요구 코어를 하나도 읽지 못했다");

        BenchmarkClusters clusters = new BenchmarkClusters();
        double max = 0.0;
        String where = null;
        for (String boardId : clusters.boardIds()) {
            SyntheticCluster cluster = clusters.get(boardId);
            for (long t : PlacementBenchmarkService.sweepPoints(cluster)) {
                for (NodeResource node : PlacementBenchmarkService.nodesAt(cluster, t)) {
                    for (int cores : requiredCores) {
                        double w = powerModel.realMarginalPower(node, cores);
                        if (w > max) {
                            max = w;
                            where = boardId + " " + node.getNodeId() + " " + cores + "코어 t=" + t + "s";
                        }
                    }
                }
            }
        }
        assertEquals(BenchmarkRules.MAX_REAL_MARGINAL_WATTS, max, 1e-6,
                "판의 실질 한계 전력 최댓값이 계약 상수와 다르다 — 실측 " + max + " (" + where + ")");
    }

    /**
     * 판정 자격 두 판에서 배치 점수용 PEI가 1.0에 포화하지 않는다 — 포화는 판정 대상이 아니다.
     *
     * <p>노드 배치 스코어 PEI는 코어 비율(그 노드 코어 수 ÷ 후보 평균 코어 수)을 곱하므로 평균보다 코어가 많은 노드가 1.0에 먼저 닿는다.
     * 비교 코드는 판 스냅샷 전체로 컨텍스트를 만들므로 평균은 그 지점의 {@code nodesAt} 전체로 낸다.
     * 예약은 0부터 코어 전량까지 모두 본다 —
     * 예약이 쌓일수록 PEI가 오르다가 실효 사용률이 100에 잘린 뒤로는 실효 전력만 늘어 내려가므로, 최댓값은 코어 전량 예약이 아닐 수 있다.
     * 이 범위는 실행에서 실제로 생기는 예약보다 넓어, 통과하면 실제 실행에서도 포화가 없다는 충분조건이다.</p>
     */
    @DisplayName("판정 자격 두 판 전 지점·전 계측 노드의 배치용 PEI 는 어떤 예약에서도 1.0 미만이다")
    @Test
    void placement_pei_does_not_saturate_on_the_judgment_boards() {
        PowerModel powerModel = new PowerModel(new SchedulerProperties());
        BenchmarkClusters clusters = new BenchmarkClusters();
        double max = 0.0;
        String where = null;
        int measured = 0;
        for (String boardId : List.of(BenchmarkRules.JUDGMENT_BOARD, BenchmarkRules.DIRECTION_CHECK_BOARD)) {
            SyntheticCluster cluster = clusters.get(boardId);
            for (long t : PlacementBenchmarkService.sweepPoints(cluster)) {
                List<NodeResource> snapshot = PlacementBenchmarkService.nodesAt(cluster, t);
                double meanCores = PowerModel.meanPositiveCores(snapshot);
                for (NodeResource node : snapshot) {
                    if (node.getPowerWatts() <= 0 || node.getPowerCapacityWatts() <= 0) continue;   // 엔진의 전력 계측 판정과 같다
                    measured++;
                    for (int reserved = 0; reserved <= node.getTotalCpu(); reserved++) {
                        NodeResource copy = node.snapshot();
                        copy.reserve(reserved, 0, 0);
                        double pei = powerModel.placementPei(copy, meanCores);
                        if (pei > max) {
                            max = pei;
                            where = boardId + " " + node.getNodeId() + " 예약 " + reserved + "코어 t=" + t + "s";
                        }
                    }
                }
            }
        }
        assertTrue(measured > 0, "판정 자격 판에서 전력 계측 노드를 하나도 읽지 못했다");
        System.out.println("placement PEI max on judgment boards = " + max + " (" + where + ")");
        assertTrue(max < 1.0, "배치용 PEI 가 1.0 에 포화했다 — " + max + " (" + where + ")");
    }

    /**
     * 엔진에게 건네는 노드 스냅샷과 평가자의 오라클이 <b>같은 세계</b>를 본다. 둘이 갈라지면 엔진은 A 세계에서
     * 결정하고 표는 B 세계에서 채점하게 되고, 그 차이가 알고리즘 차이로 읽힌다.
     *
     * <p>볼록 판에서 본다 — β=1인 판은 스냅샷 쪽이 좌결합 원형 분기를 타서 두 경로가 <b>다른 식</b>으로 같은
     * 값을 내지만, β≠1 판은 두 경로가 같은 {@code Math.pow}를 타므로 어긋나면 바로 벌어진다.</p>
     */
    @DisplayName("엔진에게 준 노드 스냅샷의 전력이 오라클의 같은 시각 값과 같다")
    @Test
    void the_node_snapshot_and_the_oracle_describe_the_same_world() {
        SyntheticCluster cluster = new BenchmarkClusters().get(BenchmarkClusters.CONVEX);
        GroundTruthOracle oracle = new GroundTruthOracle(cluster);
        int checked = 0;

        for (long t : PlacementBenchmarkService.sweepPoints(cluster)) {
            for (NodeResource node : PlacementBenchmarkService.nodesAt(cluster, t)) {
                SyntheticCluster.Node n = cluster.node(node.getNodeId()).orElseThrow();
                if (!n.ipmi()) continue;   // 미계측 노드의 스냅샷 전력은 0 — 세계가 아니라 센서의 사실이다
                assertEquals(oracle.nodePowerWatts(n.nodeId(), cluster.cpuUsage(n, t)), node.getPowerWatts(), 1e-9,
                        n.nodeId() + " @ " + t + "s: 엔진이 본 전력과 채점자가 쓴 전력이 다르다");
                checked++;
            }
        }
        assertEquals(BenchmarkRules.SWEEP_POINTS * 14, checked,
                "볼록 판은 14대 전수 계측이라 12지점 × 14대를 전부 봐야 한다 — 수가 다르면 판이 바뀐 것이다");
    }

    /**
     * 엔진의 깨우기 역산을 <b>닫힌 형태 등식</b>으로 잠근다 — 부등식으로 잠그면 다른 지수·다른 어긋남도
     * 통과해 판이 무엇을 재는지가 흐려진다.
     *
     * <p>{@code est = idle − [ (cap−idle)(u − u^β) + δ·cap·u ] / (1−u)},
     * {@code u}는 그 시각의 사용률, {@code δ = 엔진이 믿는 정격/생성식 정격 − 1}. β=1·δ=0이면 우변이
     * 선언값 그대로가 되어 항등이 되고, β≠1이거나 δ≠0인 판에서는 그 항등이 깨진다.</p>
     */
    @DisplayName("등록된 판 전 행·전 지점에서 깨우기 역산이 닫힌 형태와 같다")
    @Test
    void the_wakeup_reconstruction_matches_the_closed_form_on_every_board() {
        BenchmarkClusters clusters = new BenchmarkClusters();

        for (String boardId : clusters.boardIds()) {
            SyntheticCluster cluster = clusters.get(boardId);
            List<Long> sweep = PlacementBenchmarkService.sweepPoints(cluster);
            assertEquals(BenchmarkRules.SWEEP_POINTS, sweep.size(),
                    boardId + ": 훑는 지점이 사전 등록 수와 다르다 — 이 시험의 최솟값·최댓값이 응답과 다른 표본에서 나온다");
            Board board = service.compare(boardId, "heavytail").boards().get(0);
            assertFalse(board.wakeupReconstructionCheck().isEmpty(),
                    boardId + ": 점검표가 비었다 — 12지점 내내 유휴인 노드가 없으면 통합을 잴 자리도 없다");

            for (WakeupReconstruction row : board.wakeupReconstructionCheck()) {
                SyntheticCluster.Node n = cluster.node(row.nodeId()).orElseThrow();
                String at = boardId + "/" + row.nodeId() + ": ";

                if (!row.measured()) {
                    assertEquals(row.engineFallbackWatts(), row.engineWakeupMinWatts(), 1e-9,
                            at + "미계측 노드는 역산할 분자·분모가 없어 값이 대체값이어야 한다");
                    assertEquals(row.engineFallbackWatts(), row.engineWakeupMaxWatts(), 1e-9, at);
                    continue;
                }
                double min = Double.POSITIVE_INFINITY;
                double max = Double.NEGATIVE_INFINITY;
                for (long t : sweep) {
                    double expected = closedFormWakeup(cluster, n, t);
                    min = Math.min(min, expected);
                    max = Math.max(max, expected);
                }
                assertEquals(min, row.engineWakeupMinWatts(), 1e-9, at + "역산 최솟값이 닫힌 형태와 다르다");
                assertEquals(max, row.engineWakeupMaxWatts(), 1e-9, at + "역산 최댓값이 닫힌 형태와 다르다");
            }
        }
    }

    /**
     * 지수가 1이고 판이 준 정격이 생성식 정격과 같은 판에서는 역산이 <b>선언값을 정확히 되돌린다</b> —
     * 대수적 항등이라 코드가 깨지지 않는 한 실패하지 않는다. 이 항등이 있는 판에서 격차가 줄어드는 것은
     * 상한이지 개선의 증거가 아니고, 그 사실을 응답의 생성식 문구가 함께 밝힌다.
     */
    @DisplayName("선형·정격 일치 판에서는 역산이 선언된 유휴 전력과 같다")
    @Test
    void on_the_linear_boards_the_reconstruction_is_an_identity() {
        for (String boardId : List.of(BenchmarkClusters.MAIN, BenchmarkClusters.ADVERSARIAL, BenchmarkClusters.SPARSE)) {
            Board board = service.compare(boardId, "heavytail").boards().get(0);
            int measuredRows = 0;
            for (WakeupReconstruction row : board.wakeupReconstructionCheck()) {
                if (!row.measured()) continue;
                measuredRows++;
                String at = boardId + "/" + row.nodeId() + ": ";
                assertEquals(row.declaredIdleWatts(), row.engineWakeupMinWatts(), 1e-9, at + "최솟값");
                assertEquals(row.declaredIdleWatts(), row.engineWakeupMaxWatts(), 1e-9, at + "최댓값");
            }
            assertTrue(measuredRows > 0, boardId + ": 계측 유휴 행이 없으면 이 항등이 아무것도 보지 않는다");
        }
    }

    /**
     * 볼록 판에서는 같은 역산이 <b>선언값보다 작다</b> — 엔진이 빈 노드를 실제보다 싸게 보고 더 자주 깨운다.
     * 역산이 항등식이 아님을 숫자로 보이는 자리다.
     */
    @DisplayName("볼록 판에서는 역산이 선언된 유휴 전력보다 작다")
    @Test
    void on_the_convex_board_the_reconstruction_undershoots() {
        SyntheticCluster cluster = new BenchmarkClusters().get(BenchmarkClusters.CONVEX);
        Board board = service.compare(BenchmarkClusters.CONVEX, "heavytail").boards().get(0);
        int measuredRows = 0;
        for (WakeupReconstruction row : board.wakeupReconstructionCheck()) {
            if (!row.measured()) continue;
            measuredRows++;
            assertTrue(row.engineWakeupMaxWatts() < row.declaredIdleWatts(),
                    row.nodeId() + ": 역산 최댓값 " + row.engineWakeupMaxWatts() + "W 가 선언값 "
                            + row.declaredIdleWatts() + "W 보다 작지 않다 — 볼록 판인데 항등이 살아 있다"
                            + " (12지점 최소 사용률 " + minSweepCpu(cluster, row.nodeId()) + "%)");
        }
        assertTrue(measuredRows > 0, "볼록 판에 계측 유휴 행이 없다");
    }

    /**
     * 정격만 어긋난 판에서는 부호가 갈린다 — 엔진이 정격을 과대 믿으면 역산이 선언값보다 작고,
     * 과소 믿으면 크다. 방향이 노드마다 반대라는 것이 이 판이 볼록 판과 다른 요인이라는 증거다.
     */
    @DisplayName("정격 어긋남 판에서는 역산이 믿음의 부호를 따라 갈린다")
    @Test
    void on_the_misrated_board_the_reconstruction_follows_the_sign_of_the_belief() {
        SyntheticCluster cluster = new BenchmarkClusters().get(BenchmarkClusters.MISRATED);
        Board board = service.compare(BenchmarkClusters.MISRATED, "heavytail").boards().get(0);
        int over = 0;
        int under = 0;

        for (WakeupReconstruction row : board.wakeupReconstructionCheck()) {
            if (!row.measured()) continue;
            SyntheticCluster.Node n = cluster.node(row.nodeId()).orElseThrow();
            String at = row.nodeId() + ": ";
            String cpu = " (12지점 최소 사용률 " + minSweepCpu(cluster, row.nodeId()) + "%)";
            if (n.believedCapacityWatts() > n.capacityWatts()) {
                over++;
                assertTrue(row.engineWakeupMaxWatts() < row.declaredIdleWatts(),
                        at + "과대 믿음 노드인데 역산이 선언값보다 작지 않다" + cpu);
            } else {
                under++;
                assertTrue(row.engineWakeupMinWatts() > row.declaredIdleWatts(),
                        at + "과소 믿음 노드인데 역산이 선언값보다 크지 않다" + cpu);
            }
        }
        assertTrue(over > 0 && under > 0, "부호가 한쪽뿐이면 이 판은 어긋남이 아니라 정격 이동을 잰다");
    }

    /**
     * 행 선정이 <b>판의 회계 규칙</b>이라 엔진 노브를 돌려도 행 집합이 흔들리지 않는다. 값은 다르다 —
     * 가동 판정 문턱을 유휴 노드 아래로 내리면 그 노드들이 "가동"으로 보여 역산을 포기하고 대체값으로
     * 돌아간다. 두 방향을 함께 보아야 "노브 무관"이 행에 대한 말이지 값에 대한 말이 아님이 분명해진다.
     */
    @DisplayName("점검표의 행은 엔진 노브와 무관하고 값은 문턱 아래에서 대체값으로 돌아간다")
    @Test
    void the_check_rows_are_independent_of_the_engine_knob_but_the_values_are_not() {
        List<WakeupReconstruction> base = rowsOf(service, BenchmarkClusters.CONVEX);
        List<WakeupReconstruction> wide = rowsOf(serviceWithActiveCpuPercent(40.0), BenchmarkClusters.CONVEX);
        List<WakeupReconstruction> narrow = rowsOf(serviceWithActiveCpuPercent(1.0), BenchmarkClusters.CONVEX);

        assertEquals(nodeIds(base), nodeIds(wide), "가동 문턱을 올려도 행 집합이 달라졌다 — 행을 엔진 노브로 고르고 있다");
        assertEquals(nodeIds(base), nodeIds(narrow), "가동 문턱을 내려도 행 집합은 그대로여야 한다");
        assertEquals(base, wide, "유휴 노드가 여전히 문턱 아래라 값도 그대로여야 한다");

        int fellBack = 0;
        for (int i = 0; i < base.size(); i++) {
            WakeupReconstruction a = base.get(i);
            WakeupReconstruction b = narrow.get(i);
            if (!a.measured()) continue;
            if (Math.abs(b.engineWakeupMaxWatts() - b.engineFallbackWatts()) < 1e-9) fellBack++;
        }
        assertTrue(fellBack > 0,
                "가동 문턱을 유휴 노드 아래로 내렸는데 대체값으로 돌아간 행이 하나도 없다 — 역산 경로가 문턱을 보지 않는다");
        assertNotEquals(base, narrow, "문턱 아래로 내렸는데 값이 그대로다");
    }

    /**
     * 같은 값을 두 자리에 싣는 것은 중복이지만 의도된 중복이다 — 역산 경로를 탔는지는 엔진 안의 일이라
     * 비교 코드가 셀 수 없어서, 대체값을 나란히 실어 독자가 값을 대조하게 한다.
     * 두 자리가 갈리면 그 대조가 거짓말이 된다.
     */
    @DisplayName("점검표의 대체값이 유휴 가정 표의 엔진 가정과 행마다 같다")
    @Test
    void the_fallback_column_matches_the_idle_assumption_table() {
        for (String boardId : new BenchmarkClusters().boardIds()) {
            Board board = service.compare(boardId, "heavytail").boards().get(0);
            Map<String, Double> assumed = board.idleAssumptionGaps().stream()
                    .collect(Collectors.toMap(IdleGap::nodeId, IdleGap::engineAssumedIdleWatts));

            for (WakeupReconstruction row : board.wakeupReconstructionCheck()) {
                assertEquals(assumed.get(row.nodeId()), row.engineFallbackWatts(), 1e-9,
                        boardId + "/" + row.nodeId() + ": 두 표의 대체값이 다르면 나란히 실은 대조가 거짓이 된다");
            }
        }
    }

    /**
     * 12지점에서 이 노드가 지나는 <b>최소 사용률</b>(%). 역산은 그 순간의 사용률에 걸린 값이고 {@code 1−u}로
     * 나누므로, 부등호가 깨졌을 때 어느 사용률에서 깨졌는지가 먼저 필요하다 — 실패 메시지에 함께 싣는다.
     */
    private static double minSweepCpu(SyntheticCluster cluster, String nodeId) {
        SyntheticCluster.Node n = cluster.node(nodeId).orElseThrow();
        return PlacementBenchmarkService.sweepPoints(cluster).stream()
                .mapToDouble(t -> cluster.cpuUsage(n, t)).min().orElseThrow();
    }

    /** 닫힌 형태 (β·δ를 함께 넣은 역산). 테스트가 엔진 코드를 부르지 않고 판 파일 값만으로 다시 계산한다. */
    private static double closedFormWakeup(SyntheticCluster cluster, SyntheticCluster.Node n, long t) {
        double cap = n.capacityWatts();
        double idle = n.idleWatts();
        double delta = n.believedCapacityWatts() / cap - 1.0;
        double u = cluster.cpuUsage(n, t) / 100.0;
        return idle - ((cap - idle) * (u - Math.pow(u, cluster.powerExponent())) + delta * cap * u) / (1.0 - u);
    }

    private static List<WakeupReconstruction> rowsOf(PlacementBenchmarkService target, String boardId) {
        return target.compare(boardId, "heavytail").boards().get(0).wakeupReconstructionCheck();
    }

    private static List<String> nodeIds(List<WakeupReconstruction> rows) {
        return rows.stream().map(WakeupReconstruction::nodeId).toList();
    }

    private static PlacementBenchmarkService serviceWithActiveCpuPercent(double activeCpuPercent) {
        SchedulerProperties props = new SchedulerProperties();
        props.getPower().setActiveCpuPercent(activeCpuPercent);
        return new PlacementBenchmarkService(
                new EngineRegistry(List.of(new WeightedScoreScheduler(), new PowerAwareScheduler(),
                        new TemperatureAwareScheduler())),
                props, new BenchmarkClusters(), new PowerModel(props), new ThermalModel(props),
                PlacementBenchmarkService.BASELINE_ORDER);
    }

    // ── 도움 메서드 ───────────────────────────────────────────

    /** 응답의 모든 수치를 "판/부하율/전원/전략/지표/통계" 키로 펼친다. 결정론 비교와 NaN 검사에 쓴다. */
    private static Map<String, Double> flatten(PlacementBenchmarkResponse response) {
        Map<String, Double> flat = new LinkedHashMap<>();
        for (Board b : response.boards()) {
            for (IdleGap g : b.idleAssumptionGaps()) {
                String base = b.boardId() + "/gap/" + g.nodeId() + "/";
                flat.put(base + "declaredIdleWatts", g.declaredIdleWatts());
                flat.put(base + "gapWatts", g.gapWatts());
                flat.put(base + "wattsPerCore", g.generatorWattsPerCore());
            }
            for (ThermalRiseScale r : b.thermalRiseScaleCheck()) {
                String base = b.boardId() + "/rise/" + r.nodeId() + "/";
                flat.put(base + "engineC", r.engineRisePerCoreC());
                flat.put(base + "engineK", r.engineRisePerCoreIfKelvin());
                flat.put(base + "generator", r.generatorRisePerCoreC());
            }
            for (Cell c : b.cells()) {
                String cellKey = b.boardId() + "/" + c.loadRatioPercent() + "/" + c.powerControl() + "/";
                flat.put(cellKey + "requestedWorkloads", c.requestedWorkloads());
                flat.put(cellKey + "requestedCores", c.requestedCores());
                flat.put(cellKey + "headroomCores", c.headroomCores());
                flat.put(cellKey + "activeNodesBefore", c.activeNodesBefore());
                for (StrategyResult s : c.strategies()) {
                    for (MetricValue m : s.metrics()) {
                        String key = cellKey + s.strategyId() + "/" + m.key() + "/";
                        if (m.median() != null) flat.put(key + "median", m.median());
                        if (m.min() != null) flat.put(key + "min", m.min());
                        if (m.max() != null) flat.put(key + "max", m.max());
                        flat.put(key + "samples", (double) m.samples());
                        // 지점별 값은 짝이 아닌 지점이 null이라 그대로 넣으면 유한성 검사가 NPE로 죽는다.
                        // 값이 있는 지점만 인덱스와 함께 넣어 두면 지점이 밀렸는지도 결정론 비교가 잡는다.
                        if (m.points() != null) {
                            for (int i = 0; i < m.points().size(); i++) {
                                Double at = m.points().get(i);
                                if (at != null) flat.put(key + "p" + i, at);
                            }
                        }
                    }
                }
            }
        }
        return flat;
    }

    private static Cell cell(Board board, int loadRatio, String powerControl) {
        return board.cells().stream()
                .filter(c -> c.loadRatioPercent() == loadRatio && powerControl.equals(c.powerControl()))
                .findFirst().orElseThrow(() -> new AssertionError(loadRatio + "% " + powerControl + " 셀이 없다"));
    }

    private static Cell pick(List<Cell> cells, String powerControl) {
        return cells.stream().filter(c -> powerControl.equals(c.powerControl())).findFirst().orElseThrow();
    }

    /** 총량 짝 조건의 기준 집합 밖 열 — override 열과 스케줄 배치 순서 열(BenchmarkRules.PAIRING_RULE). */
    private static boolean outsideBaseSet(StrategyResult s) {
        return !s.engineConfigOverride().isEmpty() || BenchmarkRules.SCHEDULE_ORDER_COLUMNS.contains(s.strategyId());
    }

    private static StrategyResult strategy(Cell cell, String id) {
        return cell.strategies().stream().filter(s -> s.strategyId().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError(id + " 전략이 없다"));
    }

    private static MetricValue metric(StrategyResult s, String key) {
        return s.metrics().stream().filter(m -> m.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError(s.strategyId() + " 에 " + key + " 지표가 없다"));
    }

    private static Double median(StrategyResult s, String key) {
        return metric(s, key).median();
    }

    private static List<Double> medians(Cell cell, String key) {
        List<Double> values = new ArrayList<>();
        for (StrategyResult s : cell.strategies()) values.add(median(s, key));
        return values;
    }

    /** 접두가 맞는 열(전력 엔진 변종)은 제외한 중앙값 목록. */
    private static List<Double> mediansExcept(Cell cell, String key, String excludedPrefix) {
        List<Double> values = new ArrayList<>();
        for (StrategyResult s : cell.strategies()) if (!s.strategyId().startsWith(excludedPrefix)) values.add(median(s, key));
        return values;
    }
}
