package com.innogrid.aims.dashboard;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "aims.gpu-dashboard")
public class GpuDashboardProperties {

    // TODO:: 실제 Thanos 연동이 완료되어 Mock 전환이 불필요해지면 이 설정 제거
    private boolean mockEnabled;

    /**
     * Thanos 즉시 조회 결과(DCGM 현재값) 캐시 수명(초). 화면이 60초마다 자동 갱신하고 카드·차트가 여러 API 를 나눠 부르므로
     * 짧게 캐시해 같은 순간의 호출이 Thanos 를 반복 조회하지 않게 한다(Confluence 2320072739 R-06). 0 이면 캐시하지 않는다.
     */
    private long cacheTtlSeconds = 20;

    public boolean isMockEnabled() { return mockEnabled; }
    public void setMockEnabled(boolean mockEnabled) { this.mockEnabled = mockEnabled; }

    public long getCacheTtlSeconds() { return cacheTtlSeconds; }
    public void setCacheTtlSeconds(long cacheTtlSeconds) { this.cacheTtlSeconds = cacheTtlSeconds; }
}
