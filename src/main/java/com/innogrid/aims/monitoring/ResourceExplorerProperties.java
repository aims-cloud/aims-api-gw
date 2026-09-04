package com.innogrid.aims.monitoring;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 자원 탐색기 설정 (prefix: aims.resource-explorer). */
@Component
@ConfigurationProperties(prefix = "aims.resource-explorer")
public class ResourceExplorerProperties {

    // TODO:: 노드 패널이 자원 관리 데이터 연동으로 전환되면 이 설정 제거 (트리·전체 자원·역할·MDC 패널은 더 이상 사용하지 않음)
    private boolean mockEnabled;

    /**
     * 트리 구성용 Thanos 수집 상태 스냅샷(up·GPU 장치·하드웨어 센서 존재 여부) 캐시 유효 시간.
     * docs/06 §7 — 초기 조회 성능을 위해 지원 모니터링 항목을 캐시하고 정해진 주기로 갱신한다.
     */
    private Duration metricsCacheTtl = Duration.ofMinutes(1);

    /**
     * CB-Tumblebug Namespace·Infra·Node 조회 결과 캐시 유효 시간 (docs/06 §7.1 — 기본 1분).
     * 자원 변경은 최대 이 시간만큼 지연되어 트리에 반영된다.
     */
    private Duration tumblebugCacheTtl = Duration.ofMinutes(1);

    public boolean isMockEnabled() { return mockEnabled; }
    public void setMockEnabled(boolean mockEnabled) { this.mockEnabled = mockEnabled; }

    public Duration getMetricsCacheTtl() { return metricsCacheTtl; }
    public void setMetricsCacheTtl(Duration metricsCacheTtl) { this.metricsCacheTtl = metricsCacheTtl; }

    public Duration getTumblebugCacheTtl() { return tumblebugCacheTtl; }
    public void setTumblebugCacheTtl(Duration tumblebugCacheTtl) { this.tumblebugCacheTtl = tumblebugCacheTtl; }
}
