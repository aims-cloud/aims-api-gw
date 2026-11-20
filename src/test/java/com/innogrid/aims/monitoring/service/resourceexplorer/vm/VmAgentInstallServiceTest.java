package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** VM 설치 시작 조건(404·409·503), 에이전트 행 생성·재사용, 백그라운드 결과 기록(성공·실패·전송 오류), 상태 조회. */
class VmAgentInstallServiceTest {

    TumblebugPanelSupport support = mock(TumblebugPanelSupport.class);
    TumblebugClient tumblebug = mock(TumblebugClient.class);
    AgentNodeRepository nodes = mock(AgentNodeRepository.class);
    AgentTargetsService agentTargets = mock(AgentTargetsService.class);
    AgentProperties properties = new AgentProperties();
    TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
    List<Runnable> queued = new ArrayList<>();
    VmAgentInstallService service = new VmAgentInstallService(support, tumblebug, nodes, agentTargets, properties,
            VmAgentInstallCommand.fromClasspath(), tx, queued::add);
    CollectOwner owner = CollectOwner.vm("ns-1", "infra-1", "vm-1");
    AgentNode saved;

    @BeforeEach void setup() {
        properties.getVmInstall().setLokiPushUrl("http://10.0.0.179:3100/loki/api/v1/push");
        properties.getVmInstall().setTargetsUrl("http://10.0.0.179:8080/api/v1/agent/targets");
        properties.getVmInstall().setRemoteWriteUrl("http://10.0.0.179:19291/api/v1/receive");
        when(support.accessibleNode(any(), eq("ns-1"), eq("infra-1"), eq("vm-1"))).thenReturn(vm("Running"));
        when(nodes.save(any(AgentNode.class))).thenAnswer(inv -> {
            saved = inv.getArgument(0);
            if (saved.getId() == null) {
                ReflectionTestUtils.setField(saved, "id", 7L);
            }
            return saved;
        });
        when(nodes.findById(7L)).thenAnswer(inv -> Optional.ofNullable(saved));
    }

    static TbNode vm(String status) {
        return new TbNode("vm-1", "vm-1", status, "g1", "openstack", "101.79.72.62", "10.18.11.250");
    }

    static Map<String, Object> result(String stdout, String error) {
        return Map.of("results", List.of(Map.of("nodeId", "vm-1", "stdout", Map.of("0", stdout), "stderr", Map.of(), "error", error)));
    }

    @Test void startCreatesPullAgentRowAndRunsInBackground() {
        when(nodes.findByTbNsAndTbInfraAndTbNode("ns-1", "infra-1", "vm-1")).thenReturn(Optional.empty());
        VmAgentInstallResponse started = service.install("ADMIN", "ns-1", "infra-1", "vm-1");

        assertThat(started.installStatus()).isEqualTo(AgentInstallStatus.INSTALLING);
        assertThat(started.installStep()).isEqualTo("SEND_COMMAND");
        assertThat(saved.getTransport()).isEqualTo(AgentTransport.PULL);
        assertThat(saved.owner()).isEqualTo(owner);
        verifyNoInteractions(tumblebug);                                                  // 요청 스레드는 명령을 보내지 않는다
        assertThat(queued).hasSize(1);

        when(tumblebug.sendNodeCommand(eq("ns-1"), eq("infra-1"), eq("vm-1"), anyMap()))
                .thenReturn(result("AIMS_STEP=VERIFY\nAIMS_AGENT_OK alloy=v1.20.0 node_exporter=1.9.1\n", ""));
        queued.get(0).run();
        assertThat(saved.getInstallStatus()).isEqualTo(AgentInstallStatus.INSTALLED);
        assertThat(saved.getInstallStep()).isEqualTo("DONE");
        assertThat(saved.getAgentVersion()).isEqualTo("alloy=v1.20.0 node_exporter=1.9.1");
        assertThat(saved.getInstalledAt()).isNotNull();
        verify(agentTargets).syncOwner(owner);                                            // 원하는 목록 해시를 미리 둔다
    }

    @Test void scriptFailureIsRecordedWithStepAndReason() {
        when(nodes.findByTbNsAndTbInfraAndTbNode("ns-1", "infra-1", "vm-1")).thenReturn(Optional.empty());
        service.install(null, "ns-1", "infra-1", "vm-1");
        when(tumblebug.sendNodeCommand(any(), any(), any(), anyMap()))
                .thenReturn(result("AIMS_STEP=ALLOY\nAIMS_ERROR=Alloy 내려받기 실패(github.com 아웃바운드 확인)\n", "exit status 1"));
        queued.get(0).run();
        assertThat(saved.getInstallStatus()).isEqualTo(AgentInstallStatus.FAILED);
        assertThat(saved.getInstallStep()).isEqualTo("ALLOY");
        assertThat(saved.getInstallError()).contains("github.com");
        verifyNoInteractions(agentTargets);
    }

