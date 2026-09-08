package com.innogrid.aims.monitoring.log;

import com.innogrid.aims.auth.entity.User;
import com.innogrid.aims.auth.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 현재 요청·인증 문맥에서 포털 로그 공통 필드를 뽑는다 (docs/08 §5.2).
 *
 * <ul>
 *   <li>사용자: SecurityContext 의 인증 주체 이름(= 로그인 이메일)을 userId 로, users.name 을 userName 으로 쓴다.
 *       조회 실패 시 userName 은 userId 로 대체한다.</li>
 *   <li>IP: {@code X-Forwarded-For} 첫 값 → {@code X-Real-IP} → remoteAddr.</li>
 *   <li>요청 ID: {@link RequestIdFilter} 가 MDC 에 넣은 값.</li>
 * </ul>
 * 요청 스레드가 아니면(스케줄러) 요청 필드는 null 이다.
 */
@Component
public class PortalLogContextResolver {

    private final UserRepository userRepository;

    public PortalLogContextResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public PortalLogContext resolve() {
        HttpServletRequest request = currentRequest();
        String userId = currentUserId();
        return new PortalLogContext(
                userId,
                userId == null ? null : displayName(userId),
                request == null ? null : clientIp(request),
                MDC.get(RequestIdFilter.MDC_KEY),
                request == null ? null : request.getMethod(),
                request == null ? null : request.getRequestURI());
    }

    /** 인증 주체가 없을 때(로그인 시도 등) 요청 인자로 사용자 ID 를 지정해 문맥을 만든다 */
    public PortalLogContext resolveAs(String userId) {
        PortalLogContext base = resolve();
        if (userId == null || userId.isBlank()) {
            return base;
        }
        return new PortalLogContext(userId, displayName(userId), base.clientIp(), base.requestId(),
                base.httpMethod(), base.requestUri());
    }

    static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        String name = auth.getName();
        return name == null || name.isBlank() ? null : name;
    }

    private String displayName(String userId) {
        try {
            return userRepository.findByEmail(userId).map(User::getName).orElse(userId);
        } catch (RuntimeException e) {
            return userId;
        }
    }

    static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest() : null;
    }

    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }
}
