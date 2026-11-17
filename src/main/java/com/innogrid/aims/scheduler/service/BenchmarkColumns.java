package com.innogrid.aims.scheduler.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.innogrid.aims.scheduler.benchmark.BaselinePlacers;
import com.innogrid.aims.scheduler.benchmark.BenchmarkClusters;
import com.innogrid.aims.scheduler.benchmark.BenchmarkEngineConfigs;
import com.innogrid.aims.scheduler.benchmark.BenchmarkPolicies;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules;
import com.innogrid.aims.scheduler.benchmark.PlacementStrategy;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.EngineConfigSnapshot;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.NetworkKnobs;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.PolicySnapshot;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.PowerKnobs;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.ThermalKnobs;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.SchedulingEngine;
import com.innogrid.aims.scheduler.engine.PowerAwareScheduler;
import com.innogrid.aims.scheduler.engine.model.NetworkModel;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;

/**
 * 배치 전략 비교 서비스에서 떼어 낸 <b>비교 전략 목록 조립</b> — 운영 엔진 전량 · 엔진 쪽 변종 열 · 대조군을 정해진 순서로 세우고,
 * 전략마다 볼 정책 사본과 이 실행이 본 엔진 설정 스냅샷을 만든다.
 *
 * <p>스프링 빈이 아니다. 서비스가 생성자에서 한 번 만들고, 목록은 <b>부를 때마다</b> 다시 조립한다. 생성자는 참조를
 * 저장하고 대조군 순서만 복사한다 — 구현이 없는 대조군 id는 생성이 아니라 첫 계산에서 실패해야 하고(서비스 생성 뒤 비교 호출에서 실패를 확인하는
 * 테스트), 설정(SchedulerProperties)은 생성 뒤에 바뀔 수 있어 복사해 두지 않고 부를 때마다 읽는다.</p>
 */
final class BenchmarkColumns {

    private final EngineRegistry engineRegistry;
    private final SchedulerProperties properties;
    private final PowerModel powerModel;

    /** 전력 엔진 변종 열이 운영과 같은 온도 모델을 쓰도록 넘긴다. */
    private final ThermalModel thermalModel;

    /** 이 실행이 비교에 넣는 대조군 순서. 운영은 항상 {@link #BASELINE_ORDER}다. */
    private final List<String> baselineOrder;

    BenchmarkColumns(EngineRegistry engineRegistry, SchedulerProperties properties, PowerModel powerModel,
                     ThermalModel thermalModel, List<String> baselineOrder) {
        this.engineRegistry = engineRegistry;
        this.properties = properties;
        this.powerModel = powerModel;
        this.thermalModel = thermalModel;
        this.baselineOrder = List.copyOf(baselineOrder);
    }

    /**
     * 대조군의 순서. 응답 열 순서가 실행마다 바뀌면 표를 견줄 수 없다.
     *
     * <p>{@link #strategies()}가 이 목록과 구현 맵의 <b>키 집합이 같은지</b> 검사한다 — 목록에만 있으면 null이
     * 담겨 한참 뒤 NPE로 죽고, 맵에만 있으면 열 하나가 <b>조용히</b> 사라진 채 응답은 정상이다. 둘 다 여기서
     * 크게 실패하게 한다. 테스트가 같은 패키지에서 이 목록을 읽는다.</p>
     */
    static final List<String> BASELINE_ORDER =
            List.of("ffd-size", "ffd-priority-size", "pabfd", "bfd", "coolest-first", "random");

    /**
     * 엔진 쪽 변종 열의 순서 — 대조군의 {@link #BASELINE_ORDER}와 같은 역할. 운영 엔진 전량(레지스트리 정렬) 뒤, 대조군 앞에
     * 이 순서로 선다. {@link #engineVariants()}가 이 목록과 구현 맵의 집합 일치를 검사한다 — 엔진 쪽 열은 그동안 인라인으로
     * 붙여 열이 조용히 빠져도 아무것도 깨지지 않았다.
     */
    static final List<String> ENGINE_VARIANT_ORDER = List.of("resource-only-gated", "resource-only-ungated",
            BenchmarkRules.WEIGHTED_AFFINITY_COLUMN,
            BenchmarkRules.SENSITIVITY_COLUMN_PREFIX + "250", BenchmarkRules.SENSITIVITY_COLUMN_PREFIX + "1000",
            BenchmarkRules.SENSITIVITY_COLUMN_PREFIX + "0", BenchmarkRules.IDLE_RECONSTRUCTION_DISABLED_COLUMN,
            BenchmarkRules.SCHEDULE_ORDER_AGE_COLUMN, BenchmarkRules.SCHEDULE_ORDER_SIZE_COLUMN,
            BenchmarkRules.TEMPERATURE_LIMIT_COLUMN);

