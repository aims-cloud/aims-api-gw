package com.innogrid.aims.scheduler.model;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;

/**
 * 배치 정책 선택의 결과 — 고른 정책과 <b>어느 단계에서</b> 골랐는지.
 *
 * <p>정책 선택 순서(docs/13 5장)는 ① 스케줄 지정 → ② 리소스 종류별 활성 → ③ 공통 활성 → ④ {@code default}다.
 * 실행 경로는 정책 객체만 쓰지만, 미리보기는 "왜 이 정책인가"를 함께 보여 줘야 하므로 출처를 남긴다.</p>
 *
 * <p>{@code persisted}는 <b>고른 원본 정책이 DB 행인지</b>를 뜻한다(스케줄 온도 한계를 덮어쓴 비영속 사본인지는 뜻하지
 * 않는다). false이면 ④ 단계에서 {@code default} 행이 없어 <b>저장하지 않은</b> 기본값 객체를 돌려준 것이다. 실행 경로는 이 경우 저장해 이력이 정책 이름을 가리킬 수 있게 하고, 조회 경로(미리보기)는
 * 저장하지 않는다 — 읽기 요청이 행을 만들면 안 된다.</p>
 */
public record PolicyChoice(SchedulingPolicy policy, Source source, boolean persisted) {

    /** 정책을 고른 단계. docs/13 5장의 ①~④와 같은 순서다. */
    public enum Source {
        /** ① 스케줄이 지정한 정책 */
        SCHEDULE,
        /** ② 리소스 종류가 같은 활성 정책 */
        RESOURCE_TYPE,
        /** ③ 종류를 비운 공통 활성 정책 */
        COMMON,
        /** ④ 이름이 {@code default}인 정책의 값(비활성이어도 값만 · 없으면 기본값) */
        DEFAULT
    }

    /** 같은 출처·저장 여부로 정책만 바꾼 사본. 스케줄이 온도 한계를 덮어쓴 비영속 사본을 담을 때 쓴다. */
    public PolicyChoice withPolicy(SchedulingPolicy replacement) {
        return new PolicyChoice(replacement, source, persisted);
    }
}
