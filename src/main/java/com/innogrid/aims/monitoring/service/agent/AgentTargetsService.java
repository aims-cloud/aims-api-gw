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
 * 수집 서비스 원장 → 노드의 수집 대상 목록 반영 (docs/11 §5.3).
 *
 * {@link #sync(Long)} 은 원장으로 목록을 만들고 노드에 놓인 파일의 해시와 비교해 다르면 다시 쓴다.
 * 원장 변경 커밋 뒤({@link CollectServicesChangedListener})와 재적용 스케줄러({@link AgentTargetsReconcileJob})가 부르므로,
 * 파일이 지워지거나 첫 전송이 실패해도 다음 주기에 스스로 맞춰진다. 반영 실패는 원장에 사유로 남기고 예외를 올리지 않는다 —
 * 서비스 등록 자체는 성공이며 반영 상태는 {@link #status(Long)} 로 보여 준다.
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

    /** 노드 하나의 목록을 계산해 필요하면 반영한다. 에이전트 행이 없거나 NONE 이면 아무것도 하지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sync(Long assetId) {
        AgentNode node = nodes.findById(assetId).orElse(null);
        if (node == null || node.getTransport() == AgentTransport.NONE) {
            return;
        }
        Targets targets = builder.build(services.findByAsset_Id(assetId));
        node.markDesired(targets.hash());

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
            if (!targets.hash().equals(current)) {
                publisher.publish(node, targets.json());
                log.info("수집 대상 목록 반영: asset={} transport={} files={} path={}", assetId, node.getTransport(),
                        targets.count(), node.getTargetsPath());
            }
            node.markApplied(targets.hash(), OffsetDateTime.now());
        } catch (Exception e) {
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            node.markApplyFailed(reason);
            log.warn("수집 대상 목록 반영 실패: asset={} path={} — {}", assetId, node.getTargetsPath(), reason);
        }
    }

    /**
     * PULL 전송 — 노드 Alloy 가 자기 instance(node_exporter 주소 {@code address:port})로 목록을 가져간다.
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
        Long assetId = target.getAsset().getId();
        AgentNode node = nodes.findById(assetId).orElse(null);
        if (node == null || node.getTransport() != AgentTransport.PULL) {
            return Optional.empty();
        }
        Targets built = builder.build(services.findByAsset_Id(assetId));
        node.markDesired(built.hash());
        node.markApplied(built.hash(), OffsetDateTime.now());
        log.debug("수집 대상 목록 제공(pull): instance={} asset={} files={}", instance, assetId, built.count());
        return Optional.of(built.json());
    }

    /**
     * 목록 전송 대상(transport ≠ NONE) 노드의 자산 id — 스케줄러가 노드마다 {@link #sync(Long)} 를 프록시를 통해 부른다.
     * 같은 클래스 안에서 sync 를 직접 부르면 트랜잭션이 걸리지 않아 원장 컬렉션 지연 로딩이 실패하므로 여기서 호출하지 않는다.
     */
    @Transactional(readOnly = true)
    public List<Long> syncCandidates() {
        return nodes.findByTransportNot(AgentTransport.NONE).stream().map(AgentNode::getAssetId).toList();
    }

    /** 탭에 보여 줄 반영 상태. 원장 목록은 여기서 다시 계산해 파일 수를 함께 준다. */
    @Transactional(readOnly = true)
    public AgentTargetsStatusResponse status(Long assetId) {
        AgentNode node = nodes.findById(assetId).orElse(null);
        int count = builder.build(services.findByAsset_Id(assetId)).count();
        if (node == null || node.getTransport() == AgentTransport.NONE) {
            return new AgentTargetsStatusResponse(node == null ? AgentTransport.NONE : node.getTransport(),
                    TargetsStatus.NO_AGENT, null, count, null);
        }
        if (node.getTargetsError() != null) {
            return new AgentTargetsStatusResponse(node.getTransport(), TargetsStatus.FAILED, node.getTargetsAppliedAt(), count,
                    node.getTargetsError());
        }
        if (node.isTargetsPending() || node.getTargetsAppliedAt() == null) {
            String message = node.getTransport() == AgentTransport.PULL && node.getTargetsAppliedAt() == null
                    ? "노드의 Alloy 가 아직 목록을 가져가지 않았습니다(설치·NODE_INSTANCE 값·방화벽 확인)."
                    : null;
            return new AgentTargetsStatusResponse(node.getTransport(), TargetsStatus.PENDING, node.getTargetsAppliedAt(), count, message);
        }
        return new AgentTargetsStatusResponse(node.getTransport(), TargetsStatus.APPLIED, node.getTargetsAppliedAt(), count, null);
    }
}