    @Test void tumblebugCallFailureIsSendCommandFailure() {
        when(nodes.findByTbNsAndTbInfraAndTbNode("ns-1", "infra-1", "vm-1")).thenReturn(Optional.empty());
        service.install(null, "ns-1", "infra-1", "vm-1");
        when(tumblebug.sendNodeCommand(any(), any(), any(), anyMap())).thenThrow(new ResourceAccessException("Connection refused"));
        queued.get(0).run();
        assertThat(saved.getInstallStatus()).isEqualTo(AgentInstallStatus.FAILED);
        assertThat(saved.getInstallStep()).isEqualTo("SEND_COMMAND");
        assertThat(saved.getInstallError()).contains("Connection refused");
    }

    @Test void requestBodyTargetsOnlyThisVm() {
        when(nodes.findByTbNsAndTbInfraAndTbNode("ns-1", "infra-1", "vm-1")).thenReturn(Optional.empty());
        service.install(null, "ns-1", "infra-1", "vm-1");
        when(tumblebug.sendNodeCommand(any(), any(), any(), anyMap())).thenReturn(result("AIMS_AGENT_OK v", ""));
        queued.get(0).run();
        verify(tumblebug).sendNodeCommand(eq("ns-1"), eq("infra-1"), eq("vm-1"),
                argThat(body -> body.get("command") instanceof List<?> list && list.size() == 1 && body.get("timeoutMinutes").equals(15)));
        verify(tumblebug, never()).sendCommand(any(), any(), any());                     // Infra 전체 명령은 쓰지 않는다
    }

    @Test void existingRowIsReusedButConcurrentInstallIs409UnlessStale() {
        AgentNode existing = AgentNode.forVm(owner);
        ReflectionTestUtils.setField(existing, "id", 7L);
        existing.markInstall(AgentInstallStatus.INSTALLING, "ALLOY", null, null);
        ReflectionTestUtils.setField(existing, "updatedAt", OffsetDateTime.now().minusMinutes(1));
        when(nodes.findByTbNsAndTbInfraAndTbNode("ns-1", "infra-1", "vm-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.install(null, "ns-1", "infra-1", "vm-1"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(queued).isEmpty();

        ReflectionTestUtils.setField(existing, "updatedAt", OffsetDateTime.now().minusMinutes(60));   // 재기동 등으로 멈춘 행
        service.install(null, "ns-1", "infra-1", "vm-1");
        assertThat(saved).isSameAs(existing);
        assertThat(queued).hasSize(1);
    }

    @Test void notRunningVmIs409AndMissingSettingsIs503() {
        when(support.accessibleNode(any(), eq("ns-1"), eq("infra-1"), eq("vm-1"))).thenReturn(vm("Suspended"));
        assertThatThrownBy(() -> service.install(null, "ns-1", "infra-1", "vm-1"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        when(support.accessibleNode(any(), eq("ns-1"), eq("infra-1"), eq("vm-1"))).thenReturn(vm("Running"));
        properties.getVmInstall().setTargetsUrl(" ");
        assertThatThrownBy(() -> service.install(null, "ns-1", "infra-1", "vm-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        properties.getVmInstall().setTargetsUrl("http://10.0.0.179:8080/api/v1/agent/targets");
        properties.getVmInstall().setRemoteWriteUrl("");                                       // 지표 전송 주소도 필수
        assertThatThrownBy(() -> service.install(null, "ns-1", "infra-1", "vm-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(nodes, never()).save(any());
    }

    @Test void outOfScopeVmPropagates404() {
        when(support.accessibleNode(any(), eq("ns-1"), eq("infra-1"), eq("vm-x")))
                .thenThrow(new BusinessException(HttpStatus.NOT_FOUND, "가상머신을 찾을 수 없습니다: vm-x"));
        assertThatThrownBy(() -> service.install(null, "ns-1", "infra-1", "vm-x")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.status(null, "ns-1", "infra-1", "vm-x")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(nodes);
    }

    @Test void statusIsNotInstalledWithoutAgentRow() {
        when(nodes.findByTbNsAndTbInfraAndTbNode("ns-1", "infra-1", "vm-1")).thenReturn(Optional.empty());
        assertThat(service.status(null, "ns-1", "infra-1", "vm-1").installStatus()).isEqualTo(AgentInstallStatus.NOT_INSTALLED);

        AgentNode row = AgentNode.forVm(owner);
        row.markInstall(AgentInstallStatus.FAILED, "VERIFY", "에이전트가 60초 안에 준비되지 않았습니다", null);
        when(nodes.findByTbNsAndTbInfraAndTbNode("ns-1", "infra-1", "vm-1")).thenReturn(Optional.of(row));
        VmAgentInstallResponse failed = service.status(null, "ns-1", "infra-1", "vm-1");
        assertThat(failed.installStatus()).isEqualTo(AgentInstallStatus.FAILED);
        assertThat(failed.installStep()).isEqualTo("VERIFY");
        assertThat(failed.installError()).contains("60초");
    }
}
