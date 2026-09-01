package com.innogrid.aims.monitoring.controller.resourceexplorer.vm;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeDetailMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.VmInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.VmSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.vm.ResourceExplorerVmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자원 탐색기 > VM 상세 패널 API.
 *
 * 트리에서 가상머신을 선택했을 때 오른쪽 패널(개요·모니터링 탭)의 데이터를 제공한다.
 *   개요 탭     — 주요 지표 카드 5종, 모니터링 요약 차트 6종, VM 정보 표
 *   모니터링 탭 — 지표별 시계열 (개요 요약 차트와 metrics/trend 공용)
 * VM 하위 카테고리(CPU/Disk/…/GPU)는 화면 확정 시 노드 카테고리 패턴으로 별도 추가한다.
 * 가상머신 그룹 패널은 ResourceExplorerVmGroupController 가 담당한다.
 */
@Tag(name = "관측 > 자원 탐색기 (VM)", description = "VM 상세 — 주요 지표, 모니터링 요약 차트, VM 정보")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/vm/{vmId}")
public class ResourceExplorerVmController {

    private final ResourceExplorerVmService resourceExplorerVmService;

    public ResourceExplorerVmController(ResourceExplorerVmService resourceExplorerVmService) {
        this.resourceExplorerVmService = resourceExplorerVmService;
    }

    @Operation(summary = "VM 주요 지표",
            description = "개요 탭 카드 5종 — CPU/Memory/File System 사용률(용량 카드는 Free/Total GB 포함), "
                    + "네트워크 수신·송신(bytes/sec, 단위 변환은 프론트), GPU 사용률(VRAM Free/Total GB 병기), "
                    + "최근 업데이트 시각을 조회합니다.")
    @GetMapping("/summary")
    public ApiResponse<VmSummaryResponse> getVmSummary(@PathVariable String vmId) {
        return ApiResponse.ok(resourceExplorerVmService.getVmSummary(vmId));
    }

    @Operation(summary = "VM 모니터링 차트",
            description = "metric(CPU|MEMORY|FILE_SYSTEM|NETWORK|GPU|VRAM)별 시계열 — 개요 탭 모니터링 요약과 "
                    + "모니터링 탭에 공용입니다. NETWORK 는 receive/transmit 2개 시리즈를 반환합니다. "
                    + "from/to 미지정 시 오늘 00시~현재입니다.")
    @GetMapping("/metrics/trend")
    public ApiResponse<NodeMetricTrendResponse> getVmMetricTrend(
            @PathVariable String vmId,
            @RequestParam(defaultValue = "CPU") NodeDetailMetric metric,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "300s") String step) {
        Instant toInstant = to != null ? Instant.parse(to) : Instant.now();
        Instant fromInstant = from != null ? Instant.parse(from) : todayStart();
        return ApiResponse.ok(resourceExplorerVmService.getVmMetricTrend(
                vmId, metric, fromInstant, toInstant, step));
    }

    @Operation(summary = "VM 정보",
            description = "개요 탭 VM 정보 표 — ID/가상 시스템명/배포영역/프로젝트/IP/사양/네트워크/노드명/"
                    + "이미지/카탈로그/시스템 상태와 우상단 배지용 프로바이더·플랫폼 유형을 조회합니다.")
    @GetMapping("/info")
    public ApiResponse<VmInfoResponse> getVmInfo(@PathVariable String vmId) {
        return ApiResponse.ok(resourceExplorerVmService.getVmInfo(vmId));
    }

    /** from 기본값: 오늘 00:00:00 (서버 타임존) */
    private static Instant todayStart() {
        return LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }
}
