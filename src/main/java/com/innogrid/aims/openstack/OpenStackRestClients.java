package com.innogrid.aims.openstack;

import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * OpenStack 연동용 RestClient 생성 유틸.
 *
 * 내부 구축 OpenStack은 사설 인증서 HTTPS인 경우가 많아,
 * verify-tls=false 설정 시 인증서 검증을 건너뛰는 클라이언트를 만든다.
 * (OpenStack 엔드포인트 전용 클라이언트에만 적용되며 다른 통신에는 영향 없음)
 */
final class OpenStackRestClients {

    private OpenStackRestClients() {}

    static RestClient build(String baseUrl, boolean verifyTls) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl == null ? "" : baseUrl)
                .defaultHeader("Content-Type", "application/json");
        if (!verifyTls) {
            builder.requestFactory(new JdkClientHttpRequestFactory(insecureHttpClient()));
        }
        return builder.build();
    }

    private static HttpClient insecureHttpClient() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
            return HttpClient.newBuilder().sslContext(sslContext).build();
        } catch (Exception e) {
            throw new IllegalStateException("TLS 우회 클라이언트 생성 실패", e);
        }
    }

    /**
     * 모든 인증서를 신뢰하는 TrustManager.
     * X509ExtendedTrustManager를 무동작으로 구현하면 인증서 검증과 호스트명 검증이 모두 생략된다.
     */
    private static final class TrustAllManager extends X509ExtendedTrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, java.net.Socket socket) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, java.net.Socket socket) {}
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
