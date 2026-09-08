package com.innogrid.aims.openstack;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OpenStack 연동 설정.
 * 모든 값은 application.yml / 환경변수에서 주입받으며 Java 코드에 기본값을 두지 않는다.
 *
 * 최종 연동 대상은 1세부 OpenStack이지만 구축 전까지 자체 구축 OpenStack을 사용하므로,
 * 접속 정보를 전부 외부화하여 환경변수 변경만으로 전환할 수 있게 한다.
 *
 * <pre>
 * openstack:
 *   zone-name: ${OPENSTACK_ZONE_NAME:openstack-default}  # 배포영역 표시명 (토폴로지 드롭다운)
 *   auth-url: ${OPENSTACK_AUTH_URL:}        # Keystone v3 엔드포인트 (예: http://host:5000/v3)
 *   network-url: ${OPENSTACK_NETWORK_URL:}  # Neutron 엔드포인트 (예: http://host:9696)
 *   compute-url: ${OPENSTACK_COMPUTE_URL:}  # Nova 엔드포인트, 버전 포함 (예: http://host:8774/v2.1)
 *   username: ${OPENSTACK_USERNAME:}
 *   password: ${OPENSTACK_PASSWORD:}
 *   project-name: ${OPENSTACK_PROJECT:}
 *   domain-name: ${OPENSTACK_DOMAIN:Default}
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "openstack")
public class OpenStackProperties {

    private String zoneName;
    private String authUrl;
    private String networkUrl;
    private String computeUrl;
    private String imageUrl;
    private String username;
    private String password;
    private String projectName;
    private String domainName;

    /** TLS 인증서 검증 여부. 사설 인증서 HTTPS 환경에서는 false로 설정 (기본 true). */
    private boolean verifyTls = true;

    /** 필수 접속 정보가 모두 설정되어 있는지 여부. 미설정 환경에서는 연동 API가 503을 반환한다. */
    public boolean isConfigured() {
        return notBlank(authUrl) && notBlank(username) && notBlank(password) && notBlank(projectName);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    public String getZoneName() { return zoneName == null || zoneName.isBlank() ? "openstack-default" : zoneName; }
    public void setZoneName(String zoneName) { this.zoneName = zoneName; }
    public String getAuthUrl() { return authUrl; }
    public void setAuthUrl(String authUrl) { this.authUrl = authUrl; }
    public String getNetworkUrl() { return networkUrl; }
    public void setNetworkUrl(String networkUrl) { this.networkUrl = networkUrl; }
    public String getComputeUrl() { return computeUrl; }
    public void setComputeUrl(String computeUrl) { this.computeUrl = computeUrl; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public String getDomainName() { return domainName; }
    public void setDomainName(String domainName) { this.domainName = domainName; }
    public boolean isVerifyTls() { return verifyTls; }
    public void setVerifyTls(boolean verifyTls) { this.verifyTls = verifyTls; }
}
