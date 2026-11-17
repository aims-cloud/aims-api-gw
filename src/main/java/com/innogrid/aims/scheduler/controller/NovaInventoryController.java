package com.innogrid.aims.scheduler.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.scheduler.dto.NovaInventoryResponse;
import com.innogrid.aims.scheduler.inventory.NovaInventoryGateway;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Nova 인벤토리 조회 — 실제 하이퍼바이저 · VM 을 스케줄러 입력 모양으로 읽어 보여 준다.
 *
 * <p>배치 입력에는 아직 쓰지 않는 읽기 전용 확인 경로다. 조회 실패도 200 과 상태 값으로 돌려준다 — 화면 · 운영자가
 * 「실제로 0대」와 「연동이 안 됨」을 응답 하나로 가를 수 있게 하는 것이 목적이다.</p>
 */
@Tag(name = "스케줄러", description = "워크로드 스케줄링 (제약조건/전력/온도 기반)")
@RestController
@RequestMapping("/api/v1/scheduler")
public class NovaInventoryController {

    private final NovaInventoryGateway gateway;

    public NovaInventoryController(NovaInventoryGateway gateway) {
        this.gateway = gateway;
    }

    @Operation(summary = "Nova 인벤토리 조회",
               description = "OpenStack Nova 의 하이퍼바이저와 VM 을 읽어 스케줄러 노드 · 워크로드 입력 모양으로 돌려줍니다. 읽기 전용이며 저장하지 않습니다. "
                       + "조회 실패도 200 이고 status 로 구분합니다 — EMPTY 는 연동이 정상이고 하이퍼바이저가 0대인 경우이며, 나머지 실패 값이면 nodes · servers 가 비고 건수가 null 입니다. "
                       + "조회 전체 상한은 aims.scheduler.inventory.timeout-ms(기본 5초)이고, 앞선 조회가 아직 상류 응답을 기다리면 다시 부르지 않고 TIMEOUT 을 돌려줍니다. "
                       + "주기적으로 부르지 마세요.")
    @GetMapping("/inventory/nova")
    public ApiResponse<NovaInventoryResponse> novaInventory() {
        return ApiResponse.ok(NovaInventoryResponse.from(gateway.read()));
    }
}
