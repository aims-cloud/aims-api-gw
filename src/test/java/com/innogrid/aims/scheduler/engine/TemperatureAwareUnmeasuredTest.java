package com.innogrid.aims.scheduler.engine;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;

import static org.junit.jupiter.api.Assertions.*;

/**
 * temperature 엔진이 온도를 수집하지 못한 노드(0°C)를 어떻게 다루는지.
 *
 * <p>결함은 두 겹이었다.</p>
 *
 * <ul>
 *   <li><b>겹1 — 노드 자신의 가드.</b> 0°C 를 그대로 계산에 넣으면 열적 여유·냉각이 만점(1.0),
 *   균형도 상한에 붙어 판단 근거가 없는 노드가 가중치 0.75(여유 0.30+균형 0.25+냉각 0.20)를
 *   통째로 가져간다. 이제 {@code hasThermal = temperatureCelsius > 0 && maxTemp > 0} 일 때만
 *   실제 점수를 내고, 아니면 세 값 모두 중립 0.5 로 둔다({@code weighted}·{@code power} 엔진과 같은 관례).
 *   자원 여유는 온도와 무관하므로 손대지 않는다.</li>
 *
 *   <li><b>겹2 — 클러스터 평균의 가드.</b> {@code PlacementContext.averageTemperature} 가
 *   미계측 0°C 를 표본에 넣으면 평균이 내려간다. 균형 점수가
 *   {@code 1 − 0.5 × T / 평균} 이라, 평균이 내려간 만큼 계측된 노드가 잘리는 지점(평균의
 *   두 배)도 함께 내려와 <b>정상 범위의 계측 노드가 감점을 받는다</b>. 이제 평균은
 *   계측 노드({@code temperatureCelsius > 0})만으로 낸다.</li>
 * </ul>
 *
 * <p><b>겹1만 고치고 겹2를 고치지 않으면 여전히 미계측 노드가 이긴다.</b> 미계측 노드 U 는
 * 가드 덕에 스스로 0.5 를 받지만, 계측 노드 M 의 균형 점수는 여전히 "U 의 0°C 가 섞인 평균"을
 * 분모로 계산된다 — 2노드(M=40°C, U=0°C)에서 그 평균은 20 이 되어 M 의 균형이
 * {@code 1 − 0.5×40/20 = 0} 으로 잘리고, M 총점은 0.4581 로 주저앉는다. 반대로 U 는 겹1 가드로
 * 0.5500 을 받으므로 U 가 여전히 이긴다({@link #unmeasured_not_favored_mixed_ipmi} 주석 참고).
 * 두 겹을 모두 고쳐야 M 의 균형이 제 기준(계측 노드만의 평균 40)으로 돌아와 0.5 가 되고,
 * M 총점이 0.5831 로 U 의 0.5500 을 앞선다.</p>
 *
 * <p>균형 항을 검증하려면 최소 2노드가 있어야 한다 — 노드가 하나뿐이면 평균이 0 이 되어
 * {@code ThermalModel.balanceScore} 의 기존 가드({@code averageTemperature <= 0 → 0.5})에
 * 걸려, 새 가드가 없어도 균형이 0.5 로 나온다(증명력이 없다). 그래서 8번 메서드를 제외한
 * 나머지는 모두 2노드 픽스처를 쓴다.</p>
 */
class TemperatureAwareUnmeasuredTest {

    private static final double EPS = 1e-9;
    /** measured_node_scores_unchanged·unmeasured_not_favored_mixed_ipmi 전용 — 반복소수(0.4666…7)를
     *  수동으로 4자리 반올림한 기대값과 비교하므로 EPS 보다 넉넉히 잡는다. */
    private static final double EPS_ROUNDED = 1e-4;

    private final TemperatureAwareScheduler scheduler = new TemperatureAwareScheduler();

