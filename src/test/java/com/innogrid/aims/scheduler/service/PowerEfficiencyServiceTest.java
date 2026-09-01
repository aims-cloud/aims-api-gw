package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.PowerEfficiencyResponse;
import com.innogrid.aims.scheduler.dto.PowerEfficiencyResponse.NodeEfficiency;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.model.NodeResource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 실제 전력 데이터를 얻기 전까지는 합성 스냅샷이 기능을 증명하는 유일한 수단이다.
 *
 * <p>전력을 200W 로 고정하고 CPU 사용률만 바꿔, 기대값을 손으로 계산할 수 있게 픽스처를
 * 짰다(용량 500W, idleRatio 0.3, peiNormalizer 0.5). PEI 는 CPU÷전력 이므로
 * 80% → 0.40, 10% → 0.05 처럼 값이 바로 떨어진다.</p>
 */
class PowerEfficiencyServiceTest {

    private static final double EPS = 1e-9;

    private PowerEfficiencyService service;

    @BeforeEach
    void setUp() {
        SchedulerProperties properties = new SchedulerProperties();
        service = new PowerEfficiencyService(mock(SchedulingService.class), new PowerModel(properties),
                mock(VictoriaMetricsClient.class), properties);
    }

    // ── 지표 ───────────────────────────────────────────────────

    @Test
    void PEI_는_CPU_사용률을_소비_전력으로_나눈_값이다() {
        NodeEfficiency n = evaluateOne(node("n1", 80, 200));

        assertTrue(n.evaluable());
        assertEquals(0.40, n.peiRaw(), EPS, "80% / 200W");
        assertEquals(0.80, n.peiNormalized(), EPS, "0.40 / 0.5");
    }

    @Test
    void 유휴_전력과_여유율을_계산한다() {
        NodeEfficiency n = evaluateOne(node("n1", 80, 200));

        assertEquals(150.0, n.idlePowerWatts(), EPS, "500W × 0.3");
        assertEquals(60.0, n.headroomPercent(), EPS, "(500-200)/500");
    }

    @Test
    void 비례성은_이상_전력_곡선과의_근접도다() {
        // P_ideal = 150 + 350×0.8 = 430, |200-430|/500 = 0.46
        assertEquals(0.54, evaluateOne(node("n1", 80, 200)).proportionality(), 1e-4);
    }

    // ── 평가 불가 노드 ──────────────────────────────────────────

    @Test
    void 전력을_모르면_파생_지표를_모두_비운다() {
        // PowerModel.proportionality 는 전력이 0 이어도 0.6 같은 값을 돌려준다.
        // 판단 근거가 없는데 숫자가 보이면 그대로 믿게 되므로 서비스가 막아야 한다.
        NodeEfficiency n = evaluateOne(node("n9", 50, 0));

        assertFalse(n.evaluable());
        assertNull(n.peiRaw());
        assertNull(n.peiNormalized());
        assertNull(n.proportionality(), "전력이 0인데 비례성 숫자가 나오면 안 된다");
        assertNull(n.headroomPercent());
        assertNull(n.idlePowerWatts());
        assertNull(n.grade());
        assertNull(n.rank());
    }

    @Test
    void 평가_불가_노드도_원자료는_그대로_보여준다() {
        NodeEfficiency n = evaluateOne(node("n9", 50, 0));

        assertEquals(50.0, n.cpuUsagePercent(), EPS);
        assertEquals(0.0, n.powerWatts(), EPS);
        assertEquals(500.0, n.powerCapacityWatts(), EPS, "용량은 설정값이라 알 수 있다");
    }

    @Test
    void 평가_불가_노드에는_연동_확인_권고가_붙는다() {
        assertTrue(evaluateOne(node("n9", 50, 0)).recommendation().contains("IPMI"));
    }

    // ── 등급·순위 ──────────────────────────────────────────────

    @Test
    void 여덟_노드가_네_등급으로_고르게_갈린다() {
        PowerEfficiencyResponse result = service.evaluate(cluster());

        assertEquals("A", grade(result, "n1"));
        assertEquals("A", grade(result, "n2"));
        assertEquals("B", grade(result, "n3"));
        assertEquals("B", grade(result, "n4"));
        assertEquals("C", grade(result, "n5"));
        assertEquals("C", grade(result, "n6"));
        assertEquals("D", grade(result, "n7"));
        assertEquals("D", grade(result, "n8"));
    }

    @Test
    void 순위는_PEI_내림차순이다() {
        PowerEfficiencyResponse result = service.evaluate(cluster());

        assertEquals(1, find(result, "n1").rank(), "PEI 0.40 이 1위");
        assertEquals(8, find(result, "n8").rank(), "PEI 0.05 가 8위");
    }

