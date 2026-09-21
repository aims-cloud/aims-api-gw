package com.innogrid.aims.scheduler.engine.constraint;

/**
 * 제외 한 건 — 기계가 읽는 {@link ExclusionReason 까닭}과 사람이 읽는 문장을 함께 든다.
 *
 * <p>둘을 한 값으로 묶는 이유는 <b>갈라지지 않게</b> 하기 위해서다. 사유를 따로 만들면 문장은 바뀌었는데
 * 까닭은 옛것이거나 그 반대인 상태가 생긴다. 제약 평가는 이 레코드 하나만 만들고, 문장만 필요한 자리는
 * {@link #message()} 를 꺼내 쓴다.</p>
 *
 * @param reason  기계가 읽는 까닭. 제외가 아니면 이 레코드 자체가 {@code null} 이다
 * @param message 사람이 읽는 문장. 화면과 판 응답의 제외 사유 분포가 쓰는 값이라 함부로 바꾸지 않는다
 */
public record Exclusion(ExclusionReason reason, String message) {

    public Exclusion {
        if (reason == null) throw new IllegalArgumentException("제외 까닭이 없습니다");
        if (message == null || message.isBlank()) throw new IllegalArgumentException("제외 문장이 비었습니다: " + reason);
    }

    /** 문장만 꺼낸다. {@code null} 이면 {@code null} — 제외가 아닌 경우를 그대로 흘린다. */
    public static String messageOf(Exclusion exclusion) {
        return exclusion == null ? null : exclusion.message();
    }

    /** 까닭만 꺼낸다. {@code null} 이면 {@code null}. */
    public static ExclusionReason reasonOf(Exclusion exclusion) {
        return exclusion == null ? null : exclusion.reason();
    }
}
