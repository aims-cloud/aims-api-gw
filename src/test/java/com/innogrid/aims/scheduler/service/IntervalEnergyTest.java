package com.innogrid.aims.scheduler.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 구간 에너지 적분기 — 결손과 모집단 변동 앞에서 무엇을 세고 무엇을 세지 않는지.
 *
 * <p>이 테스트의 무게는 정확한 kWh보다 <b>세지 않기로 한 것</b>에 있다. 사다리꼴 자체는 두 줄이면 되지만, 표본이
 * 빈 구간을 직선으로 이어 버리거나 노드가 중간에 등장한 것을 에너지로 세면 값이 조용히 부푼다. 그 세 자리를
 * 값으로 못 박는다.</p>
 */
class IntervalEnergyTest {

    private static final long MINUTE = 60;

    @DisplayName("고른 표본은 손계산과 같은 kWh 를 낸다")
    @Test
    void an_even_series_matches_the_hand_calculation() {
        // 100W를 1시간 동안 — 0.1 kWh. 표본은 1분 간격 61개.
        Map<Long, IntervalEnergy.Sample> flat = new TreeMap<>();
        for (long t = 0; t <= 3600; t += MINUTE) flat.put(t, new IntervalEnergy.Sample(100, false));

        IntervalEnergy.Result r = IntervalEnergy.integrate(Map.of("n1", flat));

        assertEquals(0.1, r.kwh());
        assertEquals(0.0, r.estimatedKwh(), "실측만이면 추정분은 0 이다");
        assertEquals(1.0, r.hours());
        assertEquals(1, r.nodes());
        assertEquals(0, r.gaps());
        assertEquals(0L, r.fromEpoch());
        assertEquals(3600L, r.toEpoch());
    }

    @DisplayName("표본이 하나뿐인 노드는 구간을 만들 수 없어 세지 않는다")
    @Test
    void a_single_sample_cannot_form_an_interval() {
        Map<Long, IntervalEnergy.Sample> one = Map.of(0L, new IntervalEnergy.Sample(500, false));

        IntervalEnergy.Result r = IntervalEnergy.integrate(Map.of("n1", one));

        assertFalse(r.present(), "표본 한 개를 구간으로 펴면 없던 에너지가 생긴다");
        assertNull(r.kwh());
        assertEquals(0, r.nodes());
    }

    @DisplayName("표본이 멀면 잇지 않고 끊는다")
    @Test
    void a_long_gap_is_cut_rather_than_interpolated() {
        // 0~600초는 촘촘하고, 그 뒤 두 시간이 비었다가 다시 나타난다.
        Map<Long, IntervalEnergy.Sample> holed = new TreeMap<>();
        for (long t = 0; t <= 600; t += MINUTE) holed.put(t, new IntervalEnergy.Sample(100, false));
        holed.put(600L + 7200L, new IntervalEnergy.Sample(100, false));

        IntervalEnergy.Result r = IntervalEnergy.integrate(Map.of("n1", holed));

        assertEquals(1, r.gaps(), "먼 두 표본 사이를 끊었다고 세어야 한다");
        // 이었다면 2시간이 더해져 약 0.217 kWh가 된다. 끊으면 10분치만 남는다.
        assertEquals(0.017, r.kwh(), "빈 두 시간을 직선으로 이으면 없던 에너지가 생긴다");
        assertEquals(0.1667, r.hours(), "잰 시간은 10분이다 — 물은 구간이 아니다");
    }

    @DisplayName("구간 도중 등장한 노드는 등장 전 시간을 세지 않는다")
    @Test
    void a_node_that_appears_midway_does_not_backfill() {
        Map<Long, IntervalEnergy.Sample> early = new TreeMap<>();
        for (long t = 0; t <= 3600; t += MINUTE) early.put(t, new IntervalEnergy.Sample(100, false));
        // 늦게 합류한 노드 — 마지막 10분만 있다.
        Map<Long, IntervalEnergy.Sample> late = new TreeMap<>();
        for (long t = 3000; t <= 3600; t += MINUTE) late.put(t, new IntervalEnergy.Sample(100, false));

        Map<String, Map<Long, IntervalEnergy.Sample>> both = new LinkedHashMap<>();
        both.put("n1", early);
        both.put("n2", late);

        IntervalEnergy.Result r = IntervalEnergy.integrate(both);

        assertEquals(2, r.nodes());
        // 노드별로 적분하므로 0.1(1시간) + 0.017(10분) 이다. 합계 시계열을 적분하면 합이 뛴 자리가 에너지로 둔갑한다.
        assertEquals(0.117, r.kwh());
    }