    /** 온도 상한 열이 정책 사본에서 바꾸는 노브 이름 — 응답의 {@code engineConfigOverride}와 설정 다이제스트에 같은 이름으로 실린다. */
    static final String TEMPERATURE_LIMIT_KNOB = "maxTemperatureCelsius";

    /** 무작위 전략의 식별자. 이것만 시드를 여러 개 돌린다. */
    static final String RANDOM_ID = "random";

    /**
     * 엔진 쪽 변종 열 하나 — 전략과, 그 열만 다르게 준 노브(이름 → 값). 노브는 응답의 {@code engineConfigOverride}와 설정
     * 다이제스트의 {@code overrides=} 세그먼트에 같은 값으로 실린다. {@code believedCostModel}은 "엔진이 믿은 비용"을 재는
     * 전력 모델 — 노브가 전력 모델 형태를 바꾸는 열은 자기 모델로 재야 그 열이 믿은 값이 된다.
     */
    record EngineVariant(PlacementStrategy strategy, Map<String, String> override, PowerModel believedCostModel) {}

    /**
     * 격차 지표의 <b>분모가 되는 대조군</b>. 엔진 전력식을 공유하는 ΔP 단독 전략이라 이 열이 기준이고, 이 열 자신의 격차는 0이 아니라
     * 값이 없다. 테스트 전용 이음매가 대조군 부분 집합을 줄 수 있으므로 <b>이 실행에 없을 수도 있다</b>.
     */
    static final String PABFD_ID = "pabfd";

    /**
     * 비교할 전략 전부 — 운영 엔진 전량 + 엔진 쪽 변종 열({@link #ENGINE_VARIANT_ORDER} · 스케줄 배치 순서 2열 포함) + 대조군({@link #BASELINE_ORDER}).
     *
     * <p>엔진은 레지스트리가 밝히는 목록에서 가져온다 — 여기에 이름을 적어 두면 엔진이 늘 때 조용히
     * 빠진다. 순서는 식별자로 정렬해 응답 열 순서를 빈 등록 순서에서 떼어 놓는다.</p>
     */
    List<PlacementStrategy> strategies() {
        List<PlacementStrategy> all = new ArrayList<>();

        List<String> engineIds = engineRegistry.describe().stream()
                .map(m -> m.get("id")).sorted().toList();
        for (String id : engineIds) {
            all.add(PlacementStrategy.ofEngine(engineRegistry.resolve(id)));
        }

        for (EngineVariant variant : engineVariants()) all.add(variant.strategy());

        Map<String, PlacementStrategy> baselines = new LinkedHashMap<>();
        baselines.put("ffd-size", BaselinePlacers.ffdBySize());
        baselines.put("ffd-priority-size", BaselinePlacers.ffdByPriorityThenSize());
        baselines.put(PABFD_ID, BaselinePlacers.pabfd(powerModel));
        baselines.put("bfd", BaselinePlacers.bfd());
        baselines.put("coolest-first", BaselinePlacers.coolestFirst());
        baselines.put(RANDOM_ID, BaselinePlacers.random(BenchmarkRules.RANDOM_SEEDS.get(0)));

        // 순서 목록과 구현 맵은 서로를 모른다 — 한쪽에만 있는 id는 여기서 크게 실패해야 한다. 목록에만 있으면
        // null이 담겨 한참 뒤 NPE, 맵에만 있으면 열이 조용히 빠진 채 응답은 200이다. 테스트 전용 이음매로 부분 집합을
        // 줄 수는 있으므로 검사는 "목록 ⊆ 맵"과 "각 id 의 record id 일치"다.
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
        // 참조 비교(==)가 아니라 equals다 — List.copyOf가 같은 인스턴스를 돌려주는 것은 구현 노트일 뿐이라,
        // 참조로 걸면 어느 날 이 검사가 조용히 영구히 꺼진다. 그것이 바로 이 검사가 막으려는 사고다.
        if (BASELINE_ORDER.equals(baselineOrder) && !baselines.keySet().equals(Set.copyOf(BASELINE_ORDER))) {
            throw new IllegalStateException("구현은 있는데 순서 목록에 없는 대조군이 있다 — 열이 조용히 빠진다: "
                    + baselines.keySet() + " vs " + BASELINE_ORDER);
        }

        return all;
    }

