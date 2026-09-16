package com.innogrid.aims.scheduler.engine.model;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.entity.MdcLink;
import com.innogrid.aims.scheduler.model.LinkMatrix;
import com.innogrid.aims.scheduler.model.NodeResource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 링크 점수 표 — 설계 4-F 의 수치를 그대로 고정한다.
 *
 * <pre>
 * 지연 20ms UP                → cost 0.4          → 0.714
 * 지연 20ms DEGRADED          → cost 0.4 + 0.3    → 0.588
 * 지연 50ms(=기준) UP          → cost 1.0          → 0.5     (50ms 이상은 구별되지 않는다)
 * 지연 100ms UP               → cost 1.0 (상한)   → 0.5
 * 지연 0 손실 10% UP           → cost 0.1          → 0.909
 * DOWN                        → 0 (C5 가 제외하므로 실제로는 쓰이지 않음)
 * 원장 대역폭                  → 비용에 들어가지 않는다 (저장·응답 전용)
 * 같은 MDC                    → 1.0
 * 미등록 원격 · 라벨 없는 노드 · 빈 스냅샷 → unknownLinkScore (기본 0.5 = 50ms UP 과 같다)
 * </pre>
 */
class NetworkModelLinkTest {

    private static final double EPS = 1e-3;
    private final NetworkModel model = new NetworkModel();

    @DisplayName("링크 점수 표")
    @Test
    void link_score_table() {
        assertEquals(0.714, model.linkScore(q(20, 0, MdcLink.LinkStatus.UP)), EPS);
        assertEquals(0.588, model.linkScore(q(20, 0, MdcLink.LinkStatus.DEGRADED)), EPS);
        assertEquals(0.5, model.linkScore(q(50, 0, MdcLink.LinkStatus.UP)), EPS);
        assertEquals(0.5, model.linkScore(q(100, 0, MdcLink.LinkStatus.UP)), EPS, "지연 비용은 1 에서 잘린다");
        assertEquals(0.909, model.linkScore(q(0, 10, MdcLink.LinkStatus.UP)), EPS);
        assertEquals(0.0, model.linkScore(q(1, 0, MdcLink.LinkStatus.DOWN)), EPS);
    }

    @DisplayName("같은 MDC 는 1_0 이고 미등록 원격은 기본 0_5 라 등록된 20ms 링크 아래에 선다")
    @Test
    void same_mdc_1_0_unregistered_remote_0_5() {
        LinkMatrix m = LinkMatrix.of(List.of(new MdcLink("a", "b", 20)));

        assertEquals(1.0, model.linkScore("a", "a", m), EPS, "같은 MDC");
        assertEquals(0.5, model.linkScore("a", "z", m), EPS, "원장에 없는 원격");
        assertEquals(0.5, model.linkScore("a", null, m), EPS, "MDC 라벨이 없는 노드");
        assertEquals(0.5, model.linkScore("a", "b", null), EPS, "행렬이 없으면 등록 여부를 알 수 없다");
        assertEquals(0.5, model.linkScore("a", "b", LinkMatrix.EMPTY), EPS, "빈 스냅샷도 같다");
        assertEquals(1.0, model.linkScore("a", "a", null), EPS, "같은 MDC 판정은 행렬보다 먼저");
        assertEquals(0.714, model.linkScore("b", "a", m), EPS, "방향 무관");
        assertTrue(model.linkScore("a", "b", m) > model.linkScore("a", "z", m), "측정된 양호 링크가 미등록을 이긴다");
    }

    @DisplayName("원장의 대역폭은 링크 비용에 들어가지 않는다")
    @Test
    void ledger_bandwidth_is_not_part_of_the_cost() {
        LinkMatrix wide = LinkMatrix.of(List.of(link(10000.0)));
        LinkMatrix narrow = LinkMatrix.of(List.of(link(10.0)));

        assertEquals(0.714, model.linkScore("a", "b", wide), EPS);
        assertEquals(model.linkScore("a", "b", wide), model.linkScore("a", "b", narrow), EPS,
                "대역폭이 1000배 달라도 같은 점수다 — 원장·응답에만 있고 비용 식에는 없다");
    }

