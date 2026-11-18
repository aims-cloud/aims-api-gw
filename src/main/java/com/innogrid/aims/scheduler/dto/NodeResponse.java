package com.innogrid.aims.scheduler.dto;

import com.innogrid.aims.scheduler.model.NodeResource;

/**
 * 노드 자원 현황 응답 — {@code GET /scheduler/nodes}·{@code /nodes/{nodeId}}.
 *
 * <p>필드는 27개다. 기본 21개에 GPU 축 3개({@code gpuUsagePercent}·{@code gpuTemperatureCelsius}·{@code gpuPowerWatts},
 * 0 = 미계측)와 디스크 축 3개({@code diskCapacityGb}·{@code reservedDiskGb}·{@code availableDiskGb}, 총량 0 = 모름)가
 * 붙는다({@code NodeResourceSerializationTest}가 확인). 디스크 축의 식(C1 디스크 검사와 같음)과
 * {@code diskCapacityGb = 0}의 뜻은 docs/13 3장 「노드 응답의 디스크 축」에 있다. 모델({@link NodeResource})은
 * 디스크 축 세 값을 {@code get} 없는 이름으로 두어 모델 직렬화에는 나오지 않는다. 응답 형태는 이 record가
 * 정하므로 모델이 바뀌어도 API 필드가 조용히 늘지 않는다.</p>
 *
 * <p>{@code reserved*}·{@code available*}은 스케줄러 내부 장부지만, 응답에서 빼려면 프런트와 합의가 필요해
 * 그대로 둔다.</p>
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
