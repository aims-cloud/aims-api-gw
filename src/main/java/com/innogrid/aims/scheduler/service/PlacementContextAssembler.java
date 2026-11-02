package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.LinkMatrix;
import com.innogrid.aims.scheduler.model.MdcPowerBudgets;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.repository.MdcLinkRepository;
import com.innogrid.aims.scheduler.repository.MdcPowerBudgetRepository;
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

    /** MDC 링크 원장. null 이면(원장 없이 조립한 테스트 경로) 링크 스냅샷은 항상 비어 있다. */
    private final MdcLinkRepository linkRepository;

    /** MDC 전력 예산 원장. null 이면(직접 조립한 테스트 경로) MDC 총량 제약이 걸리지 않는다. */
    private final MdcPowerBudgetRepository budgetRepository;

    /** 실행 계층 연동. null 이면(직접 조립한 테스트 경로) 실행 중 워크로드의 시작 시각을 조회하지 않는다. */
    private final PlacementDispatchService dispatchService;

    PlacementContextAssembler(NodeResourceCollector nodeCollector,
                              ConstraintEvaluator constraintEvaluator,
                              WorkloadRepository workloadRepository,
                              SchedulerProperties properties,
                              MdcLinkRepository linkRepository,
                              MdcPowerBudgetRepository budgetRepository,
                              PlacementDispatchService dispatchService) {
        this.nodeCollector = nodeCollector;
        this.constraintEvaluator = constraintEvaluator;
        this.workloadRepository = workloadRepository;
        this.properties = properties;
        this.linkRepository = linkRepository;
        this.budgetRepository = budgetRepository;
        this.dispatchService = dispatchService;
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
                        .withMdcBudgets(loadMdcBudgets());
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
     * 스냅샷도 비고 C6 은 아무 노드도 막지 않으므로, 배포 직후 배치 결과는 그대로다.
     */
    private MdcPowerBudgets loadMdcBudgets() {
        return budgetRepository == null ? MdcPowerBudgets.EMPTY : MdcPowerBudgets.of(budgetRepository.findAll());
    }

    /**
     * 이미 배치됐지만 아직 메트릭에 드러나지 않은 워크로드를 노드 예약으로 반영한다.
     * 오래 남아 있는 배치 대기(SCHEDULED)가 자원을 계속 묶지 않도록 TTL 을 넘긴 것은 제외한다.
     *
     * <p><b>실행 중인 워크로드는 시작한 지 메트릭 지연 시간이 지나면 예약에서 뺀다.</b> 그러지 않으면 같은
     * 워크로드가 노드 사용률 메트릭에 한 번, 예약으로 또 한 번 계산된다. 이 클래스의 예약은
     * "배치됐지만 아직 메트릭에 없는 것"을 뜻하므로, 시작한 지 메트릭 지연 시간이 지난 RUNNING 은
     * 정의상 예약이 아니다. 시작 시각은 현재 결정의 전달 기록이 RUNNING 이 된 시각이고, 그런 기록이 없으면(운영자 상태
     * 변경으로 시작한 행·전달 기능 이전 행) 배치 시각으로 대신한다. 배치 시각도 모르는 행은 판단 근거가 없으니 예약으로
     * 남겨 보수적으로 다룬다. 배치 시각으로 재면 늦게 시작한 워크로드가 메트릭에 반영되기 전에 빠져 노드가 실제보다 비어
     * 보이고, TTL 을 실행 중에도 걸면 하루 넘게 대기하다 시작한 워크로드가 시작하자마자 빠진다.</p>
     *
     * <p><b>단, GPU 개수는 끝 상태가 될 때까지 예약으로 남긴다.</b> 가용 GPU 는 총 개수에서 예약만 뺀 값이고 카드 점유는
     * 사용률 메트릭에 드러나지 않는다. 실행 중인 워크로드의 GPU 까지 빼면 쓰고 있는 카드가 비어 보여 다음 GPU 워크로드가
     * 같은 카드에 배정된다. 그래서 시작한 지 메트릭 지연이 지난 RUNNING 은 CPU·메모리·디스크만 빼고 GPU 는 남긴다.</p>
     */
    private void applyReservations(PlacementContext context) {
        if (context.isEmpty()) return;

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime cutoff = now.minusHours(properties.getReservationTtlHours());
        OffsetDateTime metricCutoff = now.minusMinutes(properties.getMetricLagMinutes());

        List<Workload> held = workloadRepository.findByStatusIn(RESERVED_STATUSES);
        Map<Long, OffsetDateTime> started = runningSince(held);
        for (Workload workload : held) {
            String nodeId = workload.getAssignedNodeId();
            if (nodeId == null) continue;
            if (workload.getStatus() != Workload.WorkloadStatus.RUNNING) {
                boolean expired = workload.getScheduledAt() != null && workload.getScheduledAt().isBefore(cutoff);
                if (!expired) context.reserve(nodeId, workload);
                continue;
            }
            OffsetDateTime since = workload.getId() == null ? null : started.get(workload.getId());
            if (since == null) since = workload.getScheduledAt();
            if (since == null || !since.isBefore(metricCutoff)) {
                context.reserve(nodeId, workload);
            } else {
                reserveGpuOnly(context, nodeId, workload);
            }
        }
    }

    /** 예약 대상 가운데 실행 중인 워크로드의 시작 시각. 전달 서비스가 없거나(조립한 테스트) 실행 중이 없으면 조회하지 않는다. */
    private Map<Long, OffsetDateTime> runningSince(List<Workload> held) {
        if (dispatchService == null) return Map.of();
        List<Workload> running = held.stream()
                .filter(w -> w.getStatus() == Workload.WorkloadStatus.RUNNING && w.getAssignedNodeId() != null && w.getId() != null)
                .toList();
        return running.isEmpty() ? Map.of() : dispatchService.runningSince(running);
    }

    /** 실행 중인 워크로드의 GPU 개수만 예약으로 잡는다 — CPU·메모리·디스크는 이미 사용률 메트릭에 있다. 없는 노드는 무시한다. */
    private static void reserveGpuOnly(PlacementContext context, String nodeId, Workload workload) {
        context.node(nodeId).ifPresent(n -> n.reserve(0, 0, workload.getRequiredGpu(), 0));
    }
}
