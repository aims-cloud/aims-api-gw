package com.innogrid.aims.monitoring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// TODO:: 자원 관리 데이터(역할·노드·VM 원장) 연동이 완료되어 Mock 전환이 불필요해지면 이 설정 클래스 제거
@Component
@ConfigurationProperties(prefix = "aims.resource-explorer")
public class ResourceExplorerProperties {

    // TODO:: 자원 관리 데이터 연동이 완료되면 이 설정 제거
    private boolean mockEnabled;

    public boolean isMockEnabled() { return mockEnabled; }
    public void setMockEnabled(boolean mockEnabled) { this.mockEnabled = mockEnabled; }
}
