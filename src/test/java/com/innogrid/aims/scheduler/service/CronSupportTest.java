package com.innogrid.aims.scheduler.service;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.config.SchedulerProperties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 프런트 도움말 모달은 Quartz 규약을 안내하는데 런타임은 Spring 이다.
 * 두 규약의 차이를 실제 값으로 고정해 둔다 — 요일 해석이 어긋나도 예외가 나지 않고
 * 조용히 다른 날 실행되기 때문에 테스트로 잡지 않으면 드러나지 않는다.
 */
class CronSupportTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 2026-08-31(월) 09:00 KST. 그날 02:00 은 이미 지난 시점이다. */
    private static final OffsetDateTime BASE = OffsetDateTime.parse("2026-08-31T00:00:00Z");

    private final CronSupport cron = new CronSupport(new SchedulerProperties());

    // ── 요일 번호 변환 (Quartz 1=일 → Spring 0=일) ──────────────

    @DisplayName("숫자 요일은 한 칸씩 낮춘다")
    @Test
    void numeric_day_of_week_shifted_down() {
        assertEquals("0 0 2 ? * 0", cron.toSpringExpression("0 0 2 ? * 1"), "Quartz 1=일");
        assertEquals("0 0 2 ? * 1", cron.toSpringExpression("0 0 2 ? * 2"), "Quartz 2=월");
        assertEquals("0 0 2 ? * 6", cron.toSpringExpression("0 0 2 ? * 7"), "Quartz 7=토");
    }

    @DisplayName("범위와 목록과 증분도 변환한다")
    @Test
    void range_list_increment_converted() {
        assertEquals("0 0 2 ? * 1-5", cron.toSpringExpression("0 0 2 ? * 2-6"), "월~금");
        assertEquals("0 0 2 ? * 1,3", cron.toSpringExpression("0 0 2 ? * 2,4"), "월,수");
        assertEquals("0 0 2 ? * 1/2", cron.toSpringExpression("0 0 2 ? * 2/2"));
        assertEquals("0 0 2 ? * 1-5/2", cron.toSpringExpression("0 0 2 ? * 2-6/2"));
    }

    @DisplayName("마지막 요일과 n번째 요일도 변환한다")
    @Test
    void last_and_nth_day_of_week_converted() {
        assertEquals("0 15 10 ? * 5L", cron.toSpringExpression("0 15 10 ? * 6L"), "마지막 금요일");
        // '#' 뒤 숫자는 몇 번째 주인지를 뜻하므로 옮기지 않는다
        assertEquals("0 0 2 ? * 5#3", cron.toSpringExpression("0 0 2 ? * 6#3"), "셋째 금요일");
    }

    @DisplayName("단독 L 은 토요일로 바꾼다")
    @Test
    void standalone_l_becomes_saturday() {
        // Quartz 의 단독 L 은 그 주의 마지막 날(토). Spring 은 앞에 요일이 없으면 파싱 오류를 낸다.
        assertEquals("0 0 2 ? * 6", cron.toSpringExpression("0 0 2 ? * L"));
    }

    @DisplayName("이름 요일은 그대로 둔다")
    @Test
    void named_day_of_week_unchanged() {
        assertEquals("0 0 2 ? * MON", cron.toSpringExpression("0 0 2 ? * MON"));
        assertEquals("0 0 2 ? * MON-FRI", cron.toSpringExpression("0 0 2 ? * mon-fri"));
    }

    @DisplayName("와일드카드는 그대로 둔다")
    @Test
    void wildcard_unchanged() {
        assertEquals("0 0 2 * * ?", cron.toSpringExpression("0 0 2 * * ?"));
        assertEquals("0 0 2 ? * *", cron.toSpringExpression("0 0 2 ? * *"));
    }

    // ── 변환 결과가 의도한 요일에 실행되는가 ────────────────────

    @DisplayName("Quartz 2 는 월요일에 실행된다")
    @Test
    void quartz_2_runs_monday() {
        // 변환하지 않으면 Spring 이 화요일로 읽는다
        ZonedDateTime next = kst(cron.nextRunAt("0 0 2 ? * 2", BASE));

        assertEquals(DayOfWeek.MONDAY, next.getDayOfWeek());
        assertEquals(2, next.getHour());
    }

    @DisplayName("Quartz 6L 은 마지막 금요일에 실행된다")
    @Test
    void quartz_6l_runs_last_friday() {
        ZonedDateTime next = kst(cron.nextRunAt("0 15 10 ? * 6L", BASE));

        assertEquals(DayOfWeek.FRIDAY, next.getDayOfWeek());
        assertTrue(next.getDayOfMonth() > next.toLocalDate().lengthOfMonth() - 7,
                "그 달의 마지막 금요일이어야 한다: " + next);
    }

    @DisplayName("이름 요일과 숫자 요일이 같은 날을 가리킨다")
    @Test
    void named_and_numeric_same_day() {
        assertEquals(kst(cron.nextRunAt("0 0 2 ? * MON", BASE)),
                     kst(cron.nextRunAt("0 0 2 ? * 2", BASE)));
    }

    // ── 시간대 (입력은 KST, 저장은 UTC) ─────────────────────────

    @DisplayName("입력은 한국 시간으로 해석하고 UTC 로 돌려준다")
    @Test
    void input_parsed_as_kst_returned_utc() {
        OffsetDateTime next = cron.nextRunAt("0 0 2 * * ?", BASE);

        assertEquals(2, kst(next).getHour(), "한국 시간 02시");
        assertEquals(17, next.getHour(), "UTC 로는 전날 17시");
        assertEquals(0, next.getOffset().getTotalSeconds(), "저장·응답은 UTC");
    }

    @DisplayName("기본 시간대는 Asia Seoul 이다")
    @Test
    void default_zone_asia_seoul() {
        assertEquals(KST, cron.zone());
    }

    /**
     * 한국 시간 기준 이른 새벽은 UTC 로는 전날이 된다.
     * cron 필드를 UTC 로 옮겨 적는 방식이었다면 날짜·요일·월이 전부 어긋났을 자리다.
     */
    @DisplayName("날짜 경계를 넘는 시각도 정확하다")
    @Test
    void date_boundary_crossing_correct() {
        assertKst("0 30 0 * * ?", "00:30");    // UTC 로는 전날 15:30
        assertKst("0 0 2 * * ?", "02:00");     // UTC 로는 전날 17:00
        assertKst("0 59 8 * * ?", "08:59");    // UTC 자정 1분 전
        assertKst("0 0 9 * * ?", "09:00");     // UTC 자정 정각
        assertKst("0 0 23 * * ?", "23:00");    // 같은 날 14:00
    }

    @DisplayName("매월 1일은 UTC 로 전월 말일이 된다")
    @Test
    void monthly_first_day_is_utc_prev_month_end() {
        ZonedDateTime next = kst(cron.nextRunAt("0 0 2 1 * ?", BASE));

        assertEquals(1, next.getDayOfMonth(), "한국 시간으로는 1일이어야 한다");
        assertEquals("02:00", next.toLocalTime().toString());
        // 같은 순간을 UTC 로 보면 전월 말일이다
        var utc = cron.nextRunAt("0 0 2 1 * ?", BASE);
        assertEquals(17, utc.getHour());
        assertNotEquals(1, utc.getDayOfMonth(), "UTC 날짜는 1일이 아니다");
    }

    @DisplayName("말일 지정도 한국 시간 기준이다")
    @Test
    void last_day_of_month_uses_kst() {
        ZonedDateTime next = kst(cron.nextRunAt("0 0 2 L * ?", BASE));

        assertEquals(next.toLocalDate().lengthOfMonth(), next.getDayOfMonth(), "한국 시간으로 말일");
        assertEquals("02:00", next.toLocalTime().toString());
    }

    @DisplayName("마지막 금요일도 한국 시간 기준이다")
    @Test
    void last_friday_uses_kst() {
        ZonedDateTime next = kst(cron.nextRunAt("0 15 10 ? * 6L", BASE));

        assertEquals(DayOfWeek.FRIDAY, next.getDayOfWeek());
        assertTrue(next.getDayOfMonth() > next.toLocalDate().lengthOfMonth() - 7, "그 달 마지막 금요일: " + next);
    }

    /**
     * 여러 번 이어서 계산해도 한국 시간 벽시계 값이 흔들리지 않아야 한다.
     * UTC 날짜는 달마다 달라지지만(08-31, 09-30 …) 사용자가 입력한 02:00 은 고정이다.
     */
    @DisplayName("연속 실행이 같은 벽시계 시각을 유지한다")
    @Test
    void consecutive_runs_keep_wall_clock() {
        for (String expression : new String[]{"0 0 2 * * ?", "0 0 2 ? * 2", "0 0 2 1 * ?", "0 0 2 L * ?"}) {
            List<String> times = cron.nextRuns(expression, 5).stream()
                    .map(at -> at.atZoneSameInstant(KST).toLocalTime().toString())
                    .distinct().toList();
            assertEquals(List.of("02:00"), times, expression + " 의 5회 실행 시각");
        }
    }

    private void assertKst(String expression, String expectedLocalTime) {
        assertEquals(expectedLocalTime, kst(cron.nextRunAt(expression, BASE)).toLocalTime().toString(),
                expression);
    }

    // ── 지원하지 않는 표기는 조용히 넘기지 않는다 ───────────────

    @DisplayName("연도 필드가 있으면 거부한다")
    @Test
    void year_field_rejected() {
        // 도움말 모달의 두 번째 예시가 이 형태다
        BusinessException e = assertThrows(BusinessException.class,
                () -> cron.toSpringExpression("0 15 10 ? * 6L 2002-2005"));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains("연도"), e.getMessage());
    }

    @DisplayName("필드 수가 모자라면 거부한다")
    @Test
    void too_few_fields_rejected() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(BusinessException.class,
                        () -> cron.toSpringExpression("0 0 2 * *")).getStatus());
    }

    @DisplayName("캘린더 문자 C 는 거부한다")
    @Test
    void calendar_char_c_rejected() {
        assertTrue(assertThrows(BusinessException.class,
                () -> cron.toSpringExpression("0 0 2 ? * 5C")).getMessage().contains("C"));
        assertThrows(BusinessException.class, () -> cron.toSpringExpression("0 0 2 5C * ?"));
    }

    @DisplayName("일과 요일을 동시에 지정하면 거부한다")
    @Test
    void both_dom_and_dow_rejected() {
        // Spring 은 두 조건을 모두 만족하는 날로 읽어 2027-02-01 에 실행된다
        BusinessException e = assertThrows(BusinessException.class,
                () -> cron.toSpringExpression("0 0 2 1 * MON"));

        assertTrue(e.getMessage().contains("?"), e.getMessage());
    }

    @DisplayName("요일 번호가 범위를 벗어나면 거부한다")
    @Test
    void dow_number_out_of_range_rejected() {
        assertThrows(BusinessException.class, () -> cron.toSpringExpression("0 0 2 ? * 0"));
        assertThrows(BusinessException.class, () -> cron.toSpringExpression("0 0 2 ? * 8"));
    }

    @DisplayName("빈 값은 거부한다")
    @Test
    void empty_value_rejected() {
        assertThrows(BusinessException.class, () -> cron.toSpringExpression(null));
        assertThrows(BusinessException.class, () -> cron.toSpringExpression("   "));
    }

    // ── 최소 실행 간격 ──────────────────────────────────────────

    @DisplayName("확인 주기보다 짧은 실행은 거부한다")
    @Test
    void interval_below_check_period_rejected() {
        // 폴링이 10초라 초 단위 실행은 대부분 유실된다
        BusinessException e = assertThrows(BusinessException.class,
                () -> cron.validate("* * * * * ?"));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains("간격"), e.getMessage());
    }

    @DisplayName("분 단위 이상은 통과한다")
    @Test
    void minute_interval_passes() {
        assertDoesNotThrow(() -> cron.validate("0 */5 * * * ?"));
        assertDoesNotThrow(() -> cron.validate("0 0 12 * * ?"));   // 모달 예시 ①
        assertDoesNotThrow(() -> cron.validate("0 0 2 ? * MON"));
    }

    // ── 미리보기 ────────────────────────────────────────────────

    @DisplayName("다음 실행 시각을 요청한 개수만큼 돌려준다")
    @Test
    void next_run_times_count_honored() {
        List<OffsetDateTime> runs = cron.nextRuns("0 0 2 * * ?", 5);

        assertEquals(5, runs.size());
        for (int i = 1; i < runs.size(); i++) {
            assertTrue(runs.get(i).isAfter(runs.get(i - 1)), "시각이 순서대로 나와야 한다");
        }
    }

    private ZonedDateTime kst(OffsetDateTime at) {
        assertNotNull(at, "다음 실행 시각이 계산되어야 한다");
        return at.atZoneSameInstant(KST);
    }
}
