package com.innogrid.aims.scheduler.dto;

import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.PolicyChoice;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 스케줄을 <b>지금 실행하면</b> 무엇이 적용되고 몇 건을 다루게 되는지의 미리보기.
 *
 * <p>실행과 같은 코드로 정책·엔진을 해석하고 같은 질의로 건수를 세지만, 실행 권한을 잡지 않고 노드를
 * 모으지 않으며 어떤 행도 쓰지 않는다. 대상 건수는 "시도할 건수" 이고 "성공할 건수" 가 아니다 — 성공
 * 여부는 노드 상태에 달려 있어 워크로드별 모의 배치로만 알 수 있다.</p>
 */
@Schema(description = "스케줄 실행 미리보기 — 지금 실행하면 적용될 정책·엔진과 대상·대기·소진 건수. 저장 없음")
public record ScheduleRunPreviewResponse(
        @Schema(description = "스케줄 PK", example = "12")
        Long id,
        @Schema(description = "스케줄 ID(표시용 키)", example = "nightly-vm")
        String scheduleId,
        @Schema(description = "사용 여부. false 이면 즉시 실행이 409 지만 미리보기는 200 이다")
        boolean enabled,
        @Schema(description = "실행이 해석하는 리소스 종류. 스케줄이 비워 두면 VM(항상 값이 있음)", example = "VM")
        Workload.ResourceType resourceType,
        @Schema(description = "적용될 배치 정책 이름", example = "default")
        String policyName,
        @Schema(description = "정책을 고른 단계 — SCHEDULE(스케줄 지정) · RESOURCE_TYPE(종류별 활성) · COMMON(공통 활성) · DEFAULT(default 값)")
        PolicyChoice.Source policySource,
        @Schema(description = "적용될 엔진 ID", example = "weighted")
        String engine,
        @Schema(description = "엔진을 고른 단계 — SCHEDULE(스케줄 지정) · POLICY(정책 기본 엔진) · DEFAULT(서버 기본)")
        EngineSource engineSource,
        @Schema(description = "지금 실행하면 시도할 워크로드 수(1회 상한 적용 전)", example = "3")
        long targetCount,
        @Schema(description = "실행 1회 상한(설정값). targetCount 가 이보다 크면 초과분은 다음 실행으로", example = "500")
        int runLimit,
        @Schema(description = "재시도 간격이 아직 지나지 않아 이번에는 빠지는 워크로드 수", example = "1")
        long waitingCount,
        @Schema(description = "재시도 상한에 걸려 사람이 되살리기 전에는 대상이 되지 않는 워크로드 수", example = "0")
        long exhaustedCount,
        @Schema(description = "대상이 0건일 때의 사유. 대상이 있으면 null. 소진과 대기가 함께 있으면 EXHAUSTED(실행과 같은 우선순위)")
        EmptyReason emptyReason,
        @Schema(description = "대상이 0건일 때 실행이 남길 메시지와 같은 문구. 대상이 있으면 null", example = "대상 없음 (재시도 대기 1건 — 15분 뒤 다시 시도합니다)")
        String emptyMessage
) {

    /** 엔진을 고른 단계. */
    public enum EngineSource {
        /** 스케줄이 지정한 엔진 */
        SCHEDULE,
        /** 정책의 기본 엔진(등록된 엔진일 때만) */
        POLICY,
        /** 서버 기본 엔진 */
        DEFAULT
    }

    /** 대상 0건의 사유. 실행이 메시지를 고르는 우선순위와 같다 — 소진 &gt; 대기 &gt; 없음. */
    public enum EmptyReason {
        /** 대상이 될 워크로드가 없다(정상) */
        NONE,
        /** 재시도 간격이 지나지 않은 워크로드만 있다(시간이 지나면 풀림) */
        WAITING,
        /** 재시도 상한에 걸린 워크로드가 있다(일괄 재개가 필요 · 실행은 FAILED 로 기록) */
        EXHAUSTED
    }
}
