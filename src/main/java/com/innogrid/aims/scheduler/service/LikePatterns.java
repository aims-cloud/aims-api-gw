package com.innogrid.aims.scheduler.service;

/**
 * LIKE 패턴 조립 — 와일드카드 {@code %}·{@code _}와 이스케이프 문자를 문자 그대로 찾게 한다. 스케줄러 조회 서비스 안에서만 쓴다.
 *
 * <p>부르는 쪽은 {@code cb.like(식, 패턴, ESCAPE)}로 이스케이프 문자를 반드시 함께 넘긴다 — DB 기본값에 기대지 않는다
 * (표준 SQL에는 기본 이스케이프 문자가 없다. PostgreSQL은 기본이 백슬래시이고 H2도 기본은 같지만 설정·호환 모드로
 * 바뀔 수 있으므로 명시해야 로컬·배포 결과가 갈리지 않는다).</p>
 */
final class LikePatterns {

    /** {@code cb.like}의 escape 인자. */
    static final char ESCAPE = '\\';

    private LikePatterns() {}

    /** 백슬래시를 먼저 두 겹으로 — 순서가 바뀌면 {@code %}·{@code _} 앞에 붙인 이스케이프가 다시 이스케이프된다. */
    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 부분 일치 패턴 {@code %값%} — 값은 이스케이프한다. */
    static String contains(String value) {
        return "%" + escape(value) + "%";
    }
}
