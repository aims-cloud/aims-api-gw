package com.innogrid.aims.bmc;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * BMC(하드웨어 관리 컨트롤러) 접속 설정.
 * XCC(Lenovo XClarity Controller)·IDRAC(Dell) 등 Redfish 지원 BMC를 등록한다.
 *
 * 접속 정보(특히 비밀번호)는 절대 코드/설정 파일에 두지 않고 환경변수에서만 주입한다.
 * <pre>
 * bmc:
 *   verify-tls: false            # 사설 인증서 대응
 *   endpoints:
 *     - id: aio01
 *       url: ${BMC_AIO01_URL:}
 *       username: ${BMC_AIO01_USER:}
 *       password: ${BMC_AIO01_PASS:}
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "bmc")
public class BmcProperties {

    /** 사설 인증서 HTTPS 대응 (기본 false — BMC는 대부분 self-signed). */
    private boolean verifyTls = false;

    private List<Endpoint> endpoints = new ArrayList<>();

    public boolean isVerifyTls() { return verifyTls; }
    public void setVerifyTls(boolean verifyTls) { this.verifyTls = verifyTls; }
    public List<Endpoint> getEndpoints() { return endpoints; }
    public void setEndpoints(List<Endpoint> endpoints) { this.endpoints = endpoints; }

    public Endpoint find(String id) {
        return endpoints.stream().filter(e -> id != null && id.equals(e.getId())).findFirst().orElse(null);
    }

    public static class Endpoint {
        private String id;
        private String url;
        private String username;
        private String password;

        public boolean isConfigured() {
            return notBlank(url) && notBlank(username) && notBlank(password);
        }

        private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}
