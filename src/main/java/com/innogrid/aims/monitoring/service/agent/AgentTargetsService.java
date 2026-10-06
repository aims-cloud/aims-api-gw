package com.innogrid.aims.monitoring.service.agent;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.AgentTargetsStatusResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.AgentTargetsStatusResponse.TargetsStatus;
import com.innogrid.aims.monitoring.entity.AgentNode;
import com.innogrid.aims.monitoring.entity.AgentTransport;
import com.innogrid.aims.monitoring.entity.CollectOwner;
import com.innogrid.aims.monitoring.entity.CollectService;
import com.innogrid.aims.monitoring.entity.MonitoringTarget;
import com.innogrid.aims.monitoring.entity.MonitoringTarget.ExporterType;
import com.innogrid.aims.monitoring.repository.AgentNodeRepository;
import com.innogrid.aims.monitoring.repository.CollectServiceRepository;
import com.innogrid.aims.monitoring.repository.MonitoringTargetRepository;
import com.innogrid.aims.monitoring.service.agent.CollectTargetsBuilder.Targets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수집 서비스 원장 → 수집 에이전트의 대상 목록 반영 (docs/11 §5.3).
 *
 * 에이전트 행({@link AgentNode})은 물리 자산 1건 또는 Tumblebug VM 1건을 소유자로 갖는다. {@link #sync(Long)}·{@link #syncOwner}
 * 는 소유자의 원장으로 목록을 만들고, LOCAL 이면 노드 파일과 해시를 비교해 다를 때 다시 쓰고, PULL 이면 원하는 해시만 둔다
 * (Alloy 가 {@link #serve}·{@link #serveVm} 으로 가져갈 때 적용 기록). 원장 변경 커밋 뒤({@link CollectServicesChangedListener})와
 * 재적용 스케줄러({@link AgentTargetsReconcileJob})가 부른다. 반영 실패는 원장에 사유로 남기고 예외를 올리지 않는다 —
 * 서비스 등록 자체는 성공이며 반영 상태는 {@link #status(CollectOwner)} 로 보여 준다.
 */
@Service
public class AgentTargetsService {

    private static final Logger log = LoggerFactory.getLogger(AgentTargetsService.class);

    private final AgentNodeRepository nodes;
    private final CollectServiceRepository services;
    private final MonitoringTargetRepository targets;
    private final CollectTargetsBuilder builder;
    private final Map<AgentTransport, AgentTargetsPublisher> publishers = new EnumMap<>(AgentTransport.class);

    public AgentTargetsService(AgentNodeRepository nodes, CollectServiceRepository services,
                               MonitoringTargetRepository targets, CollectTargetsBuilder builder,
                               List<AgentTargetsPublisher> publishers) {
        this.nodes = nodes;
        this.services = services;
        this.targets = targets;
        this.builder = builder;
        for (AgentTargetsPublisher publisher : publishers) {
            this.publishers.put(publisher.transport(), publisher);
        }
    }

    /** 에이전트 하나(행 id)의 목록을 계산해 필요하면 반영한다 — 재적용 스케줄러용 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sync(Long agentId) {
        nodes.findById(agentId).ifPresent(this::apply);
    }

    /** 원장 소유자(물리 노드 또는 VM)의 에이전트 목록을 반영한다 — 원장 변경 커밋 뒤 트리거용. 에이전트 행이 없으면 아무것도 하지 않는다 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void syncOwner(CollectOwner owner) {
        findAgent(owner).ifPresent(this::apply);
    }

    private void apply(AgentNode node) {
        if (node.getTransport() == AgentTransport.NONE) {
            return;
        }
        Targets built = build(node);
        node.markDesired(built.hash());

        if (node.getTransport() == AgentTransport.PULL) {
            // 노드 Alloy 가 가져가는 방식 — 여기서는 원하는 해시만 두고, 적용 기록은 serve() 가 남긴다
            return;
        }
        AgentTargetsPublisher publisher = publishers.get(node.getTransport());
        if (publisher == null) {
            node.markApplyFailed("전송 방식 " + node.getTransport() + " 은 아직 지원하지 않습니다.");
            return;
        }
        try {
            String current = publisher.currentHash(node);
            if (!built.hash().equals(current)) {
                publisher.publish(node, built.json());
                log.info("수집 대상 목록 반영: {} transport={} files={} path={}", node.owner().label(), node.getTransport(),
                        built.count(), node.getTargetsPath());
            }
            node.markApplied(built.hash(), OffsetDateTime.now());
        } catch (Exception e) {
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            node.markApplyFailed(reason);
            log.warn("수집 대상 목록 반영 실패: {} path={} — {}", node.owner().label(), node.getTargetsPath(), reason);
        }
    }

    /**
     * PULL 전송(물리 노드) — 노드 Alloy 가 자기 instance(node_exporter 주소 {@code address:port})로 목록을 가져간다.
     * instance 로 node_exporter 타겟 → 자산 → PULL 에이전트 행을 찾아 원장 목록을 만들어 돌려주고, 그 해시를 "적용됨"으로
     * 기록한다(Alloy 가 받아 간 목록이 곧 노드에 반영된 목록). 모르는 instance 이거나 PULL 노드가 아니면 빈 Optional.
     * 토큰은 두지 않는다 — 백엔드가 사내 대역에서만 열려 있다는 전제이며, 목록에는 파일 경로만 있다.
     */
    @Transactional
    public Optional<String> serve(String instance) {
        if (instance == null || instance.isBlank()) {
            return Optional.empty();
        }
        int colon = instance.lastIndexOf(':');
        if (colon <= 0 || colon == instance.length() - 1) {
            return Optional.empty();
        }
        int port;
        try {
            port = Integer.parseInt(instance.substring(colon + 1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        MonitoringTarget target = targets.findFirstByAddressAndPortAndExporterType(
                instance.substring(0, colon), port, ExporterType.NODE_EXPORTER).orElse(null);
        if (target == null || target.getAsset() == null) {
            return Optional.empty();
        }
        return servePull(findAgent(CollectOwner.asset(target.getAsset().getId())));
    }

    /**
     * PULL 전송(Tumblebug VM) — VM 의 Alloy 가 자기 Tumblebug 키(tb_ns·tb_infra·tb_node)로 목록을 가져간다.
     * 키가 비었거나 VM 에이전트 행이 없거나 PULL 이 아니면 빈 Optional.
     */
    @Transactional
    public Optional<String> serveVm(String tbNs, String tbInfra, String tbNode) {
        if (isBlank(tbNs) || isBlank(tbInfra) || isBlank(tbNode)) {
            return Optional.empty();
        }
        return servePull(findAgent(CollectOwner.vm(tbNs, tbInfra, tbNode)));
    }

    private Optional<String> servePull(Optional<AgentNode> found) {
        AgentNode node = found.orElse(null);
        if (node == null || node.getTransport() != AgentTransport.PULL) {
            return Optional.empty();
        }
        Targets built = build(node);
        node.markDesired(built.hash());
        node.markApplied(built.hash(), OffsetDateTime.now());
        log.debug("수집 대상 목록 제공(pull): {} files={}", node.owner().label(), built.count());
        return Optional.of(built.json());
    }

    /**
     * 목록 전송 대상(transport ≠ NONE) 에이전트 행 id — 스케줄러가 행마다 {@link #sync(Long)} 를 프록시를 통해 부른다.
     * 같은 클래스 안에서 sync 를 직접 부르면 트랜잭션이 걸리지 않아 원장 컬렉션 지연 로딩이 실패하므로 여기서 호출하지 않는다.
     */
    @Transactional(readOnly = true)
    public List<Long> syncCandidates() {
        return nodes.findByTransportNot(AgentTransport.NONE).stream().map(AgentNode::getId).toList();
    }

    /** 물리 노드 탭의 반영 상태 */
    @Transactional(readOnly = true)
    public AgentTargetsStatusResponse status(Long assetId) {
        return status(CollectOwner.asset(assetId));
    }

    /** 탭에 보여 줄 반영 상태(물리 노드 또는 VM). 원장 목록은 여기서 다시 계산해 파일 수를 함께 준다. */
    @Transactional(readOnly = true)
    public AgentTargetsStatusResponse status(CollectOwner owner) {
        AgentNode node = findAgent(owner).orElse(null);
        AgentTransport transport = node == null ? AgentTransport.NONE : node.getTransport();
        int count = builder.build(servicesOf(owner), transport).count();
        if (node == null || transport == AgentTransport.NONE) {
            return new AgentTargetsStatusResponse(transport, TargetsStatus.NO_AGENT, null, count, null);
        }
        if (node.getTargetsError() != null) {
            return new AgentTargetsStatusResponse(transport, TargetsStatus.FAILED, node.getTargetsAppliedAt(), count,
                    node.getTargetsError());
        }
        if (node.isTargetsPending() || node.getTargetsAppliedAt() == null) {
            String message = transport == AgentTransport.PULL && node.getTargetsAppliedAt() == null
                    ? (owner.isVm()
                            ? "VM 의 Alloy 가 아직 목록을 가져가지 않았습니다(에이전트 설치 상태·방화벽 확인)."
                            : "노드의 Alloy 가 아직 목록을 가져가지 않았습니다(설치·NODE_INSTANCE 값·방화벽 확인).")
                    : null;
            return new AgentTargetsStatusResponse(transport, TargetsStatus.PENDING, node.getTargetsAppliedAt(), count, message);
        }
        return new AgentTargetsStatusResponse(transport, TargetsStatus.APPLIED, node.getTargetsAppliedAt(), count, null);
    }

    private Targets build(AgentNode node) {
        return builder.build(servicesOf(node.owner()), node.getTransport());
    }

    private Optional<AgentNode> findAgent(CollectOwner owner) {
        return owner.isVm()
                ? nodes.findByTbNsAndTbInfraAndTbNode(owner.tbNs(), owner.tbInfra(), owner.tbNode())
                : nodes.findByAsset_Id(owner.assetId());
    }

    private List<CollectService> servicesOf(CollectOwner owner) {
        return owner.isVm()
                ? services.findByTbNsAndTbInfraAndTbNode(owner.tbNs(), owner.tbInfra(), owner.tbNode())
                : services.findByAsset_Id(owner.assetId());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
