package com.innogrid.aims.scheduler.benchmark;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 설정 사본이 Power·Thermal·Network의 <b>getter 전량</b>에서 원본과 같다 — 필드를 하나 빠뜨리면 변종 열이 "노브 하나만 다른 설정"이
 * 아니게 된다. 반사로 훑으므로 필드가 늘면 이 테스트가 먼저 실패한다.
 */
class BenchmarkEngineConfigsTest {

    @DisplayName("설정 사본은 세 하위 클래스의 getter 전량에서 원본과 같고 조정한 노브만 다르다")
    @Test
    void copy_preserves_every_getter_and_applies_only_the_tweak() throws Exception {
        SchedulerProperties base = new SchedulerProperties();
        base.getPower().setIdleRatio(0.41); base.getPower().setMarginalScaleWatts(777.0); base.getPower().setCapacityByNode(Map.of("n1", 640.0));
        base.getThermal().setInertiaK(0.22);
        // 네 노브 전부를 기본값 밖으로 둔다 — 하나라도 기본값이면 사본이 그 줄을 빠뜨려도 반사 대조가 통과한다
        base.getNetwork().setLinkWeight(0.61);
        base.getNetwork().setLatencyScaleMs(123.0);
        base.getNetwork().setDegradedPenalty(0.77);
        base.getNetwork().setUnknownLinkScore(0.31);

        SchedulerProperties copy = BenchmarkEngineConfigs.copyOf(base);
        int checked = 0;
        checked += assertSameGetters(base.getPower(), copy.getPower());
        checked += assertSameGetters(base.getThermal(), copy.getThermal());
        checked += assertSameGetters(base.getNetwork(), copy.getNetwork());
        assertTrue(checked >= 24, "getter 를 " + checked + "개만 훑었다 — 반사가 깨졌다");
        assertNotSame(base.getPower().getCapacityByNode(), copy.getPower().getCapacityByNode(), "맵은 사본이어야 한다");

        SchedulerProperties tuned = BenchmarkEngineConfigs.withPower(base, p -> p.setMarginalScaleWatts(250.0));
        assertEquals(250.0, tuned.getPower().getMarginalScaleWatts(), 1e-9);
        assertEquals(777.0, base.getPower().getMarginalScaleWatts(), 1e-9, "원본은 건드리지 않는다");
        assertEquals(0.41, tuned.getPower().getIdleRatio(), 1e-9, "조정하지 않은 노브는 원본 값 그대로");
    }

    private static int assertSameGetters(Object a, Object b) throws Exception {
        int n = 0;
        for (Method m : a.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || m.getDeclaringClass() == Object.class) continue;
            if (!(m.getName().startsWith("get") || m.getName().startsWith("is"))) continue;
            assertEquals(m.invoke(a), m.invoke(b), a.getClass().getSimpleName() + "." + m.getName() + " 이 사본에서 다르다 — copyOf 에 빠진 필드");
            n++;
        }
        return n;
    }
}
