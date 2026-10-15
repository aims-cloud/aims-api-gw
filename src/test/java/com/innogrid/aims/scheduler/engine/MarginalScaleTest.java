package com.innogrid.aims.scheduler.engine;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.service.PowerOptimizationService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 한계 전력 점수의 <b>분모 교체가 무엇을 바꾸고 무엇을 그대로 두는지</b>를 등식으로 잠근다.
 *
 * <p>행동 테스트(어느 노드를 골랐나)만 두면 값이 왜 그렇게 나왔는지 알 수 없고, 다른 이유로 같은
 * 답이 나와도 초록불이 된다. 그래서 여기서는 점수 차의 <b>닫힌 형태</b>를 확인한다 —
 * {@code Δscore = rm·(1/pMax − 1/S)} 하나가 이 변경의 전부여야 한다.</p>
 */
class MarginalScaleTest {

    private static SchedulerProperties props(double scaleWatts) {
        SchedulerProperties p = new SchedulerProperties();
        p.getPower().setMarginalScaleWatts(scaleWatts);
        return p;
    }

    private static NodeResource node(String id, int cores, double cpuPercent,
                                     double watts, double capacityWatts) {
        return new NodeResource("mdc-01", id, cores, 65_536, 0,
                cpuPercent, 40, 30, watts, capacityWatts, 45, 1_000_000, 500_000);
    }

    /**
     * 정격이 눈금과 같은 노드는 점수가 그대로다 — 연속성이 기본값 500 을 고른 이유 중 하나다.
     */
    @DisplayName("정격이 눈금과 같은 노드는 점수가 변하지 않는다")
    @Test
    void a_node_rated_at_the_scale_keeps_its_score() {
        NodeResource same = node("same", 8, 50, 300, 500);
        assertEquals(new PowerModel(props(0.0)).marginalScore(same, 2),
                new PowerModel(props(500.0)).marginalScore(same, 2), 1e-12);
    }

    /**
     * 부호 규칙(M1) — 정격이 눈금보다 크면 점수가 내려가고 작으면 올라간다.
     *
     * <p>이것이 "일률 편향 제거" 의 관측 가능한 형태다. 정격이 큰 노드는 같은 절대 W 의 깨우기
     * 비용을 유휴비로 희석하지 못하고, 정격이 작은 노드는 과도한 벌점에서 풀린다.</p>
     */
    @DisplayName("정격이 눈금보다 크면 점수가 내려가고 작으면 올라간다")
    @Test
    void the_sign_of_the_shift_follows_the_nameplate() {
        PowerModel scaled = new PowerModel(props(500.0));
        PowerModel legacy = new PowerModel(props(0.0));
        NodeResource big = node("big", 8, 50, 500, 900);
        NodeResource small = node("small", 8, 50, 200, 360);

        assertTrue(scaled.marginalScore(big, 2) < legacy.marginalScore(big, 2),
                "정격 900W 노드의 점수가 내려가지 않았다");
        assertTrue(scaled.marginalScore(small, 2) > legacy.marginalScore(small, 2),
                "정격 360W 노드의 점수가 올라가지 않았다");
    }

    /**
     * 미계측 노드는 눈금과 무관하다 — 전력을 모르는 노드는 전력으로 판단하지 않는다는 원칙 그대로.
     */
    @DisplayName("정격을 모르는 노드는 눈금과 무관하게 중립 0.5")
    @Test
    void a_node_without_a_nameplate_stays_neutral() {
        NodeResource unknown = node("unknown", 8, 50, 0, 0);
        assertEquals(0.5, new PowerModel(props(500.0)).marginalScore(unknown, 2), 1e-12);
        assertEquals(0.5, new PowerModel(props(0.0)).marginalScore(unknown, 2), 1e-12);
    }

    /**
     * 전력 최적화 진단은 이 눈금을 보지 않는다 — 진단이 쓰는 것은 W 값이지 점수가 아니다.
     *
     * <p>의존성이 없다는 사실을 반사로 확인하면 "지금은 안 부른다" 까지만 잠근다. 여기서는 눈금을
     * 크게 벌린 두 설정으로 같은 클러스터를 진단해 <b>결과가 같은지</b>를 본다.</p>
     */
    @DisplayName("전력 최적화 진단 수치는 눈금을 바꿔도 그대로다")
    @Test
    void the_power_diagnosis_does_not_move_with_the_scale() {
        List<NodeResource> nodes = List.of(
                node("busy", 8, 60, 300, 500),
                node("idle", 8, 2, 120, 900),
                node("mid", 8, 35, 210, 360));

        var a = new PowerOptimizationService(null, new PowerModel(props(500.0)), props(500.0)).diagnose(nodes);
        var b = new PowerOptimizationService(null, new PowerModel(props(700.0)), props(700.0)).diagnose(nodes);

        assertEquals(a.cluster().totalPowerWatts(), b.cluster().totalPowerWatts());
        assertEquals(a.cluster().reclaimableIdleWatts(), b.cluster().reclaimableIdleWatts());
        assertEquals(a.summary().estimatedSavingWatts(), b.summary().estimatedSavingWatts());
        assertEquals(a.findings().size(), b.findings().size());
    }
}
