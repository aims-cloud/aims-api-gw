package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import com.innogrid.aims.cloudbarista.tumblebug.TumblebugClient;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.VmAgentInstallResponse;
import com.innogrid.aims.monitoring.entity.AgentInstallStatus;
import com.innogrid.aims.monitoring.entity.AgentNode;
import com.innogrid.aims.monitoring.entity.AgentTransport;
import com.innogrid.aims.monitoring.entity.CollectOwner;
import com.innogrid.aims.monitoring.repository.AgentNodeRepository;
import com.innogrid.aims.monitoring.service.agent.AgentProperties;
import com.innogrid.aims.monitoring.service.agent.AgentTargetsService;
import com.innogrid.aims.monitoring.service.agent.VmAgentInstallCommand;
import com.innogrid.aims.monitoring.service.agent.VmAgentInstallCommand.Outcome;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tumblebug VM 에 수집 에이전트(node_exporter + Grafana Alloy)를 설치한다 (docs/11 §5.5).
 *
 * 백엔드는 VM 에 직접 접속하지 않고 Tumblebug 원격 명령을 VM 한 대({@code ?nodeId=})에만 보낸다. 다운로드가 있어 수 분 걸리므로
 * 요청은 에이전트 행을 INSTALLING 으로 두고 바로 돌려주며, 실제 명령은 백그라운드 스레드가 보내고 결과를 행에 남긴다
 * (INSTALLED·FAILED + 단계·사유). 행이 없으면 만든다 — VM 에이전트는 항상 PULL 이라, 설치가 끝나면 VM 의 Alloy 가 tb 키로
 * 목록 API 를 가져가기 시작한다. 같은 VM 에 다시 부르면 재설치(멱등)이며, 진행 중이면 409 다.
 */
@Service
public class VmAgentInstallService {

    private static final Logger log = LoggerFactory.getLogger(VmAgentInstallService.class);
    /** 백엔드 재기동 등으로 INSTALLING 에 멈춘 행을 다시 설치할 수 있게 보는 여유(원격 명령 제한 시간 + 이 값) */
    static final long STALE_GRACE_MINUTES = 5;

    private final TumblebugPanelSupport support;
    private final TumblebugClient tumblebug;
    private final AgentNodeRepository nodes;
    private final AgentTargetsService agentTargets;
    private final AgentProperties properties;
    private final VmAgentInstallCommand command;
    private final TransactionTemplate tx;
    private final Executor executor;

    @Autowired
    public VmAgentInstallService(TumblebugPanelSupport support, TumblebugClient tumblebug, AgentNodeRepository nodes,
                                 AgentTargetsService agentTargets, AgentProperties properties, TransactionTemplate tx) {
        this(support, tumblebug, nodes, agentTargets, properties, VmAgentInstallCommand.fromClasspath(), tx, newExecutor());
    }

    VmAgentInstallService(TumblebugPanelSupport support, TumblebugClient tumblebug, AgentNodeRepository nodes,
                          AgentTargetsService agentTargets, AgentProperties properties, VmAgentInstallCommand command,
                          TransactionTemplate tx, Executor executor) {
        this.support = support;
        this.tumblebug = tumblebug;
        this.nodes = nodes;
        this.agentTargets = agentTargets;
        this.properties = properties;
        this.command = command;
        this.tx = tx;
        this.executor = executor;
    }

