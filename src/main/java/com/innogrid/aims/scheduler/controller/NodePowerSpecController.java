package com.innogrid.aims.scheduler.controller;

import java.util.List;

import org.springframework.web.bind.annotation.*;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.scheduler.dto.NodePowerSpecRequest;
import com.innogrid.aims.scheduler.dto.NodePowerSpecResponse;
import com.innogrid.aims.scheduler.service.NodePowerSpecService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 노드 전력 사양 원장 API — 노드별 정격 전력(W)을 운영자가 입력한다.
 *
 * <p>IPMI 는 정격을 알려 주지 않아 지금까지 모든 노드를 설정 기본값 하나로 봤다. 여기에 값을 넣으면
 * 다음 수집부터 그 노드의 정격이 바뀌고, 배치 제외 판정·전력 점수·회수 가능 전력·통합 절감 추정이 함께 바뀐다.
 * 원장이 비어 있으면 종전과 같이 설정값을 쓴다.</p>
 */
@Tag(name = "노드 전력 사양", description = "노드별 정격 전력(W) 원장. 값을 넣으면 다음 수집부터 전력 판단에 반영된다")
@RestController
@RequestMapping("/api/v1/scheduler/node-power-specs")
public class NodePowerSpecController {

    private final NodePowerSpecService service;

    public NodePowerSpecController(NodePowerSpecService service) {
        this.service = service;
    }

    @Operation(summary = "전력 사양 목록", description = "노드 식별자 오름차순. nodeId 를 주면 그 문자열을 포함하는 노드만(부분 일치, 대소문자 무시).")
    @GetMapping
    public ApiResponse<List<NodePowerSpecResponse>> list(
            @Parameter(description = "노드 식별자 부분 일치 필터") @RequestParam(required = false) String nodeId) {
        return ApiResponse.ok(service.list(nodeId).stream().map(NodePowerSpecResponse::from).toList());
    }

    @Operation(summary = "전력 사양 상세")
    @GetMapping("/{specId}")
    public ApiResponse<NodePowerSpecResponse> get(@PathVariable Long specId) {
        return ApiResponse.ok(NodePowerSpecResponse.from(service.require(specId)));
    }

    @Operation(summary = "전력 사양 등록",
               description = "nodeId(Prometheus instance 라벨과 같은 문자열)와 capacityWatts 필수. 정격은 0 보다 큰 유한한 값이면 무엇이든 받는다 "
                       + "— 실데이터가 없어 크기 상한을 두지 않았다. **입력값을 그대로 판정에 쓰므로** 값이 너무 크면 그 노드는 전력으로 걸러지지 않고, "
                       + "너무 작으면 항상 제외된다. source 는 MANUAL(기본)·SPEC_SHEET·MEASURED(예약), 대소문자 무시. 같은 노드를 다시 등록하면 409.")
    @PostMapping
    public ApiResponse<NodePowerSpecResponse> create(@RequestBody(required = false) NodePowerSpecRequest request) {
        return ApiResponse.ok(NodePowerSpecResponse.from(service.create(request)));
    }

    @Operation(summary = "전력 사양 수정",
               description = "부분 수정 — 요청에 있는 키만 반영합니다. description 에 null 을 주면 비웁니다. 노드 식별자를 다른 노드의 것으로 바꾸면 409.")
    @PutMapping("/{specId}")
    public ApiResponse<NodePowerSpecResponse> update(@PathVariable Long specId,
                                                     @RequestBody(required = false) NodePowerSpecRequest request) {
        return ApiResponse.ok(NodePowerSpecResponse.from(service.update(specId, request)));
    }

    @Operation(summary = "전력 사양 삭제", description = "지우면 다음 수집부터 설정 기본값으로 돌아갑니다.")
    @DeleteMapping("/{specId}")
    public ApiResponse<Void> delete(@PathVariable Long specId) {
        service.delete(specId);
        return ApiResponse.ok(null);
    }
}
