package com.innogrid.aims.scheduler.benchmark;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import com.innogrid.aims.scheduler.benchmark.BenchmarkMetric.Direction;
import com.innogrid.aims.scheduler.benchmark.BenchmarkMetric.Role;

/**
 * 배치 비교의 <b>판정 규칙</b> — 결과보다 먼저 고정한다.
 *
 * <p>이 클래스가 커밋 이력에서 결과 코드보다 <b>앞에 있는 것</b>이 사전 등록의 증거다.
 * 기술적으로 이 클래스는 아무것도 참조하지 않아 어디에 놓아도 컴파일된다 — 그래서
 * <b>순서가 유일한 증거</b>이고, 계약 테스트가 이 상수들을 고정해 나중에 조용히 바뀌는 것을 막는다.</p>
 *
 * <p><b>승자를 지명하는 필드를 응답에 두지 않는다.</b> API 가 승자를 계산해 주면 그 필드만 인용되고,
 * 판 이름·전원 상태 가정·부하율에서 분리된 순간 그 판정은 거짓이 된다. 대신 셀마다
 * {@code valid}·{@code reason} 을 하네스가 계산해 싣고, 판정은 사람이 판 이름과 함께 쓰게 한다.</p>
 */
public final class BenchmarkRules {

    private BenchmarkRules() {
    }

    // ── 실행 격자 ─────────────────────────────────────────────

    /**
     * 부하율 네 지점(%). 30% 에서는 전 전략이 100% 성공해 표가 평평한 선이 되고,
     * 110%(수용 불가 부하)가 없으면 성공률이 전략을 구분하지 못한다.
     */
    public static final List<Integer> LOAD_RATIOS = List.of(30, 60, 90, 110);

    /**
     * 부하율의 <b>분모 정의</b>. 총 코어가 아니라 <b>정책 상한까지의 여유</b>다 —
     * 노드마다 구속 자원이 다르므로(어떤 노드는 메모리가, 어떤 노드는 CPU 가 먼저 막힌다)
     * 두 축의 최솟값을 쓴다. 이 문구도 상수이고 응답에 그대로 싣는다.
     */
    public static final String HEADROOM_DEFINITION =
            "Σ min( cores × (정책 CPU 상한% − 실효 CPU%) / 100, "
                    + "memoryMb × (정책 메모리 상한% − 실효 메모리%) / 100 / 코어당메모리MB )";

    /**
     * 한 주기를 훑는 시각 지점 수. 시각을 하나로 고정하면 결정론적 전략은 셀마다 숫자가
     * <b>하나</b>뿐이라 분산 추정치가 0 이 되고, "3.2% 낮았다"에 오차 막대를 붙일 수 없다.
     * 지점마다 같은 스냅샷·같은 워크로드를 주어 <b>페어 차분</b>으로 표본을 만든다.
     */
    public static final int SWEEP_POINTS = 12;

    /**
     * 무작위 전략의 시드 목록. 시드 하나는 <b>표본 1개</b>라 격차를 주장할 수 없다.
     * 재현성은 "시드가 하나"에서 오지 않고 <b>"목록이 상수로 선언되어 커밋되어 있다"</b>에서 온다.
     * 홀수라 중앙값이 잘 정의된다.
     */
    public static final List<Long> RANDOM_SEEDS =
            IntStream.rangeClosed(1, 31).mapToObj(Long::valueOf).toList();

    /** 열 히스테리시스는 시계열 운영의 성질이라 단발 비교에 넣을 근거가 없다. */
    public static final int COOLDOWN_MINUTES_FOR_BENCHMARK = 0;

    /** 워크로드 메모리 = 코어 × 이 값. 이 값을 틀리면 전력이 아니라 메모리 산술을 측정한다. */
    public static final int MEMORY_MB_PER_CORE = 4096;

    // ── 지표 ─────────────────────────────────────────────────

    public static final String PLACEMENT_RATE = "placementRatePercent";
    public static final String GROUND_TRUTH_POWER = "groundTruthPowerW";
    public static final String PER_WORKLOAD_POWER = "perWorkloadPowerW";
    public static final String ENGINE_BELIEVED_COST = "engineBelievedCostW";
    public static final String ACTIVE_NODE_DELTA = "activeNodeDelta";
    public static final String WARN_ZONE_NODES = "warnZoneNodeCount";
    public static final String C3_INTERVENTIONS = "c3InterventionCount";
    public static final String TEMP_STDDEV = "measuredTempStdDev";
    public static final String UNMEASURED_RATE = "unmeasuredPlacementRate";
    public static final String UNMEASURED_SPREAD = "unmeasuredRateSpreadPercentPoints";

