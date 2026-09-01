package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.PowerOptimizationResponse;
import com.innogrid.aims.scheduler.dto.PowerOptimizationResponse.Finding;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.model.NodeResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 클러스터 전력 최적화 진단.
 *
 * <p>모든 노드를 8코어 · 용량 500W(유휴 150W)로 맞추고 부하·전력만 바꿔, 결과 차이의 출처를
 * 분명히 한다. PR ② 의 시나리오 픽스처와 같은 값이라 손으로 검산할 수 있다.</p>
 */
class PowerOptimizationServiceTest {

    private final SchedulerProperties properties = new SchedulerProperties();
    private final PowerOptimizationService service = new PowerOptimizationService(
            null, new PowerModel(properties), properties);

    // ── 클러스터 현황 ───────────────────────────────────────────

    @Test
    void 가동_노드와_유휴_노드를_나눠_센다() {
        var result = service.diagnose(List.of(
                node("busy", 60, 300),
                node("mid", 30, 220),
                node("idle-1", 2, 150),
                node("idle-2", 1, 150)));

        assertEquals(4, result.cluster().totalNodes());
        assertEquals(2, result.cluster().activeNodes());
        assertEquals(2, result.cluster().idleNodes());
        assertEquals(820.0, result.cluster().totalPowerWatts(), 1e-9);
    }

    @Test
    void 유휴_노드의_유휴_전력을_반납_가능으로_집계한다() {
        var result = service.diagnose(List.of(
                node("busy", 60, 300),
                node("idle-1", 2, 150),
                node("idle-2", 1, 150)));

        // 500W × 0.3 × 2대
        assertEquals(300.0, result.cluster().reclaimableIdleWatts(), 1e-9);
    }

    // ── 진단 항목 ──────────────────────────────────────────────

    @Test
    void 유휴_노드를_찾아_반납을_권고한다() {
        var result = service.diagnose(List.of(node("idle", 2, 150)));

        Finding f = find(result, "IDLE_NODE");
        assertNotNull(f);
        assertEquals("HIGH", f.severity());
        assertEquals(150.0, f.estimatedSavingWatts(), 1e-9, "반납하면 유휴 전력을 통째로 아낀다");
        assertTrue(f.detail().contains("2.0%"), f.detail());
    }

    @Test
    void 부하에_비해_전력을_많이_쓰면_낭비로_본다() {
        // CPU 20% → 이상 전력 150 + 350×0.2 = 220W. 실측 420W 는 200W 초과
        var result = service.diagnose(List.of(node("wasteful", 20, 420)));

        Finding f = find(result, "POWER_WASTE");
        assertNotNull(f);
        assertEquals(200.0, f.estimatedSavingWatts(), 1.0);
    }

    @Test
    void 이상_곡선_안에_있으면_낭비로_보지_않는다() {
        // CPU 60% → 이상 250 + ... 실측 300W 는 곡선 아래
        var result = service.diagnose(List.of(node("efficient", 60, 300)));

        assertNull(find(result, "POWER_WASTE"));
    }

    /** 여유 부족은 절감 항목이 아니라 배치 제약이다. 숫자를 채우면 절감 합계가 부풀려진다. */
    @Test
    void 전력_여유_부족은_절감액을_비운다() {
        var result = service.diagnose(List.of(node("tight", 70, 460)));

        Finding f = find(result, "LOW_HEADROOM");
        assertNotNull(f);
        assertNull(f.estimatedSavingWatts(), "절감이 아니라 제약이다");
    }

    @Test
    void 절감이_큰_항목이_위로_온다() {
        // 낭비 노드는 이상 곡선(150 + 350×0.4 = 290W)을 130W 넘겨 임계 0.8 아래로 떨어진다.
        // 350W 로 두면 초과가 60W 뿐이라 낭비로 잡히지 않아 항목이 하나만 나온다.
        var result = service.diagnose(List.of(
                node("waste", 40, 420),
                node("idle", 1, 150)));

        List<Finding> f = result.findings();
        assertTrue(f.size() >= 2);
        for (int i = 1; i < f.size(); i++) {
            double prev = f.get(i - 1).estimatedSavingWatts() == null ? 0 : f.get(i - 1).estimatedSavingWatts();
            double curr = f.get(i).estimatedSavingWatts() == null ? 0 : f.get(i).estimatedSavingWatts();
            assertTrue(prev >= curr, "절감 내림차순이어야 한다");
        }
    }

    // ── 계측이 없을 때 ─────────────────────────────────────────

    @Test
    void 전력을_수집하지_못하면_진단을_비우고_사유를_남긴다() {
        var result = service.diagnose(List.of(
                node("no-ipmi-1", 40, 0),
                node("no-ipmi-2", 10, 0)));

        assertFalse(result.cluster().measured());
        assertNotNull(result.cluster().unmeasuredReason());
        assertTrue(result.findings().isEmpty(), "근거 없이 진단을 지어내지 않는다");
        assertEquals(0, result.summary().findingCount());
        assertEquals(0.0, result.cluster().reclaimableIdleWatts(), 1e-9);
    }

    @Test
    void 총_전력이_0이면_절감_비율을_내지_않는다() {
        var result = service.diagnose(List.of(node("no-ipmi", 40, 0)));

        assertNull(result.summary().savingPercent(), "0% 로 채우면 절감 없음으로 읽힌다");
    }

    @Test
    void 노드가_없어도_예외가_나지_않는다() {
        var result = service.diagnose(List.of());

        assertEquals(0, result.cluster().totalNodes());
        assertTrue(result.findings().isEmpty());
        assertFalse(result.cluster().measured());
    }

    @Test
    void 절감_비율은_클러스터_총_전력_대비다() {
        var result = service.diagnose(List.of(
                node("busy", 60, 300),
                node("idle", 2, 150)));

        // 유휴 150W 반납 / 총 450W
        assertEquals(33.33, result.summary().savingPercent(), 0.1);
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private Finding find(PowerOptimizationResponse result, String type) {
        return result.findings().stream()
                .filter(f -> f.type().equals(type))
                .findFirst().orElse(null);
    }

    private NodeResource node(String nodeId, double cpuUsage, double power) {
        return new NodeResource("mdc-01", nodeId, 8, 16384, 0,
                cpuUsage, 40, 30, power, 500, 45, 1_000_000, 500_000);
    }
}
