package com.innogrid.aims.scheduler.benchmark;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

import com.innogrid.aims.scheduler.benchmark.PlacementStrategy.Kind;
import com.innogrid.aims.scheduler.benchmark.PlacementStrategy.PolicyVariant;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;

/**
 * 대조군 배치 규칙 — 우리 엔진과 <b>비교할 상대</b>.
 *
 * <p>다섯 종 모두 {@link PlacementStrategy} 를 돌려주고 {@code SchedulingEngine} 을 구현하지 않는다.
 * 이유는 {@link PlacementStrategy} 클래스 주석에 있다(레지스트리 자동 수집 · cron 실배치).
 * 이 클래스에는 스프링 애너테이션이 하나도 없고, 인스턴스로 만들 수도 없다.</p>
 *
 * <pre>
 * ffd-size           크기 내림차순, nodeId 순 첫 적격 노드        점수 1/(1+순번)
 * ffd-priority-size  우선순위→크기, nodeId 순 첫 적격 노드        점수 1/(1+순번)
 * pabfd              argmin marginalPower (고전 선형)            점수 1 − min(1, ΔP/정격)
 * bfd                잔여 용량 최소 (전력 무지)                  점수 1 − 잔여CPU/총CPU
 * random             적격 목록에서 균일 추출                     점수 0
 * </pre>
 *
 * <p><b>세 가지를 다섯 종 모두가 똑같이 한다.</b></p>
 * <ol>
 *   <li><b>제약 파이프라인</b>: {@code context.exclusionReason} 을 후보 <b>전원</b>에 적용한다.
 *       대조군이 우리 엔진보다 나쁘게 나오는 것이 "제약을 덜 받았기 때문"이면 비교가 아니라 반칙이다.</li>
 *   <li><b>후보 전원의 {@link NodeScore}</b>: 적격 여부와 제외 사유를 그대로 싣는다. 그래야 테스트가
 *       전략 간 제외 사유 집합을 대조해 "같은 파이프라인을 통과했다"를 증명할 수 있다.</li>
 *   <li><b>주사 순서는 {@code nodeId} 오름차순</b>: 선착순·최적적합의 결과가 저장소 응답 순서나 파일
 *       순서에 흔들리면 그건 알고리즘이 아니라 입력 순서를 측정하는 것이다. 정렬을 각 placer 안에서
 *       한다 — 하네스가 정렬해 주기를 기대하면 언젠가 빠진다.</li>
 * </ol>
 *
 * <p><b>{@code candidateScores} 는 총점 순이 아니라 nodeId 순</b>이다. 대조군은 총점으로 고르지
 * 않으므로(pabfd 는 절대 와트로 고르고 정규화 점수는 동률이 잘 난다) 총점 내림차순으로 늘어놓으면
 * 첫 줄이 선택된 노드인 것처럼 읽힌다. 주사 순서 그대로 두는 편이 오해가 없다.</p>
 *
 * <p><b>{@code powerScore}·{@code temperatureScore}·{@code networkScore} 칸은 0 이다.</b> 대조군은
 * 그 세 차원을 계산하지 않는다. {@code NodeScore} 의 칸이 원시 {@code double} 이라 "모른다"를 넣을
 * 수 없으므로, 0 이 "포화"나 "최악"으로 읽히지 않게 {@code details} 에 {@link #UNSCORED_DIMENSIONS}
 * 표시를 함께 싣는다. {@code cpuScore}·{@code memoryScore} 는 실효 여유를 그대로 담는다 — 다섯 종
 * 전부가 실제로 이 값에 의존한다(적격 판정이 여기서 나온다).</p>
 */
public final class BaselinePlacers {

    private BaselinePlacers() {
    }

    // ── 워크로드 순서 ──────────────────────────────────────────

