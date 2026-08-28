package com.innogrid.aims.dashboard;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// TODO:: 실제 Thanos 연동이 완료되어 Mock 전환이 불필요해지면 이 설정 클래스 제거 (GpuDashboardProperties.java 파일 제거)
@Component
@ConfigurationProperties(prefix = "aims.gpu-dashboard")
public class GpuDashboardProperties {

    // TODO:: 실제 Thanos 연동이 완료되어 Mock 전환이 불필요해지면 이 설정 제거
    private boolean mockEnabled;

    public boolean isMockEnabled() { return mockEnabled; }
    public void setMockEnabled(boolean mockEnabled) { this.mockEnabled = mockEnabled; }
}
