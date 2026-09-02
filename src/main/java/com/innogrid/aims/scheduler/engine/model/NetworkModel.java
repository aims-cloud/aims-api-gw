package com.innogrid.aims.scheduler.engine.model;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.entity.MdcLink;
import com.innogrid.aims.scheduler.model.LinkMatrix;
import com.innogrid.aims.scheduler.model.NodeResource;

/**
 * 네트워크 모델 — 배치 판단에 쓰는 네트워크 점수를 계산한다.
 *
 * <p>두 가지를 본다. <b>노드 자신의 트래픽 부하</b>({@link #localTrafficScore})는 예전부터 있었고,
 * <b>데이터 MDC 와 노드 MDC 사이의 링크 품질</b>({@link #linkScore})은 단계 4-② 에서 더했다.
 * 둘을 {@link #blend} 로 섞는 것은 엔진이 게이트(정책 {@code affinityEnabled} + 워크로드 {@code dataMdcId})가
 * 켜졌을 때만 한다.</p>
 *
 * <pre>
 * cost      = min(1, latencyMs / latencyScaleMs) + packetLoss / 100 + (DEGRADED ? degradedPenalty : 0)
 * linkScore = 1 / (1 + cost)            같은 MDC · 미등록 쌍 → 1.0 (중립)
 * netScore  = local × (1 − linkWeight) + linkScore × linkWeight
 * </pre>
 * 예: 20 ms UP → 0.714 · 20 ms DEGRADED → 0.588 · 50 ms 이상 UP → 0.5. DOWN 은 제약(C5)이 제외하므로
 * 점수는 0 으로 두되 실제로 쓰이지 않는다.
 */
@Component
public class NetworkModel {

    /** 100Mbps 를 기준으로 감쇠시키는 트래픽 점수 (0~1). 한산할수록 높다. */
    private static final double REFERENCE_MBPS = 100.0;

    /** 링크가 없거나 같은 MDC 일 때의 링크 점수 — 모르는 값으로 순위를 바꾸지 않는다. */
    public static final double NEUTRAL_LINK_SCORE = 1.0;

    private final SchedulerProperties.Network network;

    @Autowired
    public NetworkModel(SchedulerProperties properties) {
        this.network = properties.getNetwork();
    }

    /** 스프링 컨텍스트 없이 쓰는 기본 생성자 (단위 테스트). 설정 기본값을 사용한다. */
    public NetworkModel() {
        this(new SchedulerProperties());
    }

    /**
     * 노드의 현재 트래픽 부하 점수 (0~1).
     * 기존 WeightedScoreScheduler 의 수식을 그대로 옮겼다.
     */
    public double localTrafficScore(NodeResource node) {
        double totalBps = node.getNetworkRxBps() + node.getNetworkTxBps();
        double loadMbps = totalBps / (1024 * 1024);
        return 1.0 / (1.0 + loadMbps / REFERENCE_MBPS);
    }

    /** 링크 하나의 비용. 지연은 기준(ms)으로 정규화해 1 에서 자르고, 손실률과 DEGRADED 가산을 더한다. */
    public double linkCost(LinkMatrix.Quality q) {
        double latency = Math.min(1.0, Math.max(0.0, q.latencyMs()) / network.getLatencyScaleMs());
        double loss = Math.max(0.0, q.lossOrZero()) / 100.0;
        double degraded = q.status() == MdcLink.LinkStatus.DEGRADED
                ? network.getDegradedPenalty() : 0.0;
        return latency + loss + degraded;
    }

    /** 링크 하나의 점수 (0~1). DOWN 은 0. */
    public double linkScore(LinkMatrix.Quality q) {
        if (q.isDown()) return 0.0;
        return 1.0 / (1.0 + linkCost(q));
    }

    /**
     * 데이터 MDC 와 노드 MDC 사이의 링크 점수. 같은 MDC·미등록 쌍은 {@link #NEUTRAL_LINK_SCORE}.
     */
    public double linkScore(String dataMdcId, String nodeMdcId, LinkMatrix matrix) {
        Optional<LinkMatrix.Quality> q = matrix == null ? Optional.empty() : matrix.between(dataMdcId, nodeMdcId);
        return q.map(this::linkScore).orElse(NEUTRAL_LINK_SCORE);
    }

    /** 트래픽 점수와 링크 점수를 설정 가중치로 섞는다. 가중치 0 이면 트래픽 점수 그대로. */
    public double blend(double localScore, double linkScore) {
        double w = Math.min(1.0, Math.max(0.0, network.getLinkWeight()));
        return localScore * (1.0 - w) + linkScore * w;
    }

    public double linkWeight() {
        return network.getLinkWeight();
    }
}
