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
 * 같은 MDC · 미등록            → 1.0 (중립)
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

    @DisplayName("같은 MDC 와 미등록 쌍은 중립 1_0")
    @Test
    void same_mdc_and_unregistered_neutral() {
        LinkMatrix m = LinkMatrix.of(List.of(new MdcLink("a", "b", 20)));

        assertEquals(1.0, model.linkScore("a", "a", m), EPS);
        assertEquals(1.0, model.linkScore("a", "z", m), EPS);
        assertEquals(1.0, model.linkScore("a", "b", null), EPS, "매트릭스가 없으면 중립");
        assertEquals(0.714, model.linkScore("b", "a", m), EPS, "방향 무관");
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
