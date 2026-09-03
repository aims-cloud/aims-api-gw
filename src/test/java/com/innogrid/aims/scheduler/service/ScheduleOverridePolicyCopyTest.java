package com.innogrid.aims.scheduler.service;

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
 * <p>사본에 {@code affinityEnabled} 가 복사되지 않아, 온도 한계를 지정한 스케줄에서는 MDC 링크 게이트가
 * 꺼진 채 실행되던 결함을 고정한다. 정책의 다른 필드가 그대로 옮겨지는 것도 함께 확인한다.</p>
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
