package com.innogrid.aims.scheduler.dto;

import com.innogrid.aims.scheduler.model.NodeResource;

/**
 * 노드 자원 현황 응답 — {@code GET /scheduler/nodes}·{@code /nodes/{nodeId}}.
 *
 * <p>필드 27개 — 21개는 {@link NodeResource} 를 그대로 직렬화하던 시절의 계약과 <b>동일</b>하고, GPU 축 3개
 * ({@code gpuUsagePercent}·{@code gpuTemperatureCelsius}·{@code gpuPowerWatts}, 0 = 미계측)와 디스크 축 3개
 * ({@code diskCapacityGb}·{@code reservedDiskGb}·{@code availableDiskGb}, 총량 0 = 모름)를 2026-09 에 더했다
 * ({@code NodeResourceSerializationTest} 가 고정). 디스크 축은 C1 디스크 검사와 같은 식이다(값은 조회 시점) — 총량을
 * 모르는 노드는 디스크 때문에는 빠지지 않으므로, 화면이 그 사실을 {@code diskCapacityGb = 0} 으로 읽는다.
 * 모델은 이 세 값을 {@code get} 없는 이름으로 두어 모델 직렬화에는 나오지 않는다(응답 형태는 이 record 가 정한다).
 * 이 DTO 가 생기기 전에는 모델에 {@code get}
 * 메서드를 하나 추가하면 API 필드가 조용히 늘었고, 그래서 계산용 메서드를 비-{@code get} 으로
 * 짓는 규율에 의존했다. 이제 응답 형태는 이 record 가 정한다 — 모델은 자유롭게 바뀌어도 된다.</p>
 *
 * <p>{@code reserved*}·{@code available*} 은 스케줄러 내부 장부지만 기존 응답에 있었으므로
 * 그대로 둔다. 빼는 것은 계약 변경이라 프런트와 합의가 필요하다.</p>
 */
public record NodeResponse(
        String mdcId,
        String nodeId,
        int totalCpu,
        long totalMemoryMb,
        int totalGpu,
        long diskCapacityGb,
        double cpuUsagePercent,
        double memoryUsagePercent,
        double diskUsagePercent,
        double powerWatts,
        double powerCapacityWatts,
        double temperatureCelsius,
        double networkRxBps,
        double networkTxBps,
        double gpuUsagePercent,
        double gpuTemperatureCelsius,
        double gpuPowerWatts,
        int reservedCpu,
        long reservedMemoryMb,
        int reservedGpu,
        long reservedDiskGb,
        NodeResource.ThermalState thermalState,
        NodeResource.PowerSource powerSource,
        double availableCpu,
        long availableMemoryMb,
        int availableGpu,
        long availableDiskGb
) {
    public static NodeResponse from(NodeResource n) {
        return new NodeResponse(
                n.getMdcId(), n.getNodeId(),
                n.getTotalCpu(), n.getTotalMemoryMb(), n.getTotalGpu(), n.diskCapacityGb(),
                n.getCpuUsagePercent(), n.getMemoryUsagePercent(), n.getDiskUsagePercent(),
                n.getPowerWatts(), n.getPowerCapacityWatts(), n.getTemperatureCelsius(),
                n.getNetworkRxBps(), n.getNetworkTxBps(),
                n.getGpuUsagePercent(), n.getGpuTemperatureCelsius(), n.getGpuPowerWatts(),
                n.getReservedCpu(), n.getReservedMemoryMb(), n.getReservedGpu(), n.reservedDiskGb(),
                n.getThermalState(), n.getPowerSource(),
                n.availableCpu(), n.availableMemoryMb(), n.availableGpu(), n.availableDiskGb());
    }
}
