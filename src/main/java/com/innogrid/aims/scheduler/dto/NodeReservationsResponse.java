package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.ReservationJudgment;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 노드별 예약 내역 응답 — 배치가 노드 예약으로 잡는 워크로드(배치 대기 · 실행 중)를 노드마다 묶고 판정과 예약량을 싣는다.
 *
 * <p>모든 행을 {@code evaluatedAt} 하나로 판정한다. 노드의 예약 합계는 노드 목록({@code GET /nodes})의 {@code reserved*}와
 * 같은 식이지만, 노드 목록은 메트릭을 수집한 노드의 예약만 센다. 시각은 UTC다.</p>
 */
@Schema(description = "노드별 예약 내역 — 배치가 노드 예약으로 잡는 워크로드(배치 대기 · 실행 중)와 그 판정 · 예약량. "
        + "모든 행을 evaluatedAt 하나로 판정합니다")
public record NodeReservationsResponse(
        @Schema(description = "판정 시각(UTC)", example = "2026-11-18T03:00:00Z")
        OffsetDateTime evaluatedAt,
        @Schema(description = "배치 대기 예약의 보존 기한(시간). 배치 시각이 이보다 오래된 배치 대기는 EXPIRED", example = "24")
        int reservationTtlHours,
        @Schema(description = "메트릭 지연(분). 시작한 지 이 시간이 지난 실행 중은 GPU_ONLY", example = "10")
        int metricLagMinutes,
        @Schema(description = "배정 노드 없이 배치 대기 · 실행 중인 워크로드 수. 노드 필터와 무관한 전체 건수이며 어느 노드에도 예약되지 않습니다",
                example = "0")
        int unassignedCount,
        @Schema(description = "노드 id 순. 배치 대기 · 실행 중 워크로드가 배정된 노드만 나옵니다")
        List<Node> nodes
) {
    public NodeReservationsResponse {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /** 노드 하나의 예약 합계와 그 행. 합계는 행 예약량의 합이다. */
    @Schema(name = "NodeReservationsNode", description = "노드 하나의 예약 합계와 행. 합계는 행 예약량의 합이며, "
            + "그 노드가 메트릭 수집 대상이면 같은 때 노드 목록의 reserved* 와 같은 값입니다")
    public record Node(
            @Schema(description = "노드 id(/nodes 의 nodeId 와 같은 값)", example = "node-a:9100")
            String nodeId,
            @Schema(description = "예약 CPU 합", example = "2")
            int reservedCpu,
            @Schema(description = "예약 메모리 합(MB)", example = "4096")
            long reservedMemoryMb,
            @Schema(description = "예약 GPU 개수 합", example = "0")
            int reservedGpu,
            @Schema(description = "예약 디스크 합(GB)", example = "0")
            long reservedDiskGb,
            @Schema(description = "워크로드 id 순")
            List<HeldWorkload> workloads
    ) {
        public Node {
            workloads = workloads == null ? List.of() : List.copyOf(workloads);
        }
    }

    /** 배치 대기 · 실행 중 워크로드 하나와 그 판정. */
    @Schema(name = "NodeReservationsHeldWorkload", description = "배치 대기 · 실행 중 워크로드 하나와 판정 · 예약량")
    public record HeldWorkload(
            @Schema(description = "워크로드 id", example = "12")
            Long workloadId,
            @Schema(description = "워크로드 이름", example = "train-01")
            String name,
            @Schema(description = "SCHEDULED(배치 대기) · RUNNING(실행 중)", example = "SCHEDULED")
            Workload.WorkloadStatus status,
            @Schema(description = "FULL(CPU · 메모리 · GPU · 디스크 모두 예약) · GPU_ONLY(실행 중이고 시작한 지 메트릭 지연이 지나 "
                    + "CPU · 메모리 · 디스크는 사용률 메트릭에 있음 · GPU 개수만 예약 · 요구 GPU 가 0 이면 예약량 0) · "
                    + "EXPIRED(배치 시각이 보존 기한보다 오래된 배치 대기 · 예약 없음). "
                    + "UNASSIGNED(배정 노드 없음)는 행으로 나오지 않고 unassignedCount 로만 셉니다", example = "FULL")
            ReservationJudgment judgment,
            @Schema(description = "배치 시각(UTC). 모르면 null(배치 대기는 FULL, 실행 중은 시작 시각도 없으면 FULL)",
                    example = "2026-11-18T02:55:00Z")
            OffsetDateTime scheduledAt,
            @Schema(description = "실행 중일 때 현재 결정의 전달 기록이 RUNNING 이 된 시각(UTC). 없으면 null 이고 메트릭 지연은 "
                    + "배치 시각부터 잽니다. 배치 대기는 항상 null", example = "2026-11-18T02:56:00Z")
            OffsetDateTime startedAt,
            @Schema(description = "이 행이 예약하는 CPU", example = "2")
            int reservedCpu,
            @Schema(description = "이 행이 예약하는 메모리(MB)", example = "4096")
            long reservedMemoryMb,
            @Schema(description = "이 행이 예약하는 GPU 개수", example = "0")
            int reservedGpu,
            @Schema(description = "이 행이 예약하는 디스크(GB). 요구 디스크가 음수면 0", example = "0")
            long reservedDiskGb
    ) {}
}
