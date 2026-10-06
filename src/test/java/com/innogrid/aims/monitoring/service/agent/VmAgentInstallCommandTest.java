package com.innogrid.aims.monitoring.service.agent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.innogrid.aims.monitoring.entity.CollectOwner;
import com.innogrid.aims.monitoring.service.agent.VmAgentInstallCommand.Outcome;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 설치 명령 조립(base64 로만 값 전달·형식 검사·패키지 리소스)과 Tumblebug 응답 판정(성공 표식·실패 단계·사유 우선순위). */
class VmAgentInstallCommandTest {

    CollectOwner vm = CollectOwner.vm("openstack", "openstack-infra-01", "openstack-nodegroup-01-1");

    static AgentProperties.VmInstall settings() {
        AgentProperties.VmInstall s = new AgentProperties.VmInstall();
        s.setLokiPushUrl("http://10.0.0.179:3100/loki/api/v1/push");
        s.setTargetsUrl("http://10.0.0.179:8080/api/v1/agent/targets");
        return s;
    }

    static List<String> decodedPayloads(String line) {
        Matcher m = Pattern.compile("echo ([A-Za-z0-9+/=]+) \\| base64 -d").matcher(line);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            out.add(new String(Base64.getDecoder().decode(m.group(1)), StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test void commandCarriesScriptEnvAndConfigOnlyAsBase64() {
        VmAgentInstallCommand command = new VmAgentInstallCommand("#!/usr/bin/env bash\r\necho hi\r\n", "loki.write \"default\" {}\r\n");
        Map<String, Object> body = command.requestBody(vm, settings());

        @SuppressWarnings("unchecked")
        List<String> lines = (List<String>) body.get("command");
        assertThat(lines).hasSize(1);
        String line = lines.get(0);
        assertThat(line).doesNotContain("openstack-infra-01").doesNotContain("10.0.0.179")   // 값은 셸 문자열에 직접 들어가지 않는다
                .contains("sudo bash $d/install.sh $d/alloy.env $d/config.alloy").endsWith("exit $rc");
        List<String> payloads = decodedPayloads(line);
        assertThat(payloads).hasSize(3);
        assertThat(payloads.get(0)).isEqualTo("#!/usr/bin/env bash\necho hi\n");             // CRLF 가 섞여도 LF 로
        assertThat(payloads.get(1)).isEqualTo("""
                TB_NS=openstack
                TB_INFRA=openstack-infra-01
                TB_NODE=openstack-nodegroup-01-1
                LOKI_PUSH_URL=http://10.0.0.179:3100/loki/api/v1/push
                AIMS_TARGETS_URL=http://10.0.0.179:8080/api/v1/agent/targets
                ALLOY_VERSION=1.20.0
                NODE_EXPORTER_VERSION=1.9.1
                """);
        assertThat(payloads.get(2)).isEqualTo("loki.write \"default\" {}\n");
        assertThat(body.get("timeoutMinutes")).isEqualTo(15);
        assertThat(body).doesNotContainKey("userName");
    }

    @Test void optionalUserAndTimeoutBounds() {
        AgentProperties.VmInstall s = settings();
        s.setUserName("cb-user");
        s.setTimeoutMinutes(500);
        Map<String, Object> body = new VmAgentInstallCommand("x", "y").requestBody(vm, s);
        assertThat(body.get("userName")).isEqualTo("cb-user");
        assertThat(body.get("timeoutMinutes")).isEqualTo(120);                                 // Tumblebug 상한
    }

    @Test void unsafeValuesAreRejectedBeforeAnyCommandIsBuilt() {
        VmAgentInstallCommand command = new VmAgentInstallCommand("x", "y");
        AgentProperties.VmInstall injected = settings();
        injected.setTargetsUrl("http://10.0.0.179:8080/api/v1/agent/targets;rm -rf /");
        assertThatThrownBy(() -> command.requestBody(vm, injected)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("수집 대상 목록 API 주소");
        assertThatThrownBy(() -> command.requestBody(CollectOwner.vm("ns 1", "infra", "node"), settings()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Namespace id");
        AgentProperties.VmInstall badUser = settings();
        badUser.setUserName("root;id");
        assertThatThrownBy(() -> command.requestBody(vm, badUser)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command.requestBody(CollectOwner.asset(1L), settings())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void packagedResourcesFollowTheOutputAndLabelContract() {
        VmAgentInstallCommand command = VmAgentInstallCommand.fromClasspath();
        List<String> payloads = decodedPayloads(((List<?>) command.requestBody(vm, settings()).get("command")).get(0).toString());
        String script = payloads.get(0);
        String config = payloads.get(2);
        assertThat(script).doesNotContain("\r").startsWith("#!/usr/bin/env bash")
                .contains("AIMS_STEP=", "AIMS_ERROR=", "AIMS_AGENT_OK alloy=v${ALLOY_VERSION} node_exporter=${NODE_EXPORTER_VERSION}")
                .contains("--collector.systemd");                                               // VM 후보 탐지(systemd 유닛)가 쓴다
        assertThat(config).doesNotContain("\r").doesNotContain("/host$1").doesNotContain("discovery.relabel")   // 호스트 프로세스라 접두어 없음
                .contains("tbNs=%s&tbInfra=%s&tbNode=%s", "tb_ns    = sys.env(\"TB_NS\")", "tb_node  = sys.env(\"TB_NODE\")");
        for (String staticPath : new AgentProperties().getRemoteStaticLogPaths()) {
            assertThat(config).contains("\"" + staticPath + "\"");                             // 목록에서 빼는 정적 경로 = 설정이 읽는 경로
        }
    }

    static Map<String, Object> response(String nodeId, Map<String, String> stdout, Map<String, String> stderr, String error) {
        return Map.of("results", List.of(Map.of("nodeId", nodeId, "nodeIp", "101.79.72.62",
                "stdout", stdout, "stderr", stderr, "error", error)));
    }

    @Test void successMarkerWinsAndCarriesVersion() {
        Outcome ok = VmAgentInstallCommand.parse(response("vm-1",
                Map.of("0", "AIMS_STEP=PREPARE\nAIMS_STEP=VERIFY\nAIMS_AGENT_OK alloy=v1.20.0 node_exporter=1.9.1\n"),
                Map.of("0", "sudo: unable to resolve host tb8sursfo0ia00o3c8s1: Name or service not known\n"), ""), "vm-1");
        assertThat(ok.success()).isTrue();
        assertThat(ok.step()).isEqualTo("DONE");
        assertThat(ok.version()).isEqualTo("alloy=v1.20.0 node_exporter=1.9.1");
        assertThat(ok.error()).isNull();
    }

    @Test void failureKeepsLastStepAndBestReason() {
        Outcome scriptError = VmAgentInstallCommand.parse(response("vm-1",
                Map.of("0", "AIMS_STEP=PREPARE\nAIMS_STEP=ALLOY\nAIMS_ERROR=Alloy 내려받기 실패(github.com 아웃바운드 확인)\n"),
                Map.of("0", "curl: (6) Could not resolve host: github.com\n"), "Process exited with status 1"), "vm-1");
        assertThat(scriptError.success()).isFalse();
        assertThat(scriptError.step()).isEqualTo("ALLOY");
        assertThat(scriptError.error()).isEqualTo("Alloy 내려받기 실패(github.com 아웃바운드 확인)");

        Outcome sshError = VmAgentInstallCommand.parse(response("vm-1", Map.of(), Map.of(),
                "failed to connect directly to target Node \"vm-1\" at 101.79.72.62:22"), "vm-1");
        assertThat(sshError.step()).isEqualTo("SEND_COMMAND");                                  // 스크립트가 시작되지 않음
        assertThat(sshError.error()).startsWith("failed to connect directly");

        Outcome stderrOnly = VmAgentInstallCommand.parse(response("vm-1", Map.of("0", "AIMS_STEP=START\n"),
                Map.of("0", "sudo: unable to resolve host x: Name or service not known\nJob for aims-alloy.service failed.\n"), ""), "vm-1");
        assertThat(stderrOnly.step()).isEqualTo("START");
        assertThat(stderrOnly.error()).isEqualTo("Job for aims-alloy.service failed.");        // sudo 경고는 사유에서 뺀다

        Outcome silent = VmAgentInstallCommand.parse(response("vm-1", Map.of("0", "AIMS_STEP=VERIFY\n"), Map.of(), ""), "vm-1");
        assertThat(silent.error()).contains("성공 표식 없음");
    }

    @Test void missingOrForeignResultIsSendFailure() {
        assertThat(VmAgentInstallCommand.parse(Map.of(), "vm-1").step()).isEqualTo("SEND_COMMAND");
        assertThat(VmAgentInstallCommand.parse(null, "vm-1").success()).isFalse();
        Outcome foreign = VmAgentInstallCommand.parse(response("vm-2", Map.of("0", "AIMS_AGENT_OK x"), Map.of(), ""), "vm-1");
        assertThat(foreign.success()).isFalse();
        assertThat(foreign.error()).contains("vm-1");
    }

    @Test void longReasonIsTruncatedForTheColumn() {
        Outcome truncated = Outcome.failed("ALLOY", "x".repeat(5000));
        assertThat(truncated.error()).hasSize(2000);
    }
}
