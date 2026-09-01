package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.ConsolidationResponse;
import com.innogrid.aims.scheduler.dto.ConsolidationResponse.Candidate;
import com.innogrid.aims.scheduler.dto.ConsolidationResponse.Move;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

/**
 * 워크로드 통합 가능성 진단.
 *
 * <p>"이 노드의 워크로드를 다른 노드로 옮길 수 있는가"를 <b>실제로 배치를 시도해</b> 답한다.
 * 시도는 계산일 뿐이다 — {@link PlacementService#place} 를 부르지 않으므로 워크로드 상태도
 * 배치 이력도 바뀌지 않는다.</p>
 *
 * <p><b>유휴 여부를 고정 임계로 정하지 않는다.</b> "CPU 5% 미만이면 유휴" 같은 기준은 64코어의
 * 5%(3.2코어)와 8코어의 5%(0.4코어)를 같게 취급하는 자의적 절벽을 만든다. 대신 <b>순절감</b>을
 * 계산한다 — 노드를 비워 아끼는 유휴 전력에서 그 워크로드를 옮기는 비용을 뺀 값이며, 양수여야
 * 통합할 가치가 있다. 옮기는 비용은 PR ② 의 {@code realMarginalPower} 로 잰다.</p>
 */
@Service
public class ConsolidationService {

    private final SchedulingService schedulingService;
    private final WorkloadRepository workloadRepository;
    private final EngineRegistry engineRegistry;
    private final PowerModel powerModel;
    private final SchedulerProperties.Power props;

    public ConsolidationService(SchedulingService schedulingService,
                                WorkloadRepository workloadRepository,
                                EngineRegistry engineRegistry,
                                PowerModel powerModel,
                                SchedulerProperties properties) {
        this.schedulingService = schedulingService;
        this.workloadRepository = workloadRepository;
        this.engineRegistry = engineRegistry;
        this.powerModel = powerModel;
        this.props = properties.getPower();
    }

    /**
     * 통합 가능성을 진단한다.
     *
     * <p>후보는 <b>절감 잠재량이 큰 순서</b>로 돈다. 상한에 걸려도 가장 가치 있는 후보는 이미
     * 평가된 상태가 된다.</p>
     */
    public ConsolidationResponse diagnose() {
        SchedulingPolicy policy = schedulingService.resolvePolicy();
        List<NodeResource> nodes = schedulingService.getNodeResources();
        OffsetDateTime now = OffsetDateTime.now();

        // 시뮬레이션 기준이 될 스냅샷을 한 번만 수집한다. 후보마다 다시 수집하면 노드당
        // 아홉 종의 메트릭 질의가 후보 수만큼 되풀이된다.
        List<NodeResource> base = schedulingService.createContext(policy).nodes();

        List<NodeResource> ranked = firstPassFilter(nodes, policy);
        int limit = Math.max(0, props.getMaxConsolidationCandidates());

        List<Candidate> candidates = new ArrayList<>();
        int evaluated = 0;
        for (NodeResource node : ranked) {
            if (evaluated >= limit) {
                candidates.add(Candidate.skipped(node.getMdcId(), node.getNodeId(),
                        workloadsOn(node).size(),
                        String.format("후보 상한 %d대를 초과했습니다", limit)));
                continue;
            }
            candidates.add(evaluate(node, base, policy));
            evaluated++;
        }

        return summarize(now, candidates, nodes);
    }

    // ── 1차 필터 ───────────────────────────────────────────────

    /**
     * 명백히 후보가 아닌 노드를 싸게 걸러낸다.
     *
     * <p>여기서 쓰는 CPU 상한은 <b>판정 기준이 아니라 거르기 기준</b>이다. 값이 다소 틀려도
     * 2차에서 순절감으로 다시 판정하므로 결과가 바뀌지 않는다 — 오탐은 걸러지고 미탐만
     * 성능 문제가 된다. 그래서 새 설정을 만들지 않고 정책의 사용률 상한을 그대로 쓴다.</p>
     */
    private List<NodeResource> firstPassFilter(List<NodeResource> nodes, SchedulingPolicy policy) {
        return nodes.stream()
                .filter(n -> n.getCpuUsagePercent() <= policy.getMaxCpuUsagePercent())
                .sorted(Comparator.comparingDouble(powerModel::idlePower).reversed())
                .toList();
    }

