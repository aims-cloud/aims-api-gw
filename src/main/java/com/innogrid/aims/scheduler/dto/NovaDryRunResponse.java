package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.scheduler.model.InventoryStatus;
import com.innogrid.aims.scheduler.model.NovaDryRunStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Nova 인벤토리 모의 배치 응답 — 쓸 수 있는 하이퍼바이저를 빈 노드로 두고, 그 위의 VM 을 스케줄러가 다시 놓아 본 결과.
 *
 * <p>저장하지 않는다. 노드에 사용률 · 전력 · 온도가 없어 정책의 사용률 상한과 물리 vCPU · 메모리만 결과를 가른다 —
 * 엔진 비교나 통합 권고의 근거로 쓰지 않는다. 건수는 그 값을 알 때만 채우고, 모르면 null 이다(0 과 다름).</p>
 */
@Schema(description = "Nova 인벤토리 모의 배치 — 쓸 수 있는 하이퍼바이저를 빈 노드로 두고 그 위 VM 을 정책 상한 아래 다시 놓아 본 결과. 저장 없음")
public record NovaDryRunResponse(
        @Schema(description = "결과 출처. 실 인벤토리 결과는 NOVA(합성 모드는 GET /metrics-source 의 synthetic)", example = "NOVA")
        String source,
        @Schema(description = "OK(모의 배치함) · DISABLED(플래그 꺼짐 · Nova 를 부르지 않음) · INVENTORY_FAILED(하이퍼바이저나 VM 목록을 못 읽음) · "
                + "NO_NODES(쓸 수 있는 하이퍼바이저 0) · NO_VMS(다시 놓을 VM 0)")
        NovaDryRunStatus status,
        @Schema(description = "상태별 고정 안내 문구", example = "Nova 인벤토리로 모의 배치를 했습니다(자원 가용성만 반영 · 전력 · 온도는 계측 없음)")
        String message,
        @Schema(description = "인벤토리 조회 결과(GET /inventory/nova 의 status 와 같은 값). DISABLED 면 null", example = "OK")
        InventoryStatus inventoryStatus,
        @Schema(description = "인벤토리 조회에서 일부만 실패한 질의 이름(availabilityZones · servers · flavors). DISABLED 면 빈 배열")
        List<String> unavailable,
        @Schema(description = "적용한 배치 정책 이름 — VM 배치와 같은 규칙(VM 종류 활성 → 공통 활성 → default). 모의 배치를 하지 않았으면 null", example = "default")
        String policyName,
        @Schema(description = "놓을 때 쓴 엔진. 계측이 없는 노드라 엔진에 따른 차이는 없다. 모의 배치를 하지 않았으면 null", example = "weighted")
        String engine,
        @Schema(description = "정책의 CPU 사용률 상한(%) — 놓기 전 노드의 실효 사용률이 이 값을 넘으면 그 노드에 놓지 않는다. 모의 배치를 하지 않았으면 null", example = "80.0")
        Double cpuLimitPercent,
        @Schema(description = "정책의 메모리 사용률 상한(%). 뜻은 cpuLimitPercent 와 같다. 모의 배치를 하지 않았으면 null", example = "85.0")
        Double memoryLimitPercent,
        @Schema(description = "놓는 순서 — LARGEST_FIRST(요구 vCPU 큰 순 → 메모리 큰 순 → VM ID 순)", example = "LARGEST_FIRST")
        String order,
        @Schema(description = "요청을 받은 시각(UTC)", example = "2026-11-18T01:00:00Z")
        OffsetDateTime checkedAt,
        @Schema(description = "인벤토리 조회와 모의 배치에 걸린 시간(ms)", example = "1600")
        long elapsedMs,
        @Schema(description = "쓸 수 있는 하이퍼바이저 수(usable=true). 하이퍼바이저 목록을 못 읽었으면 null", example = "2")
        Integer nodeCount,
        @Schema(description = "다시 놓을 VM 수 — 변환 가능하고 지금 호스트가 쓸 수 있는 노드인 VM. VM 목록을 못 읽었으면 null", example = "3")
        Integer vmCount,
        @Schema(description = "대상 밖 VM 수(excluded 의 건수). VM 목록을 못 읽었으면 null", example = "1")
        Integer excludedCount,
        @Schema(description = "놓인 VM 수. 모의 배치를 하지 않았으면 null", example = "3")
        Integer placedCount,
        @Schema(description = "못 놓인 VM 수. 모의 배치를 하지 않았으면 null", example = "0")
        Integer failedCount,
        @Schema(description = "못 놓인 VM 을 제외 사유 코드별로 센 수 — 한 VM 의 후보 노드들이 서로 다른 코드로 빠졌으면 코드마다 한 번씩 센다. "
                + "모의 배치를 하지 않았으면 null")
        Map<String, Integer> failedByReason,
        @Schema(description = "지금 대상 VM 이 하나 이상 있는 쓸 수 있는 하이퍼바이저 수. 모의 배치를 하지 않았으면 null", example = "2")
        Integer hostsInUseBefore,
        @Schema(description = "모의 배치 뒤 VM 이 하나 이상 놓인 하이퍼바이저 수. 모의 배치를 하지 않았으면 null", example = "2")
        Integer hostsInUseAfter,
        @Schema(description = "놓인 VM 가운데 지금 호스트와 다른 호스트에 놓인 수 — 이동 계획이 아니라 지금 Nova 배치와 다른 정도. 모의 배치를 하지 않았으면 null", example = "1")
        Integer hostChangedCount,
        @Schema(description = "쓸 수 있는 하이퍼바이저별 지금 할당과 모의 배치 뒤 값(호스트 이름순)")
        List<Node> nodes,
        @Schema(description = "다시 놓은 VM 별 결정(놓은 순서)")
        List<Decision> decisions,
        @Schema(description = "대상 밖 VM 과 사유(호스트 · 이름순)")
        List<Excluded> excluded
) {

    /** 출처 표시 — 조회 응답과 같은 값. */
    public static final String SOURCE = "NOVA";

    /** 놓는 순서 이름. */
    public static final String ORDER = "LARGEST_FIRST";

    @Schema(name = "NovaDryRunNode", description = "쓸 수 있는 하이퍼바이저 한 대의 지금 할당(Nova 값)과 모의 배치 뒤 값")
    public record Node(
            @Schema(description = "하이퍼바이저 호스트 이름", example = "compute-a.local")
            String hypervisorHostname,
            @Schema(description = "물리 vCPU 수(Nova vcpus)", example = "16")
            int totalCpu,
            @Schema(description = "메모리(MB · Nova memory_mb)", example = "65536")
            long totalMemoryMb,
            @Schema(description = "지금 Nova 가 할당한 vCPU 합(vcpus_used · 오버커밋이면 totalCpu 를 넘음)", example = "20")
            Integer currentAllocatedCpu,
            @Schema(description = "지금 Nova 가 할당한 메모리(MB · memory_mb_used · 호스트 예약분 포함)", example = "8704")
            Long currentAllocatedMemoryMb,
            @Schema(description = "지금 이 호스트에 있는 대상 VM 수", example = "2")
            int currentVmCount,
            @Schema(description = "모의 배치 뒤 이 노드에 놓인 VM 의 요구 vCPU 합", example = "6")
            int simulatedCpu,
            @Schema(description = "모의 배치 뒤 이 노드에 놓인 VM 의 요구 메모리 합(MB)", example = "12288")
            long simulatedMemoryMb,
            @Schema(description = "모의 배치 뒤 이 노드에 놓인 VM 수", example = "2")
            int simulatedVmCount
    ) {
    }

    @Schema(name = "NovaDryRunDecision", description = "VM 한 대를 다시 놓아 본 결과")
    public record Decision(
            @Schema(description = "VM ID", example = "a1a1a1a1-0000-0000-0000-000000000001")
            String serverId,
            @Schema(description = "VM 이름", example = "vm-a")
            String name,
            @Schema(description = "Nova status(ACTIVE · SHUTOFF · PAUSED 등)", example = "ACTIVE")
            String vmStatus,
            @Schema(description = "요구 vCPU(flavor vcpus)", example = "2")
            int requiredCpu,
            @Schema(description = "요구 메모리(MB · flavor ram)", example = "4096")
            int requiredMemoryMb,
            @Schema(description = "지금 올라가 있는 하이퍼바이저", example = "compute-a.local")
            String currentHost,
            @Schema(description = "모의 배치가 고른 하이퍼바이저. 못 놓였으면 null", example = "compute-b.local")
            String simulatedHost,
            @Schema(description = "놓였고 지금 호스트와 다른 호스트인지")
            boolean hostChanged,
            @Schema(description = "놓였는지")
            boolean success,
            @Schema(description = "엔진의 결정 사유(못 놓였으면 걸린 제약)", example = "자원 여유가 가장 큼")
            String reason,
            @Schema(description = "후보 노드의 제외 사유 코드를 센 것(배치 이력 exclusionCodes 와 같은 꼴). 제외가 없으면 null", example = "{\"CPU_USAGE_EXCEEDED\":1}")
            String exclusionCodes
    ) {
    }

    @Schema(name = "NovaDryRunExcluded", description = "다시 놓지 않은 VM 과 사유")
    public record Excluded(
            @Schema(description = "VM ID", example = "a1a1a1a1-0000-0000-0000-000000000009")
            String serverId,
            @Schema(description = "VM 이름", example = "vm-z")
            String name,
            @Schema(description = "지금 올라가 있는 하이퍼바이저. 없으면 null", example = "compute-c.local")
            String currentHost,
            @Schema(description = "대상 밖인 이유 — 변환 불가 · 지금 호스트 없음 · 지금 호스트를 쓸 수 없음", example = "지금 호스트를 쓸 수 없음(status disabled)")
            String reason
    ) {
    }

    /** 모의 배치를 하지 않은 응답 — 건수는 아는 것만 채운다. */
    public static NovaDryRunResponse notRun(NovaDryRunStatus status, InventoryStatus inventoryStatus, List<String> unavailable,
                                            OffsetDateTime checkedAt, long elapsedMs, Integer nodeCount, Integer vmCount,
                                            List<Excluded> excluded) {
        return new NovaDryRunResponse(SOURCE, status, status.message(), inventoryStatus, unavailable,
                null, null, null, null, ORDER, checkedAt, elapsedMs,
                nodeCount, vmCount, excluded == null ? null : excluded.size(),
                null, null, null, null, null, null,
                List.of(), List.of(), excluded == null ? List.of() : excluded);
    }
}
