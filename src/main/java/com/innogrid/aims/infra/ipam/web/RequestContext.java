package com.innogrid.aims.infra.ipam.web;

import java.security.Principal;

import jakarta.servlet.http.HttpServletRequest;

/** 컨트롤러 공용 — 인증 사용자·접속 IP 추출. */
public final class RequestContext {

    private RequestContext() {}

    public static String user(Principal principal) {
        return principal == null ? null : principal.getName();
    }

    public static String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }
}
