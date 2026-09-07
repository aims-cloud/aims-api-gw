package com.innogrid.aims.scheduler.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import com.innogrid.aims.common.exception.BusinessException;

/**
 * 추세 API 의 구간·간격 검증 — 전력 효율 추세(노드 단위)와 전력 최적화 추세(클러스터 단위)가 같은 규칙을 쓴다.
 *
 * <p>메트릭 클라이언트에 읽기 타임아웃이 없어(단계 14 보호 장치가 2차 방어) 무거운 질의는 애초에 내보내지 않는다 —
 * 지점 수가 설정 상한을 넘으면 400.</p>
 */
public final class TrendRangeSupport {

    private static final Pattern STEP = Pattern.compile("^(\\d+)([smhd])$");

    private TrendRangeSupport() {}

    /** 구간과 간격이 감당할 만한지 본다. 아니면 400. */
    public static void validate(OffsetDateTime start, OffsetDateTime end, String step, int maxPoints) {
        if (!start.isBefore(end)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "from 은 to 보다 앞서야 합니다");
        }
        long stepSeconds = stepSeconds(step);
        long points = Duration.between(start, end).getSeconds() / stepSeconds;
        if (points > maxPoints) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, String.format(
                    "조회 지점이 너무 많습니다(%d개, 최대 %d개). 구간을 줄이거나 step 을 늘리세요",
                    points, maxPoints));
        }
    }

    /** {@code 30s}·{@code 5m}·{@code 1h}·{@code 1d} 형식을 초로 옮긴다. 형식이 틀리면 400. */
    public static long stepSeconds(String step) {
        Matcher matcher = STEP.matcher(step);
        if (!matcher.matches()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "step 형식이 올바르지 않습니다: " + step + " (예: 30s, 5m, 1h, 1d)");
        }
        // 자릿수가 long 을 넘으면 parseLong 이 NumberFormatException 을 던지는데, 그 예외를 잡는 곳이 없어
        // /error 재요청이 인증에 막혀 클라이언트는 400 이 아니라 사유 없는 403 을 받는다(배포본 실측).
        // 형식 오류와 같은 400 으로 돌려준다 — 사용자 입장에서는 둘 다 "step 값이 이상하다" 하나다.
        long unit = switch (matcher.group(2)) {
            case "s" -> 1L;
            case "m" -> 60L;
            case "h" -> 3600L;
            default -> 86400L;
        };
        long amount;
        long seconds;
        try {
            amount = Long.parseLong(matcher.group(1));
            // 단위 환산도 넘칠 수 있다 — 2^57 d 는 곱하면 정확히 0 이 되어 뒤의 나눗셈이 터지고,
            // 2×10^14 d 는 음수로 감겨 점 수 상한이 통째로 무력화된다(배포본 실측). 둘 다 같은 400 으로 막는다.
            seconds = Math.multiplyExact(amount, unit);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "step 이 너무 큽니다: " + step + " (예: 30s, 5m, 1h, 1d)");
        }
        if (amount <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "step 은 0보다 커야 합니다: " + step);
        }
        return seconds;
    }
}
