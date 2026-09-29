package com.innogrid.aims.monitoring.service.agent;

/** 수집 서비스 원장(생성·수정·삭제)이 바뀐 노드 — 트랜잭션 커밋 뒤 목록 반영을 일으킨다 */
public record CollectServicesChangedEvent(Long assetId) {
}
