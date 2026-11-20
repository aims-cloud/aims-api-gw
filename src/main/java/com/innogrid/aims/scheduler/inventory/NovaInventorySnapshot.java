package com.innogrid.aims.scheduler.inventory;

import java.time.OffsetDateTime;
import java.util.List;

import com.innogrid.aims.scheduler.model.InventoryStatus;

/**
 * Nova 인벤토리를 한 번 읽은 결과 — 하이퍼바이저를 스케줄러 노드 입력 모양으로, VM을 워크로드 입력 모양으로 바꾼 것.
 *
 * <p>읽기만 하고 저장하지 않는다. {@code status}가 실패 값이면 {@code nodes} · {@code servers}는 비고
 * {@code serversRead}는 false다. 일부 질의만 실패하면 {@code status}는 그대로 두고 {@code unavailable}에 질의 이름을 적는다.</p>
 *
 * @param status      하이퍼바이저 목록을 읽은 결과
 * @param checkedAt   조회를 시작한 시각(UTC)
 * @param elapsedMs   조회에 걸린 시간
 * @param unavailable 일부만 실패한 질의 이름 — {@code availabilityZones} · {@code servers} · {@code flavors}
 * @param nodes       하이퍼바이저별 노드 입력(호스트 이름순)
 * @param serversRead VM 목록을 읽었는지 — false면 VM 건수를 모른다
 * @param servers     VM별 워크로드 입력(호스트 · 이름순)
 */
public record NovaInventorySnapshot(
        InventoryStatus status,
        OffsetDateTime checkedAt,
        long elapsedMs,
        List<String> unavailable,
        List<Node> nodes,
        boolean serversRead,
        List<Server> servers) {

    /**
     * 하이퍼바이저 한 대. 총량 필드({@code totalCpu} · {@code totalMemoryMb} · {@code diskCapacityGb}) 이름은 스케줄러 노드 입력({@code NodeResource})과 맞춘다.
     *
     * <p>{@code allocatedCpu}는 VM에 할당한 vCPU 합(Nova {@code vcpus_used})이라 사용률이 아니고,
     * 오버커밋이면 {@code totalCpu}를 넘을 수 있다 — 값은 그대로 둔다.</p>
     */
    public record Node(
            String hypervisorHostname,
            String availabilityZone,
            String state,
            String status,
            Integer totalCpu,
            Integer allocatedCpu,
            Long totalMemoryMb,
            Long allocatedMemoryMb,
            Long diskCapacityGb,
            Long allocatedDiskGb,
            Integer runningVms,
            boolean usable,
            String skipReason) {
    }

    /** VM 한 대. 요구 자원 필드 이름은 워크로드({@code Workload})와 맞춘다. */
    public record Server(
            String serverId,
            String name,
            String status,
            String hypervisorHostname,
            String flavorId,
            Integer requiredCpu,
            Integer requiredMemoryMb,
            Integer requiredDiskGb,
            boolean convertible,
            String skipReason) {
    }

    static NovaInventorySnapshot failed(InventoryStatus status, OffsetDateTime checkedAt, long elapsedMs) {
        return new NovaInventorySnapshot(status, checkedAt, elapsedMs, List.of(), List.of(), false, List.of());
    }
}
