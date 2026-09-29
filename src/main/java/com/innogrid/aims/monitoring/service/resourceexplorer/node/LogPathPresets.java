package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 흔한 서비스의 기본 로그 파일 경로 프리셋 — 수집 서비스 후보(systemd 유닛)의 2단계 경로 입력을 미리 채우는 "제안"이다(docs/11 §5.2).
 * 배포판·설정에 따라 경로가 다를 수 있으므로 화면은 제안으로만 보여 주고 사용자가 확정한다. 원장 규칙(glob 없는 절대 파일 경로)에 맞는
 * 값만 둔다. journald 에만 쓰는 서비스(docker, containerd, kubelet, sshd 등)는 파일이 없으므로 여기 없다.
 *
 * 키는 systemd 유닛 이름에서 ".service" 와 인스턴스 접미(@…)를 뗀 소문자 이름이다. 기준 배포판은 Ubuntu 22.04/24.04 이며
 * RHEL 계열 이름(httpd, mariadb)은 별도 키로 둔다.
 */
final class LogPathPresets {

    private static final Map<String, List<String>> PRESETS = Map.ofEntries(
            Map.entry("nginx", List.of("/var/log/nginx/access.log", "/var/log/nginx/error.log")),
            Map.entry("apache2", List.of("/var/log/apache2/access.log", "/var/log/apache2/error.log")),
            Map.entry("httpd", List.of("/var/log/httpd/access_log", "/var/log/httpd/error_log")),
            Map.entry("haproxy", List.of("/var/log/haproxy.log")),
            Map.entry("postgresql", List.of("/var/log/postgresql/postgresql-16-main.log", "/var/log/postgresql/postgresql-14-main.log")),
            Map.entry("mysql", List.of("/var/log/mysql/error.log")),
            Map.entry("mariadb", List.of("/var/log/mysql/error.log")),
            Map.entry("redis-server", List.of("/var/log/redis/redis-server.log")),
            Map.entry("redis", List.of("/var/log/redis/redis-server.log")),
            Map.entry("mongod", List.of("/var/log/mongodb/mongod.log")),
            Map.entry("elasticsearch", List.of("/var/log/elasticsearch/elasticsearch.log")),
            Map.entry("grafana-server", List.of("/var/log/grafana/grafana.log")),
            Map.entry("grafana", List.of("/var/log/grafana/grafana.log")),
            Map.entry("libvirtd", List.of("/var/log/libvirt/libvirtd.log")),
            Map.entry("fail2ban", List.of("/var/log/fail2ban.log")),
            Map.entry("ufw", List.of("/var/log/ufw.log")),
            Map.entry("cron", List.of("/var/log/syslog")),
            Map.entry("rsyslog", List.of("/var/log/syslog")),
            Map.entry("ssh", List.of("/var/log/auth.log")),
            Map.entry("sshd", List.of("/var/log/auth.log"))
    );

    private LogPathPresets() {
    }

    /** 유닛 이름(예: {@code nginx.service}, {@code postgresql@16-main.service}, {@code nginx})의 기본 로그 경로. 없으면 빈 목록 */
    static List<String> forUnit(String unitName) {
        if (unitName == null) {
            return List.of();
        }
        String key = unitName.trim().toLowerCase(Locale.ROOT);
        if (key.endsWith(".service")) {
            key = key.substring(0, key.length() - ".service".length());
        }
        int at = key.indexOf('@');
        if (at > 0) {
            key = key.substring(0, at);
        }
        return PRESETS.getOrDefault(key, List.of());
    }
}
