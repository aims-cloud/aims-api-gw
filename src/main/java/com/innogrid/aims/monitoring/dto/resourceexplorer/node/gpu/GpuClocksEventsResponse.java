package com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu;

import java.util.List;

/**
 * Clocks Event Reasons 상세 패널 — GPU별 활성 이벤트와 안내 텍스트.
 * 상단 활성 이벤트 매트릭스(이벤트 × GPU)는 프론트가 activeReasons 로 렌더링한다.
 */
public record GpuClocksEventsResponse(
        List<GpuClocksEvents> gpus
) {
    /**
     * GPU 1장의 활성 이벤트 — gpuName 은 트리와 같은 표시 이름, guide 는 주의·위험 사유별 안내 텍스트(줄바꿈으로 구분).
     * level 은 활성 사유 중 가장 나쁜 단계. Clock 이벤트 지표가 수집되지 않으면 activeReasons·level 은 null 이다.
     */
    public record GpuClocksEvents(String gpuId, String gpuName, String model, GpuHealthLevel level,
                                  List<ClocksEventReason> activeReasons, String guide) {}

    public GpuClocksEventsResponse {
        gpus = gpus == null ? List.of() : List.copyOf(gpus);
    }
}
