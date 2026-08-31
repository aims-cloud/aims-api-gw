package com.innogrid.aims.monitoring.dto.resourceexplorer.vm;

/**
 * VM 상세 개요 탭의 VM 정보 표.
 * (화면 라벨은 "노드 정보" 로 표기돼 있으나 디자인 잔재로 보고 VM 정보로 정의)
 * provider / platformType 은 표 우상단 배지 노출에 사용한다.
 */
public record VmInfoResponse(
        String vmId,                    // 예: d8ae0a4d-1d11-4685-bef0-... (UUID)
        String name,                    // 가상 시스템명 (예: vm01)
        String deploymentId,            // 배포영역 (예: Openstack-GPU)
        String project,                 // 프로젝트 (예: admin)
        String ip,
        String spec,                    // 사양 (예: 8-16-100-gpu)
        String network,
        String nodeName,                // 실행 중인 호스트 노드명
        String image,
        String catalog,
        String systemStatus,            // TODO:: 시스템 상태 값 체계 확정 후 enum 전환 (접수완료 등)
        String provider,
        String platformType
) {
}
