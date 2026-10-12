package com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu;

import java.util.Arrays;
import java.util.List;

/**
 * DCGM Clocks Event(구 Clock Throttle) 사유 — 활성 이벤트 매트릭스의 행.
 * bit 는 DCGM_FI_DEV_CLOCKS_EVENT_REASONS 비트마스크의 값(NVML nvmlClocksEventReason), level 은 상태 요약 판정 단계다.
 * 하드웨어 보호(과열·전력 차단·감속)는 CRITICAL, 드라이버 제한(온도·전력 상한)은 WARNING, 유휴·설정·동기화는 정상(NORMAL)이다.
 */
public enum ClocksEventReason {
    DISPLAY_CLK(0x100L, GpuHealthLevel.NORMAL,
            "디스플레이 클럭 설정으로 클럭이 제한됨 — 서버 GPU 에서는 보통 무시해도 됩니다."),
    HW_PWR_BRAKE(0x80L, GpuHealthLevel.CRITICAL,
            "외부 전원 차단 신호(Power Brake)로 클럭이 크게 낮아짐 — 전원 공급 장치와 섀시 전력 상태를 점검하세요."),
    HW_THERMAL(0x40L, GpuHealthLevel.CRITICAL,
            "하드웨어 과열 보호로 클럭이 낮아짐 — 냉각(팬·공조·방열)과 GPU 온도를 즉시 점검하세요."),
    SW_THERMAL(0x20L, GpuHealthLevel.WARNING,
            "드라이버 온도 제한으로 클럭이 낮아짐 — GPU·메모리 온도와 서버실 냉각 상태를 확인하세요."),
    SYNC_BOOST(0x10L, GpuHealthLevel.NORMAL,
            "Sync Boost 그룹의 다른 GPU 에 맞춰 클럭이 조정됨 — 정상 동작입니다."),
    HW_SLOWDOWN(0x8L, GpuHealthLevel.CRITICAL,
            "하드웨어 감속 신호(과열 또는 전력 이상)로 클럭이 크게 낮아짐 — 온도와 전원 공급을 점검하세요."),
    SW_POWER_CAP(0x4L, GpuHealthLevel.WARNING,
            "전력 상한(Power Limit)에 도달해 클럭이 낮아짐 — 지속되면 전력 상한 설정과 워크로드 부하를 확인하세요."),
    APP_CLOCKS(0x2L, GpuHealthLevel.NORMAL,
            "애플리케이션 클럭 설정값으로 제한됨 — 의도한 설정인지 확인하세요."),
    GPU_IDLE(0x1L, GpuHealthLevel.NORMAL,
            "GPU 가 유휴 상태라 클럭이 낮음 — 정상 동작입니다.");

    private final long bit;
    private final GpuHealthLevel level;
    private final String guide;

    ClocksEventReason(long bit, GpuHealthLevel level, String guide) {
        this.bit = bit;
        this.level = level;
        this.guide = guide;
    }

    public long bit() {
        return bit;
    }

    public GpuHealthLevel level() {
        return level;
    }

    public String guide() {
        return guide;
    }

    /** 비트마스크에서 켜진 사유 — 선언 순서(매트릭스 행 순서). 알 수 없는 비트는 무시한다 */
    public static List<ClocksEventReason> fromMask(long mask) {
        return Arrays.stream(values()).filter(r -> (mask & r.bit) != 0).toList();
    }
}
