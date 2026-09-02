package com.innogrid.aims.scheduler.service;

import java.util.Arrays;

import org.springframework.http.HttpStatus;

import com.innogrid.aims.common.exception.BusinessException;

/**
 * 요청 문자열을 열거형으로 옮긴다 — 대소문자를 무시하고, 모르는 값은 500 이 아니라 400.
 *
 * <p>컨트롤러의 쿼리 파라미터와 요청 DTO 의 문자열 필드가 같은 규칙을 써야 하므로 한곳에 둔다.
 * {@code "vm"}·{@code "VM"}·{@code " Vm "} 은 전부 {@code VM} 이다.</p>
 */
public final class EnumSupport {

    private EnumSupport() {}

    public static <E extends Enum<E>> E parse(Class<E> type, String value, String label) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, label + " 값이 필요합니다");
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, String.format(
                    "지원하지 않는 %s 입니다: %s (가능한 값: %s)",
                    label, value, Arrays.toString(type.getEnumConstants())));
        }
    }
}