    /**
     * 크기만 내림차순 — <b>순수 FFD</b>(first-fit-decreasing). 우선순위를 보지 않는다.
     *
     * <p>{@code ScheduleExecutionService.BY_PRIORITY_THEN_SIZE} 와 일부러 다르다. 그쪽은 공정성을
     * 지키려고 우선순위를 첫 기준으로 두는 <b>운영</b> 비교자이고, 이쪽은 빈 패킹 문헌의 FFD 그대로다.
     * 두 순서가 배치 결과를 얼마나 바꾸는지가 측정 대상이므로 섞으면 안 된다.</p>
     *
     * <p>마지막 기준을 이름으로 둔 이유: 이 비교에 쓰는 워크로드는 영속되지 않아
     * {@code createdAt} 이 전부 {@code null} 이다. 운영 비교자처럼 생성 시각을 마지막 기준으로 두면
     * 동률이 풀리지 않아 정렬이 입력 순서에 좌우된다.</p>
     */
    public static final Comparator<Workload> BY_SIZE_DESC =
            Comparator.comparingInt((Workload w) -> w.getRequiredCpu()).reversed()
                    .thenComparing(Comparator.comparingInt((Workload w) -> w.getRequiredMemoryMb()).reversed())
                    .thenComparing(Workload::getName, Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * 우선순위 → 크기 내림차순. 운영 비교자와 같은 <b>기준 순서</b>를 쓰되 마지막 동률 기준만 이름이다.
     *
     * <p>우선순위가 첫 기준이면 큰 워크로드를 먼저 채우는 FFD 의 조각화 이점이 얼마나 남는지가
     * {@link #BY_SIZE_DESC} 와의 차이로 드러난다.</p>
     */
    public static final Comparator<Workload> BY_PRIORITY_THEN_SIZE_DESC =
            Comparator.comparingInt((Workload w) -> w.getPriority().ordinal()).reversed()
                    .thenComparing(BY_SIZE_DESC);

    // ── details 키 ────────────────────────────────────────────

    /** 대조군이 전력·열·네트워크 차원을 계산하지 않았다는 표시. 세 칸의 0 을 "측정값"으로 읽지 않게 한다. */
    public static final String UNSCORED_DIMENSIONS = "전력열네트워크미계산";

    private static final String RANK = "순번";
    private static final String MARGINAL_WATTS = "한계전력W";
    private static final String CAPACITY_WATTS = "정격W";
    private static final String FREE_CPU = "잔여CPU";
    private static final String TOTAL_CPU = "총CPU";
    private static final String ELIGIBLE_COUNT = "적격수";

    private static final String NO_CANDIDATES = "후보 노드가 없습니다";

    // ── 전략 다섯 종 ──────────────────────────────────────────

    /** 선착순(크기 내림차순) — 점수를 보지 않고 nodeId 순 첫 적격 노드를 집는다. */
    public static PlacementStrategy ffdBySize() {
        String label = "선착순(크기 내림차순)";
        return new PlacementStrategy("ffd-size", label, Kind.BASELINE, PolicyVariant.BASE,
                BY_SIZE_DESC, (workload, context) -> placeFirstFit(workload, context, label));
    }

    /** 선착순(우선순위→크기) — 노드 선택 규칙은 {@link #ffdBySize} 와 같고 워크로드 순서만 다르다. */
    public static PlacementStrategy ffdByPriorityThenSize() {
        String label = "선착순(우선순위→크기)";
        return new PlacementStrategy("ffd-priority-size", label, Kind.BASELINE, PolicyVariant.BASE,
                BY_PRIORITY_THEN_SIZE_DESC, (workload, context) -> placeFirstFit(workload, context, label));
    }

    /**
     * 전력 인지 최적적합(PABFD) — <b>문헌 기준선</b>. 배치로 늘어나는 전력이 가장 적은 노드를 고른다.
     *
     * <p><b>반드시 {@link PowerModel#marginalPower} 를 쓴다</b> — 정격과 요구 코어 비율만 보는 고전
     * 선형 모델이라 <b>빈 노드를 깨우는 비용이 없다</b>. {@link PowerModel#realMarginalPower} 를 쓰면
     * 유휴 전력 가산이 들어가 그건 우리 엔진의 판단이 되고, 대조군은 우리 모델을 조금 바꾼 것이 되어
     * 비교의 의미가 사라진다. 두 수식이 갈리는 지점이 곧 우리 기여이므로 대조군은 갈라진 반대편을
     * 그대로 지켜야 한다.</p>
     */
    public static PlacementStrategy pabfd(PowerModel powerModel) {
        String label = "전력 인지 최적적합(문헌 기준선)";
        return new PlacementStrategy("pabfd", label, Kind.BASELINE, PolicyVariant.BASE,
                BY_SIZE_DESC, (workload, context) -> placePabfd(workload, context, powerModel, label));
    }

    /**
     * 자원 최밀집(BFD) — 잔여 용량이 가장 적은 적격 노드. <b>전력을 전혀 보지 않는다.</b>
     *
     * <p>통합(consolidation)의 자원 쪽 상한선이다. 전력 지표에서 우리 엔진이 이 대조군을 이기지
     * 못하면 "전력을 봤다"는 주장의 근거가 없다.</p>
     */
    public static PlacementStrategy bfd() {
        String label = "자원 최밀집(전력 무지)";
        return new PlacementStrategy("bfd", label, Kind.BASELINE, PolicyVariant.BASE,
                BY_SIZE_DESC, (workload, context) -> placeBfd(workload, context, label));
    }

    /**
     * 무작위(고정 시드) — 적격 목록에서 균일 추출. 어떤 지표든 이것보다는 나아야 하는 <b>하한선</b>이다.
     *
     * <p>난수원은 <b>인스턴스 필드</b>로 두고 전략 인스턴스마다 새로 만든다. {@code static}
     * 이면 앞선 실행이 소비한 난수만큼 뒤 실행의 추첨 열이 밀려, 같은 시드로도 결과가 재현되지 않는다.
     * 재현성은 시드 값이 아니라 "누가 그 난수열을 소비하는가"에서 온다.</p>
     *
     * <p>난수원은 {@link BenchmarkRules#randomFor(long)} 에서 받는다 — {@code new Random(seed)} 를
     * 쓰면 시드 1~31 의 첫 뽑기가 <b>전부 같은 값</b>이 되어 시드를 늘려도 표본이 늘지 않는다.
     * 그 이유는 해당 메서드의 설명에 있다.</p>
     *
     * <p>워크로드 순서는 {@code null}(도착 순서)이다. 무작위 전략에 정렬을 붙이면 순서 효과와 추첨
     * 효과가 섞인다.</p>
     */
    public static PlacementStrategy random(long seed) {
        String label = "무작위(고정 시드)";
        RandomGenerator random = BenchmarkRules.randomFor(seed);
        return new PlacementStrategy("random", label, Kind.BASELINE, PolicyVariant.BASE,
                null, (workload, context) -> placeRandom(workload, context, random, seed, label));
    }

    // ── 배치 규칙 ─────────────────────────────────────────────

    private static SchedulingResult placeFirstFit(Workload workload, PlacementContext context, String label) {
        List<Candidate> candidates = screen(workload, context);
        if (candidates.isEmpty()) return SchedulingResult.failure(NO_CANDIDATES);

        Candidate chosen = null;
        for (Candidate c : candidates) {
            if (c.eligible()) {
                chosen = c;
                break;   // 첫 적격 노드에서 멈춘다 — 뒤를 보지 않는 것이 선착순의 정의다
            }
        }
        if (chosen == null) return noEligible(label, candidates);

        String reason = String.format(
                "[%s] nodeId 순 %d번째 후보 %s — 점수를 보지 않고 첫 적격 노드를 집는다 (후보 %d개, 적격 %d개)",
                label, chosen.index() + 1, chosen.node().getNodeId(), candidates.size(), eligibleCount(candidates));

        // 순번은 nodeId 순 전체 목록에서의 위치다(0부터). 앞의 몇 대를 건너뛰었는지가 그대로 보인다.
        return assemble(candidates, chosen, reason,
                c -> new Scored(1.0 / (1 + c.index()), details(RANK, (double) c.index())));
    }

    private static SchedulingResult placePabfd(Workload workload, PlacementContext context,
                                               PowerModel powerModel, String label) {
        List<Candidate> candidates = screen(workload, context);
        if (candidates.isEmpty()) return SchedulingResult.failure(NO_CANDIDATES);

        int requiredCpu = workload.getRequiredCpu();
        Candidate chosen = null;
        double best = Double.MAX_VALUE;
        for (Candidate c : candidates) {
            if (!c.eligible()) continue;
            double delta = powerModel.marginalPower(c.node(), requiredCpu);
            // 부등호가 strict 라 동률이면 앞선 후보가 남는다 — 목록이 nodeId 순이므로 동률은 nodeId 순
            if (delta < best) {
                best = delta;
                chosen = c;
            }
        }
        if (chosen == null) return noEligible(label, candidates);

        String reason = String.format(
                "[%s] 한계전력 최소 %s (%.1fW) — 고전 선형 모델이라 빈 노드를 깨우는 비용을 세지 않는다 "
                        + "(후보 %d개, 적격 %d개)",
                label, chosen.node().getNodeId(), best, candidates.size(), eligibleCount(candidates));

        // 정규화 점수는 정격이 다른 노드끼리도 동률이 잘 난다(ΔP/정격 = 0.7 × 요구코어/총코어).
        // 선택은 절대 와트로 하고, 그 와트를 details 에 남겨야 표에서 근거를 되짚을 수 있다.
        return assemble(candidates, chosen, reason, c -> {
            double delta = powerModel.marginalPower(c.node(), requiredCpu);
            double capacity = c.node().getPowerCapacityWatts();
            double score = capacity > 0 ? 1.0 - Math.min(1.0, delta / capacity) : 1.0;
            return new Scored(score, details(MARGINAL_WATTS, delta, CAPACITY_WATTS, capacity));
        });
    }

    private static SchedulingResult placeBfd(Workload workload, PlacementContext context, String label) {
        List<Candidate> candidates = screen(workload, context);
        if (candidates.isEmpty()) return SchedulingResult.failure(NO_CANDIDATES);

        Candidate chosen = null;
        for (Candidate c : candidates) {
            if (!c.eligible()) continue;
            if (chosen == null || tighter(c.node(), chosen.node())) {
                chosen = c;
            }
        }
        if (chosen == null) return noEligible(label, candidates);

        String reason = String.format(
                "[%s] 잔여 CPU 최소 %s (%.2f코어) — 전력을 보지 않고 자원만 밀집시킨다 (후보 %d개, 적격 %d개)",
                label, chosen.node().getNodeId(), chosen.node().availableCpu(),
                candidates.size(), eligibleCount(candidates));

        return assemble(candidates, chosen, reason, c -> {
            NodeResource n = c.node();
            double free = n.availableCpu();
            double score = n.getTotalCpu() > 0 ? clamp01(1.0 - free / n.getTotalCpu()) : 0.0;
            return new Scored(score, details(FREE_CPU, free, TOTAL_CPU, (double) n.getTotalCpu()));
        });
    }

    /**
     * 잔여 용량이 더 적은가 — {@code availableCpu} 최소, 동률은 {@code availableMemoryMb} 최소.
     * 그래도 동률이면 {@code false} 를 돌려 앞선 후보(nodeId 가 작은 쪽)를 남긴다.
     */
    private static boolean tighter(NodeResource candidate, NodeResource incumbent) {
        int byCpu = Double.compare(candidate.availableCpu(), incumbent.availableCpu());
        if (byCpu != 0) return byCpu < 0;
        return candidate.availableMemoryMb() < incumbent.availableMemoryMb();
    }

    private static SchedulingResult placeRandom(Workload workload, PlacementContext context,
                                                RandomGenerator random, long seed, String label) {
        List<Candidate> candidates = screen(workload, context);
        if (candidates.isEmpty()) return SchedulingResult.failure(NO_CANDIDATES);

        List<Candidate> eligible = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c.eligible()) eligible.add(c);
        }
        if (eligible.isEmpty()) return noEligible(label, candidates);

        // 추첨 대상은 nodeId 순 적격 목록이다. 목록 순서가 흔들리면 같은 시드로도 다른 노드가 나온다.
        Candidate chosen = eligible.get(random.nextInt(eligible.size()));

        String reason = String.format("[%s] 시드 %d — 적격 %d개 중 %s 추첨 (후보 %d개)",
                label, seed, eligible.size(), chosen.node().getNodeId(), candidates.size());

        // 점수를 0 으로 둔다. 무작위에는 "이 노드가 좋다"는 근거가 없어서, 숫자를 채우면 없는 근거를
        // 만들어 내는 것이 된다. 추첨의 폭(적격수)만 남긴다.
        double drawPool = eligible.size();
        return assemble(candidates, chosen, reason,
                c -> new Scored(0.0, details(ELIGIBLE_COUNT, drawPool)));
    }

