package com.innogrid.aims.scheduler.dto;

import com.innogrid.aims.scheduler.config.SchedulerProperties;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 워크로드 재시도 정책 — {@code GET /scheduler/workloads/retry-policy}.
 *
 * <p>화면이 "재시도 소진" 배지와 "재시도 대기" 상태를 서버 값으로 그리게 한다. 소진은 {@code status = FAILED} 이고
 * {@code retryCount >= maxRetryCount} 인 행이며(스케줄 실행 대상에서 빠진다 — 단건 배치는 이 값을 보지 않는다),
 * 대기는 {@code FAILED} 이고 소진이 아니며 마지막 실패 뒤 {@code retryBackoffMinutes} 가 지나지 않은 행이다. 값은
 * 설정({@link SchedulerProperties})을 그대로 읽는다.</p>
 */
@Schema(description = "워크로드 재시도 정책 — 스케줄 실행이 실패한 워크로드를 다시 시도하는 상한과 간격")
public record RetryPolicyResponse(
        @Schema(description = "재시도 상한. status 가 FAILED 이고 retryCount 가 이 값 이상이면 재시도 소진 — 스케줄 실행 대상에서 빠지고 상태 전이(단건)나 일괄 재개로 되살린다", example = "5")
        int maxRetryCount,
        @Schema(description = "같은 워크로드를 다시 시도하기까지 두는 최소 간격(분). lastFailedAt 에서 이 시간이 지나야 대상이 된다. 0 이하면 간격 없음", example = "15")
        int retryBackoffMinutes
) {
    public static RetryPolicyResponse from(SchedulerProperties properties) {
        return new RetryPolicyResponse(properties.getMaxRetryCount(), properties.getRetryBackoffMinutes());
    }
}
