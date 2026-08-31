package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.innogrid.aims.scheduler.entity.SchedulingHistory;

/**
 * 실행 1건의 상세 — 요약에 더해 워크로드별 결정을 함께 보여준다.
 *
 * <p>화면에는 아직 이 항목이 없다. 명세 A1(제약 조건 기반 배치)이 실제로 무엇을 근거로
 * 어느 노드를 골랐는지 보이려면 결정 단위 기록이 필요해서 만들었다.</p>
 */
public record ScheduleRunDetailResponse(
        ScheduleRunResponse run,
        List<Decision> decisions
) {
    public ScheduleRunDetailResponse {
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    /** 워크로드 한 건에 대한 배치 결정. */
    public record Decision(
            Long historyId,
            Long workloadId,
            String workloadName,
            Boolean success,
            String selectedMdcId,
            String selectedNodeId,
            double finalScore,
            String reason,                  // 성공이면 선택 근거, 실패면 제외 사유
            int candidateCount,
            String engineName,
            String policyName,
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
