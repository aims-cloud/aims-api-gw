package com.innogrid.aims.scheduler.entity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 정책 사본 {@link SchedulingPolicy#detachedCopy()}가 <b>영속되지 않고</b> 판단 필드를 하나도 빠뜨리지 않는지 고정한다.
 *
 * <p>필드 하나가 빠지면 그 사본을 쓰는 실행에서 게이트가 <b>조용히</b> 꺼진다. 실제로 예전 실행 경로의 손 복사에서
 * {@code affinityEnabled}가 빠져 있어 온도 한계를 지정한 스케줄에서만 MDC 링크 게이트가 꺼지던 결함이 있었다. 비교 판에서
 * 같은 일이 나면 "대조군이 제약을 덜 받아 유리했다"가 되어 표 전체가 무효가 된다. 그래서 이름을 하나하나 적은 테스트와,
 * 선언된 필드를 반사로 훑는 테스트를 <b>둘 다</b> 둔다 — 앞의 것은 의도를 적고, 뒤의 것은 나중에 늘어나는 필드를 잡는다.</p>
 */
class SchedulingPolicyCopyTest {

    /**
     * 사본의 {@code id}는 항상 {@code null}이다. 그 근거는 {@link SchedulingPolicy}에 {@code id}
     * 세터가 <b>없다</b>는 사실이므로, 세터가 생기는 것까지 함께 막는다 — 세터가 생기면 사본이
     * 기존 행과 같은 식별자를 갖고 영속 문맥에 들어갈 길이 열린다.
     */
    @DisplayName("사본은 id 와 createdAt 을 갖지 않는다")
    @Test
    void copy_does_not_carry_an_id() throws ReflectiveOperationException {
        SchedulingPolicy src = distinctive();
        // 저장될 때 채워지는 두 값을 미리 채워 둔다 — 비어 있으면 사본이 옮겨도 드러나지 않는다
        Field id = SchedulingPolicy.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(src, 1L);
        src.onCreate();
        assertNotNull(src.getId());
        assertNotNull(src.getCreatedAt());

        SchedulingPolicy copy = src.detachedCopy();

        assertNull(copy.getId(), "id 가 있으면 JPA 가 기존 행으로 볼 수 있다");
        assertNull(copy.getCreatedAt(), "createdAt 은 저장될 때 채워지는 값이라 사본에는 없어야 한다");

        for (Method method : SchedulingPolicy.class.getMethods()) {
            assertNotEquals("setId", method.getName(),
                    "id 세터가 생기면 '비영속 사본' 이라는 보장이 코드가 아니라 관행에 기대게 된다");
        }
    }

    /** 가중치 5 · 임계 4 · 플래그 3 · 경고온도, 그리고 이름·설명·종류·기본 엔진·활성 여부까지. */
    @DisplayName("사본이 모든 판단 필드를 옮긴다")
    @Test
    void copy_preserves_every_field() {
        SchedulingPolicy src = distinctive();

        SchedulingPolicy copy = src.detachedCopy();

        assertEquals("원본정책", copy.getName());
        assertEquals("사본 확인용", copy.getDescription());
        assertEquals(Workload.ResourceType.CONTAINER, copy.getResourceType());
        assertEquals("temperature", copy.getDefaultEngine());
        assertEquals(61.5, copy.getWarningTemperatureCelsius(), 1e-9);

        assertEquals(0.31, copy.getCpuWeight(), 1e-9);
        assertEquals(0.29, copy.getMemoryWeight(), 1e-9);
        assertEquals(0.19, copy.getPowerWeight(), 1e-9);
        assertEquals(0.13, copy.getTemperatureWeight(), 1e-9);
        assertEquals(0.08, copy.getNetworkWeight(), 1e-9);

        assertEquals(77.5, copy.getMaxCpuUsagePercent(), 1e-9);
        assertEquals(82.5, copy.getMaxMemoryUsagePercent(), 1e-9);
        assertEquals(71.0, copy.getMaxTemperatureCelsius(), 1e-9);
        assertEquals(88.0, copy.getMaxPowerUsagePercent(), 1e-9);

        assertFalse(copy.isPowerAwareEnabled(), "기본값이 true 라 안 옮기면 통과해 버린다 — 그래서 false 로 잡았다");
        assertFalse(copy.isTemperatureAwareEnabled());
        assertTrue(copy.isAffinityEnabled(), "조용히 꺼졌던 전례가 있는 필드");

        assertFalse(copy.isActive());
    }

    /**
     * 선언된 필드를 반사로 훑어 값을 대조한다. 나중에 판단 필드가 늘어났을 때
     * {@code detachedCopy}가 따라오지 않으면 여기가 빨간불이 된다.
     */
    @DisplayName("선언된 모든 필드가 사본으로 옮겨진다")
    @Test
    void copy_covers_every_declared_field() {
        SchedulingPolicy src = distinctive();

        Map<String, Object> expected = fields(src);
        Map<String, Object> actual = fields(src.detachedCopy());

        // id와 createdAt은 영속 계층의 몫이다 — 사본에 없는 것이 맞고, 세터도 없다
        for (String persistenceOwned : new String[]{"id", "createdAt"}) {
            expected.remove(persistenceOwned);
            actual.remove(persistenceOwned);
        }

        assertEquals(expected, actual, "필드가 늘어났는데 detachedCopy 가 따라오지 않았다");
        assertTrue(expected.size() >= 18,
                "필드가 줄었다면 이 테스트가 무엇을 지키는지 다시 봐야 한다: " + expected.keySet());
    }

    /**
     * 모든 필드를 <b>기본값과 다른</b> 값으로 채운 정책.
     * 기본값을 그대로 두면 옮기지 않아도 테스트가 통과해 버린다.
     */
    private static SchedulingPolicy distinctive() {
        SchedulingPolicy policy = new SchedulingPolicy("원본정책", "사본 확인용");

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
