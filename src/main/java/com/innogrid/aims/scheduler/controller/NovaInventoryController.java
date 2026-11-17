package com.innogrid.aims.scheduler.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.scheduler.dto.NovaDryRunResponse;
import com.innogrid.aims.scheduler.dto.NovaInventoryResponse;
import com.innogrid.aims.scheduler.inventory.NovaInventoryGateway;
import com.innogrid.aims.scheduler.service.NovaDryRunService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Nova 인벤토리 조회와 모의 배치 — 실제 하이퍼바이저 · VM을 스케줄러 입력 모양으로 읽고, 그 위에서 저장 없이 다시 놓아 본다.
 *
 * <p>실행 경로의 노드 입력에는 쓰지 않는 확인 경로다. 실패도 200과 상태 값으로 돌려준다 — 화면 · 운영자가
 * 「실제로 0대」와 「연동이 안 됨」을 응답 하나로 가를 수 있게 하는 것이 목적이다. 포털 로그는 붙이지 않는다(화면이 부르지 않음).</p>
 */
@Tag(name = "스케줄러", description = "워크로드 스케줄링 (제약조건/전력/온도 기반)")
@RestController
@RequestMapping("/api/v1/scheduler")
public class NovaInventoryController {

    private final NovaInventoryGateway gateway;
    private final NovaDryRunService dryRunService;

    public NovaInventoryController(NovaInventoryGateway gateway, NovaDryRunService dryRunService) {
        this.gateway = gateway;
        this.dryRunService = dryRunService;
    }

    @Operation(summary = "Nova 인벤토리 조회",
               description = "OpenStack Nova 의 하이퍼바이저와 VM 을 읽어 스케줄러 노드 · 워크로드 입력 모양으로 돌려줍니다. 읽기 전용이며 저장하지 않습니다. "
                       + "조회 실패도 200 이고 status 로 구분합니다 — EMPTY 는 연동이 정상이고 하이퍼바이저가 0대인 경우이며, 나머지 실패 값이면 nodes · servers 가 비고 건수가 null 입니다. "
                       + "조회 전체 상한은 aims.scheduler.inventory.timeout-ms(기본 5초)이고, 앞선 조회가 아직 끝나지 않았으면(정상 진행 중 포함) Nova 를 다시 부르지 않고 바로 TIMEOUT 을 돌려줍니다(elapsedMs 가 0 에 가까움). "
                       + "주기적으로 부르지 마세요.")
    @GetMapping("/inventory/nova")
    public ApiResponse<NovaInventoryResponse> novaInventory() {
        return ApiResponse.ok(NovaInventoryResponse.from(gateway.read()));
    }

    @Operation(summary = "Nova 인벤토리 모의 배치",
               description = "Nova 의 쓸 수 있는 하이퍼바이저(state up · status enabled)를 빈 노드로 두고, 그 위에 있는 VM 을 요구 vCPU 큰 순으로 스케줄러가 저장 없이 다시 놓아 봅니다. "
                       + "정책은 VM 배치와 같은 규칙으로 고르고, 결과는 정책의 사용률 상한(놓기 전 값)과 물리 vCPU · 메모리로만 갈립니다 — 사용률 · 전력 · 온도 · 디스크는 보지 않으며 엔진에 따른 차이도 없습니다. "
                       + "기능 플래그 aims.scheduler.inventory.simulation-enabled(기본 false)가 꺼져 있으면 Nova 를 부르지 않고 status DISABLED 를 돌려줍니다. "
                       + "실패도 200 이고 status 로 구분합니다 — VM 목록만 못 읽어도, 쓸 수 있는 호스트 위 VM의 flavor를 못 읽어 다시 놓을 VM이 없어도 INVENTORY_FAILED입니다. "
                       + "워크로드 · 배치 이력 · 정책을 저장하지 않고 실행 계층에 넘기지 않습니다. "
                       + "부를 때마다 Nova 를 조회하므로 주기적으로 부르지 마세요.")
    @PostMapping("/inventory/nova/dry-run")
    public ApiResponse<NovaDryRunResponse> novaDryRun() {
        return ApiResponse.ok(dryRunService.dryRun());
    }
}
