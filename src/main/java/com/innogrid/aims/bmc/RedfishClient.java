package com.innogrid.aims.bmc;

import java.util.Map;

import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.ssl.SSLContexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.innogrid.aims.bmc.BmcProperties.Endpoint;

/**
 * Redfish(하드웨어 관리 표준 REST API) 클라이언트.
 * XCC(Lenovo)·IDRAC(Dell) 모두 표준 스키마가 동일해 공통으로 조회한다.
 *
 * 엔드포인트별 접속정보(Basic 인증)는 {@link BmcProperties}에서 받아 그때그때 사용하며,
 * 비밀번호를 로그·필드에 저장하지 않는다. 사설 인증서 HTTPS를 지원한다.
 */
@Component
public class RedfishClient {

    private static final Logger log = LoggerFactory.getLogger(RedfishClient.class);

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final boolean verifyTls;

    public RedfishClient(BmcProperties props) {
        this.verifyTls = props.isVerifyTls();
    }

    /**
     * 컴퓨터 시스템(서버) 하드웨어 정보를 정규화해 조회한다.
     * /redfish/v1/Systems 의 첫 멤버를 따라가 모델/시리얼/CPU/메모리/전원상태를 읽는다.
     * 실패/미구성 시 null (호출 측이 미조회로 처리).
     */
    public RedfishSystem getSystem(Endpoint ep) {
        Map<String, Object> raw = getSystemRaw(ep);
        return raw == null ? null : normalize(ep, raw);
    }

    /**
     * 컴퓨터 시스템 원시 응답(Redfish JSON). 정규화 필드 외 항목이 필요할 때 사용.
     * 실패/미구성 시 null.
     */
    public Map<String, Object> getSystemRaw(Endpoint ep) {
        if (ep == null || !ep.isConfigured()) return null;
        try {
            RestClient rest = client(ep);
            Map<String, Object> collection = rest.get().uri("/redfish/v1/Systems").retrieve().body(MAP_TYPE);
            String systemPath = firstMemberPath(collection);
            if (systemPath == null) return null;
            return rest.get().uri(systemPath).retrieve().body(MAP_TYPE);
        } catch (Exception e) {
            log.warn("Redfish 조회 실패 (endpoint {}): {}", ep.getId(), e.getMessage());
            return null;
        }
    }

    // ── Redfish ComputerSystem → 정규화 ─────────────────────────

    static RedfishSystem normalize(Endpoint ep, Map<String, Object> sys) {
        Map<String, Object> cpu = asMap(sys.get("ProcessorSummary"));
        Map<String, Object> mem = asMap(sys.get("MemorySummary"));
        return new RedfishSystem(
                ep.getId(),
                str(sys.get("Manufacturer")),
                str(sys.get("Model")),
                str(sys.get("SerialNumber")),
                str(sys.get("HostName")),
                str(sys.get("PowerState")),
                health(sys.get("Status")),
                str(cpu.get("Model")),
                intOf(cpu.get("Count")),
                intOf(cpu.get("CoreCount")),
                intOf(cpu.get("LogicalProcessorCount")),
                intOf(mem.get("TotalSystemMemoryGiB")));
    }

    private static String health(Object status) {
        Map<String, Object> s = asMap(status);
        return str(s.get("Health"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : java.util.Map.of();
    }

    private static String str(Object v) {
        String s = v == null ? null : v.toString().trim();
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static Integer intOf(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v == null) return null;
        try { return (int) Math.round(Double.parseDouble(v.toString())); }
        catch (NumberFormatException e) { return null; }
    }

    @SuppressWarnings("unchecked")
    static String firstMemberPath(Map<String, Object> collection) {
        if (collection == null) return null;
        Object members = collection.get("Members");
        if (members instanceof java.util.List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> first) {
            Object id = ((Map<String, Object>) first).get("@odata.id");
            return id == null ? null : id.toString();
        }
        return null;
    }

    private RestClient client(Endpoint ep) {
        return RestClient.builder()
                .baseUrl(ep.getUrl())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient()))
                .defaultHeaders(h -> {
                    h.setBasicAuth(ep.getUsername(), ep.getPassword());
                    h.set("Accept", "application/json");
                })
                .build();
    }

    private HttpClient httpClient() {
        if (verifyTls) {
            return HttpClients.createDefault();
        }
        try {
            SSLContext sslContext = SSLContexts.custom()
                    .loadTrustMaterial(null, (chain, authType) -> true)
                    .build();
            var cm = PoolingHttpClientConnectionManagerBuilder.create()
                    .setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                            .setSslContext(sslContext)
                            .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                            .build())
                    .build();
            return HttpClients.custom().setConnectionManager(cm).build();
        } catch (Exception e) {
            throw new IllegalStateException("Redfish TLS 우회 클라이언트 생성 실패", e);
        }
    }
}