    @Test
    void 평가_불가_노드는_순위와_등급에서_빠진다() {
        PowerEfficiencyResponse result = service.evaluate(cluster());

        assertNull(find(result, "n9").rank());
        assertNull(find(result, "n9").grade());
        assertEquals(8, result.nodes().stream().filter(n -> n.rank() != null).count(),
                "순위가 매겨진 노드는 평가 가능한 8대뿐");
    }

    @Test
    void PEI_와_비례성은_서로_다른_것을_본다() {
        PowerEfficiencyResponse result = service.evaluate(cluster());

        // n8 은 PEI 최하위(D)지만 저부하라 실측 전력이 이상 곡선에 가까워 비례성은 최고다.
        // 지표 하나로 뭉뚱그리면 이런 노드를 잘못 판단한다.
        assertEquals("D", grade(result, "n8"));
        assertTrue(find(result, "n8").proportionality() > find(result, "n1").proportionality(),
                "저부하 노드의 비례성이 더 높다");
    }

    // ── 클러스터 요약 ───────────────────────────────────────────

    @Test
    void 평균은_평가_가능_노드만으로_낸다() {
        PowerEfficiencyResponse.Summary summary = service.evaluate(cluster()).summary();

        assertEquals(9, summary.totalNodes());
        assertEquals(8, summary.evaluableNodes());
        // (0.40+0.35+0.30+0.25+0.20+0.15+0.10+0.05) / 8 = 0.225  — 9로 나누면 틀린다
        assertEquals(0.225, summary.averagePeiRaw(), 1e-4);
    }

    @Test
    void 총_전력과_총_용량을_집계한다() {
        PowerEfficiencyResponse.Summary summary = service.evaluate(cluster()).summary();

        assertEquals(1600.0, summary.totalPowerWatts(), EPS, "200W × 8대");
        assertEquals(4500.0, summary.totalCapacityWatts(), EPS, "500W × 9대");
    }

    // ── 표본이 모자랄 때 ────────────────────────────────────────

    @Test
    void 노드가_넷보다_적으면_등급을_매기지_않는다() {
        PowerEfficiencyResponse result = service.evaluate(
                List.of(node("a", 80, 200), node("b", 40, 200), node("c", 20, 200)));

        assertFalse(result.summary().gradeAvailable());
        assertTrue(result.summary().gradeUnavailableReason().contains("3대"),
                result.summary().gradeUnavailableReason());
        assertTrue(result.nodes().stream().allMatch(n -> n.grade() == null), "등급은 비운다");
        assertTrue(result.nodes().stream().allMatch(n -> n.rank() != null), "순위는 매긴다");
    }

    @Test
    void 전부_평가_불가여도_예외가_아니다() {
        PowerEfficiencyResponse result = service.evaluate(
                List.of(node("a", 50, 0), node("b", 30, 0)));

        assertEquals(0, result.summary().evaluableNodes());
        assertNull(result.summary().averagePeiRaw());
        assertFalse(result.summary().gradeAvailable());
        assertEquals(0.0, result.summary().totalPowerWatts(), EPS);
    }

    @Test
    void 노드가_없어도_예외가_아니다() {
        PowerEfficiencyResponse result = service.evaluate(List.of());

        assertTrue(result.nodes().isEmpty());
        assertEquals(0, result.summary().totalNodes());
        assertNull(result.summary().averagePeiRaw());
        assertNotNull(result.summary().gradeUnavailableReason());
    }

    @Test
    void null_목록도_빈_결과로_받는다() {
        assertEquals(0, service.evaluate(null).summary().totalNodes());
    }

    @Test
    void PEI_가_같으면_노드_ID_순으로_안정_정렬한다() {
        PowerEfficiencyResponse first = service.evaluate(List.of(
                node("b", 50, 200), node("a", 50, 200), node("d", 50, 200), node("c", 50, 200)));
        PowerEfficiencyResponse second = service.evaluate(List.of(
                node("d", 50, 200), node("c", 50, 200), node("b", 50, 200), node("a", 50, 200)));

        assertEquals(1, find(first, "a").rank());
        assertEquals(find(first, "a").rank(), find(second, "a").rank(), "호출 순서가 달라도 같은 순위");
        assertEquals(find(first, "d").rank(), find(second, "d").rank());
    }

    // ── 작은 클러스터 ──────────────────────────────────────────

