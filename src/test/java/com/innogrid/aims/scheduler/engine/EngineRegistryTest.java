package com.innogrid.aims.scheduler.engine;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EngineRegistryTest {

    private final EngineRegistry registry = new EngineRegistry(List.of(
            new WeightedScoreScheduler(),
            new PowerAwareScheduler(),
            new TemperatureAwareScheduler()));

    @Test
    void 식별자로_엔진을_찾는다() {
        assertEquals("weighted", registry.resolve("weighted").getId());
        assertEquals("power", registry.resolve("power").getId());
        assertEquals("temperature", registry.resolve("temperature").getId());
    }

    @Test
    void 알_수_없는_엔진이면_기본_엔진을_돌려준다() {
        assertEquals(EngineRegistry.DEFAULT_ENGINE_ID, registry.resolve("no-such-engine").getId());
        assertEquals(EngineRegistry.DEFAULT_ENGINE_ID, registry.resolve(null).getId());
    }

    @Test
    void 등록_여부를_확인할_수_있다() {
        assertTrue(registry.contains("power"));
        assertFalse(registry.contains("no-such-engine"));
        assertFalse(registry.contains(null));
    }

    @Test
    void 엔진_목록을_식별자와_이름으로_설명한다() {
        List<Map<String, String>> described = registry.describe();

        assertEquals(3, described.size());
        assertTrue(described.stream().anyMatch(
                e -> "power".equals(e.get("id")) && "PowerAwareScheduler".equals(e.get("name"))));
    }
}
