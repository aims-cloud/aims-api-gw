package com.innogrid.aims.scheduler.controller;

import java.util.List;

import org.springframework.web.bind.annotation.*;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.scheduler.dto.NodePlacementHoldRequest;
import com.innogrid.aims.scheduler.dto.NodePlacementHoldResponse;
import com.innogrid.aims.scheduler.service.NodePlacementHoldService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 노드 배치 중지 원장 API — 운영자가 노드 한 대를 새 배치에서 뺀다.
 *
 * <p>행이 있는 노드는 다음 배치부터 제약 평가의 맨 앞에서 빠진다(제외 사유 {@code NODE_PLACEMENT_HOLD}). 이미 배치되거나 실행
 * 중인 워크로드는 그대로이고, 그 예약은 {@code GET /nodes/reservations?nodeId=}로 본다. 다른 담당의 자산 원장 점검 상태는 읽지
 * 않으므로 자산을 점검으로 바꿔도 배치는 막히지 않는다. 화면이 부르지 않는 운영 경로라 포털 로그를 남기지 않는다.</p>
 *
 * <p>메서드 이름은 다른 원장 컨트롤러(list · get · create · update · delete)와 겹치지 않게 둔다. 겹치면 Swagger 가
 * operationId 에 번호를 붙이고, 그 뒤에 읽히는 컨트롤러(다른 담당 API 포함)의 operationId 번호가 하나씩 밀린다.</p>
 */
@Tag(name = "노드 배치 중지", description = "운영자가 새 배치를 받지 않게 한 노드의 원장. 행이 있는 노드는 다음 배치부터 후보에서 빠진다")
@RestController
@RequestMapping("/api/v1/scheduler/node-placement-holds")
public class NodePlacementHoldController {

    private final NodePlacementHoldService service;

    public NodePlacementHoldController(NodePlacementHoldService service) {
        this.service = service;
    }

    @Operation(summary = "배치 중지 목록", description = "노드 id 오름차순. nodeId 를 주면 그 문자열을 포함하는 노드만(부분 일치, 대소문자 무시).")
    @GetMapping
    public ApiResponse<List<NodePlacementHoldResponse>> listHolds(
            @Parameter(description = "노드 id 부분 일치 필터") @RequestParam(required = false) String nodeId) {
        return ApiResponse.ok(service.list(nodeId).stream().map(NodePlacementHoldResponse::from).toList());
    }

    @Operation(summary = "배치 중지 상세")
    @GetMapping("/{holdId}")
    public ApiResponse<NodePlacementHoldResponse> getHold(@PathVariable Long holdId) {
        return ApiResponse.ok(NodePlacementHoldResponse.from(service.require(holdId)));
    }

    @Operation(summary = "배치 중지 등록",
               description = "nodeId(/nodes 의 nodeId 와 같은 값 · 앞뒤 공백은 뺌 · 대소문자 구분 · 100자) 필수, reason(300자) 선택. "
                       + "다음 배치부터 그 노드는 제약 평가의 맨 앞에서 빠지고 배치 이력의 제외 사유 코드는 NODE_PLACEMENT_HOLD 입니다. "
                       + "이미 배치되거나 실행 중인 워크로드는 그대로입니다(그 예약은 GET /nodes/reservations?nodeId= 로 확인). "
                       + "노드의 존재는 검사하지 않습니다. 자산 원장의 점검 상태는 읽지 않고, Nova 인벤토리 모의 배치는 이 원장을 보지 않습니다. "
                       + "같은 노드를 다시 등록하면 409. 만료 시각은 없고 해제(DELETE)해야 풀립니다.")
    @PostMapping
    public ApiResponse<NodePlacementHoldResponse> createHold(
            @RequestBody(required = false) NodePlacementHoldRequest request) {
        return ApiResponse.ok(NodePlacementHoldResponse.from(service.create(request)));
    }

    @Operation(summary = "배치 중지 사유 수정",
               description = "사유만 고칩니다. reason 키가 없으면 유지, null 이면 비웁니다. nodeId 를 보내면 지금 값과 같아야 하고 다르면 400 "
                       + "(노드를 바꾸려면 해제 뒤 새로 등록). createdAt(중지 시작 시각)은 그대로입니다.")
    @PutMapping("/{holdId}")
    public ApiResponse<NodePlacementHoldResponse> updateHold(
            @PathVariable Long holdId, @RequestBody(required = false) NodePlacementHoldRequest request) {
        return ApiResponse.ok(NodePlacementHoldResponse.from(service.update(holdId, request)));
    }

    @Operation(summary = "배치 중지 해제", description = "지우면 다음 배치부터 그 노드는 다시 후보가 됩니다.")
    @DeleteMapping("/{holdId}")
    public ApiResponse<Void> deleteHold(@PathVariable Long holdId) {
        service.delete(holdId);
        return ApiResponse.ok(null);
    }
}
