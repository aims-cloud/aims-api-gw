package com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu;

/** DCGM Clocks Event(구 Clock Throttle) 사유 — 활성 이벤트 매트릭스의 행. */
public enum ClocksEventReason {
    DISPLAY_CLK,
    HW_PWR_BRAKE,
    HW_THERMAL,
    SW_THERMAL,
    SYNC_BOOST,
    HW_SLOWDOWN,
    SW_POWER_CAP,
    APP_CLOCKS,
    GPU_IDLE
}
