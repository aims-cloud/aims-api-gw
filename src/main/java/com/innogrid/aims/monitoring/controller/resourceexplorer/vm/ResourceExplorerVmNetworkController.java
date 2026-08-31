package com.innogrid.aims.monitoring.controller.resourceexplorer.vm;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.network.VmNetworkMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.network.VmNetworkMetricTrendResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.vm.ResourceExplorerVmNetworkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자원 탐색기 > VM Network 카테고리 패널 API.
 *
 * 트리에서 VM 하위 Network 항목을 선택했을 때 오른쪽 모니터링 탭 차트 8종
 * (Recv/Sent, Packets Recv/Sent, Error In/Out, Drop In/Out)을 제공한다.
 * 노드 Network(9종)에서 Total 을 제외한 구성이다.
 * 개요 탭은 화면 설계 미확정 — 추가 시 CPU 패턴대로 확장한다.
 * VM 상세 패널은 ResourceExplorerVmController 가 담당한다.
 */
@Tag(name = "자원 탐색기 (VM Network)", description = "VM Network 카테고리 — 모니터링 차트")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/vm/{vmId}/network")
public class ResourceExplorerVmNetworkController {

    private final ResourceExplorerVmNetworkService resourceExplorerVmNetworkService;

    public ResourceExplorerVmNetworkController(ResourceExplorerVmNetworkService resourceExplorerVmNetworkService) {
        this.resourceExplorerVmNetworkService = resourceExplorerVmNetworkService;
    }

    @Operation(summary = "VM Network 모니터링 차트",
            description = "모니터링 탭 — metric(NETWORK_RECV|NETWORK_SENT|NETWORK_PACKETS_RECV|"
                    + "NETWORK_PACKETS_SENT|NETWORK_ERROR_IN|NETWORK_ERROR_OUT|NETWORK_DROP_IN|NETWORK_DROP_OUT)별 "
                    + "시계열을 조회합니다. 값은 원시 단위(B/s, pps, /s)이며 Kbps 등 표기 변환은 프론트가 담당합니다. "
                    + "from/to 미지정 시 오늘 00시~현재입니다.")
    @GetMapping("/metrics/trend")
    public ApiResponse<VmNetworkMetricTrendResponse> getNetworkMetricTrend(
            @PathVariable String vmId,
            @RequestParam(defaultValue = "NETWORK_RECV") VmNetworkMetric metric,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "300s") String step) {
        Instant toInstant = to != null ? Instant.parse(to) : Instant.now();
        Instant fromInstant = from != null ? Instant.parse(from) : todayStart();
        return ApiResponse.ok(resourceExplorerVmNetworkService.getNetworkMetricTrend(
                vmId, metric, fromInstant, toInstant, step));
    }

    /** from 기본값: 오늘 00:00:00 (서버 타임존) */
    private static Instant todayStart() {
        return LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }
}
