package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 생성 폼의 [자세히] 모달이 소비한다. 요일을 <b>이름과 함께</b> 돌려주는 것이 핵심이다 —
 * 도움말 모달은 Quartz 규약을 안내하는데 런타임은 Spring 이라 숫자 요일 해석이 하루씩 다르다.
 * 백엔드가 변환하지만, 사용자가 "월요일"로 의도한 값이 정말 월요일에 실행되는지 직접
 * 확인할 수 있어야 한다.
 */
@Schema(description = "실행 주기 검증 결과와 다음 실행 시각 미리보기")
public record CronPreviewResponse(
        @Schema(description = "입력한 실행 주기 원문(Quartz 표기, 앞뒤 공백 제거)", example = "0 0 2 * * ?")
        String cronExpression,
        @Schema(description = "cron 해석 기준 시간대. 서버 설정값이며 기본은 Asia/Seoul. at 은 이 값과 무관하게 UTC",
                example = "Asia/Seoul")
        String timezone,
        @Schema(description = "다음 실행 시각 최대 5건(가까운 순)")
        List<NextRun> nextRuns
) {
    public CronPreviewResponse {
        nextRuns = nextRuns == null ? List.of() : List.copyOf(nextRuns);
    }

    @Schema(description = "다음 실행 시각 1건 — UTC 시각과 해석 시간대 기준 표기")
    public record NextRun(
            @Schema(description = "실행 시각(UTC)")
            OffsetDateTime at,
            @Schema(description = "해석 시간대 기준 표기, yyyy-MM-dd HH:mm:ss", example = "2026-09-01 02:00:00")
            String localTime,
            @Schema(description = "해석 시간대 기준 요일 이름(한국어)", example = "월요일")
            String dayOfWeek
    ) {}
}
