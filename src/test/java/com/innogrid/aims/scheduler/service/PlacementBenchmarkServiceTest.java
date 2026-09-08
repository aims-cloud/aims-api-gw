package com.innogrid.aims.scheduler.service;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.benchmark.BenchmarkClusters;
import com.innogrid.aims.scheduler.benchmark.BenchmarkMetric;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules;
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
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.PowerAwareScheduler;
import com.innogrid.aims.scheduler.engine.TemperatureAwareScheduler;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.model.NodeResource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 배치 비교 하네스가 <b>비교라고 부를 수 있는 것</b>을 내는지 고정한다.
 *
 * <p>여기서 지키는 것은 "우리가 이겼다"가 아니다 — 순위는 판·부하율·전원 상태 가정에 딸린 값이라
 * 시험이 못 박을 대상이 아니다. 대신 <b>비교의 조건</b>을 못 박는다: 같은 입력이면 같은 답이 나오는가,
 * 두 전원 가정이 같은 배치 결정을 회계하는가, 대조군이 운영 경로에 새지 않는가, 평가 기준이 엔진의
 * 목적 함수와 다른가, 그리고 숫자가 언제 의미를 갖는지 응답이 스스로 밝히는가.</p>
 *
 * <p>스프링을 띄우지 않는다 — 하네스는 저장소를 하나도 쓰지 않으므로 손으로 조립할 수 있고,
 * 그 사실 자체를 {@link #harness_takes_no_repository} 가 지킨다.</p>
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
     */
    @DisplayName("엔진의 가동 판정 문턱을 바꿔도 가동 노드 회계가 움직이지 않는다")
    @Test
    void the_powered_on_accounting_ignores_the_engine_threshold() {
        SchedulerProperties tuned = new SchedulerProperties();
        tuned.getPower().setActiveCpuPercent(40.0);   // 기본 5.0 의 여덟 배 — 가동 9대 중 몇 대가 이 아래로 떨어진다
        PlacementBenchmarkService other = serviceWith(tuned);

        Cell base = cell(service.compare("main", "heavytail").boards().get(0), 60, "AVAILABLE");
        Cell moved = cell(other.compare("main", "heavytail").boards().get(0), 60, "AVAILABLE");

        assertTrue(base.activeNodesBefore() > 0, "가동 노드가 0 이면 이 시험이 아무것도 보지 않는다");
        assertEquals(base.activeNodesBefore(), moved.activeNodesBefore(), 1e-9,
                "기준선이 설정을 따라 움직이면 증감이 알고리즘이 아니라 설정의 함수가 된다");
        assertEquals(medians(base, BenchmarkRules.ACTIVE_NODE_DELTA),
                medians(moved, BenchmarkRules.ACTIVE_NODE_DELTA), "가동 노드 증감이 엔진 노브를 따라 움직였다");
        assertEquals(medians(base, BenchmarkRules.GENERATOR_POWER),
                medians(moved, BenchmarkRules.GENERATOR_POWER),
                "전원 제어 판의 통전 집합이 엔진 노브를 따라 움직이면 주 지표가 설정의 함수다");
    }

    /**
     * 반대로 엔진이 <b>스스로 믿은 비용</b>은 엔진 노브를 따라 움직여야 한다 — 그건 엔진의 믿음이다.
     * 하네스가 그것까지 대신 정해 주면 모델 가정과 생성식의 격차를 잴 수 없다. 이 시험이
     * {@code PowerModel.isActive} 를 갈아치우는 잘못된 확대 적용을 막는다.
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

    private static PlacementBenchmarkService serviceWith(SchedulerProperties props) {
        return new PlacementBenchmarkService(
                new EngineRegistry(List.of(new WeightedScoreScheduler(), new PowerAwareScheduler(),
                        new TemperatureAwareScheduler())),
                props, new BenchmarkClusters(), new PowerModel(props), new ThermalModel(props));
    }

    // ── 응답이 자기가 본 설정을 밝힌다 ────────────────────────

    /**
     * {@code policyBasis} 는 "코드 기본값으로 돌렸다"는 말이고, 이 블록은 그 말의 내용이다. 값이 없으면
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
     * <p>{@code GroundTruthOracle} 은 엔진 식({@code inertiaK × T × 코어비율})을 <b>손으로 복제</b>해
     * 갖고 있다. 복제본과 원본을 묶는 시험이 없으면, 나중에 {@code ThermalModel} 의 상승 식을 고쳐도
     * <b>아무 시험도 빨간불이 되지 않고</b> 응답의 그 칸만 조용히 거짓이 된다 — 존재하지 않는 식을
     * "엔진이 이렇게 잰다"고 설명하게 된다. 그래서 여기서 <b>실제 엔진 모델을 불러</b> 대조한다.</p>
     *
     * <p>엔진 식을 바꾸면 이 시험이 먼저 터진다. 그때 할 일은 단언을 고치는 것이 아니라
     * {@code thermalRiseScaleCheck} 가 새 식을 반영하게 하는 것이다.</p>
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
     * <p>비교 대상은 <b>자원전용 제약 해제 변종 하나</b>다. 다른 전략은 전부 온도 제약 C3 를 받고, C3 의
     * "배치 후 예상 온도"가 바로 {@code inertiaK} 를 쓰므로 노브를 돌리면 <b>배치 자체</b>가 바뀐다 —
     * 그 경우 지표가 움직이는 것은 측정이 아니라 결정이 바뀐 것이고, 그것은 옳은 동작이다(실제로
     * {@code ffd-size} 는 13.42 → 11.01 로 움직인다). 측정이 노브에 무관함을 보려면 배치가 노브에 무관한
     * 전략에서 봐야 하고, 그것이 게이트까지 끈 자원전용 변종이다.</p>
     */
    @DisplayName("열관성 계수를 바꿔도 온도 지표가 움직이지 않는다 — 배치가 노브에 무관한 전략에서")
    @Test
    void the_temperature_metrics_do_not_follow_the_engine_inertia_knob() {
        SchedulerProperties tuned = new SchedulerProperties();
        // 기본 0.15 의 두 배. 온도 분포 지표는 총량과 같이 <b>전 전략의 배치 건수가 같은 지점</b>에서만 값이
        // 나오는데, 노브를 서른 배로 돌리면 게이트가 켜진 다른 전략이 저부하에서도 배치에 실패해 어느 셀에도
        // 짝 지점이 남지 않는다 — 그러면 비교할 값 자체가 없다. 두 배면 짝은 남고, 엔진 모델로 재고 있었다면
        // 예약이 있는 노드의 온도가 (1+0.15r)→(1+0.30r) 로 움직여 4자리 반올림한 표준편차가 달라진다.
        // 경고 구간 노드 수는 정수라 작은 흔들림에 우연히 같을 수 있으므로, 이 시험의 힘은 표준편차 쪽에 있다.
        tuned.getThermal().setInertiaK(0.30);
        PlacementBenchmarkService other = serviceWith(tuned);

        // 어느 셀에서 짝이 사라질지는 노브 값에 따라 다르므로 셀을 하나로 못 박지 않고, 두 실행 모두에서 값이
        // 나온 첫 셀을 고른다. 그런 셀이 없으면 시험은 아무것도 안 본 것이라 실패다.
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
        // 지표가 노브를 안 봐도 중앙값이 움직인다. 그 경우는 이 시험의 전제가 깨진 것이라 따로 말한다.
        assertEquals(metric(a, BenchmarkRules.TEMP_STDDEV).samples(), metric(b, BenchmarkRules.TEMP_STDDEV).samples(),
                "두 실행의 짝 지점 수가 다르다 — 중앙값이 달라도 측정이 노브를 따른 것이 아니라 표본이 달라진 것이다");
        assertEquals(median(a, BenchmarkRules.TEMP_STDDEV), median(b, BenchmarkRules.TEMP_STDDEV),
                "열관성 계수가 온도 표준편차를 움직인다 — 평가 기준이 엔진의 가정이라는 뜻이다");
        assertEquals(median(a, BenchmarkRules.WARN_ZONE_NODES), median(b, BenchmarkRules.WARN_ZONE_NODES),
                "열관성 계수가 경고 구간 노드 수를 움직인다");
        // 성공률은 이 변종에서 무효라 median 이 null 이다 — null == null 로 비교하면 아무것도 안 본다.
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
     * 위 시험의 <b>짝</b>이다. 위는 "측정이 노브를 안 따른다"를 보고, 이것은 "<b>제약은 노브를 따라야 한다</b>"를 본다.
     * 둘이 함께 있어야 "노브를 바꿔도 값이 같다"가 <b>평가기가 주입 설정을 무시한 결과</b>가 아님이 증명된다.
     *
     * <p>제약 평가기가 {@code new SchedulerProperties()}(코드 기본값)로 만들어지면 운영에서 환경변수로 덮은
     * 임계값을 통째로 무시하고, 벤치마크가 <b>운영과 다른 조건</b>에서 낸 수치를 연구 근거로 쓰게 된다.
     * 응답만 봐서는 절대 드러나지 않는 종류의 오염이라 여기서 못 박는다.</p>
     *
     * <p>온도 게이트가 켜진 전략은 C3 가 "배치 후 예상 온도"를 {@code inertiaK} 로 계산하므로, 계수를 키우면
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
     * 순위 근거가 아니라고 판정한 칸에 중앙값이 실리면 {@code valid} 를 보지 않은 인용자에게는 그냥 숫자다.
     * 이 PR 이 세운 원칙("각주로 밀어 두면 인용될 때 떨어져 나간다")을 응답이 스스로 어기는 자리였다.
     */
    @DisplayName("순위 근거로 쓸 수 없는 지표는 중앙값·최소·최대를 싣지 않는다")
    @Test
    void an_invalid_metric_carries_no_number() {
        int invalidSeen = 0;
        for (String board : List.of("main", "adversarial", "sparse")) {
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
                        assertNotNull(m.invalidReason(), where + ": 사유 없는 무효는 되짚을 수 없다");
                    }
                }
            }
        }
        assertTrue(invalidSeen > 0, "무효 칸이 하나도 없으면 이 시험이 아무것도 보지 않는다");
    }

    /**
     * 위 시험은 "무효면 숫자가 없다"만 본다 — 무효여야 할 칸이 <b>유효로 둔갑</b>하면 통과해 버린다.
     * 그래서 무효여야 하는 칸 하나를 이름으로 못 박는다: 온도 제약을 끈 변종의 개입 횟수는 항상 0 이고,
     * 그 0 은 값이 아니라 게이트가 없다는 뜻이라 반드시 무효·null 이어야 한다.
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
     * 건당 전력의 무효 판정이 <b>첫 시각 지점 하나</b>로 결정되던 결함. t=0 에 0건이고 다른 지점에 배치한
     * 전략은 정당한 중앙값을 갖는데 "배치 0건" 사유로 통째로 버려졌다. 고친 뒤에는 값이 나온 지점이
     * 하나라도 있으면 유효, 하나도 없으면 무효 — 둘이 정확히 일치해야 한다.
     */
    @DisplayName("건당 전력의 유효성은 값이 나온 지점이 있는지와 정확히 일치한다")
    @Test
    void per_workload_power_validity_matches_whether_any_point_produced_a_value() {
        for (String board : List.of("main", "adversarial", "sparse")) {
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
     * 하네스가 저장소를 하나도 받지 않는다. 이것이 "아무것도 저장하지 않는다"의 <b>구조적</b> 근거다 —
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
        Map<String, Double> first = flatten(service.compare("main", "heavytail"));
        Map<String, Double> second = flatten(service.compare("main", "heavytail"));

        assertEquals(first, second,
                "두 번째 호출의 숫자가 다르다 — 앞 호출이 노드 스냅샷이나 정책을 변이시켰거나 "
                        + "벽시계·난수원 상태가 결과에 새어 들었다");
        assertFalse(first.isEmpty(), "비교할 숫자가 하나도 없다");
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
     * 대해서는 부등식을 느슨하게 두고, <b>적어도 한 전략에서는 실제로 벌어지는지</b>를 따로 단언한다.
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
     * <p>경계를 응답에서 유도한다 — 유휴 노드 수는 {@code nodeCount − activeNodesBefore} 이고 그 노드들이
     * 낼 수 있는 최대치는 {@code idleAssumptionGaps} 의 실제 유휴 전력 중 큰 것들의 합이다. 통전 노드
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
     * 순서 목록과 구현 맵은 서로를 모른다. 목록에만 있는 id 는 {@code null} 이 담겨 한참 뒤 NPE 로 죽고, 맵에만 있는
     * 구현은 열이 <b>조용히</b> 빠진 채 응답이 정상이다. 둘 다 전략 목록을 만드는 자리에서 크게 실패해야 한다 —
     * 대조군을 늘리는 PR 이 바로 그 자리를 건드린다.
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
                        // 레코드 전체 비교 — 중앙값만 보면 min·max·사유만 움직이는 누수가 초록불로 지나간다
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
     */
    @DisplayName("엔진이 믿은 비용과 생성식 기준 전력은 다른 함수다")
    @Test
    void engine_belief_and_ground_truth_are_different_functions() {
        Cell target = cell(service.compare("main", "heavytail").boards().get(0), 30, "UNAVAILABLE");

        for (StrategyResult s : target.strategies()) {
            Double believed = median(s, BenchmarkRules.ENGINE_BELIEVED_COST);
            Double truth = median(s, BenchmarkRules.GENERATOR_POWER);
            assertNotNull(believed, s.strategyId());
            assertNotNull(truth, s.strategyId());
            assertNotEquals(believed, truth, s.strategyId() + ": 두 값이 같다 — 평가 기준이 엔진의 목적 함수다");
        }
    }

    /**
     * 엔진이 믿은 비용에 <b>깨우는 비용이 들어 있다</b>. 이 값은 예약을 잡기 <b>전</b> 상태에서 재야
     * 한다 — 엔진의 가동 판정이 예약분을 반영한 사용률을 보기 때문에, 예약을 먼저 잡으면 그 노드가
     * 이미 가동 중으로 보여 유휴 전력 항이 통째로 사라진다.
     *
     * <p>경계를 응답에서 유도한다: 노드를 더 깨운 전략은 그 차이만큼의 유휴 전력을 더 물어야 하고,
     * 가장 인색하게 잡아도 <b>깨운 노드 수 × 최소 정격 × 엔진 유휴 비율</b> 이다. 측정 순서가 뒤로
     * 밀리면 이 차이가 사라져 경계 아래로 내려간다.</p>
     */
    @DisplayName("엔진이 믿은 비용에 노드를 깨우는 값이 들어 있다")
    @Test
    void the_engine_belief_includes_the_cost_of_waking_a_node() {
        Board board = service.compare("main", "heavytail").boards().get(0);
        Cell target = cell(board, 30, "UNAVAILABLE");
        assertTrue(target.totalsComparable(), "가동 노드 증감이 무효인 셀에서는 이 시험이 성립하지 않는다");

        double minCapacity = board.idleAssumptionGaps().stream()
                .mapToDouble(IdleGap::capacityWatts).min().orElseThrow();
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
     * 엔진 모델과 같은 값이 되어 위 시험이 우연히 통과할 수 있다.
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

    /** NaN·무한대는 0 으로 새어 나가면 "가장 좋은 값"으로 읽힌다. 값이 없으면 null 이어야 한다. */
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

        for (Cell c : board.cells().stream().filter(Cell::totalsComparable).toList()) {
            assertNull(c.totalsIncomparableReason(),
                    "짝이 온전한 셀에 사유가 붙었다: " + c.totalsIncomparableReason());
            for (StrategyResult s : c.strategies()) {
                assertEquals(BenchmarkRules.SWEEP_POINTS, metric(s, BenchmarkRules.GENERATOR_POWER).samples(),
                        s.strategyId() + ": 짝이 온전한 셀인데 표본이 전 지점이 아니다");
            }
        }
    }

    /**
     * 온도 분포 두 지표는 총량과 <b>같은 지점</b>에서만 표본에 들어가야 한다. 생성식 온도는 CPU 의 함수라
     * 덜 배치한 전략은 CPU 를 덜 얹어 덜 뜨겁고 분포도 덜 벌어진다 — 건수가 다른 지점을 그냥 세면 열이
     * 아니라 배치량을 재게 된다. 개입 횟수는 시도당 계수라 성공률과 같이 전 지점을 센다.
     *
     * <p>판 셋을 다 본다 — 짝이 어긋나는 셀이 어느 판에서 나올지는 판의 성질이라 하나만 보면 우연히
     * 게이트가 한 번도 작동하지 않은 채 통과할 수 있다.</p>
     */
    @DisplayName("온도 분포 두 지표는 총량과 같은 지점에서만 세고 개입 횟수는 성공률과 같이 전 지점을 센다")
    @Test
    void the_temperature_distribution_metrics_are_paired_like_the_totals() {
        boolean anyGateFired = false;

        for (String boardId : List.of(BenchmarkClusters.MAIN, BenchmarkClusters.ADVERSARIAL, BenchmarkClusters.SPARSE)) {
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
        assertTrue(anyGateFired, "세 판 어디서도 짝이 어긋난 지점이 없다 — 게이트가 한 번도 작동하지 않아 이 시험이 아무것도 보지 않았다");
    }

    /**
     * 짝 지점이 하나도 없으면 온도 분포 두 지표도 총량처럼 <b>값을 싣지 않고 사유를 적는다</b>. 값이 남아 있으면
     * valid 플래그를 보지 않은 인용자에게는 그냥 숫자다. 그런 셀이 지금 판에 없으면 이 시험은 그 사실을 알린다 —
     * 없다는 것을 조용히 통과로 바꾸지 않는다.
     */
    @DisplayName("짝 지점이 하나도 없는 셀에서는 온도 분포 두 지표도 값 없이 사유만 싣는다")
    @Test
    void with_no_paired_point_the_temperature_distribution_metrics_carry_a_reason_and_no_number() {
        int checked = 0;
        for (String boardId : List.of(BenchmarkClusters.MAIN, BenchmarkClusters.ADVERSARIAL, BenchmarkClusters.SPARSE)) {
            for (Cell c : service.compare(boardId, "heavytail").boards().get(0).cells()) {
                for (StrategyResult s : c.strategies()) {
                    if (metric(s, BenchmarkRules.GENERATOR_POWER).samples() > 0) continue;
                    for (String key : List.of(BenchmarkRules.WARN_ZONE_NODES, BenchmarkRules.TEMP_STDDEV)) {
                        MetricValue m = metric(s, key);
                        String where = boardId + " " + c.loadRatioPercent() + "% " + s.strategyId() + " " + key;
                        assertNull(m.median(), where + ": 짝 지점이 없는데 값이 실렸다");
                        assertEquals(0, m.samples(), where);
                        assertNotNull(m.invalidReason(), where + ": 사유가 없다");
                        assertTrue(m.invalidReason().contains("배치 건수가 같은 시각 지점이 없다"), where + ": " + m.invalidReason());
                    }
                    checked++;
                }
            }
        }
        // 수용 불가 부하(110%)는 판정 규칙이 일부러 넣은 지점이라 세 판 모두 짝 지점 0 인 셀이 있어야 한다. 하나도 없으면
        // 위 단언이 아무것도 안 본 것이므로 통과가 아니라 실패다 — 로그로 남기면 초록불이 "확인함"으로 읽힌다.
        assertTrue(checked > 0, "짝 지점 0 인 셀이 세 판 어디에도 없다 — 110% 부하가 전략을 구분하지 못한다는 뜻이고, 이 시험이 아무것도 보지 않았다");
    }

    /** 시각 지점을 훑었으므로 최솟값 ≤ 중앙값 ≤ 최댓값 이고, 폭이 0 이 아닌 지표가 있어야 한다. */
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

    /** 전수 계측 판에서는 미계측 배치가 0 이고 경고가 붙지 않는다 — 경고가 늘 붙으면 신호가 아니다. */
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
     * 반증판은 우리 엔진이 지도록 설계했다. 이 시험은 <b>지는지</b>를 단언하지 않는다 — 그것은
     * 결과이고, 결과를 시험으로 못 박으면 결과를 고칠 때 시험도 고치게 된다. 대신 <b>판이 설계대로
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
     * 예전 그대로여야 하고, 그렇지 않으면 새 판과의 비교가 정격 어긋남이 아니라 판 전체의 변화를 재게 된다.
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
     * 유휴 격차 표의 <b>열이 이름대로</b> 실렸는지 본다. 전 필드가 {@code double} 이라 열을 하나 끼워 넣으면
     * 뒤의 값들이 한 칸씩 밀려도 컴파일이 통과하고, 그러면 표는 그럴듯한 숫자로 가득 찬 채 거짓이 된다.
     * 오라클이 직접 낸 행과 응답의 행을 열마다 맞춰 본다.
     */
    @DisplayName("유휴 격차 표의 각 열이 오라클의 같은 이름 값과 같다")
    @Test
    void every_idle_gap_column_carries_the_value_of_its_own_name() {
        Board board = service.compare("main", "heavytail").boards().get(0);
        double engineRatio = properties.getPower().getIdleRatio();
        Map<String, com.innogrid.aims.scheduler.benchmark.GroundTruthOracle.IdleGap> expected =
                new com.innogrid.aims.scheduler.benchmark.GroundTruthOracle(new BenchmarkClusters().get("main"))
                        .idleAssumptionGaps(engineRatio).stream()
                        .collect(Collectors.toMap(g -> g.nodeId(), g -> g));

        assertEquals(expected.size(), board.idleAssumptionGaps().size());
        for (IdleGap row : board.idleAssumptionGaps()) {
            var o = Objects.requireNonNull(expected.get(row.nodeId()), row.nodeId() + " 가 오라클에 없다");
            String at = row.nodeId() + ": ";
            assertEquals(o.capacityWatts(), row.capacityWatts(), 1e-9, at + "capacityWatts");
            assertEquals(o.engineBelievedCapacityWatts(), row.engineBelievedCapacityWatts(), 1e-9,
                    at + "engineBelievedCapacityWatts");
            assertEquals(o.declaredIdleWatts(), row.declaredIdleWatts(), 1e-9, at + "declaredIdleWatts");
            assertEquals(o.engineAssumedIdleWatts(), row.engineAssumedIdleWatts(), 1e-9, at + "engineAssumedIdleWatts");
            assertEquals(o.absoluteGapWatts(), row.gapWatts(), 1e-9, at + "gapWatts");
            assertEquals(o.declaredIdleRatio(), row.declaredIdleRatio(), 1e-9, at + "declaredIdleRatio");
            assertEquals(o.generatorWattsPerCore(), row.generatorWattsPerCore(), 1e-9, at + "generatorWattsPerCore");
            assertEquals(row.engineBelievedCapacityWatts() * engineRatio, row.engineAssumedIdleWatts(), 1e-9,
                    at + "엔진 가정은 엔진이 믿는 정격 × 유휴 비율이다");
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
        assertTrue(formula.contains("상한이며 알고리즘 개선의 증거가 아니다"), formula);
    }

    @DisplayName("응답이 실측이 아님과 노드 한 대를 스스로 밝힌다")
    @Test
    void the_response_states_its_own_limits() {
        assertEquals(BenchmarkRules.LIMITATION, service.compare("main", "heavytail").limitation());
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
}
