package com.innogrid.aims.monitoring.dto.alarm.alarmPolicy;

/** 알람 조건 연산자 — 임계값과 조합해 기준(예: CPU Used > 80%)을 구성하고, 평가기가 관측값을 판정한다. */
public enum AlarmConditionOperator {
    GT(">") {
        @Override public boolean test(double value, double threshold) { return value > threshold; }
    },
    GTE(">=") {
        @Override public boolean test(double value, double threshold) { return value >= threshold; }
    },
    LT("<") {
        @Override public boolean test(double value, double threshold) { return value < threshold; }
    },
    LTE("<=") {
        @Override public boolean test(double value, double threshold) { return value <= threshold; }
    },
    EQ("=") {
        @Override public boolean test(double value, double threshold) { return value == threshold; }
    };

    private final String symbol;

    AlarmConditionOperator(String symbol) {
        this.symbol = symbol;
    }

    /** 기준 문자열에 쓰는 기호 */
    public String symbol() {
        return symbol;
    }

    /** 관측값이 조건을 충족하는가 */
    public abstract boolean test(double value, double threshold);
}
