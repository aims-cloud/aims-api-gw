package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.innogrid.aims.scheduler.inventory.NovaInventorySnapshot;
import com.innogrid.aims.scheduler.model.InventoryStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Nova 인벤토리 조회 응답 — 하이퍼바이저를 스케줄러 노드 입력 모양으로, VM을 워크로드 입력 모양으로 바꾼 것.
 *
 * <p>읽기 전용이고 저장하지 않는다. 실행 경로의 배치 입력에는 쓰지 않고, 저장 없는 모의 배치는 {@code POST /inventory/nova/dry-run}이 한다.</p>
 */
@Schema(description = "Nova 인벤토리 조회 — 하이퍼바이저 · VM 을 스케줄러 입력 모양으로. 실제로 빈 인벤토리(EMPTY)와 연동 실패를 나눈다. 저장 없음")
public record NovaInventoryResponse(
        @Schema(description = "데이터 출처. 지금은 NOVA 하나", example = "NOVA")
        String source,
        @Schema(description = "조회 결과 — OK(1대 이상) · EMPTY(연동 정상 · 0대) · NOT_CONFIGURED(설정 없음) · AUTH_FAILED(401) · FORBIDDEN(403 · 관리자 권한 필요) · "
                + "CONNECTION_FAILED(연결 안 됨) · TIMEOUT(상한 초과 또는 앞선 조회가 아직 진행 중) · UPSTREAM_ERROR(5xx 등) · MALFORMED(응답 형식 다름). "
                + "실패 값이면 nodes · servers 는 비고 건수는 null")
        InventoryStatus status,
        @Schema(description = "상태별 고정 안내 문구. 예외 원문과 주소는 싣지 않는다(서버 로그에만 남김)", example = "Nova 인벤토리를 읽었습니다")
        String message,
        @Schema(description = "조회를 시작한 시각(UTC)", example = "2026-11-17T01:00:00Z")
        OffsetDateTime checkedAt,
        @Schema(description = "조회에 걸린 시간(ms)", example = "420")
        long elapsedMs,
        @Schema(description = "일부만 실패한 질의 이름 — availabilityZones(노드 가용영역이 null) · servers(VM 목록을 못 읽음) · flavors(일부 VM 의 요구 자원을 모름). 없으면 빈 배열")
        List<String> unavailable,
        @Schema(description = "하이퍼바이저 수. 하이퍼바이저 목록을 못 읽었으면 null", example = "2")
        Integer hypervisorCount,
        @Schema(description = "배치 입력으로 쓸 수 있는 노드 수(usable=true). 하이퍼바이저 목록을 못 읽었으면 null", example = "2")
        Integer usableNodeCount,
        @Schema(description = "VM 수. VM 목록을 못 읽었으면 null — 0 과 다르다", example = "3")
        Integer serverCount,
        @Schema(description = "워크로드 입력으로 바꿀 수 있는 VM 수(convertible=true). VM 목록을 못 읽었으면 null", example = "3")
        Integer convertibleServerCount,
        @Schema(description = "하이퍼바이저별 노드 입력(호스트 이름순)")
        List<Node> nodes,
        @Schema(description = "VM 별 워크로드 입력(호스트 · 이름순 · 호스트 없는 VM 은 뒤)")
        List<Server> servers
) {

    /** 출처 표시 — 합성 모드(메트릭 소스 synthetic)와 섞이지 않게 응답마다 싣는다. */
    public static final String SOURCE = "NOVA";

    @Schema(name = "NovaInventoryNode", description = "하이퍼바이저 한 대 — 총량 필드(totalCpu · totalMemoryMb · diskCapacityGb) 이름은 스케줄러 노드 입력과 같고, Nova 가 이미 할당한 양은 allocated 로 시작하는 필드에 따로 둠")
    public record Node(
            @Schema(description = "하이퍼바이저 호스트 이름", example = "compute-a.local")
            String hypervisorHostname,
            @Schema(description = "가용영역. 가용영역 질의가 실패했거나 매핑이 없으면 null", example = "az-a")
            String availabilityZone,
            @Schema(description = "Nova state(up · down)", example = "up")
            String state,
            @Schema(description = "Nova status(enabled · disabled)", example = "enabled")
            String status,
            @Schema(description = "물리 vCPU 수(Nova vcpus)", example = "16")
            Integer totalCpu,
            @Schema(description = "VM 에 할당한 vCPU 합(Nova vcpus_used). 사용률이 아니며 오버커밋이면 totalCpu 를 넘을 수 있다", example = "4")
            Integer allocatedCpu,
            @Schema(description = "메모리(MB · Nova memory_mb)", example = "65536")
            Long totalMemoryMb,
            @Schema(description = "할당한 메모리(MB · Nova memory_mb_used). 호스트 예약분이 들어 있어 VM 합보다 클 수 있다", example = "8704")
            Long allocatedMemoryMb,
            @Schema(description = "디스크 용량(GB · Nova local_gb). 공유 스토리지면 노드마다 같은 값이 나온다", example = "450")
            Long diskCapacityGb,
            @Schema(description = "할당한 디스크(GB · Nova local_gb_used). 볼륨으로 부팅한 VM 은 들어가지 않는다", example = "40")
            Long allocatedDiskGb,
            @Schema(description = "이 하이퍼바이저에 있는 VM 수(Nova running_vms). 이름과 달리 꺼진 · 일시 정지 VM 도 센다", example = "2")
            Integer runningVms,
            @Schema(description = "배치 입력으로 쓸 수 있는지 — state up · status enabled 이고 필수 필드(호스트 이름 · vcpus · memory_mb)가 숫자로 있을 때 true")
            boolean usable,
            @Schema(description = "usable=false 인 사유. true 면 null", example = "state down")
            String skipReason
    ) {
    }

    @Schema(name = "NovaInventoryServer", description = "VM 한 대 — 요구 자원 필드 이름은 워크로드와 같음")
    public record Server(
            @Schema(description = "VM ID", example = "a1a1a1a1-0000-0000-0000-000000000001")
            String serverId,
            @Schema(description = "VM 이름", example = "vm-a")
            String name,
            @Schema(description = "Nova status(ACTIVE · SHUTOFF · SHELVED_OFFLOADED 등)", example = "ACTIVE")
            String status,
            @Schema(description = "지금 올라가 있는 하이퍼바이저. 보관(shelved) 등으로 호스트가 없으면 null", example = "compute-a.local")
            String hypervisorHostname,
            @Schema(description = "flavor ID(마이크로버전 2.47 이상이면 original_name)", example = "f-small")
            String flavorId,
            @Schema(description = "요구 vCPU(flavor vcpus). flavor 를 못 읽으면 null", example = "2")
            Integer requiredCpu,
            @Schema(description = "요구 메모리(MB · flavor ram). flavor 를 못 읽으면 null", example = "4096")
            Integer requiredMemoryMb,
            @Schema(description = "요구 루트 디스크(GB · flavor disk). flavor 를 못 읽으면 null", example = "40")
            Integer requiredDiskGb,
            @Schema(description = "워크로드 입력으로 바꿀 수 있는지 — flavor 자원을 알고 상태가 ERROR · DELETED · SOFT_DELETED 가 아닐 때 true")
            boolean convertible,
            @Schema(description = "convertible=false 인 사유. true 면 null", example = "flavor 조회 실패: f-missing")
            String skipReason
    ) {
    }

    public static NovaInventoryResponse from(NovaInventorySnapshot snapshot) {
        boolean hypervisorsRead = snapshot.status() == InventoryStatus.OK || snapshot.status() == InventoryStatus.EMPTY;
        List<Node> nodes = snapshot.nodes().stream().map(n -> new Node(n.hypervisorHostname(), n.availabilityZone(), n.state(), n.status(),
                n.totalCpu(), n.allocatedCpu(), n.totalMemoryMb(), n.allocatedMemoryMb(), n.diskCapacityGb(), n.allocatedDiskGb(),
                n.runningVms(), n.usable(), n.skipReason())).toList();
        List<Server> servers = snapshot.servers().stream().map(s -> new Server(s.serverId(), s.name(), s.status(), s.hypervisorHostname(),
                s.flavorId(), s.requiredCpu(), s.requiredMemoryMb(), s.requiredDiskGb(), s.convertible(), s.skipReason())).toList();
        return new NovaInventoryResponse(SOURCE, snapshot.status(), snapshot.status().message(), snapshot.checkedAt(), snapshot.elapsedMs(),
                snapshot.unavailable(),
                hypervisorsRead ? nodes.size() : null,
                hypervisorsRead ? (int) nodes.stream().filter(Node::usable).count() : null,
                snapshot.serversRead() ? servers.size() : null,
                snapshot.serversRead() ? (int) servers.stream().filter(Server::convertible).count() : null,
                nodes, servers);
    }
}
