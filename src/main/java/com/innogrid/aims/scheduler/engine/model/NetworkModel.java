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
 * shortfall = required &gt; 0 ∧ linkBw &gt; 0 ? clamp0to1((required − linkBw) / required) : 0
 * cost      = min(1, latencyMs / latencyScaleMs) + packetLoss / 100 + (DEGRADED ? degradedPenalty : 0)
 *             + shortfall × bandwidthPenalty
 * linkScore = 1 / (1 + cost)            같은 MDC → 1.0 · 미등록 원격 쌍 → unknownLinkScore (기본 0.5)
 * netScore  = local × (1 − linkWeight) + linkScore × linkWeight
 * </pre>
 * 예: 20 ms UP → 0.714 · 20 ms DEGRADED → 0.588 · 50 ms 이상 UP → 0.5. DOWN 은 제약(C5)이 제외하므로
 * 점수는 0 으로 두되 실제로 쓰이지 않는다.
 *
 * <p><b>대역폭은 요구가 있을 때만 본다.</b> 원장은 대역폭을 저장해 두고도 점수에 쓰지 않았는데, 워크로드 쪽에
 * "얼마가 필요한가"가 없어 견줄 대상이 없었기 때문이다. 이제 워크로드의 요구 대역폭과 링크 대역폭을 견주어
 * <b>모자란 비율만큼</b> 비용을 더한다 — 요구를 주지 않았거나(null) 원장에 대역폭이 없으면(null) 항은 0 이라
 * 예전 점수 그대로다. 제약이 아니라 점수이므로 대역폭이 모자라도 배치는 된다. 예: 요구 1000 · 링크 500 ·
 * 20 ms UP → 부족분 0.5 → cost 0.9 → 0.526.</p>
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

    /**
     * 대역폭 부족분(0~1)에 곱하는 가산 비용 — 전부 모자란 링크가 지연 기준값(50ms)과 같은 비용을 물게 하는 눈금.
     *
     * <p><b>설정 노브가 아니라 상수다</b>({@link #SAME_MDC_LINK_SCORE} 와 같은 자리). 노브로 두면 설정
     * 다이제스트가 움직여 판정 기준선을 다시 떠야 하는데, 이 항은 워크로드가 요구 대역폭을 준 배치에서만
     * 켜지기 때문이다. 되돌림은 <b>새로 만드는 워크로드에 요구를 주지 않는 것</b>이고, 이미 요구를 받은
     * 행은 워크로드 수정 API 가 없어 값을 지우려면 원장을 직접 고쳐야 한다 — 전역으로 끄는 길은 정책의
     * 친화도를 내리는 것뿐인데 그러면 지연·열화 점수와 단절 제외까지 함께 꺼진다.</p>
     *
     * <p><b>이 상수는 설정 다이제스트에 실리지 않는다.</b> 값을 바꾸면 배치 식이 달라지는데 응답의
     * {@code configDigest} 는 그대로라, 기준선 B 와 새 실행 N 이 "같은 설정"으로 보인다. 그래서 이 값을
     * 바꾸는 변경은 <b>판정 기준선 재취득 사유</b>이고, 그때 노브로 올려 정규 문자열에 편입한다.</p>
     */
    public static final double BANDWIDTH_PENALTY = 1.0;

    private final SchedulerProperties.Network network;

    @Autowired
    public NetworkModel(SchedulerProperties properties) {
        this.network = properties.getNetwork();
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

    /** 요구 대역폭 없이 보는 링크 비용 — 부족분 항이 0 이라 이 항이 생기기 전과 같다. 엔진은 이 자리를 쓰지 않는다. */
    public double linkCost(LinkMatrix.Quality q) {
        return linkCost(q, null);
    }

    /**
     * 링크 하나의 비용. 지연은 기준(ms)으로 정규화해 1 에서 자르고, 손실률·DEGRADED 가산에 대역폭 부족분을 더한다.
     *
     * @param requiredBandwidthMbps 워크로드가 요구하는 대역폭. null 이거나 0 이하면 부족분 항은 0
     */
    public double linkCost(LinkMatrix.Quality q, Double requiredBandwidthMbps) {
        double latency = Math.min(1.0, Math.max(0.0, q.latencyMs()) / network.getLatencyScaleMs());
        double loss = Math.max(0.0, q.lossOrZero()) / 100.0;
        double degraded = q.status() == MdcLink.LinkStatus.DEGRADED
                ? network.getDegradedPenalty() : 0.0;
        return latency + loss + degraded + bandwidthCost(q.bandwidthMbps(), requiredBandwidthMbps);
    }

    /**
     * 대역폭 부족분 비용. 요구와 링크 대역폭이 모두 양수일 때만 계산하고, 모자란 비율을 [0, 1] 로 잘라 벌점을 곱한다.
     *
     * <p>한쪽이라도 없거나(null) <b>유한한 양수가 아니면</b> 0 이다 — 모르는 값으로 후보를 깎지 않는다.
     * 유한성을 따로 막는 이유는 {@code (∞ − link) / ∞} 가 NaN 이고, NaN 총점은 반올림({@code Math.round})에서
     * 0.0 으로 삼켜져 <b>오류가 아니라 최하위 점수</b>로 보이기 때문이다 — 미등록 점수 설정이 같은 이유로
     * 유한성을 막는다({@link #unknownLinkScore()}).</p>
     *
     * <p>모르는 값으로 후보를 깎지 않는다. 링크가 요구를
     * 충족하면 잉여는 보너스가 되지 않는다(하한 0). 위쪽은 자를 필요가 없다 — 두 값이 양수이므로
     * {@code (required − link) / required} 는 1 <b>미만</b>이고, 링크가 0 에 가까울수록 1 에 붙는다.
     * 그래서 가산은 {@link #BANDWIDTH_PENALTY} 를 넘지 않는다.</p>
     */
    public double bandwidthCost(Double linkBandwidthMbps, Double requiredBandwidthMbps) {
        if (linkBandwidthMbps == null || requiredBandwidthMbps == null) return 0.0;
        double link = linkBandwidthMbps;
        double required = requiredBandwidthMbps;
        if (!(link > 0) || !(required > 0)) return 0.0;
        if (!Double.isFinite(link) || !Double.isFinite(required)) return 0.0;
        double shortfall = Math.max(0.0, (required - link) / required);
        return shortfall * BANDWIDTH_PENALTY;
    }

    /** 요구 대역폭 없이 보는 링크 점수 — 이 항이 생기기 전과 같다. 엔진은 이 자리를 쓰지 않는다. */
    public double linkScore(LinkMatrix.Quality q) {
        return linkScore(q, null);
    }

    /** 링크 하나의 점수 (0~1). DOWN 은 0. */
    public double linkScore(LinkMatrix.Quality q, Double requiredBandwidthMbps) {
        if (q.isDown()) return 0.0;
        return 1.0 / (1.0 + linkCost(q, requiredBandwidthMbps));
    }

    /**
     * 데이터 MDC 와 노드 MDC 사이의 링크 점수.
     *
     * <p>세 갈래다. 같은 MDC 는 {@link #SAME_MDC_LINK_SCORE}. 원장에 링크가 있으면 그 품질로 계산한다. 그 외
     * — 원장에 없는 쌍, MDC 라벨이 없는 노드(수집기는 {@code "unknown"} 문자열을 준다), 비어 있는 스냅샷 — 는
     * {@link #unknownLinkScore()}. {@code "unknown"} 은 특별 취급하지 않는 평범한 MDC 이름이다. 행렬 자체가
     * 같은 MDC 와 미등록을 모두 빈 값으로 돌리므로 같은 MDC 판정은 행렬을 보기 전에 여기서 한다.</p>
     */
    public double linkScore(String dataMdcId, String nodeMdcId, LinkMatrix matrix) {
        return linkScore(dataMdcId, nodeMdcId, matrix, null);
    }

    /**
     * 요구 대역폭을 반영한 링크 점수. 같은 MDC 는 링크를 타지 않으므로 요구와 무관하게
     * {@link #SAME_MDC_LINK_SCORE} 이고, 미등록 쌍은 견줄 대역폭이 없어 {@link #unknownLinkScore()} 그대로다.
     */
    public double linkScore(String dataMdcId, String nodeMdcId, LinkMatrix matrix, Double requiredBandwidthMbps) {
        if (dataMdcId != null && dataMdcId.equals(nodeMdcId)) return SAME_MDC_LINK_SCORE;
        Optional<LinkMatrix.Quality> q = matrix == null ? Optional.empty() : matrix.between(dataMdcId, nodeMdcId);
        return q.map(v -> linkScore(v, requiredBandwidthMbps)).orElseGet(this::unknownLinkScore);
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
