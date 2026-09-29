package com.innogrid.aims.monitoring.service.agent;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 재적용 스케줄러 — 목록 전송 대상 노드 전부를 주기적으로 동기화한다(기본 60초, aims.agent.reconcile-interval-seconds).
 * 커밋 후 트리거가 실패했거나, 노드의 파일이 지워졌거나, 백엔드 재시작 직후에도 이 주기 안에 원장과 파일이 다시 맞는다.
 * 노드별 파일 해시를 비교해 다를 때만 쓰므로 변경이 없으면 파일을 건드리지 않는다.
 */
@Component
public class AgentTargetsReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(AgentTargetsReconcileJob.class);

    private final AgentTargetsService targets;

    public AgentTargetsReconcileJob(AgentTargetsService targets) {
        this.targets = targets;
    }

    @Scheduled(fixedDelayString = "${aims.agent.reconcile-interval-seconds:60}", initialDelay = 30, timeUnit = TimeUnit.SECONDS)
    public void reconcile() {
        for (Long assetId : candidates()) {
            try {
                targets.sync(assetId);            // 노드마다 자기 트랜잭션(REQUIRES_NEW). 한 노드 실패가 다른 노드를 막지 않는다
            } catch (RuntimeException e) {
                log.warn("수집 대상 목록 재적용 실패: asset={} — {}", assetId, e.getMessage());
            }
        }
    }

    private java.util.List<Long> candidates() {
        try {
            return targets.syncCandidates();
        } catch (RuntimeException e) {
            log.warn("수집 대상 노드 조회 실패: {}", e.getMessage());
            return java.util.List.of();
        }
    }
}