    private static MdcLink link(double bandwidthMbps) {
        MdcLink l = new MdcLink("a", "b", 20);
        l.setBandwidthMbps(bandwidthMbps);
        return l;
    }

    @DisplayName("등록 여부 조회는 점수와 같은 행렬을 본다")
    @Test
    void registered_lookup_matches_score_lookup() {
        LinkMatrix m = LinkMatrix.of(List.of(new MdcLink("a", "b", 20)));

        assertTrue(model.isRegistered("a", "b", m));
        assertTrue(model.isRegistered("b", "a", m), "방향 무관");
        assertFalse(model.isRegistered("a", "z", m));
        assertFalse(model.isRegistered("a", "a", m), "같은 MDC 는 등록 대상이 아니다");
        assertFalse(model.isRegistered("a", "b", null));
    }

    @DisplayName("미등록 점수 설정은 0과 1 사이로 잘리고 유한하지 않으면 기본값이다")
    @Test
    void unknown_link_score_is_clamped_and_finite() {
        assertEquals(0.5, model.unknownLinkScore(), EPS, "기본값");
        assertEquals(0.0, withUnknown(-1).unknownLinkScore(), EPS);
        assertEquals(0.0, withUnknown(0).unknownLinkScore(), EPS);
        assertEquals(0.3, withUnknown(0.3).unknownLinkScore(), EPS);
        assertEquals(1.0, withUnknown(1).unknownLinkScore(), EPS, "1.0 이면 예전 동작(같은 MDC 와 동점)");
        assertEquals(1.0, withUnknown(2).unknownLinkScore(), EPS);
        assertEquals(0.5, withUnknown(Double.NaN).unknownLinkScore(), EPS, "NaN 은 min/max 를 그대로 통과하므로 따로 막는다");
        assertEquals(0.5, withUnknown(Double.POSITIVE_INFINITY).unknownLinkScore(), EPS);
        assertEquals(1.0, withUnknown(1).linkScore("a", "z", LinkMatrix.EMPTY), EPS, "설정 1.0 → 미등록도 1.0");
    }

    private static NetworkModel withUnknown(double v) {
        SchedulerProperties props = new SchedulerProperties();
        props.getNetwork().setUnknownLinkScore(v);
        return new NetworkModel(props);
    }

    @DisplayName("혼합은 설정 가중치를 따르고 0 이면 트래픽 점수 그대로")
    @Test
    void blend_weight_0_keeps_traffic() {
        assertEquals(0.5, model.linkWeight());
        assertEquals(0.857, model.blend(1.0, 0.714), EPS);

        SchedulerProperties props = new SchedulerProperties();
        props.getNetwork().setLinkWeight(0.0);
        assertEquals(0.3, new NetworkModel(props).blend(0.3, 0.0), EPS);

        props.getNetwork().setLinkWeight(1.0);
        assertEquals(0.714, new NetworkModel(props).blend(0.3, 0.714), EPS);
    }

    @DisplayName("기준 지연을 바꾸면 같은 링크의 점수가 달라진다")
    @Test
    void latency_scale_changes_score() {
        SchedulerProperties props = new SchedulerProperties();
        props.getNetwork().setLatencyScaleMs(200);
        assertEquals(1.0 / 1.1, new NetworkModel(props).linkScore(q(20, 0, MdcLink.LinkStatus.UP)), EPS);
    }

    @DisplayName("트래픽 점수는 그대로다")
    @Test
    void traffic_score_unchanged() {
        NodeResource idle = new NodeResource("m", "n", 8, 16384, 0, 10, 10, 0, 0, 0, 0, 0, 0);
        assertEquals(1.0, model.localTrafficScore(idle), EPS);
    }

    private static LinkMatrix.Quality q(double latency, double loss, MdcLink.LinkStatus st) {
        return new LinkMatrix.Quality(latency, loss, st);
    }
}
