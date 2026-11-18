package com.innogrid.aims.scheduler.service;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.model.EngineSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 엔진 선택 규칙 — 단건 배치·모의 배치와 스케줄 실행·미리보기·모의 실행이 함께 쓰는 한 규칙의 진리표.
 * 지정한 엔진 → 정책 기본 엔진(등록된 것일 때만) → weighted 순이다(스프링 컨텍스트 없음 · 등록부는 목 객체).
 */
class EngineChoiceTest {

    private static final Set<String> REGISTERED = Set.of("weighted", "power", "temperature");

    private final EngineRegistry registry = mock(EngineRegistry.class);

    EngineChoiceTest() {
        when(registry.contains(anyString())).thenAnswer(inv -> REGISTERED.contains((String) inv.getArgument(0)));
    }

    private static SchedulingPolicy policyWithEngine(String defaultEngine) {
        SchedulingPolicy policy = new SchedulingPolicy("engine-rule", "엔진 규칙");
        policy.setDefaultEngine(defaultEngine);
        return policy;
    }

    private void assertChoice(String engine, EngineSource source, EngineChoice choice) {
        assertEquals(new EngineChoice(engine, source), choice);
    }

    @DisplayName("지정한 엔진은 정책 기본 엔진보다 앞서고 출처는 스케줄이다")
    @Test
    void explicit_engine_wins_over_policy_default() {
        assertChoice("temperature", EngineSource.SCHEDULE, EngineChoice.choose("temperature", policyWithEngine("power"), registry));
    }

    @DisplayName("지정한 엔진은 등록되지 않은 이름이어도 그대로 싣는다 — 등록 검사는 받을 때·저장할 때 한다")
    @Test
    void explicit_engine_is_not_checked_against_registry() {
        assertChoice("retired-engine", EngineSource.SCHEDULE, EngineChoice.choose("retired-engine", policyWithEngine("power"), registry));
    }

    @DisplayName("지정한 엔진의 앞뒤 공백을 자르지 않는다")
    @Test
    void explicit_engine_is_not_trimmed() {
        assertChoice(" power", EngineSource.SCHEDULE, EngineChoice.choose(" power", null, registry));
    }

    @DisplayName("비었거나 공백뿐인 지정은 없는 것으로 보고 정책 기본 엔진을 쓴다")
    @Test
    void blank_explicit_falls_back_to_policy_default() {
        assertChoice("power", EngineSource.POLICY, EngineChoice.choose(null, policyWithEngine("power"), registry));
        assertChoice("power", EngineSource.POLICY, EngineChoice.choose("", policyWithEngine("power"), registry));
        assertChoice("power", EngineSource.POLICY, EngineChoice.choose("   ", policyWithEngine("power"), registry));
    }

    @DisplayName("정책 기본 엔진이 등록되지 않은 이름이면 weighted 를 쓰고 출처는 기본값이다")
    @Test
    void unregistered_policy_default_falls_back_to_weighted() {
        assertChoice("weighted", EngineSource.DEFAULT, EngineChoice.choose(null, policyWithEngine("retired-engine"), registry));
        assertChoice("weighted", EngineSource.DEFAULT, EngineChoice.choose(null, policyWithEngine(" power"), registry));
    }

    @DisplayName("정책에 기본 엔진이 없으면 weighted 를 쓴다")
    @Test
    void policy_without_default_engine_falls_back_to_weighted() {
        assertChoice("weighted", EngineSource.DEFAULT, EngineChoice.choose(null, policyWithEngine(null), registry));
    }

    @DisplayName("정책이 없으면 weighted 를 쓴다")
    @Test
    void missing_policy_falls_back_to_weighted() {
        assertChoice("weighted", EngineSource.DEFAULT, EngineChoice.choose(null, null, registry));
    }

    @DisplayName("정책 기본 엔진이 weighted 여도 출처는 정책이다")
    @Test
    void policy_default_weighted_is_reported_as_policy() {
        assertChoice("weighted", EngineSource.POLICY, EngineChoice.choose(null, policyWithEngine("weighted"), registry));
    }
}
