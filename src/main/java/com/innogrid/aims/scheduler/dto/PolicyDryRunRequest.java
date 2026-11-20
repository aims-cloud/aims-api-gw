package com.innogrid.aims.scheduler.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 정책 수정 전 모의 배치 요청 — 저장된 정책에 얹어 볼 부분 수정 값과, 놓아 볼 워크로드 초안 하나.
 * 아무것도 저장하지 않는다.
 */
@Schema(description = "정책 수정 전 모의 배치 요청 — 정책 부분 수정 값(PUT /policies/{policyId} 와 같은 본문)과 워크로드 초안 하나")
public record PolicyDryRunRequest(
        @Schema(description = "정책 부분 수정 값. PUT /policies/{policyId} 와 같은 뜻(요청에 있는 키만 반영 · null 로 비우는 세 필드 포함)이고 "
                + "같은 문구의 400 으로 검사합니다. name · description 은 보지 않고, resourceType · active 는 형식만 검사하고 평가에 쓰지 "
                + "않습니다. 생략하면 수정 없음")
        SchedulingPolicyRequest changes,
        @Schema(description = "놓아 볼 워크로드 초안. 워크로드 생성과 같은 본문 · 같은 400 규칙(필수)")
        WorkloadRequest workload
) {}
