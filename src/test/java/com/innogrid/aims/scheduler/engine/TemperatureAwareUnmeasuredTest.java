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
 * <p>미계측 노드는 두 겹의 가드로 다룬다.</p>
 *
 * <ul>
 *   <li><b>겹1 — 노드 자신의 가드.</b> 0°C를 그대로 계산에 넣으면 열적 여유·냉각이 만점(1.0), 균형도 상한에 붙어
 *   판단 근거가 없는 노드가 가중치 0.75(여유 0.30+균형 0.25+냉각 0.20)를 통째로 가져간다. 그래서
 *   {@code hasThermal = temperatureCelsius > 0}일 때만 실제 점수를 내고, 아니면 세 값 모두 중립 0.5로 둔다
 *   ({@code weighted}·{@code power} 엔진과 같은 관례). 임계 척도의 부재({@code maxTemp <= 0})는 가드에 섞지 않는다
 *   ({@link #zero_max_temperature_keeps_measured_nodes_measured}). 자원 여유는 온도와 무관하므로 손대지 않는다.</li>
 *
 *   <li><b>겹2 — 클러스터 평균의 가드.</b> {@code PlacementContext.averageTemperature}가 미계측 0°C를 표본에 넣으면
 *   평균이 내려간다. 균형 점수가 {@code 1 − 0.5 × T / 평균}이라 계측된 노드가 잘리는 지점(평균의 두 배)도 함께 내려와
 *   <b>정상 범위의 계측 노드가 감점을 받는다</b>. 평균은 계측 노드({@code temperatureCelsius > 0})만으로 낸다.</li>
 * </ul>
 *
 * <p>2노드(M=40°C, U=0°C)에서 겹1만 있으면 평균에 U의 0°C가 섞여 U가 이기고, 두 겹이 모두 있어야 M이 앞선다
 * (수치는 {@link #unmeasured_not_favored_mixed_ipmi}의 주석). 노드가 미계측 1대뿐이면 평균이 0이 되어
 * {@code ThermalModel.balanceScore}의 가드({@code averageTemperature <= 0 → 0.5})에 걸리고, 계측 노드가 1대뿐이면
 * 평균이 곧 그 노드의 온도라 균형이 늘 0.5다. 그래서 노드 1대 픽스처는
 * {@link #lone_unmeasured_node_total_score_pinned}·{@link #negative_temperature_treated_as_unmeasured}뿐이고,
 * 평균 수정은 계측 2대를 쓰는 {@link #average_over_two_measured_nodes_shapes_balance}에서 관측한다.</p>
 *
 * <p><b>중립 0.5는 이 점수 분포의 중립이 아니다.</b> 미계측 노드의 열 3항 합은 항상
 * {@code 0.5 × (0.30+0.25+0.20) = 0.375}로 고정이고, 균형이 0.5인 계측 노드는 {@code T² + 112.5T − 7031.25 < 0}
 * 즉 <b>T &lt; 44.72°C</b>일 때만 그것을 넘는다(유도는 {@link #unmeasured_not_favored_mixed_ipmi}의 주석).
 * 따라서 "미계측 노드가 계측 노드를 이기지 않는다"는 <b>계측 노드가 충분히 시원할 때만 성립한다</b>.
 * {@link #unmeasured_not_favored_mixed_ipmi}는 임계 아래(40°C)를, {@link #unmeasured_winner_reason_admits_no_metric}는
 * 임계 위(70°C)를 확인한다.</p>
 *
 * <p><b>선택 사유는 후보 집합이 아니라 승자를 주어로 3분기한다.</b> 승자가 계측이면 열 세 점수와 평균 온도를 적고,
 * 승자가 미계측인데 클러스터에 계측 노드가 있으면 "선택 노드는 온도 미계측"이라 밝히며 자원 여유와 계측 대수를 적고,
 * 전부 미계측이면 "온도 메트릭이 없어"라고 밝힌다. 미계측 승자의 사유에 {@code 여유:0.50 균형:0.50 냉각:0.50}을
 * 늘어놓으면 "재 봤더니 전부 평균 수준"과 글자 그대로 구분되지 않기 때문이다. 같은 이유로 {@code details}의 온도
 * 파생값(배치후예상온도C·클러스터평균온도C)은 값을 못 낼 때 0이 아니라 {@code null}이다(키는 남기고 값만 비운다).</p>
 */
class TemperatureAwareUnmeasuredTest {

    /**
     * 모든 확인이 쓰는 허용오차. 반복소수(0.4666…7, 0.6363…6)를 손으로 4자리 반올림한 기대값도
     * <b>이 값으로</b> 비교한다 — 엔진이 {@code Math.round(v × 10000) / 10000}으로 이미 4자리로
     * 반올림해 내보내므로, 기대값을 같은 4자리로 적으면 정확히 같은 double이 나온다.
     *
     * <p>이런 기대값에도 {@code 1e-4}를 따로 쓰지 않는다. 그 폭은 4자리 기대값에서
     * <b>마지막 자리 1단위를 통째로 허용</b>한다 — 0.4667 자리에 0.4666이나 0.4668이 와도
     * 통과하므로, 반올림 자리에서 갈리는 회귀를 걸러내지 못한다.</p>
     */
    private static final double EPS = 1e-9;

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
        assertEquals(0.4667, m.temperatureScore(), EPS);
        assertEquals(0.5, m.networkScore(), EPS);
        assertEquals(0.7156, m.powerScore(), EPS);
    }

    @DisplayName("계측 노드가 충분히 시원하면 미계측 노드가 이기지 않는다")
    @Test
    void unmeasured_not_favored_mixed_ipmi() {
        // 이름의 "충분히 시원하면"은 수사가 아니라 조건이다. 중립 0.5는 이 점수 분포의 중립이
        // 아니어서, 미계측 노드의 열 3항 합은 0.5×(0.30+0.25+0.20) = 0.375로 고정인 반면
        // 계측 노드의 열 3항 합은 온도에 따라 움직인다. 두 값이 같아지는 지점이 44.72°C다
        // (0.30×(75−T)/75 + 0.125 + 0.20×(1−(T/75)²) = 0.375 → T²+112.5T−7031.25 = 0의 양근).
        // 44.0°C는 0.3802로 이기고 45.0°C는 0.3730으로 진다 — 이 픽스처의 40°C는 임계 아래다.
        // 그러므로 이 테스트는 "미계측은 절대 이기지 않는다"를 고정하지 않는다. 임계 위(70°C)에서
        // 미계측이 이기는 것은 unmeasured_winner_reason_admits_no_metric이 정상 동작으로 고정한다.
        // 임계를 44.72°C에 두는 것이 옳은지는 가중치 배분의 문제이고 이 결함과는 별건이다.
        //
        // 자원 동일(cpu 30·mem 30 → 자원여유 0.7). M=40°C는 계측, U=0°C는 미계측.
        SchedulingResult result = scheduler.schedule(new Workload("w", 1, 512),
                List.of(node("M", 40), node("U", 0)), policy());

        // M 총점 = 0.4667×0.30 + 0.5×0.25 + 0.7156×0.20 + 0.7×0.25 = 0.5831
        // U 총점 = 0.5×0.30   + 0.5×0.25 + 0.5×0.20    + 0.7×0.25 = 0.5500
        assertEquals("M", result.selectedNodeId(), "미계측 노드가 계측된 정상 노드를 이기면 안 된다");
        assertEquals(0.5831, score(result, "M").totalScore(), EPS);
        assertEquals(0.5500, score(result, "U").totalScore(), EPS);

        // 수정 전이었다면(겹1·겹2 모두 없음) 평균이 U의 0°C까지 섞여 (40+0)/2=20으로 내려가고,
        // M의 균형은 1-0.5×40/20=0으로 잘린다. U는 가드가 없어 헤드룸·냉각 만점(1.0)에
        // 균형도 1-0.5×0/20=1.0을 받아 M 0.4581 < U 0.9250 — 미계측 노드가 이겼다.
        // 겹1만 고쳐도(U에만 중립 0.5) 평균은 여전히 20이라 M의 균형은 그대로 0으로 잘려
        // M 총점 0.4581 < U 총점 0.5500 — 여전히 U가 이긴다. 겹2(평균을 계측 노드만으로)까지
        // 고쳐야 평균이 40으로 돌아와 M의 균형이 0.5가 되고 M 0.5831 > U 0.5500이 된다.
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
        // 배포 VM의 실제 형태 — 노드가 한 대뿐이고 그 한 대의 IPMI가 down이면 곧 이 픽스처다.
        // 노드 1대라 평균온도 자체가 0(계측 노드 없음)이 되어 ThermalModel.balanceScore의 기존
        // 가드(averageTemperature<=0 → 0.5)만으로도 균형은 0.5가 나온다 — 이 테스트가 겨냥하는
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

    @DisplayName("음수 온도는 미계측으로 다룬다")
    @Test
    void negative_temperature_treated_as_unmeasured() {
        // 가드가 "0인가"가 아니라 "0보다 큰가"여야 하는 이유. 센서가 음수를 돌려주는 경우
        // (오프셋 오류·부호 반전)도 판단 근거가 없기는 마찬가지다. 0만 걸러내면 -5°C가
        // 계측값으로 들어가고, headroomScore에는 상한 클램프가 없어 (75−(−5))/75 = 1.0667로
        // 1.0을 넘는 점수가 나온다 — 만점보다 더한 만점이다.
        SchedulingResult result = scheduler.schedule(new Workload("w", 1, 512),
                List.of(node("N", -5)), policy());
        NodeScore n = only(result);

        assertEquals(0.5, n.temperatureScore(), EPS, "음수 온도의 열적여유는 중립이어야 한다");
        assertEquals(0.5, n.networkScore(), EPS, "음수 온도의 열적균형은 중립이어야 한다");
        assertEquals(0.5, n.powerScore(), EPS, "음수 온도의 냉각 점수는 중립이어야 한다");
        assertEquals(0.0, n.details().get("온도계측"), EPS, "음수는 잰 값이 아니다");

        assertTrue(n.details().containsKey("배치후예상온도C"));
        assertNull(n.details().get("배치후예상온도C"),
                "없는 값에 상승분을 얹으면 −5.09°C 같은 근거 없는 숫자가 된다");

        // 가드가 없었다면 여유 1.0667·냉각 0.9956을 받아 총점이 0.8191로 치솟는다 —
        // 온도를 못 잰(또는 잘못 잰) 노드가 이 클래스의 어떤 계측 노드보다도 높은 점수다.
        assertTrue(n.totalScore() <= 1.0,
                "가중치 합이 1 인 점수 체계에서 총점이 1 을 넘으면 어떤 항이 클램프를 벗어난 것이다: "
                        + n.totalScore());
        assertEquals(0.55, n.totalScore(), EPS, "미계측과 같은 총점이어야 한다(열 셋 중립 0.375 + 자원 0.175)");
    }

    // ── 선택 사유 3분기 ─────────────────────────────────────────

    @DisplayName("계측된 노드가 이기면 근거에 평균 온도를 남긴다")
    @Test
    void measured_winner_keeps_average_in_reason() {
        // 겹2(평균을 계측 노드만으로)를 점수가 아니라 사유 쪽에서 한 번 더 고정한다.
        //
        // 오라클을 문자열 "평균온도:40.0"으로 두면 안 된다 — 그 조각은 분기2(승자만 미계측)의
        // "계측 노드 평균온도:%.1f°C"에도 그대로 들어 있어 <b>두 분기를 구분하지 못한다</b>.
        // 실제로 승자 판정(bestHasThermal)을 승자가 아니라 마지막 후보 기준으로 망가뜨려도
        // 이 확인은 통과했다. 그래서 분기1에만 있는 표지 " (여유:"로 분기를 고정하고,
        // 평균값 자체는 문자열이 아니라 details에서 확인한다(로케일 의존도 함께 사라진다).
        SchedulingResult result = scheduler.schedule(new Workload("w", 1, 512),
                List.of(node("M", 40), node("U", 0)), policy());

        assertEquals("M", result.selectedNodeId());
        assertTrue(result.reason().contains(" (여유:"),
                "계측 노드가 이겼으면 열 세 점수를 늘어놓는 분기1 문구여야 한다: " + result.reason());
        assertFalse(result.reason().contains("선택 노드는 온도 미계측"),
                "계측된 노드를 골라 놓고 미계측 분기 문구를 쓰면 안 된다: " + result.reason());

        // U의 0°C를 표본에 넣었다면 (40+0)/2 = 20이 된다.
        assertEquals(40.0, score(result, "M").details().get("클러스터평균온도C"), EPS,
                "평균은 계측 노드만으로 낸다 — 미계측을 섞으면 20.0 이 된다");
    }

    @DisplayName("근거는 마지막 후보가 아니라 승자를 따른다")
    @Test
    void reason_follows_winner_not_last_candidate() {
        // 미계측 노드가 목록 끝에 와도 분기1이어야 한다 — 집합·마지막 후보 기준으로 바뀌면
        // 여기서 잡힌다. 승자는 어느 순서에서나 M(0.5831 > U 0.5500)이고, 사유의 주어는
        // 후보 집합의 마지막 원소가 아니라 그 승자다.
        for (List<NodeResource> candidates : List.of(
                List.of(node("M", 40), node("U", 0)),     // 미계측이 끝 — 마지막 후보 기준이면 분기2로 샌다
                List.of(node("U", 0), node("M", 40)))) {  // 계측이 끝 — 마지막 후보 기준이어도 우연히 맞는다

            SchedulingResult result = scheduler.schedule(new Workload("w", 1, 512), candidates, policy());
            String order = candidates.stream().map(NodeResource::getNodeId).toList().toString();

            assertEquals("M", result.selectedNodeId(), "후보 순서가 승자를 바꾸면 안 된다: " + order);
            assertTrue(result.reason().contains(" (여유:"),
                    "승자가 계측이면 순서와 무관하게 분기1이어야 한다: " + order + " → " + result.reason());
            assertFalse(result.reason().contains("선택 노드는 온도 미계측"),
                    "마지막 후보가 미계측이라고 승자를 미계측이라 적으면 안 된다: " + order + " → " + result.reason());
        }
    }

    @DisplayName("계측 노드가 뜨거워 미계측이 이기면 근거가 그 사실을 밝힌다")
    @Test
    void unmeasured_winner_reason_admits_no_metric() {
        // 70°C는 44.72°C 임계 위라 미계측 U에게 진다 — 결함이 아니라 이 점수 분포의 성질이다.
        // 문제는 이겼다는 사실이 아니라 "왜 골랐는지"를 사유가 어떻게 말하느냐다.
        //   M: 여유 (75−70)/75 = 0.0667, 균형 0.5(계측이 자기뿐이라 평균 = 70), 냉각 1−(70/75)² = 0.1289
        //      총점 = 0.0667×0.30 + 0.5×0.25 + 0.1289×0.20 + 0.7×0.25 = 0.3458
        //   U: 총점 = 0.5×(0.30+0.25+0.20) + 0.7×0.25 = 0.5500
        SchedulingResult result = scheduler.schedule(new Workload("w", 1, 512),
                List.of(node("M", 70), node("U", 0)), policy());

        assertEquals("U", result.selectedNodeId());
        assertEquals(0.3458, score(result, "M").totalScore(), EPS);
        assertEquals(0.5500, score(result, "U").totalScore(), EPS);

        // M이 제약(온도 초과·배치 후 예상 온도)으로 빠지면 이 테스트의 의도가 사라진다 —
        // "뜨거운 계측 노드가 점수로 진다"가 아니라 "애초에 후보가 아니었다"가 되기 때문이다.
        // 70°C는 임계 75°C 아래고 배치 후 예상도 71.3°C라 적격이다.
        assertTrue(score(result, "M").eligible(),
                "M 이 제약으로 제외되면 미계측 승리가 점수 때문인지 제약 때문인지 알 수 없다: "
                        + score(result, "M").exclusionReason());

        assertTrue(result.reason().contains("선택 노드는 온도 미계측"),
                "사유의 주어는 후보 집합이 아니라 승자다: " + result.reason());
        assertTrue(result.reason().contains("열 점수를 중립으로 뒀다"),
                "승자가 이긴 이유는 열 3항이 중립 바닥에 고정됐기 때문이다: " + result.reason());
        assertFalse(result.reason().contains("여유:0.50 균형:0.50 냉각:0.50"),
                "못 잰 값을 잰 것처럼 늘어놓으면 '재 봤더니 전부 평균 수준' 과 글자 그대로 구분되지 않는다: "
                        + result.reason());

        // 분기2 문구가 실어 나르는 숫자도 함께 고정한다 — 문구만 맞고 숫자가 비면
        // "미계측이라 골랐다"는 말이 검증 불가능한 주장으로 남는다.
        assertTrue(result.reason().contains("계측 1/2대"),
                "2대 중 1대만 쟀다는 대수가 사유에 있어야 한다: " + result.reason());
        assertTrue(result.reason().contains("자원:0.70"),
                "승자의 자원 여유(0.70)가 사유에 있어야 한다: " + result.reason());
        assertTrue(result.reason().contains("평균온도:70.0"),
                "계측 노드(M) 하나만의 평균 70.0 이어야 한다 — U 의 0°C 를 섞었다면 35.0 이다: "
                        + result.reason());
    }

    @DisplayName("온도 메트릭이 하나도 없으면 근거가 그 사실을 밝히고 자원으로 고른다")
    @Test
    void no_thermal_metric_stated_in_reason() {
        // 열 3항이 전부 중립 0.5로 같으니 순위를 가르는 것은 자원 여유뿐이다. 자원이 같은
        // 픽스처를 쓰면 "자원으로 골랐다"가 관측되지 않아 A·B의 사용률을 다르게 둔다.
        //   A 자원여유 = 0.8/2 + 0.8/2 = 0.80 → 총점 0.375 + 0.80×0.25 = 0.5750
        //   B 자원여유 = 0.4/2 + 0.3/2 = 0.35 → 총점 0.375 + 0.35×0.25 = 0.4625
        SchedulingResult result = scheduler.schedule(new Workload("w", 1, 512),
                List.of(node("A", 0, 20, 20), node("B", 0, 60, 70)), policy());

        assertEquals("A", result.selectedNodeId(), "열이 전부 중립이면 자원이 넉넉한 쪽을 골라야 한다");
        assertTrue(result.reason().contains("온도 메트릭이 없어"), result.reason());
        assertFalse(result.reason().contains("평균온도"),
                "계측 노드가 0대일 때의 평균은 0 이다 — 사유에 적으면 '평균 0°C 로 쟀다' 로 읽힌다: "
                        + result.reason());
    }

    // ── 임계 척도(maxTemp)의 부재와 계측 여부는 다른 문제다 ──────

    @DisplayName("온도 임계가 0인 정책에서도 계측된 노드는 계측으로 다룬다")
    @Test
    void zero_max_temperature_keeps_measured_nodes_measured() {
        // 미계측 가드에 maxTemp<=0까지 섞으면 잰 값이 있는 노드가 통째로 "못 쟀다"가 되어,
        // maxTemp를 쓰지도 않는 균형 항이 함께 죽는다(두 노드의 균형이 똑같이 0.5가 된다).
        // temperatureAwareEnabled를 끄는 이유는 별개다 — 켜 두면 제약 C3이 "온도 초과 (40.0>0.0)"로
        // 계측 노드를 전부 제외해 점수를 볼 기회 자체가 없어진다.
        SchedulingPolicy zeroMax = policy();
        zeroMax.setMaxTemperatureCelsius(0);
        zeroMax.setTemperatureAwareEnabled(false);

        SchedulingResult result = scheduler.schedule(new Workload("w", 1, 512),
                List.of(node("M1", 40), node("M2", 70)), zeroMax);
        NodeScore m1 = score(result, "M1");
        NodeScore m2 = score(result, "M2");

        assertEquals(1.0, m1.details().get("온도계측"), EPS, "임계가 0 이어도 40°C 는 잰 값이다");
        assertEquals(1.0, m2.details().get("온도계측"), EPS);

        // 여유·냉각은 ThermalModel이 maxTemperature<=0에 중립을 돌려주므로 둘 다 0.5다.
        // 갈리는 것은 균형뿐 — 평균 (40+70)/2 = 55 기준으로 0.5 ± 0.5×15/55 = 0.6364 / 0.3636.
        assertEquals(0.6364, m1.networkScore(), EPS);
        assertEquals(0.3636, m2.networkScore(), EPS);
        assertNotEquals(m1.networkScore(), m2.networkScore(),
                "두 노드의 균형이 같아지면 임계 척도의 부재가 균형 항까지 죽인 것이다");

        // 총점 = 0.5×0.30 + 균형×0.25 + 0.5×0.20 + 0.7×0.25
        assertEquals(0.5841, m1.totalScore(), EPS);
        assertEquals(0.5159, m2.totalScore(), EPS);
        assertEquals("M1", result.selectedNodeId(), "임계가 없어도 시원한 쪽을 골라야 한다");

        assertTrue(result.reason().contains("평균온도:"), result.reason());
        assertFalse(result.reason().contains("메트릭이 없어"),
                "계측된 노드를 골라 놓고 메트릭이 없다고 하면 안 된다: " + result.reason());
    }

    // ── 못 잰 값에서 파생된 항목은 0이 아니라 비어 있다 ─────────

    @DisplayName("미계측 노드의 배치 후 예상 온도는 숫자가 아니라 비어 있다")
    @Test
    void unmeasured_derived_temperature_is_null() {
        SchedulingResult result = scheduler.schedule(new Workload("w", 1, 512),
                List.of(node("M", 40), node("U", 0)), policy());
        NodeScore u = score(result, "U");

        // 키는 남기고 값만 비운다 — 항목이 통째로 사라지면 "이 엔진은 원래 안 낸다"로 읽힌다.
        assertTrue(u.details().containsKey("배치후예상온도C"),
                "항목을 빼 버리면 '못 쟀다' 가 아니라 '원래 없는 지표다' 로 읽힌다");
        assertNull(u.details().get("배치후예상온도C"),
                "0 을 넣으면 '재 봤더니 0°C' 로 읽혀 판단 근거가 없다는 사실이 가려진다");
        assertNotNull(score(result, "M").details().get("배치후예상온도C"),
                "잰 노드에는 값이 있어야 한다 — 없으면 항목 자체가 죽은 것이다");
    }

    @DisplayName("계측된 노드가 하나도 없으면 클러스터 평균 온도도 비어 있다")
    @Test
    void all_unmeasured_cluster_average_is_null() {
        SchedulingResult result = scheduler.schedule(new Workload("w", 1, 512),
                List.of(node("A", 0), node("B", 0)), policy());

        for (NodeScore s : result.candidateScores()) {
            assertTrue(s.details().containsKey("클러스터평균온도C"), s.nodeId());
            assertNull(s.details().get("클러스터평균온도C"),
                    "표본이 0개인 평균은 0°C 가 아니라 없는 값이다: " + s.nodeId());
        }
    }

    // ── 계측 2대 — 평균 수정이 점수에서 실제로 관측되는 픽스처 ───

    @DisplayName("계측 노드가 둘이면 평균이 두 노드의 균형 점수를 가른다")
    @Test
    void average_over_two_measured_nodes_shapes_balance() {
        // 미계측 노드를 섞은 다른 손 픽스처는 계측 노드가 1대 이하라 균형이 자명하게 0.5다.
        // 계측 2대를 넣어야 "평균을 계측 노드만으로 낸다"(겹2)가 점수에서 실제로 관측된다.
        //   계측만의 평균 (40+60)/2 = 50 → M1 0.5+0.5×10/50 = 0.6, M2 0.5−0.5×10/50 = 0.4
        //   U의 0°C를 섞었다면 평균 33.3 → M1 0.4, M2 0.1로 둘 다 달라진다.
        SchedulingResult result = scheduler.schedule(new Workload("w", 1, 512),
                List.of(node("M1", 40), node("M2", 60), node("U", 0)), policy());
        NodeScore m1 = score(result, "M1");
        NodeScore m2 = score(result, "M2");

        assertEquals(50.0, m1.details().get("클러스터평균온도C"), EPS, "평균은 계측된 두 대만 본다");
        assertEquals(50.0, m2.details().get("클러스터평균온도C"), EPS);

        // 0.6·0.4는 클램프(0·1)에 닿지 않는 중간값이라 평균이 움직이면 그대로 따라 움직인다 —
        // 잘린 값(0이나 1)을 고정하면 평균이 얼마든 같은 숫자가 나와 증명력이 없다.
        assertEquals(0.6, m1.networkScore(), EPS, "평균보다 시원한 쪽");
        assertEquals(0.4, m2.networkScore(), EPS, "평균보다 뜨거운 쪽");
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
        return node(id, temp, 30, 30);
    }

    /**
     * 자원 여유가 순위를 가르는 픽스처용 — CPU·메모리 사용률(%)만 달리한다.
     * 열 점수가 전부 중립이면 남는 변수는 자원뿐이라, 그때만 두 노드의 사용률을 벌린다.
     */
    private static NodeResource node(String id, double temp, double cpuPercent, double memPercent) {
        return new NodeResource("mdc-01", id, 8, 16384, 0, cpuPercent, memPercent, 40, 200, 500, temp, 1000, 500);
    }
}
