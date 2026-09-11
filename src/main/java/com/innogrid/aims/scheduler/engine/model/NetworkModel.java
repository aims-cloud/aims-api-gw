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
 * linkScore = 1 / (1 + cost)            같은 MDC → 1.0 · 미등록 원격 쌍 → unknownLinkScore (기본 0.5)
 * netScore  = local × (1 − linkWeight) + linkScore × linkWeight
 * </pre>
 * 예: 20 ms UP → 0.714 · 20 ms DEGRADED → 0.588 · 50 ms 이상 UP → 0.5. DOWN 은 제약(C5)이 제외하므로
 * 점수는 0 으로 두되 실제로 쓰이지 않는다.
 *
 * <p><b>미등록 원격 쌍은 같은 MDC 와 다르다.</b> 예전에는 둘을 똑같이 1.0 으로 보았는데, 그러면 측정된 양호
 * 링크(20ms UP 0.714)가 측정되지 않은 경로에 져서 원장을 채운 MDC 가 불리해졌다. 지금은 설정값(기본 0.5,
 * 50ms UP 과 같은 점수)을 준다 — 등록 여부만으로 후보를 버리지는 않되(제약이 아니라 점수), 모르는 경로가
 * 아는 양호 경로를 이기지는 않게. 노드에 MDC 라벨이 없거나 원장 스냅샷이 비어 있어도 같은 값이다.</p>
 */
@Component
public class NetworkModel {

    /** 100Mbps 를 기준으로 감쇠시키는 트래픽 점수 (0~1). 한산할수록 높다. */
    private static final double REFERENCE_MBPS = 100.0;

    /** 데이터와 노드가 같은 MDC 일 때의 링크 점수 — MDC 안에서는 링크를 타지 않는다. */
    public static final double SAME_MDC_LINK_SCORE = 1.0;

    /** 설정의 미등록 점수가 유한하지 않을 때 대신 쓰는 값. 설정 기본값과 같다. */
    static final double DEFAULT_UNKNOWN_LINK_SCORE = 0.5;

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
     * 데이터 MDC 와 노드 MDC 사이의 링크 점수.
     *
     * <p>세 갈래다. 같은 MDC 는 {@link #SAME_MDC_LINK_SCORE}. 원장에 링크가 있으면 그 품질로 계산한다. 그 외
     * — 원장에 없는 쌍, MDC 라벨이 없는 노드, 비어 있는 스냅샷 — 는 {@link #unknownLinkScore()}. 행렬 자체가
     * 같은 MDC 와 미등록을 모두 빈 값으로 돌리므로 같은 MDC 판정은 행렬을 보기 전에 여기서 한다.</p>
     */
    public double linkScore(String dataMdcId, String nodeMdcId, LinkMatrix matrix) {
        if (dataMdcId != null && dataMdcId.equals(nodeMdcId)) return SAME_MDC_LINK_SCORE;
        Optional<LinkMatrix.Quality> q = matrix == null ? Optional.empty() : matrix.between(dataMdcId, nodeMdcId);
        return q.map(this::linkScore).orElseGet(this::unknownLinkScore);
    }

    /** 데이터 MDC 와 노드 MDC 가 다른데 원장에 링크가 있는가. 진단 표기용 — 점수 계산과 같은 조회를 쓴다. */
    public boolean isRegistered(String dataMdcId, String nodeMdcId, LinkMatrix matrix) {
        return matrix != null && matrix.between(dataMdcId, nodeMdcId).isPresent();
    }

    /** 미등록 원격 쌍의 링크 점수. 설정값을 [0, 1] 로 자르고, 유한하지 않으면 기본값. */
    public double unknownLinkScore() {
        double v = network.getUnknownLinkScore();
        if (Double.isNaN(v) || Double.isInfinite(v)) return DEFAULT_UNKNOWN_LINK_SCORE;
        return Math.min(1.0, Math.max(0.0, v));
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
