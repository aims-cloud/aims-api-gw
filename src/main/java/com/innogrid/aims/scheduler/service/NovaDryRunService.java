package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.NovaDryRunResponse;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.inventory.NovaInventoryGateway;
import com.innogrid.aims.scheduler.inventory.NovaInventorySnapshot;
import com.innogrid.aims.scheduler.model.InventoryStatus;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.NovaDryRunStatus;
import com.innogrid.aims.scheduler.model.SchedulingResult;

/**
 * Nova 인벤토리 모의 배치 — 쓸 수 있는 하이퍼바이저를 빈 노드로 두고, 그 위에 있는 VM을 스케줄러가 다시 놓아 본다.
 *
 * <p>답하는 질문은 「지금 실 VM 이 정책의 사용률 상한과 물리 vCPU · 메모리 안에 다 들어가는가, 못 들어가면 왜인가」다.
 * 저장하지 않는다 — 워크로드 · 배치 이력 · 정책 어느 행도 쓰지 않고, 실행 계층에 넘기지 않는다. 스케줄 모의 실행과 같은
 * 방식으로 한 컨텍스트에 차례로 놓고 성공한 건의 요구량을 예약으로 쌓는다.</p>
 *
 * <p>Nova 노드는 메트릭 저장소의 노드와 이을 키가 없어 사용률 · 전력 · 온도를 넣지 않는다. 그래서 엔진 셋이 모두 자원 여유가
 * 큰 노드를 고르는 같은 결과를 내므로, 정책의 엔진 하나만 쓰고 엔진 비교 · 통합 권고의 근거로 쓰지 않는다. 디스크는 보지 않는다
 * (서버의 하이퍼바이저 디스크 값이 공유 스토리지라 노드별 값이 아니다). 기능 플래그
 * {@code aims.scheduler.inventory.simulation-enabled}를 호출마다 읽고, 꺼져 있으면 Nova를 부르지 않는다.</p>
 */
@Service
public class NovaDryRunService {

    private static final Logger log = LoggerFactory.getLogger(NovaDryRunService.class);

    /** Nova 노드의 MDC 값 — MDC 원장과 이을 키가 없어 모두 같은 값으로 둔다(링크 행렬 · MDC 전력 예산을 쓰지 않음). */
    static final String MDC_ID = "NOVA";

