package com.innogrid.aims.monitoring.log;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** @PortalLog SpEL 평가 — 인자 이름·#p0·#result·#userId 바인딩, 평가 실패 폴백, 빈 식. */
class PortalLogSpelEvaluatorTest {

    record ZoneRequest(String name, int cidrCount) {}
    record Created(Long id) {}

    static class Target {
        public Created create(ZoneRequest request, List<Long> ids) { return null; }
    }

    PortalLogSpelEvaluator evaluator = new PortalLogSpelEvaluator();
    Method method = method();

    @Test
    void 인자_이름과_위치_변수로_내용을_만든다() {
        Object[] args = {new ZoneRequest("zone-a", 3), List.of(1L, 2L)};

        assertThat(evaluator.evaluate("'네트워크 구역 생성: ' + #request.name", method, args, null, "admin", "관리자"))
                .isEqualTo("네트워크 구역 생성: zone-a");
        assertThat(evaluator.evaluate("'삭제: ' + #ids", method, args, null, null, null)).isEqualTo("삭제: 1,2");   // SpEL 컬렉션 문자열 변환
        assertThat(evaluator.evaluate("#ids.size() + '건'", method, args, null, null, null)).isEqualTo("2건");
        assertThat(evaluator.evaluate("#p0.cidrCount + '개'", method, args, null, null, null)).isEqualTo("3개");
        assertThat(evaluator.evaluate("#userName + '(' + #userId + ')'", method, args, null, "admin", "관리자"))
                .isEqualTo("관리자(admin)");
    }

    @Test
    void 결과는_성공_시에만_있고_실패_시_null이다() {
        Object[] args = {new ZoneRequest("zone-a", 1), List.of()};

        assertThat(evaluator.evaluate("#result.id", method, args, new Created(42L), null, null)).isEqualTo("42");
        assertThat(evaluator.evaluate("#result?.id", method, args, null, null, null)).isNull();
    }

    @Test
    void 평가에_실패하면_예외_대신_원문과_실패_표시를_돌려준다() {
        Object[] args = {null, List.of()};

        assertThat(evaluator.evaluate("#request.name", method, args, null, null, null))
                .isEqualTo("#request.name [평가 실패]");
        assertThat(evaluator.evaluate("T(java.lang.Runtime).getRuntime()", method, args, null, null, null))
                .endsWith("[평가 실패]");                                    // 타입 참조는 막혀 있다
    }

    @Test
    void 빈_식은_null이고_리터럴_평가도_지원한다() {
        assertThat(evaluator.evaluate("", method, new Object[0], null, null, null)).isNull();
        assertThat(evaluator.evaluate(null, method, new Object[0], null, null, null)).isNull();
        assertThat(evaluator.evaluateLiteral("'고정 문구'")).isEqualTo("고정 문구");
        assertThat(evaluator.evaluateLiteral("#missing.x")).endsWith("[평가 실패]");
    }

    private static Method method() {
        try {
            return Target.class.getMethod("create", ZoneRequest.class, List.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}
