package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.LinkMatrix;
import com.innogrid.aims.scheduler.model.MdcPowerBudgets;
import com.innogrid.aims.scheduler.model.NodePlacementHolds;
import com.innogrid.aims.scheduler.model.ReservationJudgment;
import com.innogrid.aims.scheduler.repository.MdcLinkRepository;
import com.innogrid.aims.scheduler.repository.MdcPowerBudgetRepository;
import com.innogrid.aims.scheduler.repository.NodePlacementHoldRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

/**
 * 스케줄링 서비스에서 떼어 낸 <b>배치 컨텍스트 조립</b> — 노드를 수집하고 링크·MDC 예산 원장을 읽은 뒤, 이미 배치됐지만 아직
 * 메트릭에 드러나지 않은 워크로드를 노드 예약으로 얹는다.
 *
 * <p>스프링 빈이 아니다. 서비스가 생성자에서 한 번 만들고, 생성자는 참조만 저장한다. 설정값은 만들 때 복사하지 않고 부를 때마다
 * 읽는다.</p>
 */
final class PlacementContextAssembler {

    /** 예약으로 잡아 둘 워크로드 상태. 배치됐지만 아직 끝나지 않은 것들이다. */
    private static final List<Workload.WorkloadStatus> RESERVED_STATUSES =
            List.of(Workload.WorkloadStatus.SCHEDULED, Workload.WorkloadStatus.RUNNING);

    private final NodeResourceCollector nodeCollector;
    private final ConstraintEvaluator constraintEvaluator;
    private final WorkloadRepository workloadRepository;
    private final SchedulerProperties properties;

    /** MDC 링크 원장. null이면(원장 없이 조립한 테스트 경로) 링크 스냅샷은 항상 비어 있다. */
    private final MdcLinkRepository linkRepository;

    /** MDC 전력 예산 원장. null이면(직접 조립한 테스트 경로) MDC 총량 제약이 걸리지 않는다. */
    private final MdcPowerBudgetRepository budgetRepository;

    /** 실행 계층 연동. null이면(직접 조립한 테스트 경로) 실행 중 워크로드의 시작 시각을 조회하지 않는다. */
    private final PlacementDispatchService dispatchService;

    /** 노드 배치 중지 원장. null이면(직접 조립한 테스트 경로) 배치 중지 제약이 걸리지 않는다. */
    private final NodePlacementHoldRepository holdRepository;

    PlacementContextAssembler(NodeResourceCollector nodeCollector,
                              ConstraintEvaluator constraintEvaluator,
                              WorkloadRepository workloadRepository,
                              SchedulerProperties properties,
                              MdcLinkRepository linkRepository,
                              MdcPowerBudgetRepository budgetRepository,
                              PlacementDispatchService dispatchService,
                              NodePlacementHoldRepository holdRepository) {
        this.nodeCollector = nodeCollector;
        this.constraintEvaluator = constraintEvaluator;
        this.workloadRepository = workloadRepository;
        this.properties = properties;
        this.linkRepository = linkRepository;
        this.budgetRepository = budgetRepository;
        this.dispatchService = dispatchService;
        this.holdRepository = holdRepository;
    }

    /**
     * 노드 상태를 수집하고 예약분을 반영한 배치 컨텍스트를 만든다.
     * 실행 1회당 한 번만 호출해 모든 워크로드가 같은 스냅샷을 보게 한다.
     */
    PlacementContext createContext(SchedulingPolicy policy) {
        NodeResourceCollector.Snapshot snapshot = nodeCollector.collect();
        PlacementContext context =
                PlacementContext.of(snapshot.nodes(), policy, constraintEvaluator)
                        .withExcluded(snapshot.excluded())
                        .withLinkMatrix(loadLinkMatrix(policy))
                        .withMdcBudgets(loadMdcBudgets())
                        .withPlacementHolds(loadPlacementHolds());
        applyReservations(context);
        return context;
    }