    /**
     * 엔진 쪽 변종 열 전부 — {@link #ENGINE_VARIANT_ORDER} 순서. 자원전용 두 변종은 기본 엔진에 정책만 갈아 끼운 것이다 —
     * 가중치를 안 봐도 제약이 막아 주는지, 제약까지 없으면 얼마나 나빠지는지를 갈라 본다. 스케줄 배치 순서 두 열은 기본 엔진에 배치 순서만
     * 운영 비교자로 바꾼 것이다 — 표본 순서로 돌리는 {@code weighted} 열과 견줘 우선순위 정렬과 크기 순 정렬의 효과를 갈라 본다.
     *
     * <p>순서 목록과 구현 맵의 집합이 같아야 한다 — 대조군 쪽 가드와 같은 이유로, 한쪽에만 있는 id는 여기서 크게 실패한다.</p>
     */
    List<EngineVariant> engineVariants() {
        SchedulingEngine weighted = engineRegistry.resolve(EngineRegistry.DEFAULT_ENGINE_ID);
        Map<String, EngineVariant> variants = new LinkedHashMap<>();
        variants.put("resource-only-gated", new EngineVariant(new PlacementStrategy("resource-only-gated", "자원전용(전력·온도 제약 유지)",
                PlacementStrategy.Kind.ENGINE, PlacementStrategy.PolicyVariant.RESOURCE_ONLY_GATED, null, weighted::schedule),
                Map.of(), powerModel));
        variants.put("resource-only-ungated", new EngineVariant(new PlacementStrategy("resource-only-ungated", "자원전용(제약까지 해제)",
                PlacementStrategy.Kind.ENGINE, PlacementStrategy.PolicyVariant.RESOURCE_ONLY_UNGATED, null, weighted::schedule),
                Map.of(), powerModel));
        // 링크 게이트를 켠 가중 점수 — 링크 판에서 weighted와의 차이가 netScore 경로의 효과다. 링크 없는 판에서는 워크로드에
        // dataMdcId가 없어 게이트가 켜지지 않으므로 weighted와 같은 값이다(구조적 사실 — 테스트가 고정한다)
        variants.put(BenchmarkRules.WEIGHTED_AFFINITY_COLUMN, new EngineVariant(new PlacementStrategy(BenchmarkRules.WEIGHTED_AFFINITY_COLUMN,
                "가중 점수(링크 게이트 on · 링크 판에서만 weighted 와 다르다 · 판정 자격 없음)",
                PlacementStrategy.Kind.ENGINE, PlacementStrategy.PolicyVariant.BASE, null, weighted::schedule),
                Map.of("affinityEnabled", "true"), powerModel));

        // 한계 전력 눈금 민감도 3열 — 전력 엔진에 눈금만 다른 전력 모델을 물린다. 믿은 비용도 그 모델로 잰다
        for (double scale : BenchmarkRules.SENSITIVITY_MARGINAL_SCALE_WATTS) {
            String id = BenchmarkRules.SENSITIVITY_COLUMN_PREFIX + (long) scale;
            String meaning = scale <= 0 ? "정격 분모 · 구 방식 · 독립 증거 아님"
                    : scale < BenchmarkRules.MAX_REAL_MARGINAL_WATTS_AT_JUDGMENT ? "포화 발현 · 판정 자격 없음" : "무포화 · 판정 자격 없음";
            variants.put(id, powerVariant(id, "전력 인지(한계 전력 눈금 " + (long) scale + "W · " + meaning + ")",
                    Map.of("marginalScaleWatts", String.valueOf(scale)), p -> p.setMarginalScaleWatts(scale)));
        }
        // 역산 차단 열 — 같은 판·같은 지점에서 역산 유무만 갈린 짝 비교
        variants.put(BenchmarkRules.IDLE_RECONSTRUCTION_DISABLED_COLUMN, powerVariant(BenchmarkRules.IDLE_RECONSTRUCTION_DISABLED_COLUMN,
                "전력 인지(유휴 역산 차단 · 대체값 고정 · 독립 증거 아님)",
                Map.of("idleFromMeasurement", "false"), p -> p.setIdleFromMeasurement(false)));
        // 스케줄 배치 순서 2열 — 기본 엔진에 스케줄 실행의 운영 비교자 객체를 그대로 물린다(복사본이 아니라 같은 객체라 운영 순서가 바뀌면
        // 열도 따라간다). 순서는 엔진 노브가 아니라 override가 없지만 총량 짝 조건의 기준 집합에는 넣지 않는다(BenchmarkRules.PAIRING_RULE)
        variants.put(BenchmarkRules.SCHEDULE_ORDER_AGE_COLUMN, new EngineVariant(new PlacementStrategy(BenchmarkRules.SCHEDULE_ORDER_AGE_COLUMN,
                "가중 점수(스케줄 배치 순서: 우선순위 → 등록 순 · 기준 집합 밖 · 판정 자격 없음)",
                PlacementStrategy.Kind.ENGINE, PlacementStrategy.PolicyVariant.BASE,
                ScheduleExecutionService.BY_PRIORITY_THEN_AGE, weighted::schedule),
                Map.of(), powerModel));
        variants.put(BenchmarkRules.SCHEDULE_ORDER_SIZE_COLUMN, new EngineVariant(new PlacementStrategy(BenchmarkRules.SCHEDULE_ORDER_SIZE_COLUMN,
                "가중 점수(스케줄 배치 순서: 우선순위 → 크기 순 · 기준 집합 밖 · 판정 자격 없음)",
                PlacementStrategy.Kind.ENGINE, PlacementStrategy.PolicyVariant.BASE,
                ScheduleExecutionService.BY_PRIORITY_THEN_SIZE, weighted::schedule),
                Map.of(), powerModel));
        // 온도 상한을 낮춘 열 — 기본 엔진에 기준 정책 사본의 온도 상한 하나만 낮춘다(운영 스케줄 온도 상한과 같은 필드). override 열이라
        // 총량 짝 조건의 기준 집합 밖이다(BenchmarkRules.TEMPERATURE_LIMIT_COLUMN_RULE)
        variants.put(BenchmarkRules.TEMPERATURE_LIMIT_COLUMN, new EngineVariant(new PlacementStrategy(BenchmarkRules.TEMPERATURE_LIMIT_COLUMN,
                "가중 점수(온도 상한 65℃ · 스케줄 온도 상한과 같은 정책 사본 · 판정 자격 없음)",
                PlacementStrategy.Kind.ENGINE, PlacementStrategy.PolicyVariant.BASE, null, weighted::schedule),
                Map.of(TEMPERATURE_LIMIT_KNOB, String.valueOf(BenchmarkRules.LOWERED_TEMPERATURE_LIMIT_CELSIUS)), powerModel));

        if (!variants.keySet().equals(Set.copyOf(ENGINE_VARIANT_ORDER))) {
            throw new IllegalStateException("엔진 변종 순서 목록과 구현이 다르다 — 열이 조용히 빠진다: "
                    + variants.keySet() + " vs " + ENGINE_VARIANT_ORDER);
        }
        List<EngineVariant> ordered = new ArrayList<>(ENGINE_VARIANT_ORDER.size());
        for (String id : ENGINE_VARIANT_ORDER) {
            EngineVariant v = variants.get(id);
            if (!id.equals(v.strategy().id())) throw new IllegalStateException("엔진 변종 맵의 키와 전략 id 가 다르다: " + id + " ≠ " + v.strategy().id());
            ordered.add(v);
        }
        return ordered;
    }

