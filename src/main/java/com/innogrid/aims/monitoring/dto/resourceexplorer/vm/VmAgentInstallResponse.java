package com.innogrid.aims.monitoring.dto.resourceexplorer.vm;

import java.time.OffsetDateTime;
import com.innogrid.aims.monitoring.entity.AgentInstallStatus;
import com.innogrid.aims.monitoring.entity.AgentNode;

/**
 * VM 로그 탭 — 이 VM 의 수집 에이전트(node_exporter + Alloy) 설치 상태 (docs/11 §5.5).
 * 화면은 NOT_INSTALLED 면 "설치" 버튼, INSTALLING 이면 진행 중(installStep), FAILED 면 installError 와 "다시 설치"를 보여 준다.
 * 설치가 끝난 뒤 로그 목록 반영 여부는 {@code GET /vm/services/agent} 로 따로 본다.
 */
public record VmAgentInstallResponse(
        AgentInstallStatus installStatus,
        String installStep,          // 진행 중이면 현재 단계, FAILED 면 실패한 단계(SEND_COMMAND·PREPARE·NODE_EXPORTER·ALLOY·START·VERIFY), 완료면 DONE
        String installError,         // FAILED 사유. 그 외 null
        OffsetDateTime installedAt,  // 마지막 설치 완료 시각
        String agentVersion,         // 예) alloy=v1.20.0 node_exporter=1.9.1
        OffsetDateTime updatedAt     // 상태가 마지막으로 바뀐 시각
) {
    public static VmAgentInstallResponse of(AgentNode node) {
        return new VmAgentInstallResponse(node.getInstallStatus(), node.getInstallStep(), node.getInstallError(),
                node.getInstalledAt(), node.getAgentVersion(), node.getUpdatedAt());
    }

    public static VmAgentInstallResponse notInstalled() {
        return new VmAgentInstallResponse(AgentInstallStatus.NOT_INSTALLED, null, null, null, null, null);
    }
}
