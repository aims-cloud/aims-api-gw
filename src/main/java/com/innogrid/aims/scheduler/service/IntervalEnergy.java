package com.innogrid.aims.scheduler.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 구간 에너지 적분 — 시각마다의 전력(W)을 kWh로 모은다.
 *
 * <p><b>무엇을 재료로 쓰는가.</b> 지점 배열이 아니라 <b>0을 채우기 전의 원시 구간 시계열</b>이다. 지점은 전력을
 * 못 읽은 노드를 0으로 채워 만들지만 원시 시계열에는 그 자리가 아예 없다. 소비 전력과 회수 가능 전력 둘 다
 * 이 클래스를 쓰고, 회수 가능 쪽만 시각마다 가동 판정과 유휴 역산을 한 번 더 거친 시계열을 넘긴다.</p>
 *
 * <p>저장소에 {@code avg_over_time}을 묻는 방법도 있었으나 쓰지 않는다. 즉시 질의는 <b>언제나 현재 시각에서</b>
 * 평가되어 어제 구간을 물어도 오늘 구간의 평균이 돌아오고, 시각을 지정할 통로가 클라이언트에 없다.</p>
 *
 * <p><b>노드별로 적분한 뒤 합한다.</b> 합계 시계열을 적분하면 노드가 중간에 등장하거나 사라진 구간에서 그 등장·소멸
 * 자체가 에너지로 둔갑한다. 노드마다 제 표본 사이만 적분하면 그 일이 생기지 않는다.</p>
 *
 * <p><b>구멍은 잇지 않고 끊는다.</b> 사다리꼴은 두 표본을 직선으로 잇는데, 표본이 오래 비면 그 직선이 없던 값을
 * 지어낸다. {@link #MAX_GAP_SECONDS}보다 먼 두 표본 사이는 세그먼트를 끊고 적분에서 뺀다. 그래서 답은 "구간
 * 전체"가 아니라 <b>실제로 표본이 있던 시간</b>의 에너지이고, 그 시간을 함께 돌려준다.</p>
 */
public final class IntervalEnergy {

    /**
     * 표본을 잇는 최대 간격의 <b>하한</b>(초).
     *
     * <p>설정 노브가 아니라 코드 상수다. 노브로 두면 값이 바뀔 때 어제 낸 숫자와 오늘 낸 숫자가 말없이 갈리고,
     * 그걸 막으려면 설정 다이제스트 규율이 따라붙는다. 눈금은 메트릭 저장소의 기본 조회 지연 창과 같은 5분이다.</p>
     *
     * <p><b>실제 허용 간격은 조회 간격에서 나온다.</b> 이 값을 그대로 쓰면 {@code step}을 5분보다 크게 준 조회가
     * 통째로 구멍으로 잘려 에너지가 전부 비어 버린다 — 7일을 1시간 간격으로 보는 것은 정상적인 사용이다.
     * 호출자가 {@code step}의 두 배와 이 하한 중 큰 값을 넘긴다.</p>
     */
    public static final long MAX_GAP_SECONDS = 300;

    /** 조회 간격에 맞춘 허용 간격 — 간격의 두 배까지는 이어 붙인다. 한 지점을 건너뛴 정도는 결손으로 보지 않는다. */
    public static long gapLimitFor(long stepSeconds) {
        return Math.max(MAX_GAP_SECONDS, stepSeconds * 2);
    }

    private IntervalEnergy() {}

    /**
     * 적분 결과.
     *
     * @param kwh          에너지(kWh). 적분할 표본이 없었으면 null
     * @param estimatedKwh 그중 <b>실측이 아닌</b> 값에서 온 몫(kWh). 실측만이면 0.
     *                     두 끝 중 하나라도 추정이면 그 조각을 통째로 추정으로 세므로 추정 구간 끝에서 조금 크게 잡힌다
     * @param fromEpoch    실제로 적분이 걸친 구간의 시작(초). 표본이 없었으면 null
     * @param toEpoch      실제로 적분이 걸친 구간의 끝(초). 표본이 없었으면 null
     * @param hours        한 노드가 덮은 시간 중 가장 긴 것(시). 구멍은 빠져 있다 —
     *                     노드별 시간을 더하지 않는다. 노드가 둘이면 24시간 구간에서 48이 나와 뜻이 없다
     * @param nodes        적분에 쓴 노드 수
     * @param gaps         이어 붙이지 않고 끊은 횟수. 표본이 없었을 수도, 그 시각이 적분 대상이 아니었을 수도 있다
     */
    public record Result(Double kwh, Double estimatedKwh,
                         Long fromEpoch, Long toEpoch, double hours, int nodes, int gaps) {

        static final Result NONE = new Result(null, null, null, null, 0, 0, 0);

        /** 적분할 것이 있었는가. */
        public boolean present() { return kwh != null; }
    }

    /** 한 시각 한 노드의 전력과, 그 값이 실측에서 왔는지. */
    public record Sample(double watts, boolean estimated) {}

    /** 허용 간격을 하한 그대로 두고 적분한다. */
    public static Result integrate(Map<String, ? extends Map<Long, Sample>> byNode) {
        return integrate(byNode, MAX_GAP_SECONDS);
    }

    /**
     * 노드별 시계열을 적분한다.
     *
     * @param byNode         노드 id → (시각(초) → 표본). 비어 있으면 {@link Result#NONE}
     * @param maxGapSeconds  이보다 먼 두 표본은 잇지 않는다. {@link #gapLimitFor}로 조회 간격에서 낸다
     */
    public static Result integrate(Map<String, ? extends Map<Long, Sample>> byNode, long maxGapSeconds) {
        if (byNode == null || byNode.isEmpty()) return Result.NONE;

        double joules = 0;
        double estimatedJoules = 0;
        double longestSeconds = 0;
        int nodes = 0;
        int gaps = 0;
        Long from = null;
        Long to = null;

        for (Map<Long, Sample> series : byNode.values()) {
            if (series == null || series.size() < 2) continue;   // 표본 한 개로는 구간을 만들 수 없다
            List<Long> times = new ArrayList<>(new TreeMap<>(series).keySet());
            double covered = 0;   // 이 노드가 덮은 시간. 노드끼리 더하지 않는다
            for (int i = 1; i < times.size(); i++) {
                long a = times.get(i - 1);
                long b = times.get(i);
                long span = b - a;
                if (span <= 0) continue;
                if (span > maxGapSeconds) { gaps++; continue; }   // 잇지 않는다 — 표본이 없었거나 그 시각이 대상 밖이었다

                Sample sa = series.get(a);
                Sample sb = series.get(b);
                double area = (sa.watts() + sb.watts()) / 2.0 * span;   // 사다리꼴
                joules += area;
                // 두 끝 중 하나라도 실측이 아니면 그 조각은 추정으로 센다 — 섞인 조각을 실측이라 부르지 않는다.
                if (sa.estimated() || sb.estimated()) estimatedJoules += area;
                covered += span;
                from = from == null ? a : Math.min(from, a);
                to = to == null ? b : Math.max(to, b);
            }
            if (covered > 0) {
                nodes++;
                longestSeconds = Math.max(longestSeconds, covered);
            }
        }

        if (nodes == 0) return new Result(null, null, null, null, 0, 0, gaps);
        return new Result(kwh(joules), kwh(estimatedJoules), from, to,
                round(longestSeconds / 3600.0, 4), nodes, gaps);
    }

    /** W·초 → kWh. 소수 3자리 — 모의 클러스터 규모(수백 W · 24시간)에서 세 자리면 변화가 보인다. */
    private static double kwh(double joules) {
        return round(joules / 3_600_000.0, 3);
    }

    private static double round(double v, int digits) {
        double scale = Math.pow(10, digits);
        return Math.round(v * scale) / scale;
    }
}
