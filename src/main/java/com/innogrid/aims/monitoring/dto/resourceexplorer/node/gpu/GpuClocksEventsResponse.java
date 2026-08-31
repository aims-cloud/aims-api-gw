package com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu;

import java.util.List;

/**
 * Clocks Event Reasons 상세 패널 — GPU별 활성 이벤트와 안내 텍스트.
 * 상단 활성 이벤트 매트릭스(이벤트 × GPU)는 프론트가 activeReasons 로 렌더링한다.
 */
public record GpuClocksEventsResponse(
        List<GpuClocksEvents> gpus
) {
    /** GPU 1장의 활성 이벤트 — guide 는 발생 이유별 안내 텍스트 */
    public record GpuClocksEvents(String gpuId, String model,
                                  List<ClocksEventReason> activeReasons, String guide) {}

    public GpuClocksEventsResponse {
        gpus = gpus == null ? List.of() : List.copyOf(gpus);
    }
}