    /**
     * 정책이 링크 반영을 켰을 때만 원장을 <b>실행당 1회</b> 읽는다. 꺼져 있으면 조회 자체를 하지 않아
     * 기존 정책(전부 false)의 배치 비용과 결과가 그대로다.
     */
    private LinkMatrix loadLinkMatrix(SchedulingPolicy policy) {
        if (linkRepository == null || policy == null || !policy.isAffinityEnabled()) {
            return LinkMatrix.EMPTY;
        }
        return LinkMatrix.of(linkRepository.findAll());
    }

    /**
     * MDC 전력 예산 원장을 <b>실행당 1회</b> 읽는다. 링크와 달리 정책 게이트가 없다 — 운영자가 원장에 직접
     * 넣은 값이라 정책 설정과 무관하게 적용한다(워크로드·스케줄 전력 예산과 같은 관례). 원장이 비어 있으면
     * 스냅샷도 비고 C6은 아무 노드도 막지 않으므로, 배포 직후 배치 결과는 그대로다.
     */
    private MdcPowerBudgets loadMdcBudgets() {
        return budgetRepository == null ? MdcPowerBudgets.EMPTY : MdcPowerBudgets.of(budgetRepository.findAll());
    }

    /**
     * 노드 배치 중지 원장을 <b>실행당 1회</b> 읽는다. MDC 전력 예산처럼 정책 게이트가 없다 — 운영자가 직접 넣은 값이다.
     * 원장이 비어 있으면 C7은 아무 노드도 막지 않으므로 배치 결과가 그대로다.
     */
    private NodePlacementHolds loadPlacementHolds() {
        return holdRepository == null ? NodePlacementHolds.EMPTY : NodePlacementHolds.of(holdRepository.findAll());
    }

    /**
     * 이미 배치됐지만 아직 메트릭에 드러나지 않은 워크로드를 노드 예약으로 반영한다. 판정은 {@link #judge}가 한다.
     * 노드가 하나도 없으면 예약 대상을 읽지 않는다.
     */
    private void applyReservations(PlacementContext context) {
        if (context.isEmpty()) return;
        apply(context, readReservations(OffsetDateTime.now()));
    }

    /** readReservations 결과 하나를 컨텍스트에 얹는다. 예약량은 판정의 식을 그대로 쓴다. 스냅샷에 없는 노드는 무시한다. */
    static void apply(PlacementContext context, Reservations reading) {
        for (Held held : reading.held()) {
            ReservationJudgment judgment = held.judgment();
            if (judgment != ReservationJudgment.FULL && judgment != ReservationJudgment.GPU_ONLY) continue;
            Workload workload = held.workload();
            context.node(workload.getAssignedNodeId()).ifPresent(n -> n.reserve(
                    judgment.cpu(workload.getRequiredCpu()),
                    judgment.memoryMb(workload.getRequiredMemoryMb()),
                    judgment.gpu(workload.getRequiredGpu()),
                    judgment.diskGb(workload.getRequiredDiskGb())));
        }
    }

    /**
     * 배치 대기 · 실행 중 워크로드를 한 번 읽어 {@code now} 하나로 모두 판정한다. 배치 경로의 예약 반영과 노드별 예약 내역
     * 조회가 함께 쓴다. 규칙 값(보존 기한 · 메트릭 지연)도 이때 한 번 읽는다.
     */
    Reservations readReservations(OffsetDateTime now) {
        int ttlHours = properties.getReservationTtlHours();
        int lagMinutes = properties.getMetricLagMinutes();
        List<Workload> held = workloadRepository.findByStatusIn(RESERVED_STATUSES);
        Map<Long, OffsetDateTime> started = runningSince(held);
        List<Held> judged = new ArrayList<>(held.size());
        for (Workload workload : held) {
            boolean running = workload.getStatus() == Workload.WorkloadStatus.RUNNING && workload.getId() != null;
            OffsetDateTime startedAt = running ? started.get(workload.getId()) : null;
            judged.add(new Held(workload, startedAt, judge(workload, startedAt, now, ttlHours, lagMinutes)));
        }
        return new Reservations(now, ttlHours, lagMinutes, List.copyOf(judged));
    }

