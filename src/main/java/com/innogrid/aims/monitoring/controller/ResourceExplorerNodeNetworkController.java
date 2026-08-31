package com.innogrid.aims.monitoring.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.network.NetworkMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.network.NetworkMetricTrendResponse;
import com.innogrid.aims.monitoring.service.ResourceExplorerNodeNetworkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자원 탐색기 > 노드 Network 카테고리 패널 API.
 *
 * 트리에서 노드 하위 Network 항목을 선택했을 때 오른쪽 모니터링 탭 차트 9종
 * (Total/Recv/Sent, Packets Recv/Sent, Error In/Out, Drop In/Out)을 제공한다.
 * 개요 탭은 화면 설계 미확정 — 추가 시 CPU 패턴대로 summary/devices 를 확장한다.
 * 경로 패턴은 다른 카테고리와 동일하게 /node/{nodeId}/{category}/metrics/trend 를 따른다.
 * 노드 상세 패널은 ResourceExplorerNodeController 가 담당한다.
 */
@Tag(name = "자원 탐색기 (노드 Network)", description = "노드 Network 카테고리 — 모니터링 차트")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/node/{nodeId}/network")
public class ResourceExplorerNodeNetworkController {

    private final ResourceExplorerNodeNetworkService resourceExplorerNodeNetworkService;

    public ResourceExplorerNodeNetworkController(ResourceExplorerNodeNetworkService resourceExplorerNodeNetworkService) {
        this.resourceExplorerNodeNetworkService = resourceExplorerNodeNetworkService;
    }

    @Operation(summary = "Network 모니터링 차트",
            description = "모니터링 탭 — metric(NETWORK_TOTAL|NETWORK_RECV|NETWORK_SENT|"
                    + "NETWORK_PACKETS_RECV|NETWORK_PACKETS_SENT|NETWORK_ERROR_IN|NETWORK_ERROR_OUT|"
                    + "NETWORK_DROP_IN|NETWORK_DROP_OUT)별 시계열을 조회합니다. "
                    + "값은 원시 단위(B/s, pps, /s)이며 Kbps 등 표기 변환은 프론트가 담당합니다. "
                    + "from/to 미지정 시 오늘 00시~현재입니다.")
    @GetMapping("/metrics/trend")
    public ApiResponse<NetworkMetricTrendResponse> getNetworkMetricTrend(
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "NETWORK_TOTAL") NetworkMetric metric,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "300s") String step) {
        Instant toInstant = to != null ? Instant.parse(to) : Instant.now();
        Instant fromInstant = from != null ? Instant.parse(from) : todayStart();
        return ApiResponse.ok(resourceExplorerNodeNetworkService.getNetworkMetricTrend(
                nodeId, metric, fromInstant, toInstant, step));
    }

    /** from 기본값: 오늘 00:00:00 (서버 타임존) */
    private static Instant todayStart() {
        return LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }
}