    /**
     * 설치를 시작한다. 스코프 밖·없는 VM 404, Running 이 아니면 409, 설치 설정(두 URL)이 비면 503, 이미 진행 중이면 409.
     * 응답은 시작 직후 상태(INSTALLING)이며, 결과는 {@link #status} 로 본다.
     */
    public VmAgentInstallResponse install(String roleName, String nsId, String infraId, String vmId) {
        TbNode vm = support.accessibleNode(roleName, nsId, infraId, vmId);
        if (!"Running".equalsIgnoreCase(vm.status())) {
            throw BusinessException.conflict("실행 중(Running)인 VM 에만 설치할 수 있습니다. 현재 상태: " + vm.status());
        }
        AgentProperties.VmInstall settings = properties.getVmInstall();
        if (isBlank(settings.getLokiPushUrl()) || isBlank(settings.getTargetsUrl())) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "VM 에이전트 설치 설정이 비어 있습니다(AIMS_AGENT_VM_LOKI_PUSH_URL, AIMS_AGENT_VM_TARGETS_URL).");
        }
        CollectOwner owner = CollectOwner.vm(nsId, infraId, vmId);
        Map<String, Object> body;
        try {
            body = command.requestBody(owner, settings);
        } catch (IllegalArgumentException e) {
            throw BusinessException.badRequest(e.getMessage());
        }

        Long agentId = tx.execute(s -> begin(owner, settings.getTimeoutMinutes()));
        try {
            executor.execute(() -> run(agentId, owner, body));
        } catch (RejectedExecutionException e) {
            finish(agentId, Outcome.failed(VmAgentInstallCommand.STEP_SEND, "설치 작업을 시작하지 못했습니다(서버 종료 중)."));
        }
        log.info("VM 에이전트 설치 시작: {}", owner.label());
        return tx.execute(s -> nodes.findById(agentId).map(VmAgentInstallResponse::of).orElseGet(VmAgentInstallResponse::notInstalled));
    }

    /** 이 VM 의 설치 상태. 에이전트 행이 없으면 NOT_INSTALLED */
    public VmAgentInstallResponse status(String roleName, String nsId, String infraId, String vmId) {
        support.accessibleNode(roleName, nsId, infraId, vmId);
        return tx.execute(s -> nodes.findByTbNsAndTbInfraAndTbNode(nsId, infraId, vmId)
                .map(VmAgentInstallResponse::of)
                .orElseGet(VmAgentInstallResponse::notInstalled));
    }

    /** 에이전트 행을 찾거나 만들어 INSTALLING 으로 둔다 — 같은 VM 의 설치가 진행 중이면 409 */
    private Long begin(CollectOwner owner, int timeoutMinutes) {
        AgentNode node = nodes.findByTbNsAndTbInfraAndTbNode(owner.tbNs(), owner.tbInfra(), owner.tbNode())
                .orElseGet(() -> AgentNode.forVm(owner));
        if (node.getInstallStatus() == AgentInstallStatus.INSTALLING && !isStale(node, timeoutMinutes)) {
            throw BusinessException.conflict("이 VM 의 에이전트 설치가 이미 진행 중입니다(단계: " + node.getInstallStep() + ").");
        }
        if (node.getTransport() != AgentTransport.PULL) {
            node.configurePull(null);
        }
        node.markInstall(AgentInstallStatus.INSTALLING, VmAgentInstallCommand.STEP_SEND, null, null);
        return nodes.save(node).getId();
    }

    /** 백그라운드 — 원격 명령을 보내고(응답까지 대기) 결과를 행에 남긴다. 성공하면 원하는 목록 해시를 계산해 둔다 */
    void run(Long agentId, CollectOwner owner, Map<String, Object> body) {
        Outcome outcome;
        try {
            Map<String, Object> response = tumblebug.sendNodeCommand(owner.tbNs(), owner.tbInfra(), owner.tbNode(), body);
            outcome = VmAgentInstallCommand.parse(response, owner.tbNode());
        } catch (Exception e) {
            outcome = Outcome.failed(VmAgentInstallCommand.STEP_SEND, "Tumblebug 원격 명령 실패: " + e.getMessage());
        }
        finish(agentId, outcome);
        if (outcome.success()) {
            log.info("VM 에이전트 설치 완료: {} {}", owner.label(), outcome.version());
            try {
                agentTargets.syncOwner(owner);
            } catch (Exception e) {
                log.warn("VM 에이전트 목록 해시 계산 실패(재적용 스케줄러가 다시 시도): {} — {}", owner.label(), e.getMessage());
            }
        } else {
            log.warn("VM 에이전트 설치 실패: {} step={} — {}", owner.label(), outcome.step(), outcome.error());
        }
    }

    private void finish(Long agentId, Outcome outcome) {
        tx.executeWithoutResult(s -> nodes.findById(agentId).ifPresent(node -> {
            if (outcome.success()) {
                node.markInstall(AgentInstallStatus.INSTALLED, outcome.step(), null, outcome.version());
            } else {
                node.markInstall(AgentInstallStatus.FAILED, outcome.step(), outcome.error(), null);
            }
        }));
    }

    private static boolean isStale(AgentNode node, int timeoutMinutes) {
        OffsetDateTime updatedAt = node.getUpdatedAt();
        return updatedAt != null
                && updatedAt.isBefore(OffsetDateTime.now().minusMinutes(Math.max(1, timeoutMinutes) + STALE_GRACE_MINUTES));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ExecutorService newExecutor() {
        AtomicInteger seq = new AtomicInteger();
        return Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "vm-agent-install-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        if (executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }
}
