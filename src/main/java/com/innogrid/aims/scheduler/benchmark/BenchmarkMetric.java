package com.innogrid.aims.scheduler.benchmark;

/**
 * 배치 비교 지표 하나의 <b>사전 등록된 정의</b>.
 *
 * <p>응답이 이 record를 그대로 싣는다. 지표를 뽑은 뒤에 "무엇이 좋은 값인가"를 정하면
 * 그 기준이 결과를 보고 정해졌다는 반론을 막을 수 없다 — 정의·단위·좋은 방향·유효성 조건을
 * 결과 코드보다 <b>먼저</b> 고정하고, 그 사실을 커밋 순서로 남긴다.</p>
 *
 * @param key       응답 필드 이름과 같은 식별자
 * @param label     사람이 읽는 이름
 * @param unit      단위. 무차원이면 빈 문자열
 * @param direction 좋은 방향. {@link Direction#CONTEXT_ONLY}는 순위 근거로 쓰지 않는다
 * @param role      주 지표인지 보조인지 정직성 측정치인지
 * @param validity  이 숫자가 의미를 갖는 조건. 조건이 깨진 셀은 {@code valid=false}로 나간다
 */
public record BenchmarkMetric(String key, String label, String unit,
                              Direction direction, Role role, String validity) {

    /** 좋은 방향. */
    public enum Direction {
        LOWER_IS_BETTER, HIGHER_IS_BETTER,
        /** 해석에만 쓰고 승패 판정에 넣지 않는다. */
        CONTEXT_ONLY
    }

    /** 지표의 위치. */
    public enum Role {
        /** 결론을 지지하는 지표. */
        PRIMARY,
        /** 참고용. 헤드라인으로 쓰지 않는다. */
        SECONDARY,
        /** "우리가 모르는 것"을 드러내는 측정치. 판정이 아니라 숫자로 싣는다. */
        HONESTY
    }
}
