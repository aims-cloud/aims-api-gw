package com.innogrid.aims.scheduler.service;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 스케줄이 온도 한계를 덧씌울 때 만드는 정책 사본이 <b>판단 필드를 하나도 잃지 않는가</b>.
 *
 * <p>사본에 {@code affinityEnabled}가 복사되지 않아, 온도 한계를 지정한 스케줄에서는 MDC 링크 게이트가
 * 꺼진 채 실행되던 결함을 고정한다. 정책의 다른 필드가 그대로 옮겨지는 것도 함께 확인한다. 사본은
 * {@code SchedulingPolicy.detachedCopy()}가 만들므로 선언된 필드 전량을 반사로 대조하는 테스트를 하나 둔다.</p>
 */
@SpringBootTest
class ScheduleOverridePolicyCopyTest {

    @Autowired
    ScheduleExecutionService executionService;

    @DisplayName("온도 한계를 지정한 스케줄의 정책 사본은 affinityEnabled 를 유지한다")
    @Test
    void policy_copy_keeps_affinity_enabled() {
        SchedulingPolicy policy = policy();
        policy.setAffinityEnabled(true);
        Schedule schedule = new Schedule("s1", "s1", "0 0 2 * * ?");
        schedule.setMaxTemperatureCelsius(70.0);

        SchedulingPolicy copy = executionService.applyOverrides(policy, schedule);

        assertNotSame(policy, copy, "영속되지 않는 사본이어야 한다");
        assertTrue(copy.isAffinityEnabled(), "게이트가 사본에서 사라지면 링크 반영이 조용히 꺼진다");
        assertEquals(70.0, copy.getMaxTemperatureCelsius());
        assertEquals(80.0, policy.getMaxTemperatureCelsius(), "원본은 그대로");
    }

    @DisplayName("사본은 정책의 다른 판단 필드도 그대로 옮긴다")
    @Test
    void copy_carries_other_decision_fields() {
        SchedulingPolicy policy = policy();
        Schedule schedule = new Schedule("s2", "s2", "0 0 2 * * ?");
        schedule.setMaxTemperatureCelsius(65.0);

        SchedulingPolicy copy = executionService.applyOverrides(policy, schedule);

        assertEquals(Workload.ResourceType.GPU, copy.getResourceType());
        assertEquals("power", copy.getDefaultEngine());
        assertEquals(0.4, copy.getCpuWeight());
        assertEquals(0.1, copy.getNetworkWeight());
        assertEquals(85.0, copy.getMaxCpuUsagePercent());
        assertTrue(copy.isPowerAwareEnabled());
        assertFalse(copy.isTemperatureAwareEnabled());
        assertFalse(copy.isAffinityEnabled(), "원본이 false 면 false");
    }

    @DisplayName("온도 한계가 없으면 원본을 그대로 쓴다")
    @Test
    void no_thermal_limit_uses_original() {
        SchedulingPolicy policy = policy();
        Schedule schedule = new Schedule("s3", "s3", "0 0 2 * * ?");

        assertSame(policy, executionService.applyOverrides(policy, schedule));
    }

    /**
     * 선언된 필드를 반사로 훑어 사본이 온도 한계 말고는 원본과 같은지 대조한다. 나중에 판단 필드가 늘어났을 때
     * 사본이 따라오지 않으면 이 테스트가 실패한다.
     */
    @DisplayName("사본은 온도 한계를 빼면 선언된 모든 필드가 원본과 같다")
    @Test
    void override_copy_matches_original_except_thermal_limit() {
        SchedulingPolicy policy = distinctive();
        Schedule schedule = new Schedule("s4", "s4", "0 0 2 * * ?");
        schedule.setMaxTemperatureCelsius(66.0);

        SchedulingPolicy copy = executionService.applyOverrides(policy, schedule);

        Map<String, Object> expected = fields(policy);
        Map<String, Object> actual = fields(copy);
        // id와 createdAt은 영속 계층의 몫이고, 온도 한계는 스케줄 값으로 덮어쓴 것이 맞다
        for (String excluded : new String[]{"id", "createdAt", "maxTemperatureCelsius"}) {
            expected.remove(excluded);
            actual.remove(excluded);
        }
        assertEquals(expected, actual, "필드가 늘어났는데 사본이 따라오지 않았다");
        assertTrue(expected.size() >= 17, "필드가 줄었다면 이 테스트가 무엇을 지키는지 다시 봐야 한다: " + expected.keySet());
        assertEquals(66.0, copy.getMaxTemperatureCelsius());
        assertEquals(71.0, policy.getMaxTemperatureCelsius(), "원본은 그대로");
    }

    /** 모든 필드를 기본값과 다른 값으로 채운 정책 — 기본값을 그대로 두면 옮기지 않아도 통과해 버린다. */
    private static SchedulingPolicy distinctive() {
        SchedulingPolicy p = new SchedulingPolicy("override-원본", "반사 대조용");
        p.setResourceType(Workload.ResourceType.CONTAINER);
        p.setDefaultEngine("temperature");
        p.setWarningTemperatureCelsius(61.5);
        p.setCpuWeight(0.31);
        p.setMemoryWeight(0.29);
        p.setPowerWeight(0.19);
        p.setTemperatureWeight(0.13);
        p.setNetworkWeight(0.08);
        p.setMaxCpuUsagePercent(77.5);
        p.setMaxMemoryUsagePercent(82.5);
        p.setMaxTemperatureCelsius(71.0);
        p.setMaxPowerUsagePercent(88.0);
        p.setPowerAwareEnabled(false);
        p.setTemperatureAwareEnabled(false);
        p.setAffinityEnabled(true);
        p.setActive(false);
        return p;
    }

    /** 선언된 인스턴스 필드 전량의 값. 합성 필드와 정적 필드는 뺀다. */
    private static Map<String, Object> fields(SchedulingPolicy policy) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Field field : SchedulingPolicy.class.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            try {
                values.put(field.getName(), field.get(policy));
            } catch (IllegalAccessException e) {
                throw new AssertionError("필드를 읽을 수 없다: " + field.getName(), e);
            }
        }
        return values;
    }

    private static SchedulingPolicy policy() {
        SchedulingPolicy p = new SchedulingPolicy("copy-test", "d");
        p.setResourceType(Workload.ResourceType.GPU);
        p.setDefaultEngine("power");
        p.setCpuWeight(0.4);
        p.setNetworkWeight(0.1);
        p.setMaxCpuUsagePercent(85.0);
        p.setMaxTemperatureCelsius(80.0);
        p.setPowerAwareEnabled(true);
        p.setTemperatureAwareEnabled(false);
        return p;
    }
}
