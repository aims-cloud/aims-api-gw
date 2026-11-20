package com.innogrid.aims.monitoring.controller.resourceexplorer.vm;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoDeviceListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.vm.ResourceExplorerVmDiskIoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자원 탐색기 > VM Disk I/O 카테고리 패널 API.
 *
 * 트리에서 VM 하위 Disk &gt; Disk I/O 항목을 선택했을 때 오른쪽 패널의 탭별 데이터를 제공한다.
 *   개요 탭     — KPI 카드(장치 수, 읽기·쓰기 B/s, 읽기·쓰기 IOPS, 읽기·쓰기 지연, 사용률), 블록 장치 목록
 *   모니터링 탭 — 지표 7종 시계열 차트 (전체 합산 또는 장치 하나)
 * VM 은 다른 VM 패널과 같이 nsId·infraId·vmId 쿼리 파라미터로 식별한다(트리 VIRTUAL_MACHINE 노드의 같은 이름 필드).
 * 응답 형식은 노드 Disk I/O 패널(ResourceExplorerNodeDiskIoController)과 같다. 스코프 밖·없는 VM 은 404 다.
 */
@Tag(name = "관측 > 자원 탐색기 (VM Disk I/O)", description = "VM Disk I/O 카테고리 — KPI 카드, 블록 장치 목록, 모니터링 차트")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/vm/disk-io")
public class ResourceExplorerVmDiskIoController {

    private final ResourceExplorerVmDiskIoService resourceExplorerVmDiskIoService;

    public ResourceExplorerVmDiskIoController(ResourceExplorerVmDiskIoService resourceExplorerVmDiskIoService) {
        this.resourceExplorerVmDiskIoService = resourceExplorerVmDiskIoService;
    }

    @Operation(summary = "VM Disk I/O 개요 KPI",
            description = "개요 탭 카드 — 장치 수, 읽기·쓰기 B/s, 읽기·쓰기 IOPS, 읽기·쓰기 평균 지연(ms, IOPS 가중), 평균 사용률(%). "
                    + "VM 안 node_exporter 를 tb_ns/tb_infra/tb_node 라벨로 조회한 최근 5분 rate 이며 loop·ram·dm-·sr 장치는 제외합니다. "
                    + "미수집 값은 null, 장치가 없으면 장치 수 0 입니다.")
    @GetMapping("/summary")
    public ApiResponse<DiskIoSummaryResponse> getDiskIoSummary(@RequestParam(required = false) String roleName,
                                                               @RequestParam String nsId,
                                                               @RequestParam String infraId,
                                                               @RequestParam String vmId) {
        return ApiResponse.ok(resourceExplorerVmDiskIoService.getDiskIoSummary(roleName, nsId, infraId, vmId));
    }

    @Operation(summary = "VM Disk I/O 장치 목록",
            description = "개요 탭 블록 장치 목록 — 장치/읽기·쓰기 B/s/읽기·쓰기 IOPS/읽기·쓰기 지연(ms)/사용률(%). "
                    + "구간에 I/O 가 없는 장치의 지연은 null 입니다. "
                    + "컬럼 정렬(device|readBytesPerSec|writeBytesPerSec|readIops|writeIops|readLatencyMs|writeLatencyMs|utilizationPercent)과 "
                    + "페이징을 지원합니다. page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다.")
    @GetMapping("/devices")
    public ApiResponse<PageResponse<DiskIoDeviceListItemResponse>> getDiskIoDevices(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "device,asc") String sort) {
        return ApiResponse.ok(resourceExplorerVmDiskIoService.getDiskIoDevices(roleName, nsId, infraId, vmId, page, size, sort));
    }

    @Operation(summary = "VM Disk I/O 모니터링 차트",
            description = "모니터링 탭 — metric(DISK_READ_BYTES|DISK_WRITE_BYTES|DISK_READ_IOPS|DISK_WRITE_IOPS|"
                    + "DISK_READ_LATENCY|DISK_WRITE_LATENCY|DISK_UTILIZATION)별 시계열(B/s, IOPS, ms, %)을 조회합니다. "
                    + "device 를 주면 그 장치만, 없으면 장치 전체 합산(지연은 IOPS 가중, 사용률은 평균)입니다. "
                    + "interval(LAST_10M|LAST_1H|LAST_6H|LAST_24H|LAST_30D|LAST_90D)은 조회 기간(지금 기준)이며 "
                    + "버킷 간격은 기간별 기본값(30s/5m/15m/1h/1d/3d)입니다. 미수집이면 빈 시리즈입니다.")
    @GetMapping("/metrics/trend")
    public ApiResponse<DiskIoMetricTrendResponse> getDiskIoMetricTrend(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId,
            @RequestParam(defaultValue = "DISK_READ_BYTES") DiskIoMetric metric,
            @RequestParam(required = false) String device,
            @RequestParam(defaultValue = "LAST_10M") NodeMetricInterval interval) {
        return ApiResponse.ok(resourceExplorerVmDiskIoService.getDiskIoMetricTrend(roleName, nsId, infraId, vmId, metric, device, interval));
    }
}
