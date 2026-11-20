package com.innogrid.aims.monitoring.dto.alarm.alarmPolicy;

import java.util.Locale;
import java.util.Optional;

/**
 * 알람 정책 대상 자원 유형 — 자원 탐색기 트리·마법사 RESOURCE_TYPE_OPTIONS 와 같은 값 체계(docs/10 §1 #7, §5).
 * 코드({@code node|vm|gpu}, 소문자)가 요청·응답·원장에 그대로 오가는 값이고, 입력은 대소문자를 가리지 않는다.
 * 검증 메시지를 한글로 유지하기 위해 요청 DTO 는 문자열로 받고 서비스에서 {@link #fromCode}로 판정한다.
 */
public enum AlarmResourceType {
    NODE("node"),
    VM("vm"),
    GPU("gpu");

    private final String code;

    AlarmResourceType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 코드로 찾기(대소문자·앞뒤 공백 무관). 모르는 값·빈 값은 empty */
    public static Optional<AlarmResourceType> fromCode(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        String c = code.trim().toLowerCase(Locale.ROOT);
        for (AlarmResourceType t : values()) {
            if (t.code.equals(c)) return Optional.of(t);
        }
        return Optional.empty();
    }
}
