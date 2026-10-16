package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.util.List;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.common.util.MetricFormat;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuDeviceSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuMetricTrendResponse.Series;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostGpuMetricsQueryService.GpuSample;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostGpuMetricsQueryService.GpuSeries;

/**
 * 노드 GPU 패널과 VM GPU 패널이 함께 쓰는 응답 조립 규칙 — 같은 DCGM 표본({@link GpuSample})을 같은 단위·표기로 내린다.
 * 노드는 dcgm_exporter instance 선택자, VM 은 tb_* 선택자로 표본을 읽을 뿐 조립은 같다(File system 패널의 {@link FileSystemPanelAssembler} 와 같은 역할).
 *
 * <ul>
 *   <li>VRAM 은 DCGM 이 MB 로 주므로 GB 로 바꾸고, 값은 소수 1자리. 값이 없으면 null(미수집)</li>
 *   <li>GPU 표시 이름은 트리 GPU 장치 노드와 같은 "GPU {인덱스}" — 인덱스가 없으면 UUID</li>
 *   <li>수집 상태 문자열은 MonitoringStatus 이름 — 지금 수집 중이면 COLLECTING, 끊겼으면 UNAVAILABLE</li>
 * </ul>
 */
public final class GpuPanelAssembler {

    private GpuPanelAssembler() {}

    /** 개별 GPU 개요 KPI 카드 6종 — Util / VRAM Util / VRAM 사용·전체 / Power / 온도 / Fan. 지금 수집되지 않는 값은 null */
    public static GpuDeviceSummaryResponse deviceSummary(GpuSample gpu) {
        return new GpuDeviceSummaryResponse(
                MetricFormat.round1(gpu.utilization()),
                MetricFormat.round1(gpu.vramPercent()),
                MetricFormat.mbToGb(gpu.fbUsedMb()),
                MetricFormat.mbToGb(gpu.fbTotalMb()),
                MetricFormat.round1(gpu.powerWatts()),
                MetricFormat.round1(gpu.temperatureC()),
                MetricFormat.round1(gpu.fanSpeedPercent()));
    }

    /** 표본 목록에서 gpuId(UUID) 1장 — 최근 24시간 안에 수집된 적 없으면 404 */
    public static GpuSample requireDevice(List<GpuSample> gpus, String gpuId) {
        return gpus.stream()
                .filter(g -> g.uuid().equals(gpuId))
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("GPU 를 찾을 수 없습니다: " + gpuId));
    }

    /** 차트 시리즈 — GPU 별 1개, 이름은 트리와 같은 표시 이름 */
    public static List<Series> series(List<GpuSeries> series) {
        return series.stream().map(s -> new Series(gpuName(s.index(), s.uuid()), s.points())).toList();
    }

    public static String collectStatus(GpuSample gpu) {
        return (gpu.collecting() ? MonitoringStatus.COLLECTING : MonitoringStatus.UNAVAILABLE).name();
    }

    /** 트리와 같은 표시 이름 — "GPU {인덱스}", 인덱스가 없으면 UUID */
    public static String gpuName(String index, String uuid) {
        return "GPU " + (index == null ? uuid : index);
    }

    /** "GPU {n}" 이름을 인덱스 숫자순으로 — "GPU 2" < "GPU 10". 인덱스가 없는 이름("GPU {UUID}")은 뒤에 문자열순 */
    public static int compareGpuName(String a, String b) {
        return HostGpuMetricsQueryService.compareIndex(a.substring("GPU ".length()), b.substring("GPU ".length()));
    }
}
