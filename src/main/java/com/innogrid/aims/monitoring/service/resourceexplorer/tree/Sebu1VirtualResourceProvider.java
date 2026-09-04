package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.util.List;

import com.innogrid.aims.infra.entity.Asset;

/**
 * 1세부 가상화 플랫폼의 물리 Host ↔ 가상자원(VM) 관계 제공자 (docs/06 §4.1, §9.2, §9.3).
 *
 * 물리 호스트는 {@code Asset.source + Asset.externalId} 로 1세부 Host ID 와 동기화되며,
 * 1세부 응답의 Host ID 가 {@code Asset.externalId} 와 일치할 때만 하위 가상자원을 연결한다.
 * Host ID 가 없거나 매핑되지 않으면 hostname/IP 로 추정하지 않고 빈 목록을 돌려준다.
 */
public interface Sebu1VirtualResourceProvider {

    /**
     * 1세부 가상자원.
     *
     * @param externalId 1세부가 제공하는 VM UUID 등 불변 자원 ID — 트리 resourceId 는 {@code SEBU1:{externalId}}
     * @param name       표시명 (식별 키로 사용하지 않는다)
     * @param status     1세부 가상화 플랫폼의 VM 실행 상태
     * @param instance   VM 내부 node_exporter 의 Thanos instance 라벨 값. 없으면 모니터링 항목을 생성하지 않는다
     */
    record VirtualResource(String externalId, String name, String status, String instance) {}

    /** 해당 물리 호스트(Asset)에서 실행 중인 1세부 VM 목록. 미매핑이면 빈 목록. */
    List<VirtualResource> findByHost(Asset host);
}
