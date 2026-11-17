package com.innogrid.aims.scheduler.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 스케줄링 엔진 레지스트리.
 *
 * 엔진을 목록으로 주입받아 식별자로 찾을 수 있게 한다.
 * 엔진을 추가할 때 이 클래스를 고칠 필요가 없다.
 */
@Component
public class EngineRegistry {

    /** 엔진을 지정하지 않았을 때 쓰는 기본 엔진. */
    public static final String DEFAULT_ENGINE_ID = "weighted";

    private final Map<String, SchedulingEngine> engines = new LinkedHashMap<>();

    public EngineRegistry(List<SchedulingEngine> engineList) {
        for (SchedulingEngine engine : engineList) {
            engines.put(engine.getId(), engine);
        }
    }

    /** 등록된 엔진 식별자인지 확인한다. */
    public boolean contains(String engineId) {
        return engineId != null && engines.containsKey(engineId);
    }

    /**
     * 식별자로 엔진을 찾는다. 없으면 기본 엔진을 돌려준다.
     * 실제로 어떤 엔진이 쓰였는지는 {@link SchedulingEngine#getId()}로 확인할 수 있다.
     */
    public SchedulingEngine resolve(String engineId) {
        SchedulingEngine engine = engines.get(engineId);
        return engine != null ? engine : engines.get(DEFAULT_ENGINE_ID);
    }

    /** 사용 가능한 엔진 목록. */
    public List<Map<String, String>> describe() {
        return engines.values().stream()
                .map(e -> Map.of("id", e.getId(), "name", e.getName()))
                .toList();
    }
}
