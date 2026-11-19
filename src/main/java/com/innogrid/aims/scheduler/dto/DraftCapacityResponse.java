package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.ExclusionReason;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 워크로드 수용 개수 모의 배치 응답 — 저장하지 않은 워크로드 초안 하나를 지금 노드에 차례로 놓아 몇 개까지 들어가는지 센 결과.
 * 아무것도 저장하거나 예약하지 않는다. 시각은 UTC다.
 */
@Schema(description = "워크로드 수용 개수 모의 배치 — 같은 워크로드 초안을 지금 노드에 차례로 놓아 몇 개까지 들어가는지 센 결과. "
        + "아무것도 저장하거나 예약하지 않습니다")
public record DraftCapacityResponse(
        @Schema(description = "계산 시각(UTC)", example = "2026-11-20T03:00:00Z")
        OffsetDateTime evaluatedAt,
        @Schema(description = "초안의 리소스 종류(비우면 VM). 이 종류로 배치 정책을 고릅니다", example = "VM")
        Workload.ResourceType resourceType,
        @Schema(description = "적용한 배치 정책 이름", example = "default")
        String policyName,
        @Schema(description = "놓아 볼 때 쓴 엔진", example = "weighted")
        String engine,
        @Schema(description = "이번 수집 후보 노드 수(제약 적용 전)", example = "3")
        int nodeCount,
        @Schema(description = "핵심 메트릭을 알 수 없어 수집에서 뺀 노드 수(/nodes/excluded 와 같은 목록)", example = "0")
        int excludedNodeCount,
        @Schema(description = "같은 초안을 차례로 놓아 들어간 개수", example = "7")
        int placeableCount,
        @Schema(description = "한 번에 세는 상한(max-workloads-per-run)", example = "500")
        int limit,
        @Schema(description = "상한까지 모두 들어가 멈췄는지. true 면 실제로 들어가는 개수는 상한 이상일 수 있습니다", example = "false")
        boolean truncated,
        @Schema(description = "멈춘 까닭 — 처음 들어가지 못한 시도에서 엔진이 낸 후보 없음 문장, 상한까지 들어갔으면 상한 문장, "
                + "수집 후보가 없으면 활성 노드가 없다는 문장. 후보 없음 문장은 엔진마다 정해진 한 문장이라 막은 제약을 가리키지 "
                + "않습니다 — 막은 제약은 stopExclusionCodes 와 nodes 의 stopCode 로 봅니다",
                example = "모든 후보 노드가 제약 조건을 충족하지 못합니다")
        String stopReason,
        @Schema(description = "처음 들어가지 못한 시도의 제외 사유 코드 집계 — 실행 상세 · 배치 이력의 exclusionCodes 와 같은 JSON 객체 문자열. "
                + "그런 시도가 없으면 null", example = "{\"INSUFFICIENT_RESOURCE\":2,\"MEMORY_USAGE_EXCEEDED\":1}")
        String stopExclusionCodes,
        @Schema(description = "수집 후보 노드마다 들어간 개수와 멈춘 사유(노드 id 순 · 0개인 노드 포함). 수집 후보가 없으면 빈 배열")
        List<Node> nodes,
        @Schema(description = "이 계산의 한계(고정 문장)")
        String limitation
) {
    public DraftCapacityResponse {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /** 수집 후보 노드 하나에 들어간 개수와, 처음 들어가지 못한 시도에서 이 노드가 빠진 사유. */
    @Schema(name = "DraftCapacityNode", description = "수집 후보 노드 하나에 들어간 개수와 멈춘 사유")
    public record Node(
            @Schema(description = "노드의 MDC id", example = "mdc-a")
            String mdcId,
            @Schema(description = "노드 id(/nodes 의 nodeId 와 같은 값)", example = "node-a:9100")
            String nodeId,
            @Schema(description = "이 노드에 들어간 개수", example = "3")
            int placedCount,
            @Schema(description = "처음 들어가지 못한 시도에서 이 노드가 빠진 사유. 상한까지 들어갔으면 null",
                    example = "자원 부족 (필요 CPU:2 MEM:1024MB GPU:0, 가용 CPU:1.0 MEM:512MB GPU:0)")
            String stopReason,
            @Schema(description = "그 사유의 제외 코드. 상한까지 들어갔으면 null", example = "INSUFFICIENT_RESOURCE")
            ExclusionReason stopCode
    ) {}
}
