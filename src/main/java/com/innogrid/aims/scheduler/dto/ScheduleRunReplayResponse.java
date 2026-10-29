package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 저장한 실행 입력으로 그 실행의 배치를 다시 계산해 배치 이력과 대조한 결과.
 *
 * <p>화면은 이 응답을 쓰지 않는다. 1세부 실데이터로 돈 실행을 다시 계산해 보거나, 서로 다른 빌드 사이에 엔진 결정이
 * 바뀌었는지 확인하는 검증용이다. 저장한 입력 원문(payload)은 싣지 않는다.</p>
 */
@Schema(description = "저장한 실행 입력으로 그 실행의 배치를 지금 코드로 다시 계산해 워크로드별로 배치 이력과 대조한 결과")
public record ScheduleRunReplayResponse(
        @Schema(description = "실행 PK")
        Long runId,
        @Schema(description = "저장한 입력의 형식 판. 지금 재생할 수 있는 판은 1뿐이다")
        int formatVersion,
        @Schema(description = "입력을 저장한 시각(UTC) — 배치 루프 직전")
        OffsetDateTime capturedAt,
        @Schema(description = "그 실행이 쓴 엔진 id", example = "weighted")
        String engineId,
        @Schema(description = "저장한 입력 원문의 SHA-256 앞 12자. 재생 전에 다시 계산해 같은지 확인했다", example = "9c1e07a4b2d5")
        String inputDigest,
        @Schema(description = "그 실행의 설정 노브 지문(실행 상세 clusterSnapshot.knobsDigest 와 같은 값)", example = "3f2a91c0d4e7")
        String knobsDigest,
        @Schema(description = "전체 판정. MATCH 는 확인할 수 없는 구간 없이 건너뛰지 않은 워크로드가 모두 같음, "
                + "MISMATCH 는 확인할 수 있는 구간에서 어긋난 워크로드가 있음, "
                + "INCOMPLETE 는 확인할 수 있는 구간은 모두 같지만 배치 이력 행이나 성공한 배치의 전달 기록이 없어 그 뒤를 확인할 수 없음"
                + "(뒤가 모두 같아도 INCOMPLETE)")
        Verdict verdict,
        @Schema(description = "대상 워크로드 수(저장한 입력의 배치 순서 그대로)")
        int total,
        @Schema(description = "선택 노드·노드 배치 스코어·성공 여부가 배치 이력과 같은 워크로드 수")
        int matched,
        @Schema(description = "셋 중 하나라도 배치 이력과 다른 워크로드 수")
        int mismatched,
        @Schema(description = "실행이 배치 직전에 다시 읽어 건너뛴 워크로드 수(배치 이력의 후보 0 행)")
        int skipped,
        @Schema(description = "배치 이력 행이 없는 워크로드 수 — 실행이 도중에 끊긴 경우 등")
        int notRecorded,
        @Schema(description = "배치 이력은 성공인데 이 실행의 전달 기록이 없는 워크로드 수. 워크로드를 지워 전달 기록이 함께 "
                + "지워졌거나 같은 결정의 기록이 다른 실행에 있던 경우로, 예약을 되돌렸는지 알 수 없어 그 뒤 워크로드는 확인할 수 없다")
        int missingDispatchRecords,
        @Schema(description = "워크로드별 대조, 배치 순서 그대로")
        List<Step> steps
) {
    public ScheduleRunReplayResponse {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /** 전체 판정. */
    public enum Verdict { MATCH, MISMATCH, INCOMPLETE }

    /** 워크로드 한 건의 대조 결과. */
    public enum Outcome { MATCH, MISMATCH, SKIPPED, NOT_RECORDED }

    @Schema(name = "ScheduleRunReplayStep", description = "워크로드 한 건의 대조 — 배치 이력에 남은 결정과 다시 계산한 결정")
    public record Step(
            @Schema(description = "워크로드 id")
            Long workloadId,
            @Schema(description = "대조 결과. SKIPPED 는 실행이 건너뛴 워크로드, NOT_RECORDED 는 배치 이력 행이 없는 워크로드")
            Outcome outcome,
            @Schema(description = "배치 이력의 선택 노드. 실패·건너뜀이면 null, 이력 행이 없으면 null")
            String recordedNodeId,
            @Schema(description = "배치 이력의 노드 배치 스코어(finalScore). 이력 행이 없으면 null")
            Double recordedScore,
            @Schema(description = "배치 이력의 엔진 결정 성공 여부(실행 계층 전달 결과가 아니다). 이력 행이 없으면 null")
            Boolean recordedSuccess,
            @Schema(description = "다시 계산한 선택 노드. 실패면 null, 건너뛴 워크로드는 계산하지 않아 null")
            String replayedNodeId,
            @Schema(description = "다시 계산한 노드 배치 스코어(finalScore). 건너뛴 워크로드는 null")
            Double replayedScore,
            @Schema(description = "다시 계산한 엔진 결정 성공 여부. 건너뛴 워크로드는 null")
            Boolean replayedSuccess
    ) {}
}
