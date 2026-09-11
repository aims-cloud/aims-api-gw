package com.innogrid.aims.monitoring.log;

import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

/**
 * {@code @PortalLog} 의 content·refId SpEL 식을 평가한다 (docs/08 §4).
 *
 * 컨텍스트 변수: 메서드 인자(이름, {@code #p0}/{@code #a0}), {@code #result}(성공 시, 실패 시 null),
 * {@code #userId}, {@code #userName}. 평가가 실패해도 기록은 남겨야 하므로 예외 대신
 * "식 원문 [평가 실패]" 문자열을 돌려준다. 빈 식은 null.
 */
@Component
public class PortalLogSpelEvaluator {

    private static final Logger log = LoggerFactory.getLogger(PortalLogSpelEvaluator.class);

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();

    public String evaluate(String expression, Method method, Object[] args, Object result,
                           String userId, String userName) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            Expression parsed = parser.parseExpression(expression);
            // 데이터 바인딩용 읽기 전용 컨텍스트 — 빈 참조·T() 타입 참조·생성자 호출을 막아 템플릿이 코드 실행 통로가 되지 않게 한다.
            // 인자는 이름(#request)과 위치(#p0/#a0) 변수로 노출한다.
            SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding()
                    .withInstanceMethods()
                    .build();
            Object[] safeArgs = args == null ? new Object[0] : args;
            String[] names = parameterNames.getParameterNames(method);
            for (int i = 0; i < safeArgs.length; i++) {
                context.setVariable("p" + i, safeArgs[i]);
                context.setVariable("a" + i, safeArgs[i]);
                if (names != null && i < names.length && names[i] != null) {
                    context.setVariable(names[i], safeArgs[i]);
                }
            }
            context.setVariable("result", result);
            context.setVariable("userId", userId);
            context.setVariable("userName", userName);
            Object value = parsed.getValue(context);
            return value == null ? null : String.valueOf(value);
        } catch (RuntimeException e) {
            log.warn("포털 로그 SpEL 평가 실패 ({}): {}", expression, e.getMessage());
            return expression + " [평가 실패]";
        }
    }

    /** 인자·결과 없이 단순 리터럴 식만 평가할 때 */
    public String evaluateLiteral(String expression) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            Object value = parser.parseExpression(expression).getValue(SimpleEvaluationContext.forReadOnlyDataBinding().build());
            return value == null ? null : String.valueOf(value);
        } catch (RuntimeException e) {
            return expression + " [평가 실패]";
        }
    }
}
