package com.innogrid.aims.infra.dto;

import java.util.List;

/**
 * 가상화 자산 응답 DTO.
 * 상위: 호스트(하이퍼바이저) 요약 / 하위: 호스트가 품은 가상서버.
 *
 * OpenStack에 없는 vCenter 전용 필드(시리얼/데이터스토어/클러스터)는 null로 둔다.
 * 배포영역은 provider(openstack/aws/azure/gcp...) 단위로, 추후 CB-Spider 퍼블릭 클라우드도 같은 형태로 합류한다.
 */
public final class VirtualAssetDtos {

    private VirtualAssetDtos() {}

    /** 호스트(하이퍼바이저) 행. */
    public record HostSummary(
            String deployZone,        // 배포영역 (openstack zone / spider connection)
            String provider,          // openstack | aws | azure | gcp | vmware ...
            String hostId,            // 하위 VM 조회 키 (하이퍼바이저 hostname)
            String hostName,          // 호스트명 (IP 또는 hostname)
            String alias,             // 별칭 (없으면 null)
            String serialNumber,      // 시리얼 넘버 (OpenStack 없음 → null)
            Integer vmCount,          // 가상서버 수
            Integer datastoreCount,   // 데이터스토어 수 (OpenStack 없음 → null)
            Integer vcore,            // vCore (총 vCPU)
            Integer allocatedCore,    // 할당된 코어 (vcpus_used)
            Long memoryTotalMb,       // 메모리 총량
            Long memoryUsedMb,        // 메모리 사용량
            Long memoryFreeMb) {}     // 메모리 여유

    /** 호스트 하위 가상서버 행. */
    public record HostVm(
            String vmId,
            String vmName,
            String alias,             // 별칭 (없으면 null)
            String status,            // 시스템 상태 (Powered On/Off = ACTIVE/SHUTOFF)
            List<String> ipAddresses, // IP 주소
            String cluster,           // 클러스터 (OpenStack 없음 → null, AZ로 근사 가능하나 null 유지)
            List<String> datastores) {} // 데이터스토어 (OpenStack 없음 → null)
}