    @Test
    void 등급을_못_매기는_작은_클러스터에서도_저부하_권고는_나온다() {
        // 등급은 4대 이상이어야 매겨진다. 그렇다고 노드 3대짜리 클러스터에서
        // "이 노드는 놀고 있다"는 사실까지 감출 이유는 없다.
        PowerEfficiencyResponse result = service.evaluate(List.of(
                node("바쁨-1", 80, 200), node("바쁨-2", 70, 200), node("놀고있음-3", 5, 200)));

        assertFalse(result.summary().gradeAvailable(), "3대라 등급은 못 매긴다");
        assertNull(find(result, "놀고있음-3").grade());
        assertEquals("저부하로 전력 효율이 낮습니다. 워크로드 통합을 검토하세요",
                find(result, "놀고있음-3").recommendation(),
                "등급이 없어도 저부하는 알려줘야 한다");
        assertNull(find(result, "바쁨-1").recommendation());
    }

    @Test
    void 노드_ID_가_겹쳐도_순위가_뒤섞이지_않는다() {
        // 수집 설정이 잘못되면 같은 instance 가 두 번 올라올 수 있다. 그때는 지표까지
        // 똑같아서, 순위를 값으로 되찾으면 한쪽이 순위를 못 받고 다른 쪽이 두 번 받는다.
        PowerEfficiencyResponse result = service.evaluate(List.of(
                node("중복", 50, 200), node("중복", 50, 200),
                node("정상-1", 60, 200), node("정상-2", 20, 200)));

        assertEquals(4, result.nodes().stream().filter(n -> n.rank() != null).count(),
                "네 노드 모두 순위를 받아야 한다");
        assertEquals(4, result.nodes().stream().map(PowerEfficiencyResponse.NodeEfficiency::rank)
                        .distinct().count(),
                "순위가 중복되면 안 된다");
    }

    // ── 설정 방어 ──────────────────────────────────────────────

    @Test
    void PEI_정규화_기준이_0_이면_기본값으로_되돌린다() {
        // min(1, x/0) 은 1.0 이라 방어하지 않으면 전 노드가 조용히 만점이 된다
        SchedulerProperties broken = new SchedulerProperties();
        broken.getPower().setPeiNormalizer(0);
        PowerEfficiencyService guarded = new PowerEfficiencyService(mock(SchedulingService.class),
                new PowerModel(broken), mock(VictoriaMetricsClient.class), broken);

        NodeEfficiency n = guarded.evaluate(List.of(node("n1", 10, 200))).nodes().get(0);

        assertEquals(0.10, n.peiNormalized(), 1e-4, "0.05 / 0.5 — 1.0 이 되면 안 된다");
    }

    // ── 용량을 모를 때 ──────────────────────────────────────────

    @Test
    void 전력_용량을_모르면_비례성은_중립값이고_여유율은_100_이다() {
        NodeEfficiency n = evaluateOne(new NodeResource("mdc-01", "n1", 8, 16384, 0,
                50, 50, 30, 200, 0, 40, 0, 0));

        assertTrue(n.evaluable(), "실측 전력이 있으므로 평가는 가능하다");
        assertEquals(0.25, n.peiRaw(), EPS);
        assertEquals(100.0, n.headroomPercent(), EPS, "용량을 모르면 여유가 있다고 본다");
        assertEquals(0.5, n.proportionality(), EPS, "이상 곡선을 그릴 수 없어 중립값");
        assertEquals(0.0, n.idlePowerWatts(), EPS, "용량 × 유휴비율 = 0");
    }

    // ── 픽스처 ─────────────────────────────────────────────────

    /** 설계 문서의 9노드 픽스처. 전력 200W 고정, CPU 만 10~80% 로 변화. */
    private List<NodeResource> cluster() {
        return List.of(
                node("n1", 80, 200), node("n2", 70, 200), node("n3", 60, 200),
                node("n4", 50, 200), node("n5", 40, 200), node("n6", 30, 200),
                node("n7", 20, 200), node("n8", 10, 200),
                node("n9", 50, 0));          // IPMI 없음
    }

    private NodeResource node(String id, double cpuPercent, double powerWatts) {
        NodeResource node = new NodeResource("mdc-01", id, 8, 16384, 0,
                cpuPercent, 50, 30, powerWatts, 500, 40, 0, 0);
        node.setPowerSource(powerWatts > 0
                ? NodeResource.PowerSource.IPMI : NodeResource.PowerSource.NONE);
        return node;
    }

    private NodeEfficiency evaluateOne(NodeResource node) {
        return service.evaluate(List.of(node)).nodes().get(0);
    }

    private NodeEfficiency find(PowerEfficiencyResponse result, String nodeId) {
        return result.nodes().stream().filter(n -> n.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    private String grade(PowerEfficiencyResponse result, String nodeId) {
        return find(result, nodeId).grade();
    }
}
