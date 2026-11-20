package com.innogrid.aims.scheduler.service;

import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.model.EngineSource;

/**
 * 배치에 쓸 엔진과 그 엔진을 고른 단계 — 단건 배치·모의 배치와 스케줄 실행·미리보기·모의 실행이 같은 규칙으로 고른다.
 *
 * <p>규칙: 지정한 엔진(비어 있지 않으면 그대로) → 정책 기본 엔진(등록된 것일 때만) → 서버 기본값. 지정한 엔진은 여기서 등록
 * 여부를 보지 않는다 — 단건 배치는 요청을 받을 때, 스케줄은 저장할 때 검사하므로 그 뒤 등록이 풀린 스케줄 엔진도 그대로 싣는다.
 * 단건 배치는 엔진만 쓰고 출처는 스케줄 경로가 응답에 싣는다.</p>
 */
record EngineChoice(String engine, EngineSource source) {

    static EngineChoice choose(String explicit, SchedulingPolicy policy, EngineRegistry registry) {
        if (explicit != null && !explicit.isBlank()) {
            return new EngineChoice(explicit, EngineSource.SCHEDULE);
        }
        if (policy != null && policy.getDefaultEngine() != null && registry.contains(policy.getDefaultEngine())) {
            return new EngineChoice(policy.getDefaultEngine(), EngineSource.POLICY);
        }
        return new EngineChoice(EngineRegistry.DEFAULT_ENGINE_ID, EngineSource.DEFAULT);
    }
}
