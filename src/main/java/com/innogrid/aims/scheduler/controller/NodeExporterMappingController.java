package com.innogrid.aims.scheduler.controller;

import java.util.List;

import org.springframework.web.bind.annotation.*;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.scheduler.dto.ExporterInstanceCandidatesResponse;
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
@Tag(name = "노드 익스포터 instance 대응", description = "노드별 DCGM·IPMI instance 원장. 값을 넣으면 다음 수집부터 GPU 개수·전력·온도가 이어진다")
@RestController
@RequestMapping("/api/v1/scheduler/node-exporter-mappings")
public class NodeExporterMappingController {

    private final NodeExporterMappingService service;

    public NodeExporterMappingController(NodeExporterMappingService service) {
        this.service = service;
    }

    @Operation(summary = "익스포터 instance 대응 목록", description = "노드 식별자 오름차순. nodeId 를 주면 그 문자열을 포함하는 노드만(부분 일치, 대소문자 무시).")
    @GetMapping
    public ApiResponse<List<NodeExporterMappingResponse>> list(
            @Parameter(description = "노드 식별자 부분 일치 필터") @RequestParam(required = false) String nodeId) {
        return ApiResponse.ok(service.list(nodeId).stream().map(NodeExporterMappingResponse::from).toList());
    }

    @Operation(summary = "익스포터 instance 후보",
               description = "저장소에 지금 있는 DCGM·IPMI 시리즈의 instance 와, 수집기가 그것을 어느 노드에 붙이는지(assignedBy — "
                       + "LEDGER 원장 · HOSTNAME 호스트명 일치 · SAME_AS_NODE 노드 instance 그대로 · AMBIGUOUS 둘 이상이 가리켜 자기 instance 로 되돌림 · "
                       + "UNASSIGNED 어느 노드도 못 얻음 → 원장에 적어야 이어짐 · UNKNOWN 노드 목록을 못 받아 배정 불명). claimedByNodeIds 는 충돌 규칙 전에 "
                       + "그 instance 로 해석되는 노드 전부, ledgerNodeIds 는 원장에서 그것을 적은 노드 전부. gpuCount 는 DCGM 시리즈 수(= 카드 수). "
                       + "ledgerOnly* 는 원장에 있으나 저장소에 없는 instance(오타·익스포터 다운) — 그 질의가 답했을 때만 채워집니다. "
                       + "unavailable 에 질의 이름(dcgm·ipmi·nodes)이 있으면 그 목록은 답을 못 받은 것이라 비었다고 읽으면 안 됩니다. "
                       + "메트릭 값은 쓰지 않고 count by(instance) 의 라벨과 시리즈 수만 봅니다. 저장소 질의 세 건을 동시에 던져 상한 한 번을 기다리며 캐시는 없습니다 — "
                       + "화면 진입·새로고침·원장 저장 직후에만 부르고 폴링하지 마십시오.")
    @GetMapping("/candidates")
    public ApiResponse<ExporterInstanceCandidatesResponse> candidates() {
        return ApiResponse.ok(ExporterInstanceCandidatesResponse.from(service.candidates()));
    }

    @Operation(summary = "익스포터 instance 대응 상세")
    @GetMapping("/{mappingId}")
    public ApiResponse<NodeExporterMappingResponse> get(@PathVariable Long mappingId) {
        return ApiResponse.ok(NodeExporterMappingResponse.from(service.require(mappingId)));
    }

    @Operation(summary = "익스포터 instance 대응 등록",
               description = "nodeId(노드 익스포터의 instance 라벨 그대로) 필수. dcgmInstance·ipmiInstance 는 선택 — 비우면 수집기가 "
                       + "포트를 뗀 호스트명이 같은 시리즈가 정확히 하나일 때 그것을 쓰고, 아니면 노드 instance 를 그대로 씁니다. "
                       + "중앙 IPMI 익스포터처럼 호스트명이 다른 형태는 이 칸에 적는 길밖에 없습니다. 노드 식별자와 같은 값은 뜻이 없어 400. "
                       + "instance 의 존재는 검증하지 않습니다 — 오타면 그 노드만 미계측으로 남습니다. 같은 노드를 다시 등록하면 409(대소문자만 달라도). "
                       + "다른 노드가 이미 쓰는 instance 도 409 — 한 시리즈를 두 노드에 넣으면 값이 두 번 세어져 합계가 부풉니다.")
    @PostMapping
    public ApiResponse<NodeExporterMappingResponse> create(@RequestBody(required = false) NodeExporterMappingRequest request) {
        return ApiResponse.ok(NodeExporterMappingResponse.from(service.create(request)));
    }

    @Operation(summary = "익스포터 instance 대응 수정",
               description = "부분 수정 — 요청에 있는 키만 반영합니다. dcgmInstance·ipmiInstance·description 에 null 을 주면 비웁니다. "
                       + "노드 식별자를 다른 노드의 것으로 바꾸면 409.")
    @PutMapping("/{mappingId}")
    public ApiResponse<NodeExporterMappingResponse> update(@PathVariable Long mappingId,
                                                           @RequestBody(required = false) NodeExporterMappingRequest request) {
        return ApiResponse.ok(NodeExporterMappingResponse.from(service.update(mappingId, request)));
    }

    @Operation(summary = "익스포터 instance 대응 삭제", description = "지우면 다음 수집부터 호스트명 일치와 그대로로 돌아갑니다.")
    @DeleteMapping("/{mappingId}")
    public ApiResponse<Void> delete(@PathVariable Long mappingId) {
        service.delete(mappingId);
        return ApiResponse.ok(null);
    }
}
