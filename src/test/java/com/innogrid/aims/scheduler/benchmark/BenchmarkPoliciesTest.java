package com.innogrid.aims.scheduler.benchmark;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 비교용 정책 변종이 <b>원본을 건드리지 않고</b> 정한 가중치·게이트만 바꾸는지 고정한다.
 *
 * <p>변종의 출발점인 사본 자체(판단 필드를 하나도 빠뜨리지 않는가)는 {@code SchedulingPolicyCopyTest} 가
 * 선언된 필드를 반사로 훑어 고정한다. 여기서는 자원 전용 변종이 무엇을 끄고 무엇을 남기는지와,
 * 변종을 만드는 동안 원본이 바뀌지 않는지를 본다.</p>
 */
class BenchmarkPoliciesTest {

    // ── 자원 전용 변종 ─────────────────────────────────────────

    /**
     * 전력·온도·네트워크만 0 으로 만들고 <b>CPU·메모리 가중치는 유지</b>한다.
     *
     * <p>다섯 개를 다 0 으로 두면 모든 노드의 총점이 같아지고, {@code weighted} 엔진은 동점에서 첫
     * 후보를 남기므로 결과가 선착순(FFD)과 같아진다 — 대조군 두 개가 사실은 하나가 된다.</p>
     */
    @DisplayName("자원 전용은 전력 온도 네트워크 가중치만 0 으로 만든다")
    @Test
    void resource_only_zeroes_power_temperature_network_weights() {
        SchedulingPolicy base = base();

        SchedulingPolicy resourceOnly = BenchmarkPolicies.resourceOnly(base, true);

        assertEquals(0.0, resourceOnly.getPowerWeight(), 1e-9);
        assertEquals(0.0, resourceOnly.getTemperatureWeight(), 1e-9);
        assertEquals(0.0, resourceOnly.getNetworkWeight(), 1e-9);

        assertEquals(base.getCpuWeight(), resourceOnly.getCpuWeight(), 1e-9);
        assertEquals(base.getMemoryWeight(), resourceOnly.getMemoryWeight(), 1e-9);
        assertTrue(resourceOnly.getCpuWeight() > 0,
                "CPU·메모리까지 0 이면 전 노드 총점이 같아져 첫 후보가 뽑히고 선착순과 같아진다");
        assertTrue(resourceOnly.getMemoryWeight() > 0);

        // 제약 임계는 가중치와 다른 축이다 — 자원 전용이라고 상한이 느슨해지면 후보 집합이 달라진다
        assertEquals(base.getMaxCpuUsagePercent(), resourceOnly.getMaxCpuUsagePercent(), 1e-9);
        assertEquals(base.getMaxMemoryUsagePercent(), resourceOnly.getMaxMemoryUsagePercent(), 1e-9);
        assertEquals(base.getMaxTemperatureCelsius(), resourceOnly.getMaxTemperatureCelsius(), 1e-9);
        assertEquals(base.getMaxPowerUsagePercent(), resourceOnly.getMaxPowerUsagePercent(), 1e-9);
    }

    /** 게이트를 남기는 변종은 점수에서만 전력·온도를 뺀다 — 제약(C3·C4)은 그대로 걸린다. */
    @DisplayName("게이트 유지 변종은 전력 온도 플래그를 남긴다")
    @Test
    void gated_variant_keeps_the_flags() {
        SchedulingPolicy gated = BenchmarkPolicies.resourceOnly(base(), true);

        assertTrue(gated.isPowerAwareEnabled());
        assertTrue(gated.isTemperatureAwareEnabled());
        assertTrue(gated.isAffinityEnabled(),
                "링크 게이트는 다른 축이다 — 대조군이 마음대로 끄면 후보 집합 자체가 달라진다");
    }

    /** 게이트까지 끄는 변종은 제약에서도 전력·온도를 뺀다. 두 변종의 차가 "제약의 몫"이다. */
    @DisplayName("게이트 해제 변종은 전력 온도 플래그를 끈다")
    @Test
    void ungated_variant_turns_the_flags_off() {
        SchedulingPolicy ungated = BenchmarkPolicies.resourceOnly(base(), false);

        assertFalse(ungated.isPowerAwareEnabled());
        assertFalse(ungated.isTemperatureAwareEnabled());
        assertTrue(ungated.isAffinityEnabled(), "끄기로 한 것은 전력·온도 두 개뿐이다");
    }

    // ── 원본 보호 ─────────────────────────────────────────────

    /**
     * 정책은 여러 스케줄이 공유하고, 저장소에서 읽어 온 인스턴스는 영속 문맥에 붙어 있다.
     * 여기서 원본을 고치면 그 변경이 flush 되어 <b>비교가 운영 설정을 바꾸는</b> 일이 된다.
     */
    @DisplayName("원본 정책은 절대 바뀌지 않는다")
    @Test
    void original_policy_is_never_mutated() {
        SchedulingPolicy original = distinctive();
        Map<String, Object> before = fields(original);

        original.detachedCopy();
        BenchmarkPolicies.resourceOnly(original, true);
        BenchmarkPolicies.resourceOnly(original, false);

        assertEquals(before, fields(original), "사본을 만드는 동안 원본이 바뀌었다");
    }

    // ── 픽스처 ───────────────────────────────────────────────

    /**
     * 모든 필드를 <b>기본값과 다른</b> 값으로 채운 정책.
     * 기본값을 그대로 두면 옮기지 않아도 테스트가 통과해 버린다.
     */
    private static SchedulingPolicy distinctive() {
        SchedulingPolicy policy = new SchedulingPolicy("원본정책", "사본 시험용");

        policy.setResourceType(Workload.ResourceType.CONTAINER);
        policy.setDefaultEngine("temperature");
        policy.setWarningTemperatureCelsius(61.5);

        policy.setCpuWeight(0.31);
        policy.setMemoryWeight(0.29);
        policy.setPowerWeight(0.19);
        policy.setTemperatureWeight(0.13);
        policy.setNetworkWeight(0.08);

        policy.setMaxCpuUsagePercent(77.5);
        policy.setMaxMemoryUsagePercent(82.5);
        policy.setMaxTemperatureCelsius(71.0);
        policy.setMaxPowerUsagePercent(88.0);

        policy.setPowerAwareEnabled(false);
        policy.setTemperatureAwareEnabled(false);
        policy.setAffinityEnabled(true);

        policy.setActive(false);
        return policy;
    }

    /** 게이트가 켜진 정책. 자원 전용 변종이 무엇을 끄고 무엇을 남기는지 보려면 켜진 것에서 출발해야 한다. */
    private static SchedulingPolicy base() {
        SchedulingPolicy policy = new SchedulingPolicy("기준정책", "자원 전용 변종의 출발점");
        policy.setAffinityEnabled(true);
        return policy;
    }

    /**
     * 선언된 인스턴스 필드 전량의 값.
     *
     * <p>합성 필드(커버리지 도구가 끼우는 것 등)와 정적 필드는 뺀다 — 정책의 상태가 아니다.</p>
     */
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
}
