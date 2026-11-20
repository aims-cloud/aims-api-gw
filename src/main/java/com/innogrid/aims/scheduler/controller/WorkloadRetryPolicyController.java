package com.innogrid.aims.scheduler.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.RetryPolicyResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 워크로드 재시도 정책 조회 — 상한과 간격을 설정에서 읽어 준다.
 *
 * <p>{@code SchedulerController}와 따로 둔다 — 그쪽 생성자를 건드리지 않으려는 것이고,
 * 설정값만 돌려주는 소형 컨트롤러라는 점에서 {@link MetricsSourceController}와 같은 자리다.
 * 리터럴 경로 {@code /workloads/retry-policy}는 {@code /workloads/{workloadId}}보다 우선한다.</p>
 */
@Tag(name = "스케줄러", description = "워크로드 스케줄링 (제약조건/전력/온도 기반)")
@RestController
@RequestMapping("/api/v1/scheduler/workloads")
public class WorkloadRetryPolicyController {

    private final SchedulerProperties properties;

    public WorkloadRetryPolicyController(SchedulerProperties properties) {
        this.properties = properties;
    }

    @Operation(summary = "워크로드 재시도 정책",
               description = "재시도 상한(maxRetryCount)과 간격(retryBackoffMinutes, 분)을 돌려줍니다. "
                       + "재시도 소진 = status 가 FAILED 이고 retryCount 가 상한 이상 — 스케줄 실행 대상에서 빠집니다. 되살리려면 상태 전이(FAILED→PENDING, 단건)나 일괄 재개(/workloads/resume-exhausted)를 씁니다. "
                       + "재시도 대기 = status 가 PENDING 또는 FAILED 이고 retryCount 가 상한 미만이며 lastFailedAt 에서 간격이 지나지 않음 — 예약 취소는 lastFailedAt 을 지우지 않으므로 예약 취소로 PENDING 이 된 워크로드도 들어갑니다. 단건 배치(/workloads/{id}/schedule)는 둘 다 보지 않습니다. 2필드.")
    @GetMapping("/retry-policy")
    public ApiResponse<RetryPolicyResponse> retryPolicy() {
        return ApiResponse.ok(RetryPolicyResponse.from(properties));
    }
}