    @DisplayName("온도를 수집하지 못한 노드의 열적여유는 중립 0_5다")
    @Test
    void unmeasured_headroom_score_0_5() {
        NodeScore u = score(scheduler.schedule(new Workload("w", 1, 512),
                List.of(node("M", 40), node("U", 0)), policy()), "U");

        assertEquals(0.5, u.temperatureScore(), EPS, "0°C 를 여유 100% 로 읽어 헤드룸 만점을 주면 안 된다");
        assertEquals(0.5, u.details().get("열적여유"), EPS);
    }

    @DisplayName("온도를 수집하지 못한 노드의 열적균형은 중립 0_5다")
    @Test
    void unmeasured_balance_score_0_5() {
        NodeScore u = score(scheduler.schedule(new Workload("w", 1, 512),
                List.of(node("M", 40), node("U", 0)), policy()), "U");

        assertEquals(0.5, u.networkScore(), EPS, "평균을 계측 노드만으로 내도 U 자신은 판단 근거가 없다");
        assertEquals(0.5, u.details().get("열적균형"), EPS);
    }

    @DisplayName("온도를 수집하지 못한 노드의 냉각 점수는 중립 0_5다")
    @Test
    void unmeasured_cooling_score_0_5() {
        NodeScore u = score(scheduler.schedule(new Workload("w", 1, 512),
                List.of(node("M", 40), node("U", 0)), policy()), "U");

        assertEquals(0.5, u.powerScore(), EPS, "0°C 를 제곱 비용식에 그대로 넣으면 냉각비용이 0이 되어 만점을 준다");
        assertEquals(0.5, u.details().get("냉각점수"), EPS);
    }

    @DisplayName("상세에 온도 계측 여부가 드러난다")
    @Test
    void unmeasured_flag_exposed_in_details() {
        SchedulingResult result = scheduler.schedule(new Workload("w", 1, 512),
                List.of(node("M", 40), node("U", 0)), policy());

        assertEquals(0.0, score(result, "U").details().get("온도계측"), EPS,
                "중립 0.5 가 '재봤더니 0.5' 로 읽히지 않게 계측 여부를 별도로 밝힌다");
        assertEquals(1.0, score(result, "M").details().get("온도계측"), EPS);
    }

    @DisplayName("계측된 노드의 세 점수는 그대로다")
    @Test
    void measured_node_scores_unchanged() {
        NodeScore m = score(scheduler.schedule(new Workload("w", 1, 512),
                List.of(node("M", 40), node("U", 0)), policy()), "M");

        // 클러스터 평균은 계측 노드(M)만으로 낸다 → 평균 40.0, M 자신과 같아 균형은 정확히 0.5.
        // 여유 (75-40)/75 = 0.4667, 냉각 1-(40/75)^2 = 0.7156.
        assertEquals(0.4667, m.temperatureScore(), EPS_ROUNDED);
        assertEquals(0.5, m.networkScore(), EPS_ROUNDED);
        assertEquals(0.7156, m.powerScore(), EPS_ROUNDED);
    }

