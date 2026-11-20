package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 저장한 실행 입력 하나로 운영 엔진 세 개를 각각 다시 계산한 결과 — 워크로드별 선택 노드와 성공 여부를 나란히 싣는다.
 *
 * <p>화면은 이 응답을 쓰지 않는다. 「같은 정책에서 스케줄 엔진만 바꿨다면」을 같은 실제 입력에서 보려는 검증·연구용이다.
 * 노드 배치 스코어·평가 지표·순위는 싣지 않는다 — 스코어는 엔진마다 뜻이 다르고, 엔진 자신의 모델로 낸 값을 엔진끼리 나란히
 * 두면 판정처럼 읽힌다. 엔진 우열은 배치 전략 비교의 판정판에서만 다룬다.</p>
 */
@Schema(description = "저장한 실행 입력으로 운영 엔진 세 개를 각각 다시 계산한 워크로드별 선택 노드·성공 여부. 엔진 우열을 말하는 응답이 아니다")
public record ScheduleRunEngineReplayResponse(
        @Schema(description = "실행 PK")
        Long runId,
        @Schema(description = "저장한 입력의 형식 판")
        int formatVersion,
        @Schema(description = "입력을 저장한 시각(UTC) — 배치 루프 직전")
        OffsetDateTime capturedAt,
        @Schema(description = "그 실행이 실제로 쓴 엔진 id", example = "weighted")
        String recordedEngineId,
        @Schema(description = "저장한 입력 원문의 SHA-256 앞 12자", example = "9c1e07a4b2d5")
        String inputDigest,
        @Schema(description = "그 실행의 설정 노브 지문", example = "3f2a91c0d4e7")
        String knobsDigest,
        @Schema(description = "실제로 쓴 엔진을 다시 계산해 배치 이력과 대조한 판정(실행 재생과 같은 규칙). MATCH 가 아니면 다른 엔진 열을 "
                + "같은 조건의 대안으로 읽을 수 없다")
        ScheduleRunReplayResponse.Verdict replayVerdict,
        @Schema(description = "대상 워크로드 수(저장한 입력의 배치 순서 그대로)")
        int total,
        @Schema(description = "실행이 배치 직전에 다시 읽어 건너뛴 워크로드 수 — 모든 엔진에서 같게 건너뛴다")
        int skipped,
        @Schema(description = "실행 계층이 받지 않아 실행이 예약을 되돌린 워크로드 수 — 모든 엔진에서 같은 워크로드의 예약을 되돌린다. "
                + "그 밖의 배치는 실행 계층이 받았다고 보므로, 실행 계층이 노드마다 다르게 받게 되면 0 이어도 다른 엔진 열은 실제와 다를 수 있다")
        int rolledBack,
        @Schema(description = "엔진별 집계, 순서 고정(weighted · power · temperature)")
        List<Engine> engines,
        @Schema(description = "워크로드별 배치 이력 결정과 엔진별 다시 계산한 선택, 배치 순서 그대로")
        List<Step> steps
) {
    public ScheduleRunEngineReplayResponse {
        engines = engines == null ? List.of() : List.copyOf(engines);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    @Schema(name = "ScheduleRunEngineReplayEngine", description = "엔진 하나로 다시 계산한 결과의 사실 집계 — 순위가 아니다")
    public record Engine(
            @Schema(description = "엔진 id", example = "power")
            String engineId,
            @Schema(description = "그 실행이 실제로 쓴 엔진이면 true")
            boolean recorded,
            @Schema(description = "엔진 결정이 성공한 워크로드 수(건너뛴 워크로드 제외). 엔진 결정 기준이라 예약을 되돌린 워크로드가 있으면 "
                    + "실행 이력의 성공 수와 다를 수 있다")
            int placed,
            @Schema(description = "엔진 결정이 실패한 워크로드 수(건너뛴 워크로드 제외). 엔진 결정 기준이다")
            int failed,
            @Schema(description = "선택 노드·성공 여부가 배치 이력과 같은 워크로드 수. 건너뛴 워크로드와 이력 행이 없는 워크로드는 세지 않고, "
                    + "실패와 실패는 같은 것으로 센다")
            int sameAsRecorded
    ) {}

    @Schema(name = "ScheduleRunEngineReplayStep", description = "워크로드 한 건 — 배치 이력의 결정과 엔진별로 다시 계산한 선택")
    public record Step(
            @Schema(description = "워크로드 id")
            Long workloadId,
            @Schema(description = "실행이 배치 직전에 다시 읽어 건너뛴 워크로드면 true — 이때 엔진별 선택은 비어 있다")
            boolean skipped,
            @Schema(description = "배치 이력의 선택 노드. 실패·건너뜀·이력 행 없음이면 null")
            String recordedNodeId,
            @Schema(description = "배치 이력의 엔진 결정 성공 여부. 이력 행이 없으면 null")
            Boolean recordedSuccess,
            @Schema(description = "엔진별 다시 계산한 선택, 엔진 순서는 engines 와 같다")
            List<Choice> choices
    ) {
        public Step {
            choices = choices == null ? List.of() : List.copyOf(choices);
        }
    }

    @Schema(name = "ScheduleRunEngineReplayChoice", description = "엔진 하나가 그 워크로드에 대해 다시 계산한 선택")
    public record Choice(
            @Schema(description = "엔진 id", example = "temperature")
            String engineId,
            @Schema(description = "다시 계산한 선택 노드. 실패면 null")
            String nodeId,
            @Schema(description = "다시 계산한 엔진 결정 성공 여부")
            boolean success
    ) {}
}
