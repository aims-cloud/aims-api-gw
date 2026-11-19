package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.ReservationJudgment;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 노드 비우기 모의 배치 응답 — 노드 한 대를 비운다고 할 때, 그 노드에 배정된 배치 대기 · 실행 중 워크로드를 나머지 노드에 차례로
 * 놓아 본 결과. 아무것도 옮기거나 저장하지 않는다.
 *
 * <p>대상 행은 같은 {@code evaluatedAt}의 노드별 예약 내역({@code GET /nodes/reservations?nodeId=})과 같은 판정에서 나온다.
 * 시각은 UTC다.</p>
 */
@Schema(description = "노드 비우기 모의 배치 — 노드 한 대를 비운다고 할 때 그 노드의 배치 대기 · 실행 중 워크로드를 나머지 노드에 "
        + "차례로 놓아 본 결과. 아무것도 옮기거나 저장하지 않습니다")
public record NodeDrainPreviewResponse(
        @Schema(description = "판정 시각(UTC). 대상 행의 예약 판정과 같은 시각", example = "2026-11-20T03:00:00Z")
        OffsetDateTime evaluatedAt,
        @Schema(description = "비울 노드 id(앞뒤 공백을 뺀 값 · /nodes 의 nodeId 와 같은 값)", example = "node-a:9100")
        String nodeId,
        @Schema(description = "이번 수집 후보에 이 노드가 있었는지. false 면 수집 제외(/nodes/excluded)이거나 메트릭이 없는 노드입니다",
                example = "true")
        boolean collected,
        @Schema(description = "노드 배치 중지 원장(/node-placement-holds)에 있는지", example = "false")
        boolean held,
        @Schema(description = "배치 중지 사유. 중지가 아니거나 사유가 없으면 null", example = "디스크 교체 점검")
        String holdReason,
        @Schema(description = "옮길 곳 후보로 본 다른 수집 노드 수(제약 적용 전)", example = "3")
        int otherNodeCount,
        @Schema(description = "이 노드에 배정된 배치 대기 · 실행 중 워크로드 수(상한 적용 전). 같은 판정 시각의 노드별 예약 내역 행 수와 같습니다",
                example = "2")
        int workloadCount,
        @Schema(description = "옮길 곳을 찾은 건수", example = "2")
        int placeableCount,
        @Schema(description = "대상이 한 실행 처리 상한(max-workloads-per-run)을 넘어 앞부분만 평가했는지", example = "false")
        boolean truncated,
        @Schema(description = "상한에 걸리지 않고 모든 대상이 옮길 곳을 찾았는지. 대상이 0건이면 true. 실행 중 워크로드는 옮길 수단이 "
                + "없으므로 true 여도 노드를 바로 꺼도 된다는 뜻이 아닙니다", example = "true")
        boolean allPlaceable,
        @Schema(description = "놓아 본 순서대로 — 배치 대기 먼저, 실행 중 다음, 같은 묶음 안은 우선순위 → 등록 순")
        List<Decision> decisions,
        @Schema(description = "이 계산의 한계(고정 문장)")
        String limitation
) {
    public NodeDrainPreviewResponse {
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    /** 대상 워크로드 하나를 나머지 노드에 놓아 본 결과. 결과 필드 이름은 스케줄 모의 실행의 결정과 같다. */
    @Schema(name = "NodeDrainPreviewDecision", description = "대상 워크로드 하나를 나머지 노드에 놓아 본 결과 — "
            + "success · selectedMdcId · selectedNodeId · finalScore · reason · exclusionCodes 는 "
            + "스케줄 모의 실행의 결정과 같은 뜻입니다")
    public record Decision(
            @Schema(description = "워크로드 id", example = "12")
            Long workloadId,
            @Schema(description = "워크로드 이름", example = "train-01")
            String workloadName,
            @Schema(description = "SCHEDULED(배치 대기) · RUNNING(실행 중)", example = "SCHEDULED")
            Workload.WorkloadStatus status,
            @Schema(description = "노드별 예약 내역과 같은 판정. FULL · GPU_ONLY · EXPIRED(보존 기한이 지나 자원을 묶지 않는 배치 대기 · "
                    + "노드를 비우려면 이것도 풀어야 하므로 같은 규칙으로 놓아 봄)", example = "FULL")
            ReservationJudgment judgment,
            @Schema(description = "워크로드 리소스 종류(비어 있으면 VM). 이 종류로 배치 정책을 고릅니다", example = "VM")
            Workload.ResourceType resourceType,
            @Schema(description = "적용한 배치 정책 이름", example = "default")
            String policyName,
            @Schema(description = "놓아 볼 때 쓴 엔진", example = "weighted")
            String engine,
            @Schema(description = "옮길 곳을 찾았는지", example = "true")
            boolean success,
            @Schema(description = "옮길 노드의 MDC id. 실패면 null", example = "mdc-a")
            String selectedMdcId,
            @Schema(description = "옮길 노드 id. 실패면 null", example = "node-b:9100")
            String selectedNodeId,
            @Schema(description = "옮길 노드의 총점. 실패면 0", example = "0.72")
            double finalScore,
            @Schema(description = "성공이면 선택 근거, 실패면 제외 사유", example = "옮길 다른 노드가 없습니다")
            String reason,
            @Schema(description = "후보 노드의 제외 사유 코드 집계 — 실행 상세 · 배치 이력의 exclusionCodes 와 같은 JSON 객체 문자열. "
                    + "제외가 없으면 null", example = "{\"NODE_PLACEMENT_HOLD\":1}")
            String exclusionCodes
    ) {}
}
