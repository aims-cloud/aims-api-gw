package com.innogrid.aims.monitoring.dto.resourceexplorer.node.service;

import java.util.List;

/**
 * 수집 서비스 추가/편집 요청 — 서비스 추가 위저드(서비스 선택 → 수집 설정 → 요약) 기준.
 * 로그·트레이스 수집 중 하나 이상 enabled 여야 하며, 생성 전 유효성 검증은 validate API 로 수행한다.
 */
public record CollectServiceRequest(
        String serviceName,             // 위저드 1단계에서 선택한 탐지 서비스 이름
        String description,             // 탐지 기본값을 사용자가 수정 가능
        LogCollection logCollection,
        TraceCollection traceCollection
) {
    /** 로그 수집 설정 — 위저드 2단계 '로그 수집' 토글 하위 */
    public record LogCollection(
            boolean enabled,
            boolean syslogEnabled,      // Syslog 수집 활성화 토글
            List<String> logPaths       // 로그 경로 다중 입력 (예: /var/log/nginx/access.log)
    ) {
        public LogCollection {
            logPaths = logPaths == null ? List.of() : List.copyOf(logPaths);
        }
    }

    /** 트레이스 수집 설정 — 위저드 2단계 '트레이스 수집' 토글 하위 */
    public record TraceCollection(
            boolean enabled,
            List<Integer> ports         // 트레이스 포트 다중 선택 (예: 8080, 9000)
    ) {
        public TraceCollection {
            ports = ports == null ? List.of() : List.copyOf(ports);
        }
    }
}
