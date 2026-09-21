package com.innogrid.aims.scheduler.controller;

import java.util.List;

import org.springframework.web.bind.annotation.*;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.scheduler.dto.NodeExporterMappingRequest;
import com.innogrid.aims.scheduler.dto.NodeExporterMappingResponse;
import com.innogrid.aims.scheduler.service.NodeExporterMappingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 익스포터 대응 원장 API — 노드 한 대의 DCGM·IPMI 시리즈가 어떤 {@code instance} 로 저장소에 있는지 운영자가 적는다.
 *
 * <p>프로메테우스의 {@code instance} 라벨은 익스포터마다 다르다(노드 {@code host:9100}, DCGM {@code host:9400},
 * IPMI 는 중앙 주소). 수집기는 노드 익스포터의 값을 키로 GPU 개수·전력·온도를 찾아 왔으므로 실장비에서는 셋 다
 * 빈손이었다. 여기에 값을 넣으면 다음 수집부터 그 노드의 GPU 개수·전력·온도가 이어진다. 비어 있으면 수집기가
 * 호스트명 일치로 대체하고, 그것도 안 되면 노드 instance 를 그대로 쓴다.</p>
 */
@Tag(name = "익스포터 대응", description = "노드별 DCGM·IPMI instance 원장. 값을 넣으면 다음 수집부터 GPU 개수·전력·온도가 이어진다")
@RestController
@RequestMapping("/api/v1/scheduler/node-exporter-mappings")
public class NodeExporterMappingController {

    private final NodeExporterMappingService service;

    public NodeExporterMappingController(NodeExporterMappingService service) {
        this.service = service;
    }

    @Operation(summary = "익스포터 대응 목록", description = "노드 식별자 오름차순. nodeId 를 주면 그 문자열을 포함하는 노드만(부분 일치, 대소문자 무시).")
    @GetMapping
    public ApiResponse<List<NodeExporterMappingResponse>> list(
            @Parameter(description = "노드 식별자 부분 일치 필터") @RequestParam(required = false) String nodeId) {
        return ApiResponse.ok(service.list(nodeId).stream().map(NodeExporterMappingResponse::from).toList());
    }

    @Operation(summary = "익스포터 대응 상세")
    @GetMapping("/{mappingId}")
    public ApiResponse<NodeExporterMappingResponse> get(@PathVariable Long mappingId) {
        return ApiResponse.ok(NodeExporterMappingResponse.from(service.require(mappingId)));
    }

    @Operation(summary = "익스포터 대응 등록",
               description = "nodeId(노드 익스포터의 instance 라벨 그대로) 필수. dcgmInstance·ipmiInstance 는 선택 — 비우면 수집기가 "
                       + "포트를 뗀 호스트명이 같은 시리즈가 정확히 하나일 때 그것을 쓰고, 아니면 노드 instance 를 그대로 씁니다. "
                       + "중앙 IPMI 익스포터처럼 호스트명이 다른 형태는 이 칸에 적는 길밖에 없습니다. 노드 식별자와 같은 값은 뜻이 없어 400. "
                       + "instance 의 존재는 검증하지 않습니다 — 오타면 그 노드만 미계측으로 남습니다. 같은 노드를 다시 등록하면 409.")
    @PostMapping
    public ApiResponse<NodeExporterMappingResponse> create(@RequestBody(required = false) NodeExporterMappingRequest request) {
        return ApiResponse.ok(NodeExporterMappingResponse.from(service.create(request)));
    }

    @Operation(summary = "익스포터 대응 수정",
               description = "부분 수정 — 요청에 있는 키만 반영합니다. dcgmInstance·ipmiInstance·description 에 null 을 주면 비웁니다. "
                       + "노드 식별자를 다른 노드의 것으로 바꾸면 409.")
    @PutMapping("/{mappingId}")
    public ApiResponse<NodeExporterMappingResponse> update(@PathVariable Long mappingId,
                                                           @RequestBody(required = false) NodeExporterMappingRequest request) {
        return ApiResponse.ok(NodeExporterMappingResponse.from(service.update(mappingId, request)));
    }

    @Operation(summary = "익스포터 대응 삭제", description = "지우면 다음 수집부터 호스트명 일치와 그대로로 돌아갑니다.")
    @DeleteMapping("/{mappingId}")
    public ApiResponse<Void> delete(@PathVariable Long mappingId) {
        service.delete(mappingId);
        return ApiResponse.ok(null);
    }
}
