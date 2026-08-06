package com.innogrid.aims.cloudbarista;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cloud-Barista 컴포넌트 연결 설정.
 * 모든 값은 application.yml / 환경변수에서 주입받으며 Java 코드에 기본값을 두지 않는다.
 *
 * <pre>
 * cloud-barista:
 *   spider:
 *     base-url: ${CB_SPIDER_URL}
 *   tumblebug:
 *     base-url: ${CB_TUMBLEBUG_URL}
 *     username: ${CB_TUMBLEBUG_USER}
 *     password: ${CB_TUMBLEBUG_PASS}
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "cloud-barista")
public class CloudBaristaProperties {

    private final Spider spider = new Spider();
    private final Tumblebug tumblebug = new Tumblebug();

    public Spider getSpider() { return spider; }
    public Tumblebug getTumblebug() { return tumblebug; }

    public static class Spider {
        private String baseUrl;
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }

    public static class Tumblebug {
        private String baseUrl;
        private String username;
        private String password;
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}
