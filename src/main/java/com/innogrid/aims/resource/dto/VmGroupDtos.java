package com.innogrid.aims.resource.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 가상서버 그룹 응답/요청 DTO 모음 (Swagger 스키마 명세화).
 */
public final class VmGroupDtos {

    private VmGroupDtos() {}

    // ── 그룹 ────────────────────────────────────────────────────

    /** 리스트/상세 공통 그룹 요약. */
    public record GroupSummary(Long id, String deployZone, String name, String description,
                               boolean enabled, String createdBy,
                               OffsetDateTime createdAt, int memberCount, int connectionCount) {}

    public record GroupRequest(String deployZone, String name, String description, Boolean enabled) {}

    // ── 그룹에 담긴 가상서버 ────────────────────────────────────

    /** 가상서버 탭 행 (상태는 Nova 실시간). */
    public record GroupServer(Long memberId, String serverId, String deployZone, String vmName,
                              String host, String ip, List<String> connections, String status) {}

    /** 가상서버 추가 요청 (서버 id 목록). */
    public record AddServersRequest(List<String> serverIds) {}

    /** 가상서버 추가 모달 후보 행. */
    public record CandidateServer(String serverId, String deployZone, String vmName,
                                  String ip, String status) {}

    // ── 네트워크 맵 ─────────────────────────────────────────────

    public record NetworkMap(Long groupId, List<NetworkNode> nodes, List<NetworkEdge> edges) {}

    public record NetworkNode(String serverId, String vmName, String ip, String status) {}

    public record NetworkEdge(Long id, String sourceServerId, String sourceEndpoint,
                              String targetServerId, String targetEndpoint) {}

    /** 네트워크 맵 저장 요청 (연결 전체 교체). */
    public record NetworkMapRequest(List<EdgeRequest> edges) {}

    public record EdgeRequest(String sourceServerId, String sourceEndpoint,
                              String targetServerId, String targetEndpoint) {}

    // ── 행위 이력 ───────────────────────────────────────────────

    public record HistoryRow(Long id, String performedBy, String clientIp, String target,
                             String action, String result, String detail,
                             OffsetDateTime startedAt, OffsetDateTime completedAt) {}
}
