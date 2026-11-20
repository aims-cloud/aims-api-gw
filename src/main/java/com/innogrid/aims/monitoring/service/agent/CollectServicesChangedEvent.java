package com.innogrid.aims.monitoring.service.agent;

import com.innogrid.aims.monitoring.entity.CollectOwner;

/** 수집 서비스 원장(생성·수정·삭제)이 바뀐 소유자(물리 노드 또는 VM) — 트랜잭션 커밋 뒤 목록 반영을 일으킨다 */
public record CollectServicesChangedEvent(CollectOwner owner) {

    /** 물리 노드(자산) 원장 변경 */
    public CollectServicesChangedEvent(Long assetId) {
        this(CollectOwner.asset(assetId));
    }
}