    /**
     * 사전 등록된 지표 전량. 응답이 이 목록을 그대로 싣는다.
     *
     * <p><b>최고 온도가 없는 이유</b> — 배치 1건의 온도 상승분은 열관성 계수 × 코어 비율 × 기준 온도라
     * 16코어 노드에 2코어를 얹으면 약 +1.4℃ 인데, 시각에 따른 자연 변동이 ±4.5℃ 다(배치 효과보다 3배 크다).
     * 게다가 제약 C3 가 "배치 후 예상 온도 &gt; 임계"를 막으므로 <b>제약이 켜진 전략은 최고 온도가 전부
     * 임계 이하로 잘린다</b> — 한 비트짜리 열이 된다. 대신 경고 구간 노드 수·제약 개입 횟수·표준편차를 쓴다.</p>
     */
    public static final List<BenchmarkMetric> METRICS = List.of(
            new BenchmarkMetric(PLACEMENT_RATE, "배치 성공률", "%",
                    Direction.HIGHER_IS_BETTER, Role.PRIMARY,
                    "항상 유효. 단 분모(요구 총량)가 전략과 무관하게 고정되어야 한다"),

            new BenchmarkMetric(GROUND_TRUTH_POWER, "총전력(지상진실)", "W",
                    Direction.LOWER_IS_BETTER, Role.PRIMARY,
                    "전 전략의 배치 건수가 같을 때만 총량을 비교한다. 건수가 다르면 건당 값을 쓴다"),

            new BenchmarkMetric(PER_WORKLOAD_POWER, "건당 전력(지상진실)", "W/건",
                    Direction.LOWER_IS_BETTER, Role.PRIMARY,
                    "배치 건수가 다를 때의 대체 지표. 배치 0건이면 null"),

            new BenchmarkMetric(ENGINE_BELIEVED_COST, "엔진이 믿은 비용", "W",
                    Direction.CONTEXT_ONLY, Role.SECONDARY,
                    "전력 엔진 총점의 한 항을 그대로 합한 값이라 순위 근거로 쓸 수 없다. "
                            + "지상진실과의 차가 모델 오사양의 크기다"),

            new BenchmarkMetric(ACTIVE_NODE_DELTA, "가동 노드 증감", "대",
                    Direction.LOWER_IS_BETTER, Role.PRIMARY,
                    "유휴 노드가 충분히 많은 판에서만 판별력이 있다. 유휴가 한 대뿐이면 0/1 이진값이 된다"),

            new BenchmarkMetric(WARN_ZONE_NODES, "경고 구간 노드 수", "대",
                    Direction.LOWER_IS_BETTER, Role.PRIMARY,
                    "계측 노드가 있을 때만. 미계측 노드는 온도 판단 근거가 없어 셈에서 뺀다"),

            new BenchmarkMetric(C3_INTERVENTIONS, "온도 제약 개입 횟수", "건",
                    Direction.LOWER_IS_BETTER, Role.PRIMARY,
                    "온도 제약이 꺼진 전략에서는 0 이 나오므로 그 전략과는 비교하지 않는다"),

            new BenchmarkMetric(TEMP_STDDEV, "계측 온도 표준편차", "℃",
                    Direction.LOWER_IS_BETTER, Role.SECONDARY,
                    "계측 노드가 둘 이상일 때만. 평균은 배치로 거의 안 움직이지만 분산은 움직인다"),

            new BenchmarkMetric(UNMEASURED_RATE, "미계측 노드 배치 비율", "%",
                    Direction.CONTEXT_ONLY, Role.HONESTY,
                    "미계측 노드는 전력·온도 지표에 기여하지 않으므로, 이 비율이 전략마다 다르면 "
                            + "전력·온도 비교의 유효성이 떨어진다"),

            new BenchmarkMetric(UNMEASURED_SPREAD, "미계측 비율의 전략 간 최대 격차", "%p",
                    Direction.CONTEXT_ONLY, Role.HONESTY,
                    "이 값이 크면 전력 순위가 알고리즘이 아니라 센서 공백의 산물일 수 있다"));

    // ── 판정 ─────────────────────────────────────────────────

    /**
     * 총량 비교가 유효한가 — <b>전 전략의 배치 건수가 같을 때만</b>.
     *
     * <p>적게 배치하고 전력을 덜 쓰는 것은 승리가 아니다. 건수가 다르면 총량 열을
     * {@code valid=false} 로 내리고 건당 값으로 비교한다.</p>
     */
    public static boolean totalsComparable(Collection<Integer> placedByStrategy) {
        return placedByStrategy != null && !placedByStrategy.isEmpty()
                && placedByStrategy.stream().distinct().count() == 1;
    }

    /** 총량을 비교할 수 없을 때 셀에 적는 사유. */
    public static String totalsIncomparableReason(Map<String, Integer> placedByStrategy) {
        int min = placedByStrategy.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        int max = placedByStrategy.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return String.format("전략 간 배치 건수 상이 (%d~%d건) — 총량 대신 건당 값으로 비교한다", min, max);
    }

    /**
     * 미계측 비율의 전략 간 격차가 이 값(%p)을 넘으면 전력·온도 비교의 유효성을 의심한다.
     *
     * <p>이 임계로 <b>판정을 저장하지 않는다</b> — 임계가 바뀌면 이미 캡처된 자료가 어느 규칙으로
     * 판정된 것인지 알 수 없게 된다. 격차는 측정치로 싣고, 이 상수는 해석 문장을 만들 때만 쓴다.</p>
     */
    public static final double UNMEASURED_SPREAD_CAUTION_PP = 10.0;

    /** 미계측 격차가 임계를 넘을 때 셀이 스스로 만드는 해석 문장. 각주에 의존하지 않는다. */
    public static String unmeasuredCaution(double spreadPercentPoints, String highest, String lowest) {
        return String.format(
                "미계측 노드 배치 비율이 전략 간 %.1f%%p 차이난다 (%s 최고, %s 최저) — "
                        + "전력·온도 순위가 알고리즘이 아니라 센서 공백을 반영할 수 있다",
                spreadPercentPoints, highest, lowest);
    }

    /** 응답이 스스로 밝히는 한계. */
    public static final String LIMITATION =
            "가상 클러스터에서 계산한 결과이며 실측이 아니다. 전력·온도는 클러스터 파일의 생성식으로 만든 값이고, "
                    + "지상진실 오라클도 그 생성식을 기준으로 삼으므로 실측 검증이 아니다. "
                    + "배포 환경은 노드가 한 대뿐이라 이 비교가 성립하지 않는다.";
}