    // ── 2차 판정 — 순절감 ──────────────────────────────────────

    private Candidate evaluate(NodeResource node, List<NodeResource> base, SchedulingPolicy policy) {
        List<Workload> workloads = workloadsOn(node);
        int perNodeLimit = Math.max(0, props.getMaxRelocationsPerNode());

        // 상한을 넘는 노드는 판정하지 않는다. 60건 중 50건만 옮겨보고 "반납 가능" 이라 하면
        // 거짓이다 — 남은 10건이 그 노드를 붙잡고 있다.
        if (workloads.size() > perNodeLimit) {
            return Candidate.skipped(node.getMdcId(), node.getNodeId(), workloads.size(),
                    String.format("워크로드 %d건이 노드당 상한 %d건을 초과합니다",
                            workloads.size(), perNodeLimit));
        }

        double idle = powerModel.idlePower(node);
        Double ratio = powerModel.isActive(node) || node.getPowerWatts() <= 0 || idle <= 0
                ? null : PowerOptimizationService.round(node.getPowerWatts() / idle);

        // 스케줄러가 아는 워크로드가 없다고 노드가 빈 것은 아니다. 수동 배치나 다른 시스템의
        // 부하는 메트릭에만 보인다. 실제로 놀고 있는지 부하로 한 번 더 확인하지 않으면
        // 가동 중인 노드를 반납하라고 권고하게 된다.
        if (workloads.isEmpty()) {
            if (powerModel.isActive(node)) {
                return blocked(node, 0, idle, ratio, String.format(
                        "스케줄러가 아는 워크로드는 없지만 CPU %.1f%% 로 다른 부하가 돌고 있습니다",
                        node.getCpuUsagePercent()));
            }
            return reclaimable(node, "ALREADY_EMPTY", 0, idle, 0, List.of(), ratio,
                    String.format("배치된 워크로드가 없고 부하도 없어 %.0fW 를 그대로 반납할 수 있습니다", idle));
        }

        // 이 노드를 뺀 나머지에만 배치를 시도한다. 컨텍스트는 여기서 새로 만든다 —
        // NodeResource 는 가변이라(reserve) 조회용 스냅샷에 예약을 쌓으면 응답이 오염된다.
        PlacementContext sandbox = sandboxWithout(node, base, policy);
        if (sandbox.isEmpty()) {
            return blocked(node, workloads.size(), idle, ratio, "옮길 다른 노드가 없습니다");
        }

        List<Move> moves = new ArrayList<>();
        double cost = 0;
        for (Workload workload : workloads) {
            SchedulingResult result = engineRegistry.resolve("power").schedule(workload, sandbox);
            if (!result.success()) {
                return blocked(node, workloads.size(), idle, ratio,
                        String.format("워크로드 %d 를 받을 노드가 없습니다 (%s)",
                                workload.getId(), result.reason()));
            }
            NodeResource target = sandbox.node(result.selectedNodeId()).orElseThrow();
            double marginal = powerModel.realMarginalPower(target, workload.getRequiredCpu());
            cost += marginal;

            moves.add(new Move(workload.getId(), workload.getName(), result.selectedNodeId(),
                    PowerOptimizationService.round(marginal)));

            // 다음 워크로드가 같은 노드에 몰리지 않도록 예약을 쌓는다. 샌드박스라 안전하다.
            sandbox.reserve(result.selectedNodeId(), workload);
        }

        // 워크로드를 전부 옮겨도 노드가 비지 않으면 반납할 수 없다. 옮기는 워크로드가 차지하던
        // 몫을 빼고도 부하가 남으면 스케줄러 밖의 무언가가 돌고 있다는 뜻이다.
        double movedCpuShare = workloads.stream()
                .mapToInt(Workload::getRequiredCpu).sum() * 100.0 / Math.max(node.getTotalCpu(), 1);
        double remaining = node.getCpuUsagePercent() - movedCpuShare;
        if (remaining > props.getActiveCpuPercent()) {
            return blocked(node, workloads.size(), idle, ratio, String.format(
                    "워크로드를 모두 옮겨도 CPU %.1f%% 의 부하가 남아 반납할 수 없습니다", remaining));
        }

        double net = idle - cost;
        if (net <= 0) {
            return blocked(node, workloads.size(), idle, ratio,
                    String.format("옮기는 데 %.0fW 가 들어 반납분 %.0fW 보다 손해입니다", cost, idle));
        }

        return reclaimable(node, "NET_SAVING", workloads.size(), idle, cost, moves, ratio,
                String.format("워크로드 %d건을 옮기는 데 %.0fW 가 들고 %.0fW 를 반납해 %.0fW 를 아낍니다",
                        workloads.size(), cost, idle, net));
    }

