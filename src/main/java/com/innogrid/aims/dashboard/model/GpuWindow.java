package com.innogrid.aims.dashboard.model;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import com.innogrid.aims.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * GPU 대시보드 조회 기간 (Confluence 2320072739 §3 B-7). ISO-8601, 양끝 포함, 기본 최근 1시간, 상한 30일.
 * 사용률·VRAM 은 이 창의 평균, Peak VRAM 은 창의 최대값이다.
 *
 * @param defaultWindow from·to 를 둘 다 생략했는가 — 캐시 키를 "지금 기준 최근 1시간"으로 묶을 때 쓴다
 */
public record GpuWindow(Instant from, Instant to, boolean defaultWindow) {

    public static final Duration DEFAULT_LENGTH = Duration.ofHours(1);
    public static final Duration MAX_LENGTH = Duration.ofDays(30);
    static final long MIN_STEP_SECONDS = 15;
    static final long MAX_STEP_SECONDS = 3600;

    /** from·to 중 생략한 쪽은 기본값(to = 지금, from = to - 1시간). from ≥ to 이거나 30일을 넘으면 400 */
    public static GpuWindow of(OffsetDateTime from, OffsetDateTime to, Clock clock) {
        Instant end = to == null ? clock.instant() : to.toInstant();
        Instant start = from == null ? end.minus(DEFAULT_LENGTH) : from.toInstant();
        if (!start.isBefore(end)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "조회 기간의 from 은 to 보다 앞이어야 합니다.");
        }
        if (Duration.between(start, end).compareTo(MAX_LENGTH) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "조회 기간은 30일을 넘을 수 없습니다.");
        }
        return new GpuWindow(start, end, from == null && to == null);
    }

    public long seconds() {
        return Duration.between(from, to).getSeconds();
    }

    /** PromQL 범위 선택자 — 예) [3600s] */
    public String range() {
        return "[" + seconds() + "s]";
    }

    /** 시계열 차트 간격 — 창/60, 최소 15초·최대 1시간 (B-6) */
    public String step() {
        return Math.max(MIN_STEP_SECONDS, Math.min(MAX_STEP_SECONDS, seconds() / 60)) + "s";
    }

    /** 캐시 키 — 기본 창은 길이로만, 지정 창은 양끝으로 */
    public String cacheKey() {
        return defaultWindow ? "default" : from.getEpochSecond() + "-" + to.getEpochSecond();
    }
}
