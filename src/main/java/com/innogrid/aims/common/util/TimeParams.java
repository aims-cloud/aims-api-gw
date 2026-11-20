package com.innogrid.aims.common.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

import com.innogrid.aims.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * 조회 API 의 시각 쿼리 파라미터({@code from}·{@code to} 등, ISO-8601 문자열)를 해석한다.
 *
 * <p>컨트롤러가 {@link Instant#parse} 를 그대로 부르면 형식 오류가 {@link DateTimeParseException} 으로 새어 나가
 * 500 이 된다. 여기서 400({@link BusinessException})으로 바꿔 공통 응답 형식으로 내린다.</p>
 *
 * <p>스프링 빈이 아니다. {@link SortSupport} 와 같은 정적 도우미다.</p>
 */
public final class TimeParams {

    private TimeParams() {}

    /**
     * ISO-8601 시각 문자열({@code 2026-10-12T00:00:00Z}, 오프셋 포함)을 {@link Instant} 로 옮긴다.
     *
     * @param name     파라미터 이름 — 오류 문구에 쓴다
     * @param value    요청 값. null 이면 {@code fallback}
     * @param fallback 값이 없을 때 쓸 시각
     * @throws BusinessException 형식이 틀리면 400
     */
    public static Instant parseInstant(String name, String value, Instant fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    name + " 은(는) ISO-8601 시각이어야 합니다 (예: 2026-10-12T00:00:00Z): " + value);
        }
    }

    /** 오늘 00:00:00 (서버 타임존) — 조회 기간 from 의 기본값 */
    public static Instant startOfToday() {
        ZoneId zone = ZoneId.systemDefault();
        return LocalDate.now(zone).atStartOfDay(zone).toInstant();
    }
}
