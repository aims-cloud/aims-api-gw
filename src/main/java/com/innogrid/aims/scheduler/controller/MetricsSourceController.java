package com.innogrid.aims.scheduler.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.scheduler.metrics.SwitchingMetricsGateway;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 스케줄러가 지금 어떤 메트릭 소스를 보는지 — 실제 저장소({@code victoria})인지 가상 클러스터({@code synthetic})인지.
 * 모의 모드가 켜진 채 운영되는 것을 화면·운영자가 알아볼 수 있게 하는 유일한 표시다(기존 응답에는 표시를 넣지 않는다).
 */
@Tag(name = "스케줄러", description = "워크로드 스케줄링 (제약조건/전력/온도 기반)")
@RestController
@RequestMapping("/api/v1/scheduler")
public class MetricsSourceController {

    private final SwitchingMetricsGateway gateway;

    public MetricsSourceController(SwitchingMetricsGateway gateway) {
        this.gateway = gateway;
    }

    @Operation(summary = "메트릭 소스",
               description = "source 가 synthetic 이면 모의 메트릭 모드(AIMS_SCHEDULER_MOCK_ENABLED=true)이며 clusterFile·clusterName·mdcCount·nodeCount 가 채워집니다. "
                       + "victoria 면 실제 저장소이고 나머지는 null. 6필드.")
    @GetMapping("/metrics-source")
    public ApiResponse<SwitchingMetricsGateway.MetricsSource> metricsSource() {
        return ApiResponse.ok(gateway.describe());
    }
}
