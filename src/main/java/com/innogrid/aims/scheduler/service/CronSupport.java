package com.innogrid.aims.scheduler.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.config.SchedulerProperties;

/**
 * Quartz cron 문자열을 Spring {@link CronExpression} 의미로 옮겨 다음 실행 시각을 계산한다.
 *
 * <p><b>시간대</b> — cron 문자열에는 시간대 정보가 없다. 배포 VM 은 OS·DB 모두 UTC 라서
 * 그대로 두면 화면에서 "매일 02:00" 으로 만든 스케줄이 한국 시간 오전 11시에 돈다.
 * 그래서 <b>입력을 한국 시간(UTC+9)으로 가정</b>해 해석하고
 * ({@link SchedulerProperties#getTimezone()}), 결과는 UTC {@code OffsetDateTime} 으로 돌려준다.
 * 저장·응답은 전부 UTC 이며 표출 변환은 프런트가 한다.</p>
 *
 * <p><b>왜 변환이 필요한가</b> — 프런트 도움말 모달은 Quartz 규약을 안내하는데 런타임은
 * Spring 이다. 요일 번호 규약이 서로 하나씩 어긋나고, 이 차이는 예외로 드러나지 않고
 * <b>조용히 다른 날 실행</b>되는 형태로 나타난다. 아래는 Spring 7.0.8 로 직접 확인한 값이다.</p>
 *
 * <pre>
 * 입력                        Quartz 의미        Spring 이 그대로 해석하면
 * ─────────────────────────  ─────────────────  ────────────────────────
 * 0 0 2 ? * 1                일요일              월요일
 * 0 0 2 ? * 2                월요일              화요일
 * 0 15 10 ? * 6L             마지막 금요일        마지막 토요일
 * 0 0 2 ? * 7L               마지막 토요일        마지막 일요일
 * 0 0 2 ? * L                토요일              파싱 오류
 * 0 15 10 ? * 6L 2002-2005   연도 지정           필드 수 오류(7필드)
 * 0 0 2 1 * MON              (Quartz 는 금지)     1일이면서 월요일 = 2027-02-01
 * </pre>
 *
 * <p>요일 번호 대응은 <b>Spring = Quartz − 1</b> 이다(Quartz {@code 1}=일 … {@code 7}=토,
 * Spring {@code 0}=일 … {@code 6}=토). 요일 <b>이름</b>({@code MON}~{@code SUN})은 양쪽이
 * 같으므로 변환하지 않는다.</p>
 *
 * <p>지원하지 않는 표기는 조용히 무시하지 않고 400 으로 거부한다. 무시하면 사용자가 의도한
 * 것과 다른 시각에 실행되는데, 그 사실이 어디에도 드러나지 않기 때문이다.</p>
 */
@Component
public class CronSupport {

    /** 미리보기로 돌려줄 다음 실행 시각 개수. */
    public static final int PREVIEW_COUNT = 5;

    /** 허용하는 최소 실행 간격(초). 폴링 주기보다 짧으면 대부분의 실행이 유실된다. */
    private static final long MIN_INTERVAL_SECONDS = 60;

    private static final Pattern LAST_OF_WEEK = Pattern.compile("^(\\d+)L$");
    private static final Pattern NTH_OF_WEEK = Pattern.compile("^(\\d+)#(\\d+)$");
    private static final Pattern RANGE = Pattern.compile("^(\\d+)-(\\d+)(?:/(\\d+))?$");
    private static final Pattern STEP_FROM = Pattern.compile("^(\\d+)/(\\d+)$");
    private static final Pattern NUMBER = Pattern.compile("^(\\d+)$");
    private static final Pattern WILDCARD_STEP = Pattern.compile("^\\*/\\d+$");
    private static final Pattern DAY_NAMES = Pattern.compile("^[A-Z]{3}(-[A-Z]{3})?(/\\d+)?$");

    private final SchedulerProperties properties;

    public CronSupport(SchedulerProperties properties) {
        this.properties = properties;
    }

    /** cron 해석에 쓰는 시간대. 저장·응답은 이 값과 무관하게 UTC 다. */
    public ZoneId zone() {
        return ZoneId.of(properties.getTimezone());
    }

    /**
     * Quartz 표기를 검증하고 Spring 표기로 옮긴다.
     *
     * @throws BusinessException 지원하지 않는 표기이면 400
     */
    public String toSpringExpression(String quartzCron) {
        if (quartzCron == null || quartzCron.isBlank()) {
            throw badRequest("실행 주기(cron)는 필수입니다");
        }
        String[] fields = quartzCron.trim().split("\\s+");

        if (fields.length == 7) {
            throw badRequest("연도 필드(7번째)는 지원하지 않습니다. 앞 6필드만 입력하세요: "
                    + "[초] [분] [시] [일] [월] [요일]");
        }
        if (fields.length != 6) {
            throw badRequest(String.format(
                    "cron 은 6필드여야 합니다(현재 %d개). 형식: [초] [분] [시] [일] [월] [요일]", fields.length));
        }

        String dayOfMonth = fields[3];
        String dayOfWeek = fields[5].toUpperCase(Locale.ROOT);

        if (containsCalendarChar(dayOfMonth) || containsCalendarChar(dayOfWeek)) {
            throw badRequest("특수문자 C(캘린더 기준)는 지원하지 않습니다");
        }
        if (!isUnrestricted(dayOfMonth) && !isUnrestricted(dayOfWeek)) {
            throw badRequest("일과 요일 중 하나는 ? 또는 * 여야 합니다. "
                    + "둘 다 지정하면 두 조건을 모두 만족하는 날에만 실행되어 의도와 크게 달라집니다");
        }

        fields[5] = convertDayOfWeek(dayOfWeek);
        return String.join(" ", fields);
    }