    // ── 공통 ─────────────────────────────────────────────────

    /**
     * 후보 하나를 제약 파이프라인에 통과시킨 결과.
     *
     * @param node            후보 노드
     * @param index           nodeId 오름차순 목록에서의 위치(0부터)
     * @param exclusionReason 제외 사유. 적격이면 {@code null}
     */
    private record Candidate(NodeResource node, int index, String exclusionReason) {
        boolean eligible() {
            return exclusionReason == null;
        }
    }

    /** 후보 하나의 총점과 판단 근거. */
    private record Scored(double score, Map<String, Double> details) {
    }

    @FunctionalInterface
    private interface Basis {
        Scored of(Candidate candidate);
    }

    /**
     * 후보 전원을 nodeId 오름차순으로 세우고 제약 파이프라인을 적용한다.
     *
     * <p>정렬을 여기서 하는 이유는 클래스 주석에 있다 — 저장소 응답 순서가 결과를 바꾸면
     * 측정 대상이 알고리즘이 아니게 된다.</p>
     */
    private static List<Candidate> screen(Workload workload, PlacementContext context) {
        List<NodeResource> ordered = new ArrayList<>(context.nodes());
        ordered.sort(Comparator.comparing(NodeResource::getNodeId,
                Comparator.nullsFirst(Comparator.naturalOrder())));

        List<Candidate> candidates = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            NodeResource node = ordered.get(i);
            candidates.add(new Candidate(node, i, context.exclusionReason(workload, node)));
        }
        return candidates;
    }

    /** 후보 전원의 {@link NodeScore} 를 채워 결과를 만든다. */
    private static SchedulingResult assemble(List<Candidate> candidates, Candidate chosen,
                                             String reason, Basis basis) {
        List<NodeScore> scores = new ArrayList<>(candidates.size());
        double finalScore = 0;
        for (Candidate c : candidates) {
            Scored scored = basis.of(c);
            scores.add(nodeScore(c, scored));
            if (c == chosen) finalScore = round(scored.score());
        }
        NodeResource node = chosen.node();
        return new SchedulingResult(true, node.getMdcId(), node.getNodeId(), finalScore, reason, scores);
    }

    private static NodeScore nodeScore(Candidate candidate, Scored scored) {
        NodeResource n = candidate.node();

        Map<String, Double> details = new LinkedHashMap<>(scored.details());
        details.put(UNSCORED_DIMENSIONS, 1.0);

        return new NodeScore(n.getMdcId(), n.getNodeId(),
                round((100.0 - n.effectiveCpuUsagePercent()) / 100.0),
                round((100.0 - n.effectiveMemoryUsagePercent()) / 100.0),
                0, 0, 0,                                   // 전력·열·네트워크는 계산하지 않는다
                round(scored.score()),
                candidate.eligible(), candidate.exclusionReason(), details);
    }

    /**
     * 적격 노드가 없을 때의 실패 결과.
     *
     * <p>첫 제외 사유를 문장에 넣는다 — 실패 셀만 남으면 "왜 전부 막혔는지"를 되짚을 자료가
     * 사유 문장뿐이다.</p>
     */
    private static SchedulingResult noEligible(String label, List<Candidate> candidates) {
        String first = "사유 없음";
        for (Candidate c : candidates) {
            if (c.exclusionReason() != null) {
                first = c.exclusionReason();
                break;
            }
        }
        return SchedulingResult.failure(String.format(
                "[%s] 적격 노드가 없습니다 — 후보 %d개 전부 제외 (첫 사유: %s)",
                label, candidates.size(), first));
    }

    private static int eligibleCount(List<Candidate> candidates) {
        int count = 0;
        for (Candidate c : candidates) {
            if (c.eligible()) count++;
        }
        return count;
    }

    private static Map<String, Double> details(String key, double value) {
        Map<String, Double> map = new LinkedHashMap<>();
        map.put(key, round(value));
        return map;
    }

    private static Map<String, Double> details(String key1, double value1, String key2, double value2) {
        Map<String, Double> map = details(key1, value1);
        map.put(key2, round(value2));
        return map;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** 엔진들과 같은 4자리 반올림. 표에서 두 집단의 숫자 정밀도가 다르면 비교가 지저분해진다. */
    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
