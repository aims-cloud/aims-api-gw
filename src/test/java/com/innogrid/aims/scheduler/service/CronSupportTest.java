package com.innogrid.aims.scheduler.service;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

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

    @Test
    void 숫자_요일은_한_칸씩_낮춘다() {
        assertEquals("0 0 2 ? * 0", cron.toSpringExpression("0 0 2 ? * 1"), "Quartz 1=일");
        assertEquals("0 0 2 ? * 1", cron.toSpringExpression("0 0 2 ? * 2"), "Quartz 2=월");
        assertEquals("0 0 2 ? * 6", cron.toSpringExpression("0 0 2 ? * 7"), "Quartz 7=토");
    }

    @Test
    void 범위와_목록과_증분도_변환한다() {
        assertEquals("0 0 2 ? * 1-5", cron.toSpringExpression("0 0 2 ? * 2-6"), "월~금");
        assertEquals("0 0 2 ? * 1,3", cron.toSpringExpression("0 0 2 ? * 2,4"), "월,수");
        assertEquals("0 0 2 ? * 1/2", cron.toSpringExpression("0 0 2 ? * 2/2"));
        assertEquals("0 0 2 ? * 1-5/2", cron.toSpringExpression("0 0 2 ? * 2-6/2"));
    }

    @Test
    void 마지막_요일과_n번째_요일도_변환한다() {
        assertEquals("0 15 10 ? * 5L", cron.toSpringExpression("0 15 10 ? * 6L"), "마지막 금요일");
        // '#' 뒤 숫자는 몇 번째 주인지를 뜻하므로 옮기지 않는다
        assertEquals("0 0 2 ? * 5#3", cron.toSpringExpression("0 0 2 ? * 6#3"), "셋째 금요일");
    }

    @Test
    void 단독_L_은_토요일로_바꾼다() {
        // Quartz 의 단독 L 은 그 주의 마지막 날(토). Spring 은 앞에 요일이 없으면 파싱 오류를 낸다.
        assertEquals("0 0 2 ? * 6", cron.toSpringExpression("0 0 2 ? * L"));
    }

    @Test
    void 이름_요일은_그대로_둔다() {
        assertEquals("0 0 2 ? * MON", cron.toSpringExpression("0 0 2 ? * MON"));
        assertEquals("0 0 2 ? * MON-FRI", cron.toSpringExpression("0 0 2 ? * mon-fri"));
    }

    @Test
    void 와일드카드는_그대로_둔다() {
        assertEquals("0 0 2 * * ?", cron.toSpringExpression("0 0 2 * * ?"));
        assertEquals("0 0 2 ? * *", cron.toSpringExpression("0 0 2 ? * *"));
    }

    // ── 변환 결과가 의도한 요일에 실행되는가 ────────────────────

    @Test
    void Quartz_2_는_월요일에_실행된다() {
        // 변환하지 않으면 Spring 이 화요일로 읽는다
        ZonedDateTime next = kst(cron.nextRunAt("0 0 2 ? * 2", BASE));

        assertEquals(DayOfWeek.MONDAY, next.getDayOfWeek());
        assertEquals(2, next.getHour());
    }

    @Test
    void Quartz_6L_은_마지막_금요일에_실행된다() {
        ZonedDateTime next = kst(cron.nextRunAt("0 15 10 ? * 6L", BASE));

        assertEquals(DayOfWeek.FRIDAY, next.getDayOfWeek());
        assertTrue(next.getDayOfMonth() > next.toLocalDate().lengthOfMonth() - 7,
                "그 달의 마지막 금요일이어야 한다: " + next);
    }

    @Test
    void 이름_요일과_숫자_요일이_같은_날을_가리킨다() {
        assertEquals(kst(cron.nextRunAt("0 0 2 ? * MON", BASE)),
                     kst(cron.nextRunAt("0 0 2 ? * 2", BASE)));
    }

    // ── 시간대 (입력은 KST, 저장은 UTC) ─────────────────────────

    @Test
    void 입력은_한국_시간으로_해석하고_UTC_로_돌려준다() {
        OffsetDateTime next = cron.nextRunAt("0 0 2 * * ?", BASE);

        assertEquals(2, kst(next).getHour(), "한국 시간 02시");
        assertEquals(17, next.getHour(), "UTC 로는 전날 17시");
        assertEquals(0, next.getOffset().getTotalSeconds(), "저장·응답은 UTC");
    }

    @Test
    void 기본_시간대는_Asia_Seoul_이다() {
        assertEquals(KST, cron.zone());
    }

    /**
     * 한국 시간 기준 이른 새벽은 UTC 로는 전날이 된다.
     * cron 필드를 UTC 로 옮겨 적는 방식이었다면 날짜·요일·월이 전부 어긋났을 자리다.
     */
    @Test
    void 날짜_경계를_넘는_시각도_정확하다() {
        assertKst("0 30 0 * * ?", "00:30");    // UTC 로는 전날 15:30
        assertKst("0 0 2 * * ?", "02:00");     // UTC 로는 전날 17:00
        assertKst("0 59 8 * * ?", "08:59");    // UTC 자정 1분 전
        assertKst("0 0 9 * * ?", "09:00");     // UTC 자정 정각
        assertKst("0 0 23 * * ?", "23:00");    // 같은 날 14:00
    }

    @Test
    void 매월_1일은_UTC_로_전월_말일이_된다() {
        ZonedDateTime next = kst(cron.nextRunAt("0 0 2 1 * ?", BASE));

        assertEquals(1, next.getDayOfMonth(), "한국 시간으로는 1일이어야 한다");
        assertEquals("02:00", next.toLocalTime().toString());
        // 같은 순간을 UTC 로 보면 전월 말일이다
        var utc = cron.nextRunAt("0 0 2 1 * ?", BASE);
        assertEquals(17, utc.getHour());
        assertNotEquals(1, utc.getDayOfMonth(), "UTC 날짜는 1일이 아니다");
    }

    @Test
    void 말일_지정도_한국_시간_기준이다() {
        ZonedDateTime next = kst(cron.nextRunAt("0 0 2 L * ?", BASE));

        assertEquals(next.toLocalDate().lengthOfMonth(), next.getDayOfMonth(), "한국 시간으로 말일");
        assertEquals("02:00", next.toLocalTime().toString());
    }

    @Test
    void 마지막_금요일도_한국_시간_기준이다() {
        ZonedDateTime next = kst(cron.nextRunAt("0 15 10 ? * 6L", BASE));

        assertEquals(DayOfWeek.FRIDAY, next.getDayOfWeek());
        assertTrue(next.getDayOfMonth() > next.toLocalDate().lengthOfMonth() - 7, "그 달 마지막 금요일: " + next);
    }

    /**
     * 여러 번 이어서 계산해도 한국 시간 벽시계 값이 흔들리지 않아야 한다.
     * UTC 날짜는 달마다 달라지지만(08-31, 09-30 …) 사용자가 입력한 02:00 은 고정이다.
     */
    @Test
    void 연속_실행이_같은_벽시계_시각을_유지한다() {
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

    @Test
    void 연도_필드가_있으면_거부한다() {
        // 도움말 모달의 두 번째 예시가 이 형태다
        BusinessException e = assertThrows(BusinessException.class,
                () -> cron.toSpringExpression("0 15 10 ? * 6L 2002-2005"));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains("연도"), e.getMessage());
    }

    @Test
    void 필드_수가_모자라면_거부한다() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(BusinessException.class,
                        () -> cron.toSpringExpression("0 0 2 * *")).getStatus());
    }

    @Test
    void 캘린더_문자_C_는_거부한다() {
        assertTrue(assertThrows(BusinessException.class,
                () -> cron.toSpringExpression("0 0 2 ? * 5C")).getMessage().contains("C"));
        assertThrows(BusinessException.class, () -> cron.toSpringExpression("0 0 2 5C * ?"));
    }

    @Test
    void 일과_요일을_동시에_지정하면_거부한다() {
        // Spring 은 두 조건을 모두 만족하는 날로 읽어 2027-02-01 에 실행된다
        BusinessException e = assertThrows(BusinessException.class,
                () -> cron.toSpringExpression("0 0 2 1 * MON"));

        assertTrue(e.getMessage().contains("?"), e.getMessage());
    }

    @Test
    void 요일_번호가_범위를_벗어나면_거부한다() {
        assertThrows(BusinessException.class, () -> cron.toSpringExpression("0 0 2 ? * 0"));
        assertThrows(BusinessException.class, () -> cron.toSpringExpression("0 0 2 ? * 8"));
    }

    @Test
    void 빈_값은_거부한다() {
        assertThrows(BusinessException.class, () -> cron.toSpringExpression(null));
        assertThrows(BusinessException.class, () -> cron.toSpringExpression("   "));
    }

    // ── 최소 실행 간격 ──────────────────────────────────────────

    @Test
    void 확인_주기보다_짧은_실행은_거부한다() {
        // 폴링이 10초라 초 단위 실행은 대부분 유실된다
        BusinessException e = assertThrows(BusinessException.class,
                () -> cron.validate("* * * * * ?"));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains("간격"), e.getMessage());
    }

    @Test
    void 분_단위_이상은_통과한다() {
        assertDoesNotThrow(() -> cron.validate("0 */5 * * * ?"));
        assertDoesNotThrow(() -> cron.validate("0 0 12 * * ?"));   // 모달 예시 ①
        assertDoesNotThrow(() -> cron.validate("0 0 2 ? * MON"));
    }

    // ── 미리보기 ────────────────────────────────────────────────

    @Test
    void 다음_실행_시각을_요청한_개수만큼_돌려준다() {
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
