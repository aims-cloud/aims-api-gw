package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 화면에는 아직 이 항목이 없다. 명세의 제약 조건 기반 배치 항목이 실제로 무엇을 근거로
 * 어느 노드를 골랐는지 보이려면 결정 단위 기록이 필요해서 만들었다.
 */
@Schema(description = "실행 1회의 요약과 워크로드별 배치 결정 목록")
public record ScheduleRunDetailResponse(
        @Schema(description = "실행 요약(실행 이력 1행과 같은 형태)")
        ScheduleRunResponse run,
        @Schema(description = "이 실행이 내린 워크로드별 배치 결정, 결정 시각 오름차순")
        List<Decision> decisions
) {
    public ScheduleRunDetailResponse {
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    @Schema(description = "워크로드 한 건에 대한 배치 결정 — 어느 노드를 왜 골랐는지, 또는 왜 못 골랐는지")
    public record Decision(
            @Schema(description = "배치 이력 PK")
            Long historyId,
            @Schema(description = "대상 워크로드 id")
            Long workloadId,
            @Schema(description = "대상 워크로드 이름")
            String workloadName,
            @Schema(description = "배치 성공 여부. 실행이 만든 결정이라 항상 채워진다")
            Boolean success,
            @Schema(description = "선택한 MDC id. 실패면 null")
            String selectedMdcId,
            @Schema(description = "선택한 노드 id. 실패면 null")
            String selectedNodeId,
            @Schema(description = "선택 노드의 총점. 실패면 0")
            double finalScore,
            @Schema(description = "성공이면 선택 근거, 실패면 제외 사유")
            String reason,
            @Schema(description = "이 결정에서 후보로 본 노드 수. 핵심 메트릭을 못 읽어 수집 단계에서 빠진 노드는 세지 않고, "
                    + "제약으로 걸러진 노드는 포함한다")
            int candidateCount,
            @Schema(description = "결정에 쓴 엔진 id — run.engineId 와 같은 값이다(이름이 아니라 id)")
            String engineName,
            @Schema(description = "결정에 쓴 배치 정책 이름")
            String policyName,
            @Schema(description = "결정 시각(UTC)")
            OffsetDateTime decidedAt
    ) {
        public static Decision from(SchedulingHistory h) {
            return new Decision(
                    h.getId(), h.getWorkloadId(), h.getWorkloadName(),
                    h.getSuccess(), h.getSelectedMdcId(), h.getSelectedNodeId(),
                    h.getFinalScore(), h.getReason(), h.getCandidateCount(),
                    h.getEngineName(), h.getPolicyName(), h.getCreatedAt());
        }
    }
}
