package com.innogrid.aims.scheduler.controller;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.innogrid.aims.scheduler.benchmark.BenchmarkMetric;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 배치 비교 API 의 HTTP 계약 — 응답 골격, 잘못된 값의 400 <b>본문</b>, 인증, 그리고 <b>저장 부작용 없음</b>.
 *
 * <p>이 진단은 우리 엔진이 대조군에 어떻게 나오는지를 밝히는 것이라 대외 산출물로 인용될 가능성이 높다.
 * 그래서 API 계층에서 지킬 것이 둘이다. 하나는 <b>숫자가 언제 의미를 갖는지 응답이 스스로 밝히는가</b>
 * (전제·유효성·한계·짝짓기 사유), 다른 하나는 <b>진단이 데이터를 만들지 않는가</b>다.</p>
 *
 * <p>모르는 판·분포를 400 <b>본문</b>으로 확인하는 이유: 처리되지 않은 예외는 {@code sendError} 를 타고
 * {@code /error} 로 다시 디스패치되며 그 경로는 인증을 요구하므로 <b>403 빈 응답</b>이 된다. 호출자는
 * 무엇이 틀렸는지 알 수 없고, 서버 로그에만 원인이 남는다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class PlacementBenchmarkApiTest {

    private static final String URL = "/api/v1/scheduler/benchmark/placement";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired SchedulingPolicyRepository policyRepository;
    @Autowired SchedulingHistoryRepository historyRepository;

    // ── 응답 골격 ─────────────────────────────────────────────

    @DisplayName("기본 호출은 주 결과판을 부하율 네 지점 × 전원 가정 두 판으로 돌려준다")
    @Test
    void the_default_call_returns_the_main_board_as_a_grid() throws Exception {
        JsonNode board = body(URL).path("data").path("boards").path(0);

        assertEquals("main", board.path("boardId").asString());
        assertEquals(14, board.path("nodeCount").asInt());
        assertEquals(14, board.path("measuredNodeCount").asInt());
        assertEquals(12, board.path("digest").asString().length(), "어느 파일로 낸 숫자인지 지목해야 한다");
        assertEquals(14, board.path("idleAssumptionGaps").size(), "노드 전량의 유휴 가정 격차를 실어야 한다");

        assertEquals(BenchmarkRules.LOAD_RATIOS.size() * 2, board.path("cells").size(),
                "부하율 네 지점 × 전원 가정 두 판 = 여덟 칸이어야 한다");

        Set<String> controls = new TreeSet<>();
        Set<Integer> ratios = new TreeSet<>();
        for (JsonNode cell : board.path("cells")) {
            controls.add(cell.path("powerControl").asString());
            ratios.add(cell.path("loadRatioPercent").asInt());
        }
        assertEquals(Set.of("AVAILABLE", "UNAVAILABLE"), controls);
        assertEquals(new TreeSet<>(BenchmarkRules.LOAD_RATIOS), ratios);

        // 가동 노드 증감의 기준선이 없으면 그 증감이 무엇에 대한 값인지 알 수 없다.
        JsonNode first = board.path("cells").path(0);
        assertTrue(first.path("activeNodesBefore").asDouble() > 0,
                "배치 전부터 돌던 노드 수를 밝혀야 한다");
        assertTrue(first.path("activeNodesBefore").asDouble() < board.path("nodeCount").asInt(),
                "전 노드가 이미 가동 중이면 깨우기 비용이 드러나지 않는다");
    }

    @DisplayName("설정 스냅샷이 숫자와 함께 JSON 응답에 실린다")
    @Test
    void the_engine_configuration_travels_with_the_numbers() throws Exception {
        JsonNode config = body(URL).path("data").path("engineConfig");

        assertEquals(12, config.path("configDigest").asString().length());
        assertEquals(3, config.path("engineIds").size());
        assertEquals(3, config.path("policies").size());
        assertTrue(config.path("power").has("marginalScaleWatts"),
                "한계 전력 점수의 분모가 응답에 없으면 두 표의 눈금이 같은지 알 수 없다");
        assertTrue(config.path("power").has("benchmarkPoweredOnCpuPercent"),
                "회계 문턱과 엔진 문턱을 나란히 실어야 둘이 갈렸는지 보인다");
        assertTrue(config.path("thermal").has("inertiaK"));
        assertTrue(body(URL).path("data").path("assumptions").has("generatorFormula"),
                "전력·온도가 실측이 아니라 식의 출력임을 전제 블록이 말해야 한다");
    }

    @DisplayName("셀마다 전략 열 열여덟 개가 사전 등록된 지표 전량을 싣는다")
    @Test
    void every_cell_carries_eleven_strategies_with_every_declared_metric() throws Exception {
        Set<String> declared = BenchmarkRules.METRICS.stream()
                .map(BenchmarkMetric::key).collect(Collectors.toCollection(TreeSet::new));

        for (JsonNode cell : body(URL).path("data").path("boards").path(0).path("cells")) {
            JsonNode strategies = cell.path("strategies");
            assertEquals(18, strategies.size(), "엔진 3 + 변종 7(자원전용 2 · 링크 게이트 1 · 눈금 3 · 역산 차단 1) + 스케줄 배치 순서 2 + 대조군 6 = 열여덟 열이어야 한다");

            for (JsonNode s : strategies) {
                Set<String> keys = new TreeSet<>();
                for (JsonNode m : s.path("metrics")) {
                    keys.add(m.path("key").asString());
                    assertTrue(m.has("valid"), "이 숫자를 순위 근거로 써도 되는지 밝혀야 한다");
                    assertTrue(m.has("samples"), "몇 개 지점의 값인지 밝혀야 한다");
                    assertTrue(m.has("min") && m.has("max"), "폭이 없으면 오차 막대를 붙일 수 없다");
                }
                assertEquals(declared, keys, s.path("strategyId").asString());
                assertTrue(List.of("ENGINE", "BASELINE").contains(s.path("kind").asString()),
                        "우리 엔진과 대조군을 섞어 놓지 않아야 한다: " + s.path("kind").asString());
            }
        }
    }

    @DisplayName("지표 선언이 응답 최상위에 실려 정의와 좋은 방향을 함께 준다")
    @Test
    void the_metric_declarations_travel_with_the_numbers() throws Exception {
        JsonNode metrics = body(URL).path("data").path("metrics");

        assertEquals(BenchmarkRules.METRICS.size(), metrics.size());
        for (JsonNode m : metrics) {
            assertFalse(m.path("label").asString().isBlank(), m.path("key").asString());
            assertFalse(m.path("validity").asString().isBlank(),
                    m.path("key").asString() + ": 이 숫자가 언제 의미를 갖는지 없으면 유효 판정의 근거가 없다");
            assertTrue(List.of("LOWER_IS_BETTER", "HIGHER_IS_BETTER", "CONTEXT_ONLY")
                    .contains(m.path("direction").asString()));
        }

        JsonNode believed = stream(metrics)
                .filter(m -> BenchmarkRules.ENGINE_BELIEVED_COST.equals(m.path("key").asString()))
                .findFirst().orElseThrow();
        assertEquals("CONTEXT_ONLY", believed.path("direction").asString(),
                "엔진 목적함수와 같은 함수에 좋은 방향을 주면 순환 논증이다");
    }

    @DisplayName("전제와 한계가 응답에 있고 승자를 지명하는 필드는 없다")
    @Test
    void the_response_states_its_premises_and_names_no_winner() throws Exception {
        JsonNode data = body(URL).path("data");

        assertEquals(BenchmarkRules.HEADROOM_DEFINITION,
                data.path("assumptions").path("headroomDefinition").asString());
        assertEquals(0, data.path("assumptions").path("cooldownMinutes").asInt());
        assertTrue(data.path("assumptions").path("policyBasis").asString().contains("활성 정책"),
                "배포된 활성 정책이 아니라는 사실을 밝혀야 한다");
        assertTrue(data.path("assumptions").path("powerControlNote").asString().contains("UNAVAILABLE"));
        assertEquals(BenchmarkRules.LIMITATION, data.path("limitation").asString());

        String json = objectMapper.writeValueAsString(data).toLowerCase();
        assertFalse(json.contains("\"winner\""), "승자를 계산해 주면 그 필드만 인용된다");
        assertFalse(json.contains("\"rank\""), "순위를 계산해 주면 판 이름과 분리되어 인용된다");
    }

    /** 총량 열을 비교할 수 없는 셀은 사유를 <b>같은 자리에</b> 싣는다 — 각주로 밀면 인용될 때 떨어진다. */
    @DisplayName("총량 비교의 짝짓기 사유가 셀에 함께 실린다")
    @Test
    void the_pairing_note_travels_inside_the_cell() throws Exception {
        int checked = 0;
        for (JsonNode cell : body(URL).path("data").path("boards").path(0).path("cells")) {
            if (cell.path("totalsComparable").asBoolean()) {
                assertTrue(cell.path("totalsIncomparableReason").isNull(),
                        "짝이 온전한데 사유가 붙었다: " + cell.path("totalsIncomparableReason").asString());
                continue;
            }
            String reason = cell.path("totalsIncomparableReason").asString();
            assertTrue(reason.contains("건"), "무엇이 어긋났는지 건수로 밝혀야 한다: " + reason);
            checked++;
        }
        assertTrue(checked > 0, "짝이 어긋난 셀이 없다 — 수용 불가 부하 지점이 전략을 구분하지 못한다는 뜻이다");
    }

    // ── 판과 분포 ─────────────────────────────────────────────

    @DisplayName("계측 공백판은 미계측 노드 다섯 대와 함께 센서 공백 경고를 돌려준다")
    @Test
    void the_sensor_gap_board_returns_its_own_warning() throws Exception {
        JsonNode board = body(URL + "?board=sparse").path("data").path("boards").path(0);

        assertEquals("sparse", board.path("boardId").asString());
        assertEquals(14, board.path("nodeCount").asInt());
        assertEquals(9, board.path("measuredNodeCount").asInt());

        boolean warned = false;
        for (JsonNode cell : board.path("cells")) {
            if (!cell.path("unmeasuredCaution").isNull()) {
                assertTrue(cell.path("unmeasuredCaution").asString().contains("센서"),
                        cell.path("unmeasuredCaution").asString());
                warned = true;
            }
        }
        assertTrue(warned, "계측이 빈 판인데 경고가 하나도 없다 — 정직성 지표가 작동하지 않는다");
    }

    @DisplayName("반증판은 자기가 우리 엔진이 지도록 설계된 판임을 밝힌다")
    @Test
    void the_adversarial_board_declares_its_intent() throws Exception {
        JsonNode board = body(URL + "?board=adversarial").path("data").path("boards").path(0);

        assertEquals("adversarial", board.path("boardId").asString());
        String role = board.path("role").asString();
        assertTrue(role.contains("지도록") || role.contains("반증"),
                "심은 데이터에 대한 방어는 반대로 심은 판을 함께 내는 것이다: " + role);
    }

    @DisplayName("판 전제 블록이 생성식 지수와 유휴 역산 점검표를 함께 싣는다")
    @Test
    void the_board_carries_its_exponent_and_the_wakeup_reconstruction_check() throws Exception {
        JsonNode main = body(URL).path("data").path("boards").path(0);
        assertEquals(1.0, main.path("powerExponent").asDouble(), 1e-9, "주 결과판은 선형이다");

        JsonNode convex = body(URL + "?board=convex").path("data").path("boards").path(0);
        assertEquals(BenchmarkRules.CONVEX_POWER_EXPONENT, convex.path("powerExponent").asDouble(), 1e-9,
                "판이 실제로 쓴 지수가 응답에 없으면 전제 블록의 생성식이 가리킬 값이 없다");

        JsonNode rows = convex.path("wakeupReconstructionCheck");
        assertTrue(rows.size() > 0, "볼록 판인데 점검표가 비었다 — 역산이 항등이 아님을 보일 자리가 없다");
        for (JsonNode row : rows) {
            for (String field : List.of("nodeId", "measured", "declaredIdleWatts",
                    "engineFallbackWatts", "engineWakeupMinWatts", "engineWakeupMaxWatts")) {
                assertTrue(row.has(field), field + " 열이 없다: " + row);
            }
            assertFalse(row.has("gapWatts") || row.has("ratio"),
                    "점검표는 판정·비율 열을 만들지 않는다 — 어느 쪽이 옳은지는 이 표가 말하지 않는다: " + row);
        }
    }

    @DisplayName("복합 판정판도 같은 경로로 돌고 자기가 판정판임을 밝힌다")
    @Test
    void the_judgment_board_runs_through_the_same_endpoint() throws Exception {
        JsonNode board = body(URL + "?board=" + BenchmarkRules.JUDGMENT_BOARD).path("data").path("boards").path(0);

        assertEquals(BenchmarkRules.JUDGMENT_BOARD, board.path("boardId").asString());
        assertEquals(14, board.path("nodeCount").asInt(),
                "판정판은 주 결과판과 같은 모집단이어야 판 사이의 차이를 요인으로 읽을 수 있다");
        assertEquals(14, board.path("measuredNodeCount").asInt());
        assertTrue(board.path("role").asString().contains("판정판"),
                "이 판이 무엇을 판정하는 자리인지 응답이 스스로 밝혀야 한다: " + board.path("role").asString());
    }

    @DisplayName("균일 분포로도 돌아가고 분포 이름이 응답에 실린다")
    @Test
    void the_uniform_distribution_runs_too() throws Exception {
        JsonNode data = body(URL + "?distribution=uniform").path("data");

        assertEquals("uniform-4class", data.path("assumptions").path("workloadDistribution").asString());
        for (JsonNode c : data.path("assumptions").path("workloadClasses")) {
            assertEquals(25, c.path("weightPercent").asInt(), "균일 분포의 구성비는 전부 같다");
        }
    }

    // ── 잘못된 값 ─────────────────────────────────────────────

    @DisplayName("모르는 판은 사유를 담은 400 이다 — 403 빈 응답이 아니다")
    @Test
    void an_unknown_board_returns_four_hundred_with_a_body() throws Exception {
        MvcResult result = mockMvc.perform(get(URL + "?board=no-such-board"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("no-such-board"), body);
        assertTrue(body.contains("main"), "쓸 수 있는 판을 알려줘야 한다: " + body);
    }

    @DisplayName("모르는 분포는 사유를 담은 400 이다")
    @Test
    void an_unknown_distribution_returns_four_hundred_with_a_body() throws Exception {
        MvcResult result = mockMvc.perform(get(URL + "?distribution=no-such-distribution"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("no-such-distribution"), body);
        assertTrue(body.contains("heavytail"), "쓸 수 있는 분포를 알려줘야 한다: " + body);
    }

    @DisplayName("인증 없이는 부르지 못한다")
    @Test
    void the_endpoint_requires_authentication() throws Exception {
        mockMvc.perform(get(URL).with(anonymous()))
                .andExpect(status().is4xxClientError());
    }

    // ── 저장 부작용 없음 ──────────────────────────────────────

    /**
     * 진단이 데이터를 만들지 않는다. 활성 정책 조회 경로는 정책이 없으면 기본 정책을 <b>새로 넣고</b>,
     * 저장된 워크로드를 쓰는 경로는 배치 이력을 남긴다. 둘 다 응답만 봐서는 드러나지 않으므로
     * 세 저장소의 개수를 호출 전후로 비교한다.
     */
    @DisplayName("여러 번 불러도 워크로드·정책·배치 이력이 늘지 않는다")
    @Test
    void repeated_calls_create_no_rows() throws Exception {
        long workloads = workloadRepository.count();
        long policies = policyRepository.count();
        long history = historyRepository.count();

        body(URL);
        body(URL + "?board=sparse");
        body(URL + "?board=adversarial&distribution=uniform");

        assertEquals(workloads, workloadRepository.count(), "비교가 워크로드를 저장했다");
        assertEquals(policies, policyRepository.count(),
                "비교가 정책을 저장했다 — 활성 정책 조회 경로를 탔을 수 있다");
        assertEquals(history, historyRepository.count(), "비교가 배치 이력을 남겼다");
    }

    // ── 도움 메서드 ───────────────────────────────────────────

    /** 이름을 {@code get} 으로 두면 정적 임포트한 요청 빌더를 가려 컴파일이 엉킨다. */
    private JsonNode body(String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static java.util.stream.Stream<JsonNode> stream(JsonNode array) {
        java.util.List<JsonNode> nodes = new java.util.ArrayList<>();
        array.forEach(nodes::add);
        return nodes.stream();
    }
}