    /**
     * 후보 노드를 제외한 배치 컨텍스트.
     *
     * <p>기준 스냅샷을 <b>복사해서</b> 쓴다. 그대로 넘기면 시뮬레이션이 쌓는 예약이 다음 후보의
     * 판정에 섞이고, 조회용으로 나간 객체까지 오염된다. 메트릭을 다시 수집하지 않는 이유는
     * {@link NodeResource#snapshot()} 주석에 적었다.</p>
     */
    private PlacementContext sandboxWithout(NodeResource excluded, List<NodeResource> base,
                                            SchedulingPolicy policy) {
        List<NodeResource> others = base.stream()
                .filter(n -> !n.getNodeId().equals(excluded.getNodeId()))
                .map(NodeResource::snapshot)
                .toList();
        return PlacementContext.of(others, policy);
    }

    private List<Workload> workloadsOn(NodeResource node) {
        return workloadRepository.findByAssignedNodeIdOrderByScheduledAtDesc(node.getNodeId()).stream()
                .filter(w -> w.getStatus() == Workload.WorkloadStatus.SCHEDULED
                        || w.getStatus() == Workload.WorkloadStatus.RUNNING)
                .toList();
    }

    // ── 응답 조립 ──────────────────────────────────────────────

    private Candidate reclaimable(NodeResource node, String basis, int count,
                                  double idle, double cost, List<Move> moves,
                                  Double ratio, String detail) {
        return new Candidate(node.getMdcId(), node.getNodeId(), true, null,
                true, basis, count,
                PowerOptimizationService.round(idle),
                PowerOptimizationService.round(cost),
                PowerOptimizationService.round(idle - cost),
                ratio, moves, null, detail);
    }

    private Candidate blocked(NodeResource node, int count, double idle, Double ratio, String reason) {
        return new Candidate(node.getMdcId(), node.getNodeId(), true, null,
                false, null, count,
                PowerOptimizationService.round(idle), null, null,
                ratio, List.of(), reason, null);
    }

    private ConsolidationResponse summarize(OffsetDateTime now, List<Candidate> candidates,
                                            List<NodeResource> nodes) {
        List<Candidate> reclaimable = candidates.stream()
                .filter(c -> c.evaluated() && c.reclaimable()).toList();

        double watts = reclaimable.stream()
                .mapToDouble(c -> c.netSavingWatts() == null ? 0 : c.netSavingWatts()).sum();
        double totalPower = nodes.stream().mapToDouble(NodeResource::getPowerWatts).sum();

        long skipped = candidates.stream().filter(c -> !c.evaluated()).count();
        String truncatedReason = skipped == 0 ? null
                : String.format("후보 %d대가 상한을 넘어 평가되지 않았습니다", skipped);

        return new ConsolidationResponse(now, !reclaimable.isEmpty(), candidates,
                new ConsolidationResponse.Summary(
                        candidates.size(), reclaimable.size(),
                        PowerOptimizationService.round(watts),
                        PowerOptimizationService.percentOf(watts, totalPower),
                        skipped > 0, truncatedReason),
                ConsolidationResponse.LIMITATION);
    }
}