    /**
     * 표현식을 검증한다. 파싱 가능 여부와 최소 실행 간격까지 확인한다.
     *
     * @return Spring 표기로 옮긴 표현식
     */
    public String validate(String quartzCron) {
        String spring = toSpringExpression(quartzCron);
        CronExpression parsed = parse(spring, quartzCron);

        ZonedDateTime base = ZonedDateTime.now(zone());
        ZonedDateTime first = parsed.next(base);
        if (first == null) {
            throw badRequest("실행될 시각이 없는 표현식입니다: " + quartzCron);
        }
        ZonedDateTime second = parsed.next(first);
        if (second != null && Duration.between(first, second).getSeconds() < MIN_INTERVAL_SECONDS) {
            throw badRequest(String.format(
                    "실행 간격이 너무 짧습니다(%d초). 최소 %d초 이상이어야 합니다 — 확인 주기가 %d초라 그보다 짧은 실행은 유실됩니다",
                    Duration.between(first, second).getSeconds(), MIN_INTERVAL_SECONDS,
                    properties.getPollIntervalMs() / 1000));
        }
        return spring;
    }

    /**
     * 기준 시각 다음의 실행 시각. 없으면 {@code null}.
     *
     * @param after 기준 시각(UTC). {@code null} 이면 현재
     */
    public OffsetDateTime nextRunAt(String quartzCron, OffsetDateTime after) {
        CronExpression parsed = parse(toSpringExpression(quartzCron), quartzCron);
        ZonedDateTime base = (after == null ? OffsetDateTime.now() : after).atZoneSameInstant(zone());
        ZonedDateTime next = parsed.next(base);
        return next == null ? null : toUtc(next);
    }

    /** 미리보기용 — 현재 이후의 실행 시각을 차례로 돌려준다(UTC). */
    public List<OffsetDateTime> nextRuns(String quartzCron, int count) {
        CronExpression parsed = parse(toSpringExpression(quartzCron), quartzCron);
        List<OffsetDateTime> result = new ArrayList<>();
        ZonedDateTime cursor = ZonedDateTime.now(zone());
        for (int i = 0; i < count; i++) {
            cursor = parsed.next(cursor);
            if (cursor == null) break;
            result.add(toUtc(cursor));
        }
        return result;
    }

    // ── 내부 ────────────────────────────────────────────────────

    /**
     * 요일 필드를 Quartz 규약에서 Spring 규약으로 옮긴다.
     * 숫자만 하나씩 낮추고, 이름·와일드카드는 그대로 둔다.
     */
    private String convertDayOfWeek(String field) {
        String[] tokens = field.split(",", -1);
        StringBuilder converted = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) converted.append(',');
            converted.append(convertDayOfWeekToken(tokens[i].trim()));
        }
        return converted.toString();
    }

    private String convertDayOfWeekToken(String token) {
        if (token.isEmpty()) {
            throw badRequest("요일 필드가 비어 있습니다");
        }
        if ("*".equals(token) || "?".equals(token) || WILDCARD_STEP.matcher(token).matches()) {
            return token;
        }
        // Quartz 의 단독 L 은 "그 주의 마지막 날" = 토요일. Spring 은 앞에 요일이 있어야 한다.
        if ("L".equals(token)) {
            return "6";
        }
        Matcher lastOfWeek = LAST_OF_WEEK.matcher(token);
        if (lastOfWeek.matches()) {
            return shift(lastOfWeek.group(1)) + "L";
        }
        Matcher nth = NTH_OF_WEEK.matcher(token);
        if (nth.matches()) {
            // '#' 뒤의 숫자는 몇 번째 주인지를 뜻하므로 옮기지 않는다.
            return shift(nth.group(1)) + "#" + nth.group(2);
        }
        Matcher range = RANGE.matcher(token);
        if (range.matches()) {
            String base = shift(range.group(1)) + "-" + shift(range.group(2));
            return range.group(3) == null ? base : base + "/" + range.group(3);
        }
        Matcher stepFrom = STEP_FROM.matcher(token);
        if (stepFrom.matches()) {
            return shift(stepFrom.group(1)) + "/" + stepFrom.group(2);
        }
        Matcher number = NUMBER.matcher(token);
        if (number.matches()) {
            return shift(number.group(1));
        }
        if (DAY_NAMES.matcher(token).matches()) {
            return token;   // MON, MON-FRI 등 이름 표기는 양쪽이 같다
        }
        throw badRequest("지원하지 않는 요일 표기입니다: " + token);
    }

    /** Quartz 요일 번호(1=일 … 7=토)를 Spring 번호(0=일 … 6=토)로 낮춘다. */
    private String shift(String quartzNumber) {
        int value = Integer.parseInt(quartzNumber);
        if (value < 1 || value > 7) {
            throw badRequest(String.format(
                    "요일은 1~7 이어야 합니다(1=일, 7=토). 입력값: %d", value));
        }
        return String.valueOf(value - 1);
    }

    /** Quartz 의 C(캘린더 기준)는 지원하지 않는다. 요일 이름에는 C 가 들어가지 않아 이 검사로 충분하다. */
    private boolean containsCalendarChar(String field) {
        return field.indexOf('C') >= 0 || field.indexOf('c') >= 0;
    }

    private boolean isUnrestricted(String field) {
        return "*".equals(field) || "?".equals(field);
    }

    private CronExpression parse(String springCron, String original) {
        try {
            return CronExpression.parse(springCron);
        } catch (IllegalArgumentException e) {
            throw badRequest(String.format("cron 표현식을 해석할 수 없습니다: %s (%s)",
                    original, e.getMessage()));
        }
    }

    private OffsetDateTime toUtc(ZonedDateTime zoned) {
        return zoned.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }
}
