package com.innogrid.aims.monitoring.log;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.common.log.PortalLog;
import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@code @PortalLog} 가 붙은 메서드를 감싸 포털 로그를 기록한다 (docs/08 §5).
 *
 * <ul>
 *   <li>정상 반환 → SUCCESS (async 면 IN_PROGRESS), 예외 → FAILURE + 메시지. 예외는 그대로 다시 던진다.</li>
 *   <li>트랜잭션 Aspect 보다 바깥({@code @Order} 낮은 값)에서 감싸 커밋 시점 예외도 FAILURE 로 잡는다.</li>
 *   <li>기록했음을 요청 속성에 남겨 {@link PortalLogFallbackInterceptor} 가 중복 기록하지 않게 한다.</li>
 * </ul>
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class PortalLogAspect {

    /** Aspect 가 이 요청을 기록했음을 표시하는 요청 속성 — 폴백 인터셉터 중복 방지 */
    public static final String RECORDED_ATTRIBUTE = PortalLogAspect.class.getName() + ".recorded";

    private final PortalLogContextResolver contextResolver;
    private final PortalLogSpelEvaluator spel;
    private final PortalLogWriter writer;

    public PortalLogAspect(PortalLogContextResolver contextResolver, PortalLogSpelEvaluator spel, PortalLogWriter writer) {
        this.contextResolver = contextResolver;
        this.spel = spel;
        this.writer = writer;
    }

    @Around("@annotation(portalLog)")
    public Object record(ProceedingJoinPoint pjp, PortalLog portalLog) throws Throwable {
        OffsetDateTime startedAt = OffsetDateTime.now();
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        Object[] args = pjp.getArgs();
        PortalLogContext ctx = contextResolver.resolve();
        markRecorded();

        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable e) {
            String content = spel.evaluate(portalLog.content(), method, args, null, ctx.userId(), ctx.userName());
            writer.write(entry(portalLog, ctx, startedAt, content)
                    .result(PortalLogResult.FAILURE).description(messageOf(e)).build());
            throw e;
        }

        String content = spel.evaluate(portalLog.content(), method, args, result, ctx.userId(), ctx.userName());
        PortalLogEntry.Builder builder = entry(portalLog, ctx, startedAt, content);
        if (!portalLog.refType().isBlank()) {
            builder.ref(portalLog.refType(), spel.evaluate(portalLog.refId(), method, args, result, ctx.userId(), ctx.userName()));
        }
        if (portalLog.async()) {
            builder.result(PortalLogResult.IN_PROGRESS).completedAt(null);
        } else {
            builder.result(PortalLogResult.SUCCESS);
        }
        writer.write(builder.build());
        return result;
    }

    private static PortalLogEntry.Builder entry(PortalLog portalLog, PortalLogContext ctx, OffsetDateTime startedAt, String content) {
        return PortalLogEntry.builder(portalLog.type())
                .startedAt(startedAt).completedAt(OffsetDateTime.now())
                .user(ctx.userId(), ctx.userName()).clientIp(ctx.clientIp()).requestId(ctx.requestId())
                .request(ctx.httpMethod(), ctx.requestUri())
                .screen(portalLog.screen()).action(portalLog.action()).content(content);
    }

    /** 실패 사유 — 업무 예외는 메시지만, 그 외는 예외 종류를 함께 남긴다(스택은 서버 로그에만) */
    static String messageOf(Throwable e) {
        if (e instanceof BusinessException) {
            return e.getMessage();
        }
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + message;
    }

    private static void markRecorded() {
        HttpServletRequest request = PortalLogContextResolver.currentRequest();
        if (request != null) {
            request.setAttribute(RECORDED_ATTRIBUTE, Boolean.TRUE);
        }
    }
}
