package com.innogrid.aims.monitoring;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 자원 탐색기 설정 (prefix: aims.resource-explorer). */
@Component
@ConfigurationProperties(prefix = "aims.resource-explorer")
public class ResourceExplorerProperties {

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

    /**
     * 모니터링 항목 "존재" 판정 범위 (docs/06 §5.1, §9.4) — GPU 장치·하드웨어 센서·Tumblebug VM exporter 등록은
     * 이 범위 안에 한 번이라도 수집 이력이 있으면 유지한다. 일시적 수집 실패만으로 기존 항목을 제거하지 않기 위한 값.
     */
    private Duration metricsPresenceWindow = Duration.ofHours(24);

    public Duration getMetricsCacheTtl() { return metricsCacheTtl; }
    public void setMetricsCacheTtl(Duration metricsCacheTtl) { this.metricsCacheTtl = metricsCacheTtl; }

    public Duration getTumblebugCacheTtl() { return tumblebugCacheTtl; }
    public void setTumblebugCacheTtl(Duration tumblebugCacheTtl) { this.tumblebugCacheTtl = tumblebugCacheTtl; }

    public Duration getMetricsPresenceWindow() { return metricsPresenceWindow; }
    public void setMetricsPresenceWindow(Duration metricsPresenceWindow) { this.metricsPresenceWindow = metricsPresenceWindow; }
}
