package com.innogrid.aims.monitoring.service.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 원장 변경 커밋 뒤 그 노드의 목록을 즉시 반영한다. 롤백된 변경은 반영하지 않는다.
 * {@link AgentTargetsService#sync} 는 REQUIRES_NEW 라 커밋된 트랜잭션 밖에서 자기 트랜잭션으로 원장을 갱신한다.
 * 예외는 삼킨다 — 반영 실패가 서비스 등록 응답을 바꾸지 않으며, 재적용 스케줄러가 다시 시도한다.
 */
@Component
public class CollectServicesChangedListener {

    private static final Logger log = LoggerFactory.getLogger(CollectServicesChangedListener.class);

    private final AgentTargetsService targets;

    public CollectServicesChangedListener(AgentTargetsService targets) {
        this.targets = targets;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(CollectServicesChangedEvent event) {
        try {
            targets.sync(event.assetId());
        } catch (RuntimeException e) {
            log.warn("수집 대상 목록 반영 트리거 실패: asset={} — {}", event.assetId(), e.getMessage());
        }
    }
}
