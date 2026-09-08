package com.innogrid.aims.openstack;

import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.ssl.SSLContexts;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * OpenStack 연동용 RestClient 생성 유틸.
 *
 * Apache HttpClient5 를 사용한다:
 * - 요청 본문에 Content-Length 를 붙여 chunked 전송으로 인한 411 Length Required 를 피한다
 *   (JDK HttpClient 는 프록시 뒤 OpenStack 에 chunked 로 보내 411 이 발생했다)
 * - 자체 구축 OpenStack 의 사설 인증서 HTTPS 는 verify-tls=false 시 검증을 생략한다
 */
final class OpenStackRestClients {

    private OpenStackRestClients() {}

    static RestClient build(String baseUrl, boolean verifyTls) {
        return RestClient.builder()
                .baseUrl(baseUrl == null ? "" : baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient(verifyTls)))
                .build();
    }

    private static HttpClient httpClient(boolean verifyTls) {
        if (verifyTls) {
            return HttpClients.createDefault();
        }
        try {
            SSLContext sslContext = SSLContexts.custom()
                    .loadTrustMaterial(null, (chain, authType) -> true)
                    .build();
            var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                    .setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                            .setSslContext(sslContext)
                            .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                            .build())
                    .build();
            return HttpClients.custom()
                    .setConnectionManager(connectionManager)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("TLS 우회 OpenStack 클라이언트 생성 실패", e);
        }
    }
}
