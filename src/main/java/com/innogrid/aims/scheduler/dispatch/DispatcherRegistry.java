package com.innogrid.aims.scheduler.dispatch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.innogrid.aims.scheduler.config.SchedulerProperties;

/**
 * 디스패처 레지스트리 — 구현체를 목록으로 받아 설정 {@code aims.scheduler.dispatch.mode} 로 고른다.
 * {@link com.innogrid.aims.scheduler.engine.EngineRegistry} 와 같은 방식. 모르는 모드면 기록형으로 떨어진다.
 */
@Component
public class DispatcherRegistry {

    private final Map<String, PlacementDispatcher> dispatchers = new LinkedHashMap<>();
    private final SchedulerProperties properties;

    public DispatcherRegistry(List<PlacementDispatcher> list, SchedulerProperties properties) {
        for (PlacementDispatcher d : list) dispatchers.put(d.getMode(), d);
        this.properties = properties;
    }

    public boolean contains(String mode) {
        return mode != null && dispatchers.containsKey(mode);
    }

    /** 설정된 모드의 디스패처. 모드가 비었거나 모르면 기록형, 기록형도 없으면(비정상 조립) 첫 등록 구현체. 아무것도 없으면 예외. */
    public PlacementDispatcher current() {
        PlacementDispatcher d = dispatchers.get(properties.getDispatch().getMode());
        if (d == null) d = dispatchers.get(RecordingPlacementDispatcher.MODE);
        if (d == null) d = dispatchers.values().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("등록된 PlacementDispatcher 가 없습니다"));
        return d;
    }

    public List<String> modes() {
        return List.copyOf(dispatchers.keySet());
    }
}