    @DisplayName("실측이 아닌 값에서 온 몫을 따로 센다")
    @Test
    void the_estimated_share_is_counted_apart() {
        Map<Long, IntervalEnergy.Sample> measured = new TreeMap<>();
        Map<Long, IntervalEnergy.Sample> guessed = new TreeMap<>();
        for (long t = 0; t <= 3600; t += MINUTE) {
            measured.put(t, new IntervalEnergy.Sample(100, false));
            guessed.put(t, new IntervalEnergy.Sample(150, true));
        }
        Map<String, Map<Long, IntervalEnergy.Sample>> both = new LinkedHashMap<>();
        both.put("measured", measured);
        both.put("guessed", guessed);

        IntervalEnergy.Result r = IntervalEnergy.integrate(both);

        assertEquals(0.25, r.kwh(), "0.1 + 0.15");
        assertEquals(0.15, r.estimatedKwh(), "추정 노드 몫만 따로 센다");
        assertTrue(r.estimatedKwh() < r.kwh(), "추정분은 전체의 일부다");
    }

    @DisplayName("잴 것이 없으면 값 대신 비어 있다")
    @Test
    void nothing_to_integrate_yields_no_value() {
        assertFalse(IntervalEnergy.integrate(Map.of()).present());
        assertFalse(IntervalEnergy.integrate(null).present());
        assertFalse(IntervalEnergy.integrate(Map.of("n1", Map.<Long, IntervalEnergy.Sample>of())).present());
    }

    @DisplayName("허용 간격은 조회 간격의 두 배까지 늘어난다")
    @Test
    void the_gap_limit_follows_the_query_step() {
        // 5분은 하한이다. 1시간 간격으로 보는 조회에서 하한을 그대로 쓰면 모든 표본 사이가 구멍이 되어
        // 에너지가 통째로 비어 버린다 — 7일을 1시간 간격으로 보는 것은 정상적인 사용이다.
        assertEquals(300, IntervalEnergy.gapLimitFor(60), "간격이 짧으면 하한이 이긴다");
        assertEquals(7200, IntervalEnergy.gapLimitFor(3600), "1시간 간격이면 두 시간까지 잇는다");

        Map<Long, IntervalEnergy.Sample> hourly = new TreeMap<>();
        for (long t = 0; t <= 3 * 3600; t += 3600) hourly.put(t, new IntervalEnergy.Sample(100, false));

        assertFalse(IntervalEnergy.integrate(Map.of("n", hourly)).present(), "하한만 쓰면 전부 끊긴다");
        IntervalEnergy.Result r = IntervalEnergy.integrate(Map.of("n", hourly), IntervalEnergy.gapLimitFor(3600));
        assertTrue(r.present(), "조회 간격을 반영하면 값이 나온다");
        assertEquals(0, r.gaps());
        assertEquals(0.3, r.kwh(), "100W 를 3시간");
    }

    @DisplayName("끊는 기준은 5분이다")
    @Test
    void the_cut_off_is_five_minutes() {
        assertEquals(300, IntervalEnergy.MAX_GAP_SECONDS);

        // 딱 5분이면 잇고, 1초라도 더 멀면 끊는다.
        Map<Long, IntervalEnergy.Sample> edge = new TreeMap<>(Map.of(
                0L, new IntervalEnergy.Sample(100, false), 300L, new IntervalEnergy.Sample(100, false)));
        Map<Long, IntervalEnergy.Sample> over = new TreeMap<>(Map.of(
                0L, new IntervalEnergy.Sample(100, false), 301L, new IntervalEnergy.Sample(100, false)));

        assertEquals(0, IntervalEnergy.integrate(Map.of("n", edge)).gaps());
        assertEquals(1, IntervalEnergy.integrate(Map.of("n", over)).gaps());
        assertFalse(IntervalEnergy.integrate(Map.of("n", over)).present(), "끊고 나면 남는 조각이 없다");
    }
}
