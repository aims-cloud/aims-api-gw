package com.innogrid.aims.scheduler.engine;

/**
 * 세 엔진이 함께 쓰는 점수 반올림.
 *
 * <p>식은 글자 그대로 둔다 — 소수 넷째 자리 반올림 경계에 정확히 걸린 값이 있어(가중치 엔진 미계측 노드 총점 0.71875 등)
 * 나눗셈 형태나 반올림 방식을 바꾸면 기준 파일의 값이 움직인다. 상한·하한 자르기나 NaN 치환을 여기에 두지 않는다 —
 * 온도 엔진의 냉각 점수는 임계 온도를 넘은 노드에서 음수가 되고 그 값이 응답에 그대로 나간다.</p>
 */
final class Scores {

    private Scores() {}

    static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
