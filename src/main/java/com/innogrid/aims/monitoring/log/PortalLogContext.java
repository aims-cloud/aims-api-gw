package com.innogrid.aims.monitoring.log;

/**
 * 요청 로그 한 건의 공통 필드 — 사용자·클라이언트 IP·요청 ID·HTTP 메서드·URI.
 * 요청이 없는 문맥(스케줄러)에서는 전부 null 인 {@link #EMPTY} 를 쓴다.
 */
public record PortalLogContext(String userId,
                               String userName,
                               String clientIp,
                               String requestId,
                               String httpMethod,
                               String requestUri) {

    public static final PortalLogContext EMPTY = new PortalLogContext(null, null, null, null, null, null);
}
