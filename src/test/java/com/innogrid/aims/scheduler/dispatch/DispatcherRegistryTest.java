package com.innogrid.aims.scheduler.dispatch;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;

import static org.junit.jupiter.api.Assertions.*;

/** 모드 선택 — 설정값으로 고르고, 모르는 값이면 기록형으로 떨어진다. */
class DispatcherRegistryTest {

    private static PlacementDispatcher stub(String mode) {
        return new PlacementDispatcher() {
            @Override public String getMode() { return mode; }
            @Override public Outcome dispatch(Request request) { return Outcome.rejected(mode); }
        };
    }

    @DisplayName("기본 설정은 record 이고 기록형 디스패처가 수락 결과를 돌려준다")
    @Test
    void default_mode_is_record() {
        SchedulerProperties p = new SchedulerProperties();
        DispatcherRegistry r = new DispatcherRegistry(List.of(new RecordingPlacementDispatcher(), stub("remote")), p);
        assertEquals("record", p.getDispatch().getMode());
        assertEquals("record", r.current().getMode());
        assertTrue(r.contains("remote"));
        assertEquals(List.of("record", "remote"), r.modes());
        PlacementDispatcher.Outcome o = r.current().dispatch(new PlacementDispatcher.Request(
                1L, "k", 2L, "w", "VM", 1, 64, 0, 0, "mdc", "n", 0.5, "r", null));
        assertTrue(o.accepted());
        assertNull(o.externalRef(), "기록형은 외부 식별자가 없다");
    }

    @DisplayName("설정 모드가 등록되어 있으면 그 디스패처, 모르는 모드면 기록형")
    @Test
    void configured_mode_or_fallback() {
        SchedulerProperties p = new SchedulerProperties();
        DispatcherRegistry r = new DispatcherRegistry(List.of(new RecordingPlacementDispatcher(), stub("remote")), p);
        p.getDispatch().setMode("remote");
        assertEquals("remote", r.current().getMode());
        p.getDispatch().setMode("nope");
        assertEquals("record", r.current().getMode());
        p.getDispatch().setMode(null);
        assertEquals("record", r.current().getMode());
    }
}