    /** 요구 vCPU 큰 순 → 메모리 큰 순 → VM ID 순. 같은 입력이면 같은 순서다. */
    static final Comparator<NovaInventorySnapshot.Server> LARGEST_FIRST = Comparator
            .comparing(NovaInventorySnapshot.Server::requiredCpu, Comparator.reverseOrder())
            .thenComparing(NovaInventorySnapshot.Server::requiredMemoryMb, Comparator.reverseOrder())
            .thenComparing(NovaInventorySnapshot.Server::serverId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final NovaInventoryGateway gateway;
    private final SchedulingService schedulingService;
    private final EngineRegistry engineRegistry;
    private final SchedulerProperties properties;

    public NovaDryRunService(NovaInventoryGateway gateway, SchedulingService schedulingService,
                             EngineRegistry engineRegistry, SchedulerProperties properties) {
        this.gateway = gateway;
        this.schedulingService = schedulingService;
        this.engineRegistry = engineRegistry;
        this.properties = properties;
    }

    /** 플래그가 켜져 있으면 인벤토리를 읽고 모의 배치를 한다. 실패해도 예외를 던지지 않고 상태로 돌려준다. */
    public NovaDryRunResponse dryRun() {
        OffsetDateTime checkedAt = OffsetDateTime.now(ZoneOffset.UTC);
        long start = System.nanoTime();
        if (!properties.getInventory().isSimulationEnabled()) {
            return NovaDryRunResponse.notRun(NovaDryRunStatus.DISABLED, null, List.of(), checkedAt, elapsedMs(start), null, null, null);
        }
        return simulate(gateway.read(), checkedAt, start);
    }

    /** 읽은 인벤토리로 모의 배치를 한다 — 테스트가 스냅샷을 직접 넣는 자리. */
    NovaDryRunResponse simulate(NovaInventorySnapshot snapshot, OffsetDateTime checkedAt, long start) {
        InventoryStatus inventoryStatus = snapshot.status();
        boolean hypervisorsRead = inventoryStatus == InventoryStatus.OK || inventoryStatus == InventoryStatus.EMPTY;

        Map<String, NovaInventorySnapshot.Node> usable = new LinkedHashMap<>();
        Map<String, String> unusableReason = new LinkedHashMap<>();
        for (NovaInventorySnapshot.Node node : snapshot.nodes()) {
            if (node.usable()) usable.put(node.hypervisorHostname(), node);
            else if (node.hypervisorHostname() != null) unusableReason.put(node.hypervisorHostname(), node.skipReason());
        }
        if (!hypervisorsRead || !snapshot.serversRead()) {
            // VM 목록만 못 읽어도 「VM 0대」로 답하지 않는다 — 빈 인벤토리와 연동 실패를 가르는 것이 이 경로의 전제다
            return NovaDryRunResponse.notRun(NovaDryRunStatus.INVENTORY_FAILED, inventoryStatus, snapshot.unavailable(), checkedAt,
                    elapsedMs(start), hypervisorsRead ? usable.size() : null, null, null);
        }

        List<NovaInventorySnapshot.Server> targets = new ArrayList<>();
        List<NovaDryRunResponse.Excluded> excluded = new ArrayList<>();
        for (NovaInventorySnapshot.Server server : snapshot.servers()) {
            String host = server.hypervisorHostname();
            String reason = null;
            if (!server.convertible()) reason = "변환 불가: " + server.skipReason();
            else if (host == null) reason = "지금 호스트 없음(보관 등)";
            else if (!usable.containsKey(host)) reason = "지금 호스트를 쓸 수 없음("
                    + Objects.requireNonNullElse(unusableReason.get(host), "하이퍼바이저 목록에 없음") + ")";
            if (reason == null) targets.add(server);
            else excluded.add(new NovaDryRunResponse.Excluded(server.serverId(), server.name(), host, reason));
        }
        if (usable.isEmpty()) {
            return NovaDryRunResponse.notRun(NovaDryRunStatus.NO_NODES, inventoryStatus, snapshot.unavailable(), checkedAt,
                    elapsedMs(start), 0, targets.size(), excluded);
        }
        if (targets.isEmpty()) {
            return NovaDryRunResponse.notRun(NovaDryRunStatus.NO_VMS, inventoryStatus, snapshot.unavailable(), checkedAt,
                    elapsedMs(start), usable.size(), 0, excluded);
        }

        // VM 배치와 같은 규칙으로 정책을 고르되 행을 만들지 않는다
        SchedulingPolicy policy = schedulingService.choosePolicy(Workload.ResourceType.VM).policy();
        String engine = EngineChoice.choose(null, policy, engineRegistry).engine();
        List<NodeResource> nodes = new ArrayList<>();
        usable.values().forEach(node -> nodes.add(toNodeResource(node)));
        PlacementContext context = PlacementContext.of(nodes, policy);

        targets.sort(LARGEST_FIRST);
        List<NovaDryRunResponse.Decision> decisions = new ArrayList<>(targets.size());
        Map<String, Integer> failedByReason = new TreeMap<>();
        for (NovaInventorySnapshot.Server server : targets) {
            Workload workload = toWorkload(server);
            SchedulingResult result;
            try {
                result = schedulingService.evaluateIn(workload, engine, context);
            } catch (RuntimeException | LinkageError e) {
                log.warn("Nova 모의 배치 평가 실패(계속한다): server={}", server.serverId(), e);
                result = SchedulingResult.failure("평가 중 오류: " + e.getMessage(), policy.getName());
            }
            if (result.success()) {
                context.reserve(result.selectedNodeId(), workload);
            } else {
                result.candidateScores().stream()
                        .map(SchedulingResult.NodeScore::exclusionCode)
                        .filter(Objects::nonNull)
                        .map(Enum::name)
                        .distinct()
                        .forEach(code -> failedByReason.merge(code, 1, Integer::sum));
            }
            String simulatedHost = result.success() ? result.selectedNodeId() : null;
            decisions.add(new NovaDryRunResponse.Decision(server.serverId(), server.name(), server.status(),
                    server.requiredCpu(), server.requiredMemoryMb(), server.hypervisorHostname(), simulatedHost,
                    simulatedHost != null && !simulatedHost.equals(server.hypervisorHostname()),
                    result.success(), result.reason(), PlacementService.exclusionCodesOf(result)));
        }

        List<NovaDryRunResponse.Node> nodeRows = nodeRows(usable, targets, decisions, nodes, policy);
        int placed = (int) decisions.stream().filter(NovaDryRunResponse.Decision::success).count();
        int hostChanged = (int) decisions.stream().filter(NovaDryRunResponse.Decision::hostChanged).count();
        int overLimit = (int) nodeRows.stream().filter(NovaDryRunResponse.Node::overLimit).count();
        log.info("Nova 모의 배치: 노드 {} · 대상 {} · 놓임 {} · 못 놓임 {} · 대상 밖 {} · 정책 {}",
                usable.size(), targets.size(), placed, targets.size() - placed, excluded.size(), policy.getName());
        return new NovaDryRunResponse(NovaDryRunResponse.SOURCE, NovaDryRunStatus.OK, NovaDryRunStatus.OK.message(),
                inventoryStatus, snapshot.unavailable(), policy.getName(), engine,
                policy.getMaxCpuUsagePercent(), policy.getMaxMemoryUsagePercent(), NovaDryRunResponse.ORDER,
                checkedAt, elapsedMs(start),
                usable.size(), targets.size(), excluded.size(), placed, targets.size() - placed, failedByReason,
                (int) nodeRows.stream().filter(n -> n.currentVmCount() > 0).count(),
                (int) nodeRows.stream().filter(n -> n.simulatedVmCount() > 0).count(),
                hostChanged, overLimit, nodeRows, decisions, excluded);
    }

    /**
     * 노드별 지금 할당(Nova 값)과 모의 배치 뒤 값 · 사용률. 호스트 이름순(스냅샷 순서).
     *
     * <p>사용률은 예약이 쌓인 컨텍스트 노드의 실효 사용률을 그대로 읽는다 — 엔진이 다음 VM을 놓을 때 보는 값과 같다.
     * 상한을 넘었는지는 엔진의 사용률 제약(C2)과 같은 식으로 반올림 전 값을 비교하고, 응답에는 둘째 자리로 반올림한 값을 싣는다.</p>
     */
    private static List<NovaDryRunResponse.Node> nodeRows(Map<String, NovaInventorySnapshot.Node> usable,
                                                         List<NovaInventorySnapshot.Server> targets,
                                                         List<NovaDryRunResponse.Decision> decisions,
                                                         List<NodeResource> reserved, SchedulingPolicy policy) {
        Map<String, Long> currentVms = targets.stream()
                .collect(Collectors.groupingBy(NovaInventorySnapshot.Server::hypervisorHostname, Collectors.counting()));
        Map<String, NodeResource> after = reserved.stream().collect(Collectors.toMap(NodeResource::getNodeId, n -> n));
        List<NovaDryRunResponse.Node> rows = new ArrayList<>(usable.size());
        for (NovaInventorySnapshot.Node node : usable.values()) {
            String host = node.hypervisorHostname();
            List<NovaDryRunResponse.Decision> here = decisions.stream().filter(d -> host.equals(d.simulatedHost())).toList();
            NodeResource placed = after.get(host);
            double cpu = placed.effectiveCpuUsagePercent();
            double memory = placed.effectiveMemoryUsagePercent();
            boolean overLimit = cpu > policy.getMaxCpuUsagePercent() || memory > policy.getMaxMemoryUsagePercent();
            rows.add(new NovaDryRunResponse.Node(host, node.totalCpu(), node.totalMemoryMb(),
                    node.allocatedCpu(), node.allocatedMemoryMb(), currentVms.getOrDefault(host, 0L).intValue(),
                    here.stream().mapToInt(NovaDryRunResponse.Decision::requiredCpu).sum(),
                    here.stream().mapToLong(NovaDryRunResponse.Decision::requiredMemoryMb).sum(),
                    here.size(),
                    placed.getTotalCpu() > 0 ? percent(cpu) : null,
                    placed.getTotalMemoryMb() > 0 ? percent(memory) : null,
                    overLimit));
        }
        return rows;
    }

    /** 응답에 싣는 사용률 — 소수 둘째 자리 반올림(0.5는 올림). 상한 비교에는 쓰지 않는다. */
    private static double percent(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** 빈 노드 — 사용률 · 전력 · 온도 0(계측 없음), 디스크 총량 0(모름 · 디스크 제약을 보지 않음). */
    static NodeResource toNodeResource(NovaInventorySnapshot.Node node) {
        return new NodeResource(MDC_ID, node.hypervisorHostname(), node.totalCpu(), node.totalMemoryMb(), 0,
                0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** 저장하지 않는 워크로드 사본 — VM 종류 · 요구 vCPU · 메모리만. 디스크 요구는 0(보지 않음). */
    static Workload toWorkload(NovaInventorySnapshot.Server server) {
        Workload workload = new Workload(server.name() != null ? server.name() : server.serverId(),
                server.requiredCpu(), server.requiredMemoryMb());
        workload.setResourceType(Workload.ResourceType.VM);
        return workload;
    }

    private static long elapsedMs(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
