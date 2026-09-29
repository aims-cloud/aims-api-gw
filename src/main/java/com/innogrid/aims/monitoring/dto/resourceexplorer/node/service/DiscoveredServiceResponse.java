package com.innogrid.aims.monitoring.dto.resourceexplorer.node.service;

import java.util.List;

/**
 * 수집 후보 서비스 1건 — 서비스 추가 위저드 1단계 드롭다운에 사용한다.
 * 선택 시 설명(기본값)/PID/포트/수집 가능 배지가 화면에 표시된다.
 * <ul>
 *   <li>{@code source}: 후보를 어디서 관측했는지. LOKI 는 수집기가 이미 보내고 있는 파일(job), SYSTEMD 는 노드에서 활성 상태인
 *       systemd 유닛(node_exporter {@code --collector.systemd}).</li>
 *   <li>{@code suggestedLogPaths}: 2단계 로그 경로 입력을 미리 채울 제안. LOKI 는 실제로 수집 중인 파일, SYSTEMD 는 흔한 서비스의
 *       기본 경로 프리셋이며 배포판·설정에 따라 다를 수 있어 사용자가 확정한다. 없으면 빈 목록.</li>
 * </ul>
 */
public record DiscoveredServiceResponse(
        String serviceName,
        String description,
        Integer pid,
        Integer port,
        List<CollectCapability> capabilities,
        DiscoveredSource source,
        List<String> suggestedLogPaths
) {
    public enum DiscoveredSource { LOKI, SYSTEMD }

    public DiscoveredServiceResponse {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        suggestedLogPaths = suggestedLogPaths == null ? List.of() : List.copyOf(suggestedLogPaths);
    }
}