    /**
     * 워크로드 하나의 예약 판정. 저장소를 읽지 않는다.
     *
     * <p>오래 남아 있는 배치 대기(SCHEDULED)가 자원을 계속 묶지 않도록 TTL을 넘긴 것은 예약하지 않는다.</p>
     *
     * <p><b>실행 중인 워크로드는 시작한 지 메트릭 지연 시간이 지나면 예약에서 뺀다.</b> 그러지 않으면 같은
     * 워크로드가 노드 사용률 메트릭에 한 번, 예약으로 또 한 번 계산된다. 이 클래스의 예약은
     * "배치됐지만 아직 메트릭에 없는 것"을 뜻하므로, 시작한 지 메트릭 지연 시간이 지난 RUNNING은
     * 정의상 예약이 아니다. 시작 시각은 현재 결정의 전달 기록이 RUNNING이 된 시각이고, 그런 기록이 없으면(운영자 상태
     * 변경으로 시작한 행·전달 기능 이전 행) 배치 시각으로 대신한다. 배치 시각도 모르는 행은 판단 근거가 없으니 예약으로
     * 남겨 보수적으로 다룬다. 배치 시각으로 재면 늦게 시작한 워크로드가 메트릭에 반영되기 전에 빠져 노드가 실제보다 비어
     * 보이고, TTL을 실행 중에도 걸면 하루 넘게 대기하다 시작한 워크로드가 시작하자마자 빠진다.</p>
     *
     * <p><b>단, GPU 개수는 끝 상태가 될 때까지 예약으로 남긴다.</b> 가용 GPU는 총 개수에서 예약만 뺀 값이고 카드 점유는
     * 사용률 메트릭에 드러나지 않는다. 실행 중인 워크로드의 GPU까지 빼면 쓰고 있는 카드가 비어 보여 다음 GPU 워크로드가
     * 같은 카드에 배정된다. 그래서 시작한 지 메트릭 지연이 지난 RUNNING은 CPU·메모리·디스크만 빼고 GPU는 남긴다.</p>
     *
     * @param startedAt 실행 중일 때 현재 결정의 전달 기록이 RUNNING이 된 시각. 없으면 null(배치 시각으로 대신)
     */
    static ReservationJudgment judge(Workload workload, OffsetDateTime startedAt, OffsetDateTime now,
                                     int ttlHours, int lagMinutes) {
        if (workload.getAssignedNodeId() == null) return ReservationJudgment.UNASSIGNED;
        if (workload.getStatus() != Workload.WorkloadStatus.RUNNING) {
            OffsetDateTime cutoff = now.minusHours(ttlHours);
            boolean expired = workload.getScheduledAt() != null && workload.getScheduledAt().isBefore(cutoff);
            return expired ? ReservationJudgment.EXPIRED : ReservationJudgment.FULL;
        }
        OffsetDateTime since = startedAt != null ? startedAt : workload.getScheduledAt();
        OffsetDateTime metricCutoff = now.minusMinutes(lagMinutes);
        return since == null || !since.isBefore(metricCutoff) ? ReservationJudgment.FULL : ReservationJudgment.GPU_ONLY;
    }

    /** 예약 대상 가운데 실행 중인 워크로드의 시작 시각. 전달 서비스가 없거나(조립한 테스트) 실행 중이 없으면 조회하지 않는다. */
    private Map<Long, OffsetDateTime> runningSince(List<Workload> held) {
        if (dispatchService == null) return Map.of();
        List<Workload> running = held.stream()
                .filter(w -> w.getStatus() == Workload.WorkloadStatus.RUNNING && w.getAssignedNodeId() != null && w.getId() != null)
                .toList();
        return running.isEmpty() ? Map.of() : dispatchService.runningSince(running);
    }

    /** readReservations 한 번의 결과. 판정 시각 · 규칙 값 · 워크로드마다의 판정을 담는다. */
    record Reservations(OffsetDateTime now, int ttlHours, int lagMinutes, List<Held> held) {
    }

    /** 워크로드 하나의 판정. {@code startedAt}은 실행 중일 때 전달 기록의 시작 시각이고, 그 밖에는 null이다. */
    record Held(Workload workload, OffsetDateTime startedAt, ReservationJudgment judgment) {
    }
}
