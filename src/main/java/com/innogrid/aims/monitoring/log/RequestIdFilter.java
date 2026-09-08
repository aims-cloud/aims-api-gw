package com.innogrid.aims.monitoring.log;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 추적 ID 를 발급해 MDC 와 응답 헤더에 싣는다.
 * 포털 로그의 {@code request_id} 와 서버 로그를 대조하는 열쇠다. 클라이언트가 {@code X-Request-Id} 를 보내면 그대로 쓴다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = incomingOrNew(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    static String incomingOrNew(String incoming) {
        if (incoming == null || incoming.isBlank() || incoming.length() > MAX_LENGTH
                || !incoming.matches("[A-Za-z0-9._:-]+")) {
            return UUID.randomUUID().toString();
        }
        return incoming;
    }
}