    /**
     * 전력 엔진 변종 열 — 기본 설정 사본에 전력 노브 하나를 바꾼 {@link PowerAwareScheduler}를 새로 조립한다. 열 모델은 온도 모델·
     * 네트워크 모델을 운영 것과 같은 설정으로 만들고 전력 모델만 갈아 끼운다. 스프링 컨텍스트에 등록되지 않는 객체다.
     */
    private EngineVariant powerVariant(String id, String label, Map<String, String> override,
                                       java.util.function.Consumer<SchedulerProperties.Power> tweak) {
        SchedulerProperties tuned = BenchmarkEngineConfigs.withPower(properties, tweak);
        PowerModel model = new PowerModel(tuned);
        PowerAwareScheduler engine = new PowerAwareScheduler(model, thermalModel, new NetworkModel(tuned), tuned);
        return new EngineVariant(new PlacementStrategy(id, label, PlacementStrategy.Kind.ENGINE, PlacementStrategy.PolicyVariant.BASE,
                null, engine::schedule), override, model);
    }

    /** 열별 노브 override — 전략 id → (노브 이름 → 값). 기본 설정 그대로인 열은 없다. */
    Map<String, Map<String, String>> overrides() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (EngineVariant v : engineVariants()) if (!v.override().isEmpty()) out.put(v.strategy().id(), v.override());
        return out;
    }

    /**
     * 이 전략이 볼 정책 — 변종 사본에 열 override를 얹는다. 지금 정책 노브 override는 {@code affinityEnabled}와
     * 온도 상한({@link #TEMPERATURE_LIMIT_KNOB}) 둘이다. 어떤 경우에도 <b>영속되지 않는 사본</b>이다.
     */
    static SchedulingPolicy policyFor(PlacementStrategy strategy, Map<String, String> override) {
        SchedulingPolicy policy = policyFor(strategy.policy());
        boolean affinity = "true".equals(override.get("affinityEnabled"));
        String temperatureLimit = override.get(TEMPERATURE_LIMIT_KNOB);
        if (affinity || temperatureLimit != null) {
            policy = policy.detachedCopy();
            if (affinity) policy.setAffinityEnabled(true);
            if (temperatureLimit != null) policy.setMaxTemperatureCelsius(Double.parseDouble(temperatureLimit));
        }
        return policy;
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
     * <p>정책은 전략이 실제로 쓰는 {@link #policyFor(PlacementStrategy.PolicyVariant)}로 만든다 — 스냅샷을 따로
     * 조립하면 언젠가 두 벌이 갈려 응답이 밝히는 정책과 돌린 정책이 달라진다. 다이제스트의 정규화 문자열은
     * record의 {@code toString}이 아니라 <b>명시적 이름=값 목록</b>이다 — 필드 순서를 바꾸는 리팩터링에
     * 어제 낸 표와 오늘 낸 표가 "다른 설정"으로 보이면 안 된다.</p>
     */
    EngineConfigSnapshot engineConfig() {
        SchedulerProperties.Power p = properties.getPower();
        SchedulerProperties.Thermal t = properties.getThermal();
        SchedulerProperties.Network nw = properties.getNetwork();

        List<String> engineIds = engineRegistry.describe().stream().map(m -> m.get("id")).sorted().toList();
        PowerKnobs power = new PowerKnobs(p.getIdleRatio(), p.getActiveCpuPercent(),
                BenchmarkRules.POWERED_ON_CPU_PERCENT, p.getCurveAlpha(), p.getPeiWeight(),
                p.getWasteWeight(), p.getMarginalWeight(), p.getPeiNormalizer(),
                p.getResourceShare(), p.getDefaultCapacityWatts(),
                p.getCapacityByNode() == null ? Map.of() : new TreeMap<>(p.getCapacityByNode()),
                p.isIdleFromMeasurement(), p.getMarginalScaleWatts());
        ThermalKnobs thermal = new ThermalKnobs(t.getInertiaK(), t.getDefaultWarningCelsius(),
                t.getCooldownMinutes(), BenchmarkRules.COOLDOWN_MINUTES_FOR_BENCHMARK);
        NetworkKnobs network = new NetworkKnobs(nw.getLatencyScaleMs(), nw.getDegradedPenalty(), nw.getLinkWeight(), nw.getUnknownLinkScore());

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
        // 네트워크 노브 — 링크를 선언한 판에서 결과에 반영된다. 없으면 같은 다이제스트로 다른 표가 나올 수 있다
        canonical.add("network=" + network.latencyScaleMs() + "," + network.degradedPenalty() + ","
                + network.linkWeight() + "," + network.unknownLinkScore());
        // 열별 override — 전략 id 오름차순, 노브는 이름 오름차순(TreeMap). 열마다 다른 노브를 쓰면서 다이제스트가 고정되면
        // "응답이 자기 입력을 지목한다"가 거짓이 된다
        List<String> overrideSegments = new ArrayList<>();
        overrides().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            List<String> kv = new ArrayList<>();
            new TreeMap<>(e.getValue()).forEach((k, v) -> kv.add(k + "=" + v));
            overrideSegments.add(e.getKey() + ":" + String.join(",", kv));
        });
        canonical.add("overrides=" + String.join(";", overrideSegments));
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
                engineIds, power, thermal, network, policies);
    }

    /**
     * 비교의 기준 정책 — 코드에 선언된 기본값.
     *
     * <p>활성 정책을 읽지 않는다. 조회 경로가 정책이 없으면 기본 정책을 <b>새로 넣기</b> 때문에 진단
     * 호출이 데이터를 만들게 된다. 그 사실은 응답의 {@code policyBasis}가 밝힌다.</p>
     */
    static SchedulingPolicy baseline() {
        return new SchedulingPolicy("benchmark-base", "배치 비교용 기준 정책 (저장되지 않음)");
    }
}
