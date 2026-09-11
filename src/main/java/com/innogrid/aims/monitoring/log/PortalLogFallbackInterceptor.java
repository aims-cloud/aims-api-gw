package com.innogrid.aims.monitoring.log;

import java.time.OffsetDateTime;

import com.innogrid.aims.common.log.PortalLog;
import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 메서드 진입 전에 실패한 요청의 보조 기록 경로 (docs/08 §5.4).
 *
 * {@code @Valid} 검증 실패, JSON 파싱 실패, 파라미터 타입 변환 실패는 컨트롤러 메서드가 호출되기 전에 예외가 나서
 * {@link PortalLogAspect} 가 실행되지 않는다. 이 인터셉터는 요청이 끝난 뒤({@code afterCompletion}) 핸들러에
 * {@code @PortalLog} 가 있고 Aspect 가 기록하지 않았으며 응답이 4xx/5xx 면 FAILURE 행을 남긴다.
 * 인자가 바인딩되지 않았으므로 내용은 SpEL 대신 고정 문구를 쓴다. 응답 본문·상태는 바꾸지 않는다.
 */
@Component
public class PortalLogFallbackInterceptor implements HandlerInterceptor {

    private final PortalLogContextResolver contextResolver;
    private final PortalLogWriter writer;

    public PortalLogFallbackInterceptor(PortalLogContextResolver contextResolver, PortalLogWriter writer) {
        this.contextResolver = contextResolver;
        this.writer = writer;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return;
        }
        PortalLog portalLog = handlerMethod.getMethodAnnotation(PortalLog.class);
        if (portalLog == null || request.getAttribute(PortalLogAspect.RECORDED_ATTRIBUTE) != null
                || response.getStatus() < 400) {
            return;
        }
        PortalLogContext ctx = contextResolver.resolve();
        OffsetDateTime now = OffsetDateTime.now();
        writer.write(PortalLogEntry.builder(portalLog.type())
                .startedAt(now).completedAt(now)
                .user(ctx.userId(), ctx.userName()).clientIp(ctx.clientIp()).requestId(ctx.requestId())
                .request(ctx.httpMethod(), ctx.requestUri())
                .screen(portalLog.screen()).action(portalLog.action())
                .content(portalLog.screen() + " " + portalLog.action() + " 요청 검증 실패")
                .result(PortalLogResult.FAILURE)
                .description(ex != null ? PortalLogAspect.messageOf(ex) : "HTTP " + response.getStatus())
                .build());
    }
}
