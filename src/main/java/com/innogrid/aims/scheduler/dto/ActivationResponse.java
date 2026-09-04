package com.innogrid.aims.scheduler.dto;

import java.util.List;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;

/**
 * 정책 전환({@code POST /scheduler/policies/{id}/activate}) 응답 — 무엇이 켜지고 무엇이 꺼졌는지 한 번에 돌려준다.
 *
 * <p>전환은 같은 리소스 종류의 다른 활성 정책을 끄고 대상을 켠다. 꺼진 정책이 요청의 명시된 효과라는 것을 응답으로
 * 보여 주기 위해 목록을 함께 담는다. 규칙대로면 0 또는 1개지만, API 밖에서 들어온 데이터로 둘 이상일 수 있어 목록이다.
 * 이미 활성인 정책을 전환하면 {@code deactivated} 는 빈 목록이다(멱등).</p>
 *
 * @param activated   켜진 정책(대상)
 * @param deactivated 그 결과로 꺼진 같은 종류의 정책들
 */
public record ActivationResponse(SchedulingPolicyResponse activated, List<SchedulingPolicyResponse> deactivated) {

    public static ActivationResponse of(SchedulingPolicy activated, List<SchedulingPolicy> deactivated) {
        return new ActivationResponse(SchedulingPolicyResponse.from(activated),
                deactivated.stream().map(SchedulingPolicyResponse::from).toList());
    }
}
