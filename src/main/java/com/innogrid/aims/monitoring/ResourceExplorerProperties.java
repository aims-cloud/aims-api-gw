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

    /**
     * 수집 서비스 후보 탐지에서 제외할 systemd 유닛 이름 정규식(docs/11 §5.2). node_exporter 쪽 {@code --collector.systemd.unit-exclude}
     * (docs/03 §2.1)와 같은 취지이며, exporter 에 제외 패턴이 없는 노드를 위해 백엔드에서 한 번 더 거른다. 유닛 이름(".service" 포함)에 find 로 적용.
     */
    private String discoverySystemdExclude =
            "^(systemd-|getty@|serial-getty@|snap[.-]|snapd|dbus|polkit|rsyslog|cron|user@|apt-|unattended-upgrades|"
            + "networkd-dispatcher|ModemManager|udisks2|upower|packagekit|fwupd|plymouth|console-setup|keyboard-setup|"
            + "setvtrgb|e2scrub|ua-|ubuntu-advantage|multipathd|lvm2-|blk-availability|finalrd|irqbalance|thermald|"
            + "kmod-static-nodes|sys-|dev-|proc-|run-|boot-)";

    public Duration getMetricsCacheTtl() { return metricsCacheTtl; }
    public void setMetricsCacheTtl(Duration metricsCacheTtl) { this.metricsCacheTtl = metricsCacheTtl; }

    public Duration getTumblebugCacheTtl() { return tumblebugCacheTtl; }
    public void setTumblebugCacheTtl(Duration tumblebugCacheTtl) { this.tumblebugCacheTtl = tumblebugCacheTtl; }

    public Duration getMetricsPresenceWindow() { return metricsPresenceWindow; }
    public void setMetricsPresenceWindow(Duration metricsPresenceWindow) { this.metricsPresenceWindow = metricsPresenceWindow; }

    public String getDiscoverySystemdExclude() { return discoverySystemdExclude; }
    public void setDiscoverySystemdExclude(String discoverySystemdExclude) { this.discoverySystemdExclude = discoverySystemdExclude; }
}