    @DisplayName("IPMI가 섞인 클러스터에서 미계측 노드가 이기지 않는다")
    @Test
    void unmeasured_not_favored_mixed_ipmi() {
        // 자원 동일(cpu 30·mem 30 → 자원여유 0.7). M=40°C 는 계측, U=0°C 는 미계측.
        SchedulingResult result = scheduler.schedule(new Workload("w", 1, 512),
                List.of(node("M", 40), node("U", 0)), policy());

        // M 총점 = 0.4667×0.30 + 0.5×0.25 + 0.7156×0.20 + 0.7×0.25 = 0.5831
        // U 총점 = 0.5×0.30   + 0.5×0.25 + 0.5×0.20    + 0.7×0.25 = 0.5500
        assertEquals("M", result.selectedNodeId(), "미계측 노드가 계측된 정상 노드를 이기면 안 된다");
        assertEquals(0.5831, score(result, "M").totalScore(), EPS_ROUNDED);
        assertEquals(0.5500, score(result, "U").totalScore(), EPS_ROUNDED);

        // 수정 전이었다면(겹1·겹2 모두 없음) 평균이 U 의 0°C 까지 섞여 (40+0)/2=20 으로 내려가고,
        // M 의 균형은 1-0.5×40/20=0 으로 잘린다. U 는 가드가 없어 헤드룸·냉각 만점(1.0)에
        // 균형도 1-0.5×0/20=1.0 을 받아 M 0.4581 < U 0.9250 — 미계측 노드가 이겼다.
        // 겹1만 고쳐도(U 에만 중립 0.5) 평균은 여전히 20 이라 M 의 균형은 그대로 0 으로 잘려
        // M 총점 0.4581 < U 총점 0.5500 — 여전히 U 가 이긴다. 겹2(평균을 계측 노드만으로)까지
        // 고쳐야 평균이 40 으로 돌아와 M 의 균형이 0.5 가 되고 M 0.5831 > U 0.5500 이 된다.
    }

    @DisplayName("전 노드가 미계측이면 세 점수가 모두 중립 0_5다")
    @Test
    void all_unmeasured_scores_stay_neutral() {
        SchedulingResult result = scheduler.schedule(new Workload("w", 1, 512),
                List.of(node("A", 0), node("B", 0), node("C", 0)), policy());

        assertTrue(result.success(), "계측 노드가 하나도 없어도 자원 여유만으로 배치는 성립해야 한다");
        for (NodeScore s : result.candidateScores()) {
            assertEquals(0.5, s.temperatureScore(), EPS, s.nodeId());
            assertEquals(0.5, s.networkScore(), EPS, s.nodeId());
            assertEquals(0.5, s.powerScore(), EPS, s.nodeId());
            assertFalse(Double.isNaN(s.totalScore()),
                    "평균온도 0(계측 노드 없음)이 나눗셈에 섞여 NaN 이 나오면 안 된다: " + s.nodeId());
        }
    }

    @DisplayName("노드가 한 대뿐이고 미계측이면 총점은 0_55다")
    @Test
    void lone_unmeasured_node_total_score_pinned() {
        // 배포 VM 의 실제 형태 — 노드가 한 대뿐이고 그 한 대의 IPMI 가 down 이면 곧 이 픽스처다.
        // 노드 1대라 평균온도 자체가 0(계측 노드 없음)이 되어 ThermalModel.balanceScore 의 기존
        // 가드(averageTemperature<=0 → 0.5)만으로도 균형은 0.5 가 나온다 — 이 테스트가 겨냥하는
        // 것은 그 기존 가드가 아니라 여유·냉각까지 중립으로 미는 새 가드다.
        // 자원여유 0.7(cpu 30·mem 30) 픽스처에 고정된 등식이다 — 픽스처를 바꾸면 다시 계산해야 한다.
        // 0.7×0.25(자원) + 0.5×(0.30+0.25+0.20)(열 셋 중립) = 0.175 + 0.375 = 0.55
        SchedulingResult result = scheduler.schedule(new Workload("w", 1, 512),
                List.of(node("U", 0)), policy());
        NodeScore u = only(result);

        assertTrue(result.success());
        assertTrue(u.eligible());
        assertEquals(0.55, u.totalScore(), EPS);
    }

    // ── 픽스처 ──────────────────────────────────────────────────

    private static SchedulingPolicy policy() {
        return new SchedulingPolicy("test", "test");
    }

    private static NodeScore only(SchedulingResult result) {
        assertEquals(1, result.candidateScores().size());
        return result.candidateScores().get(0);
    }

    private static NodeScore score(SchedulingResult result, String nodeId) {
        return result.candidateScores().stream()
                .filter(s -> s.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    private static NodeResource node(String id, double temp) {
        return new NodeResource("mdc-01", id, 8, 16384, 0, 30, 30, 40, 200, 500, temp, 1000, 500);
    }
}
