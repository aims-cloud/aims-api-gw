package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 실행 주기 검증 결과 — 생성 폼의 [자세히] 모달에서 쓴다.
 *
 * <p>요일을 <b>이름과 함께</b> 돌려주는 것이 핵심이다. 도움말 모달은 Quartz 규약을
 * 안내하는데 런타임은 Spring 이라 숫자 요일 해석이 하루씩 다르다. 백엔드가 변환하지만,
 * 사용자가 "월요일"로 의도한 값이 정말 월요일에 실행되는지 직접 확인할 수 있어야 한다.</p>
 */
public record CronPreviewResponse(
        String cronExpression,          // 입력 원문(Quartz 표기)
        String timezone,                // 해석 기준 시간대. 현재 Asia/Seoul 고정
        List<NextRun> nextRuns          // 다음 실행 시각
) {
    public CronPreviewResponse {
        nextRuns = nextRuns == null ? List.of() : List.copyOf(nextRuns);
    }

    /**
     * 다음 실행 시각 1건.
     *
     * @param at        UTC 기준 시각. 저장·응답의 표준
     * @param localTime 해석 시간대 기준 표기 (예: 2026-09-01 02:00:00)
     * @param dayOfWeek 해석 시간대 기준 요일 (예: 월요일)
     */
    public record NextRun(OffsetDateTime at, String localTime, String dayOfWeek) {}
}
