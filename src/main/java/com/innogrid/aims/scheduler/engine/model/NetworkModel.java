package com.innogrid.aims.scheduler.engine.model;

import org.springframework.stereotype.Component;

import com.innogrid.aims.scheduler.model.NodeResource;

/**
 * 네트워크 모델 — 배치 판단에 쓰는 네트워크 점수를 계산한다.
 *
 * 현재는 노드 자신의 트래픽 부하만 본다. MDC 간 링크 비용은
 * MdcLink 도입 단계에서 이 클래스에 더한다.
 */
@Component
public class NetworkModel {

    /** 100Mbps 를 기준으로 감쇠시키는 트래픽 점수 (0~1). 한산할수록 높다. */
    private static final double REFERENCE_MBPS = 100.0;

    /**
     * 노드의 현재 트래픽 부하 점수 (0~1).
     * 기존 WeightedScoreScheduler 의 수식을 그대로 옮겼다.
     */
    public double localTrafficScore(NodeResource node) {
        double totalBps = node.getNetworkRxBps() + node.getNetworkTxBps();
        double loadMbps = totalBps / (1024 * 1024);
        return 1.0 / (1.0 + loadMbps / REFERENCE_MBPS);
    }
}
