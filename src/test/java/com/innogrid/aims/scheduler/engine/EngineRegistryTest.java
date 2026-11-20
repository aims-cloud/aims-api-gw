package com.innogrid.aims.scheduler.engine;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EngineRegistryTest {

    private final EngineRegistry registry = new EngineRegistry(List.of(
            new WeightedScoreScheduler(),
            new PowerAwareScheduler(),
            new TemperatureAwareScheduler()));

    @DisplayName("식별자로 엔진을 찾는다")
    @Test
    void engine_found_by_id() {
        assertEquals("weighted", registry.resolve("weighted").getId());
        assertEquals("power", registry.resolve("power").getId());
        assertEquals("temperature", registry.resolve("temperature").getId());
    }

    @DisplayName("알 수 없는 엔진이면 기본 엔진을 돌려준다")
    @Test
    void unknown_engine_falls_back_default() {
        assertEquals(EngineRegistry.DEFAULT_ENGINE_ID, registry.resolve("no-such-engine").getId());
        assertEquals(EngineRegistry.DEFAULT_ENGINE_ID, registry.resolve(null).getId());
    }

    @DisplayName("등록 여부를 확인할 수 있다")
    @Test
    void registration_checkable() {
        assertTrue(registry.contains("power"));
        assertFalse(registry.contains("no-such-engine"));
        assertFalse(registry.contains(null));
    }

    @DisplayName("엔진 목록을 식별자와 이름으로 설명한다")
    @Test
    void engine_list_has_id_and_name() {
        List<Map<String, String>> described = registry.describe();

        assertEquals(3, described.size());
        assertTrue(described.stream().anyMatch(
                e -> "power".equals(e.get("id")) && "PowerAwareScheduler".equals(e.get("name"))));
    }
}
