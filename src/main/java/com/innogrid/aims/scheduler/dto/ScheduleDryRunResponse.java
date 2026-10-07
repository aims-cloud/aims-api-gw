package com.innogrid.aims.scheduler.dto;

import java.util.List;

import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.PolicyChoice;
import com.innogrid.aims.scheduler.model.SchedulingResult;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 스케줄을 <b>지금 실행하면 어떻게 배치될지</b>의 모의 실행 결과.
 *
 * <p>실행과 같은 코드로 정책·엔진·대상·순서·상한·전력 상한을 해석하고, 노드 상태 하나 위에 대상을 차례로 놓아
 * 본다(앞 건의 예약이 뒤 건에 보인다). 실행 계층 전달은 하지 않으며(전부 받는다고 본다) 실행 권한을 잡지 않고
 * 워크로드·배치 이력·전달·실행 이력 어느 행도 쓰지 않는다. 그래서 {@code status}·{@code message}·집계는
 * "실행 계층이 전부 받았을 때" 실행이 남길 값과 같다.</p>
 *
 * <p>실행 미리보기({@link ScheduleRunPreviewResponse})는 노드를 모으지 않고 건수만 세는 반면, 여기는 노드를 모으고
 * 결정까지 낸다. {@code targetCount} 는 미리보기(상한 적용 전)와 달리 실행 이력과 같은 상한 적용 뒤 값이다.</p>
 */
@Schema(description = "스케줄 모의 실행 — 지금 실행하면 어떻게 배치될지를 저장 없이. 실행 계층 전달은 하지 않으며 전부 받는다고 본다")
public record ScheduleDryRunResponse(
        @Schema(description = "스케줄 PK", example = "12")
        Long id,
        @Schema(description = "스케줄 ID(표시용 키)", example = "nightly-vm")
        String scheduleId,
        @Schema(description = "사용 여부. false 이면 즉시 실행은 409 지만 모의 실행은 200 이다")
        boolean enabled,
        @Schema(description = "실행이 해석하는 리소스 종류. 스케줄이 비워 두면 VM", example = "VM")
        Workload.ResourceType resourceType,
        @Schema(description = "적용된 배치 정책 이름", example = "default")
        String policyName,
        @Schema(description = "정책을 고른 단계 — SCHEDULE · RESOURCE_TYPE · COMMON · DEFAULT")
        PolicyChoice.Source policySource,
        @Schema(description = "적용된 엔진 ID", example = "weighted")
        String engine,
        @Schema(description = "엔진을 고른 단계 — SCHEDULE · POLICY · DEFAULT")
        ScheduleRunPreviewResponse.EngineSource engineSource,
        @Schema(description = "지금 실행하면 남을 처리 상태. 한 건이라도 배치되면 COMPLETED, 전부 실패·대상 없음(재시도 소진)·노드 없음이면 FAILED")
        ScheduleRun.RunStatus status,
        @Schema(description = "실행이 남길 메시지와 같은 문구", example = "대상 3건 / 성공 2건 / 실패 1건")
        String message,
        @Schema(description = "대상이 0건일 때의 사유. 대상이 있으면 null. 화면 분기는 이 값으로")
        ScheduleRunPreviewResponse.EmptyReason emptyReason,
        @Schema(description = "모의로 놓아 본 워크로드 수(실행 1회 상한 적용 뒤 — 실행 이력의 targetCount 와 같은 뜻)", example = "3")
        int targetCount,
        @Schema(description = "배치된 수", example = "2")
        int successCount,
        @Schema(description = "배치되지 못한 수", example = "1")
        int failedCount,
        @Schema(description = "대상이 실행 1회 상한을 넘어 일부만 놓아 봤는지")
        boolean truncated,
        @Schema(description = "모의 전후 클러스터 상태(실행 상세와 같은 열). 여기서 '후'는 모의가 잡은 예약을 반영한 값이다. "
                + "대상이 0건이면 NO_TARGET(노드를 모으지 않음), 노드가 없으면 NO_NODES")
        ScheduleRunDetailResponse.ClusterSnapshot clusterSnapshot,
        @Schema(description = "워크로드별 모의 결정, 실행이 처리할 순서대로")
        List<DryRunDecision> decisions
) {
    public ScheduleDryRunResponse {
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    /**
     * 실행 상세의 결정({@link ScheduleRunDetailResponse.Decision})과 같은 이름·순서의 부분집합 — 이력 PK·엔진·정책·시각처럼
     * 저장이 있어야 생기는 칸을 뺐다. 후보 점수 목록은 싣지 않는다(한 건을 자세히 보려면 워크로드 모의 배치).
     */
    @Schema(description = "워크로드 한 건의 모의 결정 — 실행 상세의 결정과 같은 칸 이름")
    public record DryRunDecision(
            @Schema(description = "대상 워크로드 id")
            Long workloadId,
            @Schema(description = "대상 워크로드 이름")
            String workloadName,
            @Schema(description = "배치 성공 여부")
            boolean success,
            @Schema(description = "선택한 MDC id. 실패면 null")
            String selectedMdcId,
            @Schema(description = "선택한 노드 id. 실패면 null")
            String selectedNodeId,
            @Schema(description = "선택 노드의 총점. 실패면 0")
            double finalScore,
            @Schema(description = "성공이면 선택 근거, 실패면 제외 사유")
            String reason,
            @Schema(description = "후보 노드의 제외 사유 코드 집계 — 실행 상세·배치 이력의 exclusionCodes 와 같은 JSON 객체 문자열. 제외가 없으면 null")
            String exclusionCodes
    ) {
        public static DryRunDecision of(Workload workload, SchedulingResult result, String exclusionCodes) {
            return new DryRunDecision(workload.getId(), workload.getName(), result.success(),
                    result.selectedMdcId(), result.selectedNodeId(), result.finalScore(), result.reason(), exclusionCodes);
        }
    }
}
