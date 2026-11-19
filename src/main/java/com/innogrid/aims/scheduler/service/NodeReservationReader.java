package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.innogrid.aims.scheduler.dto.NodeReservationsResponse;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.ReservationJudgment;

/**
 * 스케줄링 서비스가 맡기는 <b>노드별 예약 내역</b> — 배치 컨텍스트가 노드 예약으로 잡는 워크로드를 노드마다 묶는다.
 *
 * <p>스프링 빈이 아니다. 메트릭을 수집하지 않고 배치 대기 · 실행 중 워크로드와 전달 기록의 시작 시각만 읽는다. 판정과
 * 예약량 식은 배치 경로와 같은 {@link PlacementContextAssembler#readReservations} · {@link ReservationJudgment}를
 * 쓴다.</p>
 */
final class NodeReservationReader {

    private static final Comparator<NodeReservationsResponse.HeldWorkload> BY_WORKLOAD_ID = Comparator.comparing(
            NodeReservationsResponse.HeldWorkload::workloadId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final PlacementContextAssembler contextAssembler;

    NodeReservationReader(PlacementContextAssembler contextAssembler) {
        this.contextAssembler = contextAssembler;
    }

    /** 지금 시각으로 판정한 노드별 예약 내역. {@code nodeId}의 뜻은 {@link #toResponse}와 같다. */
    NodeReservationsResponse read(String nodeId) {
        return toResponse(contextAssembler.readReservations(OffsetDateTime.now()), nodeId);
    }

    /**
     * readReservations 결과 하나를 응답으로 바꾼다. 노드는 id 순, 노드 안의 행은 워크로드 id 순이다. 배정 노드가 없는
     * 워크로드는 행으로 싣지 않고 전체 건수만 센다.
     *
     * @param nodeId 비어 있지 않으면 앞뒤 공백을 뺀 값과 정확히 같은 노드만 싣는다. 그런 노드가 없으면 빈 목록
     */
    static NodeReservationsResponse toResponse(PlacementContextAssembler.Reservations reading, String nodeId) {
        String only = nodeId == null || nodeId.isBlank() ? null : nodeId.trim();
        int unassigned = 0;
        Map<String, List<NodeReservationsResponse.HeldWorkload>> byNode = new TreeMap<>();
        for (PlacementContextAssembler.Held held : reading.held()) {
            if (held.judgment() == ReservationJudgment.UNASSIGNED) {
                unassigned++;
                continue;
            }
            String node = held.workload().getAssignedNodeId();
            if (only != null && !only.equals(node)) continue;
            byNode.computeIfAbsent(node, k -> new ArrayList<>()).add(row(held));
        }
        List<NodeReservationsResponse.Node> nodes = new ArrayList<>(byNode.size());
        for (Map.Entry<String, List<NodeReservationsResponse.HeldWorkload>> e : byNode.entrySet()) {
            nodes.add(node(e.getKey(), e.getValue().stream().sorted(BY_WORKLOAD_ID).toList()));
        }
        return new NodeReservationsResponse(reading.now(), reading.ttlHours(), reading.lagMinutes(), unassigned, nodes);
    }

    /** 노드 하나 — 합계는 행 예약량을 네 자원마다 더한 값이다. */
    private static NodeReservationsResponse.Node node(String nodeId, List<NodeReservationsResponse.HeldWorkload> rows) {
        int cpu = 0;
        long memoryMb = 0;
        int gpu = 0;
        long diskGb = 0;
        for (NodeReservationsResponse.HeldWorkload r : rows) {
            cpu += r.reservedCpu();
            memoryMb += r.reservedMemoryMb();
            gpu += r.reservedGpu();
            diskGb += r.reservedDiskGb();
        }
        return new NodeReservationsResponse.Node(nodeId, cpu, memoryMb, gpu, diskGb, rows);
    }

    /** 판정 하나를 행으로. 예약량은 판정의 식으로 낸다. */
    private static NodeReservationsResponse.HeldWorkload row(PlacementContextAssembler.Held held) {
        Workload w = held.workload();
        ReservationJudgment j = held.judgment();
        return new NodeReservationsResponse.HeldWorkload(w.getId(), w.getName(), w.getStatus(), j,
                w.getScheduledAt(), held.startedAt(),
                j.cpu(w.getRequiredCpu()), j.memoryMb(w.getRequiredMemoryMb()),
                j.gpu(w.getRequiredGpu()), j.diskGb(w.getRequiredDiskGb()));
    }
}
