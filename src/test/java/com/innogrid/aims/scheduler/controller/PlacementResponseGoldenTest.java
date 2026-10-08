package com.innogrid.aims.scheduler.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.innogrid.aims.scheduler.engine.PowerAwareScheduler;
import com.innogrid.aims.scheduler.engine.SchedulingEngine;
import com.innogrid.aims.scheduler.engine.TemperatureAwareScheduler;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.engine.constraint.ExclusionReason;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.PlacementDispatchRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;
import com.innogrid.aims.scheduler.service.NodeResourceCollector;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 배치 결정 응답의 기준 파일 시험.
 *
 * <p><b>값 층</b> — 엔진 3종을 고정 입력(노드·워크로드·정책)으로 직접 불러 결정 결과 전체(키 순서·타입·값)를
 * {@code src/test/resources/scheduler/golden/placement/<시나리오>.json} 과 문자열로 비교한다. 같은 매퍼로 들여쓰기
 * 출력한 문자열을 비교하므로 키 순서가 바뀌면 실패한다(트리 비교는 순서를 보지 않아 쓰지 않는다).</p>
 *
 * <p><b>HTTP 층</b> — 단건 배치·모의 배치·저장 전 모의 배치 응답의 키 순서(일곱 키 · 후보 열두 키 · 엔진별 상세 키)를
 * 고정한다. 공유 H2 상태(정책·예약)가 점수를 흔들 수 있어 점수 값은 보지 않는다. 실패·노드 없음 응답의 선택 칸 null · 제외 코드 ·
 * 빈 후보 목록(상태와 무관)과 단건 배치의 성공 여부(활성 정책에 기댐)만 본다.</p>
 *
 * <p><b>운용 규칙</b> — 기준 파일이 없거나 다르면 실제 결과를 {@code build/golden-candidates/placement/} 에 쓰고 실패한다
 * (소스 아래에는 쓰지 않는다 · 기준과 같으면 옛 후보는 지운다). 다시 만들 때는
 * {@code gradlew test --tests "*PlacementResponseGoldenTest" --no-build-cache --rerun-tasks} 로 돌려 후보를 검토한 뒤 복사한다.
 * 기준 파일은 시험 입력으로 잡히도록 {@code src/test/resources} 아래에 둔다. 출력을 바꾸는 코드 변경은 같은 커밋에서 기준 파일을
 * 갱신하고, 커밋 본문에 바뀐 시나리오·키·이유를 적는다. 기준 파일만 고치는 커밋은 두지 않으며, 시나리오 추가는 test 커밋으로
 * 한다. 값은 설정 기본값·정책 기본값·엔진 상수로 계산하므로 이 중 하나가 바뀌어 값이 움직이면 그 원인(설정 다이제스트 변화
 * 포함)을 함께 적는다. 동작이 바뀌지 않아야 하는 리팩터링은 기준 파일과 시나리오 입력·추가 확인을 그대로 둔 채 통과해야
 * 한다(엔진을 만드는 방식은 고쳐도 된다).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class PlacementResponseGoldenTest {

    private static final String BASE = "/api/v1/scheduler/workloads";
    private static final String PREFIX = "gld-";
    private static final Path GOLDEN_DIR = Path.of("src", "test", "resources", "scheduler", "golden", "placement");
    private static final Path CANDIDATE_DIR = Path.of("build", "golden-candidates", "placement");

    /** 응답 최상위·결정·후보의 키 순서. */
    private static final List<String> TOP_KEYS = List.of("success", "data");
    private static final List<String> DATA_KEYS = List.of("success", "selectedMdcId", "selectedNodeId", "finalScore", "reason",
            "candidateScores", "policyName");
    private static final List<String> NODE_SCORE_KEYS = List.of("mdcId", "nodeId", "cpuScore", "memoryScore", "powerScore",
            "temperatureScore", "networkScore", "totalScore", "eligible", "exclusionReason", "exclusionCode", "details");
    /** 노드 한 대 · 디스크 요구 0 · 데이터 MDC 없음일 때의 엔진별 상세 키(조건부 키 없음). */
    private static final Map<String, List<String>> DETAILS_KEYS = Map.of(
            "weighted", List.of("cpu여유", "메모리여유", "전력여유", "온도여유", "네트워크한산도"),
            "power", List.of("전력계측", "온도계측", "PEI", "비낭비", "실질한계전력점수", "전력점수", "열점수", "cpu여유", "메모리여유",
                    "네트워크한산도", "실질한계전력W", "배치후예상전력W", "배치후예상온도C", "가동중"),
            "temperature", List.of("온도계측", "열적여유", "열적균형", "냉각점수", "자원여유", "배치후예상온도C", "클러스터평균온도C"));

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired SchedulingHistoryRepository historyRepository;
    @Autowired PlacementDispatchRepository dispatchRepository;
    @MockitoBean NodeResourceCollector collector;

    @AfterEach
    void clean() {
        dispatchRepository.findAll().stream().filter(d -> d.getWorkloadName() != null && d.getWorkloadName().startsWith(PREFIX))
                .forEach(dispatchRepository::delete);
        historyRepository.findAll().stream().filter(h -> h.getWorkloadName() != null && h.getWorkloadName().startsWith(PREFIX))
                .forEach(historyRepository::delete);
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith(PREFIX)).forEach(workloadRepository::delete);
    }

    // ── 값 층 ──────────────────────────────────────────────────────────────

    /** 생성자 인자 순서: mdc, id, cpu, memMb, gpu, cpu%, mem%, disk%, W, capW, °C, rx, tx. 매번 새 객체를 만든다. */
    private static NodeResource active() { return new NodeResource("gld-mdc-1", "gld-active:9100", 8, 16_384, 0, 50, 40, 0, 250, 500, 55, 1_000, 500); }
    private static NodeResource idle() { return new NodeResource("gld-mdc-2", "gld-idle:9100", 8, 16_384, 0, 1, 10, 0, 150, 500, 40, 0, 0); }
    private static NodeResource tiny() { return new NodeResource("gld-mdc-1", "gld-tiny:9100", 1, 512, 0, 1, 10, 0, 120, 500, 38, 0, 0); }
    private static NodeResource bare() { return new NodeResource("gld-mdc-1", "gld-bare:9100", 4, 8_192, 0, 12.5, 30, 0, 0, 0, 0, 0, 0); }
    private static NodeResource activeDisk() { return active().withDiskCapacityGb(500); }
    private static List<NodeResource> three() { return List.of(active(), idle(), tiny()); }
    private static List<NodeResource> threeDisk() { return List.of(activeDisk(), idle(), tiny()); }

    private static Workload small() { return new Workload("gld-w", 2, 1024); }
    private static Workload big() { return new Workload("gld-big", 16, 1024); }
    private static Workload disk() { Workload w = small(); w.setRequiredDiskGb(50); return w; }
    private static Workload diskLink() { Workload w = disk(); w.setDataMdcId("gld-mdc-1"); return w; }

    private static SchedulingPolicy policy() { return new SchedulingPolicy("golden", "기준 파일 시험"); }
    private static SchedulingPolicy linkPolicy() { SchedulingPolicy p = policy(); p.setAffinityEnabled(true); return p; }

    private static SchedulingEngine engine(String id) {
        return switch (id) {
            case "weighted" -> new WeightedScoreScheduler();
            case "power" -> new PowerAwareScheduler();
            case "temperature" -> new TemperatureAwareScheduler();
            default -> throw new IllegalArgumentException(id);
        };
    }

    record Scenario(String name, String engine, Supplier<List<NodeResource>> nodes, Supplier<Workload> workload,
                    Supplier<SchedulingPolicy> policy, Consumer<SchedulingResult> intent) {
        @Override public String toString() { return name; }
    }

    static Stream<Scenario> scenarios() {
        return Stream.of(
                new Scenario("weighted-success", "weighted", PlacementResponseGoldenTest::three, PlacementResponseGoldenTest::small, PlacementResponseGoldenTest::policy, r -> {
                    assertTrue(r.success()); assertEquals(3, r.candidateScores().size());
                    assertEquals(List.of("gld-tiny:9100"), excluded(r, ExclusionReason.INSUFFICIENT_RESOURCE));
                    assertEquals(List.of(5, 5, 5), detailCounts(r));
                }),
                new Scenario("weighted-insufficient", "weighted", PlacementResponseGoldenTest::three, PlacementResponseGoldenTest::big, PlacementResponseGoldenTest::policy, r -> {
                    assertFalse(r.success()); assertNull(r.selectedNodeId());
                    assertEquals(3, excluded(r, ExclusionReason.INSUFFICIENT_RESOURCE).size());
                }),
                new Scenario("weighted-disk-link", "weighted", PlacementResponseGoldenTest::threeDisk, PlacementResponseGoldenTest::diskLink, PlacementResponseGoldenTest::linkPolicy, r -> {
                    r.candidateScores().forEach(c -> assertTrue(c.details().containsKey("디스크검사") && c.details().containsKey("링크품질")
                            && c.details().containsKey("링크등록"), c.nodeId()));
                }),
                new Scenario("power-success-margin", "power", PlacementResponseGoldenTest::three, PlacementResponseGoldenTest::small, PlacementResponseGoldenTest::policy, r -> {
                    assertTrue(r.success());
                    r.candidateScores().forEach(c -> assertEquals("통합여유", lastKey(c.details()), c.nodeId()));
                    assertEquals(List.of(15, 15, 15), detailCounts(r));
                }),
                new Scenario("power-disk-margin", "power", PlacementResponseGoldenTest::threeDisk, PlacementResponseGoldenTest::disk, PlacementResponseGoldenTest::policy, r -> {
                    r.candidateScores().forEach(c -> {
                        assertEquals("디스크검사", new ArrayList<>(c.details().keySet()).get(10), c.nodeId());
                        assertEquals("통합여유", lastKey(c.details()), c.nodeId());
                    });
                }),
                new Scenario("power-unmeasured", "power", () -> List.of(bare()), PlacementResponseGoldenTest::small, PlacementResponseGoldenTest::policy, r -> {
                    Map<String, Double> d = r.candidateScores().get(0).details();
                    assertEquals(14, d.size()); assertFalse(d.containsKey("통합여유"));
                    assertEquals(4, d.values().stream().filter(v -> v == null).count(), "미계측 null 칸");
                }),
                new Scenario("power-insufficient", "power", PlacementResponseGoldenTest::three, PlacementResponseGoldenTest::big, PlacementResponseGoldenTest::policy, r -> {
                    assertFalse(r.success()); r.candidateScores().forEach(c -> assertFalse(c.details().containsKey("통합여유")));
                }),
                new Scenario("temperature-success", "temperature", PlacementResponseGoldenTest::three, PlacementResponseGoldenTest::small, PlacementResponseGoldenTest::policy, r -> {
                    assertTrue(r.success()); assertEquals(List.of("gld-tiny:9100"), excluded(r, ExclusionReason.INSUFFICIENT_RESOURCE));
                    assertEquals(List.of(7, 7, 7), detailCounts(r));
                }),
                new Scenario("temperature-disk", "temperature", PlacementResponseGoldenTest::threeDisk, PlacementResponseGoldenTest::disk, PlacementResponseGoldenTest::policy, r -> {
                    r.candidateScores().forEach(c -> assertEquals("디스크검사", new ArrayList<>(c.details().keySet()).get(5), c.nodeId()));
                }),
                new Scenario("temperature-unmeasured", "temperature", () -> List.of(bare()), PlacementResponseGoldenTest::small, PlacementResponseGoldenTest::policy, r -> {
                    Map<String, Double> d = r.candidateScores().get(0).details();
                    assertNull(d.get("배치후예상온도C")); assertNull(d.get("클러스터평균온도C"));
                }),
                new Scenario("temperature-insufficient", "temperature", PlacementResponseGoldenTest::three, PlacementResponseGoldenTest::big, PlacementResponseGoldenTest::policy, r -> {
                    assertFalse(r.success());
                }));
    }

    @DisplayName("엔진 결정 결과가 기준 파일과 키 순서·타입·값까지 같다")
    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void engine_results_match_golden_files(Scenario s) throws IOException {
        SchedulingPolicy p = s.policy().get();
        SchedulingResult result = engine(s.engine()).schedule(s.workload().get(), s.nodes().get(), p).withPolicyName(p.getName());
        assertGolden(s.name(), result);   // 기준 파일 비교가 먼저 — 다르면 후보를 남긴 뒤 실패한다
        assertSelectionRule(result);      // 기준 파일을 다시 만들어도 남는 세 엔진 공통 규칙
        s.intent().accept(result);
    }

    /** 후보는 총점 내림차순, 적격 후보가 있을 때만 성공, 선택은 적격 후보 중 최고점(동점이면 입력 순서 앞). */
    private static void assertSelectionRule(SchedulingResult r) {
        List<SchedulingResult.NodeScore> c = r.candidateScores();
        for (int i = 1; i < c.size(); i++) assertTrue(c.get(i - 1).totalScore() >= c.get(i).totalScore(), "총점 내림차순");
        List<SchedulingResult.NodeScore> eligible = c.stream().filter(SchedulingResult.NodeScore::eligible).toList();
        assertEquals(!eligible.isEmpty(), r.success(), "적격 후보가 있을 때만 성공");
        if (!r.success()) { assertNull(r.selectedMdcId()); assertNull(r.selectedNodeId()); return; }
        assertEquals(eligible.get(0).nodeId(), r.selectedNodeId(), "부적격 최고점이 아니라 적격 최고점을 고른다");
        assertEquals(eligible.get(0).mdcId(), r.selectedMdcId());
        assertEquals(eligible.get(0).totalScore(), r.finalScore());
    }

    @DisplayName("기준 파일 디렉터리에 시나리오 밖의 파일이 없다")
    @Test
    void golden_directory_has_no_orphans() throws IOException {
        Set<String> expected = new TreeSet<>();
        scenarios().forEach(s -> expected.add(s.name() + ".json"));
        Set<String> actual = new TreeSet<>();
        try (Stream<Path> files = Files.list(GOLDEN_DIR)) {
            files.forEach(f -> actual.add(f.getFileName().toString()));
        }
        assertEquals(expected, actual);
    }

    // ── HTTP 층 ────────────────────────────────────────────────────────────

    private void oneNode() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                List.of(new NodeResource("gld-mdc", "gld-node:9100", 8, 65_536, 0, 10, 20, 0, 100, 500, 36.5, 0, 0)), List.of()));
    }

    @DisplayName("모의 배치 응답은 엔진마다 일곱 키·열두 키·상세 키를 그 순서로 낸다")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"weighted", "power", "temperature"})
    void dry_run_keeps_key_order(String engine) throws Exception {
        oneNode();
        long id = workloadRepository.saveAndFlush(new Workload(PREFIX + "dry-" + engine, 1, 64)).getId();
        JsonNode body = objectMapper.readTree(mockMvc.perform(post(BASE + "/" + id + "/schedule").param("dryRun", "true").param("engine", engine))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertShape(body, engine);
        assertEquals(Workload.WorkloadStatus.PENDING, workloadRepository.findById(id).orElseThrow().getStatus());
    }

    @DisplayName("저장 전 모의 배치 응답도 같은 키 순서다")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"weighted", "power", "temperature"})
    void draft_dry_run_keeps_key_order(String engine) throws Exception {
        oneNode();
        JsonNode body = objectMapper.readTree(mockMvc.perform(post(BASE + "/dry-run").param("engine", engine).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"gld-draft\",\"requiredCpu\":1,\"requiredMemoryMb\":64}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertShape(body, engine);
    }

    @DisplayName("실제 단건 배치 응답도 같은 키 순서다")
    @Test
    void single_schedule_keeps_key_order() throws Exception {
        oneNode();
        long id = workloadRepository.saveAndFlush(new Workload(PREFIX + "single", 1, 64)).getId();
        JsonNode body = objectMapper.readTree(mockMvc.perform(post(BASE + "/" + id + "/schedule").param("engine", "weighted"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertShape(body, "weighted");
        assertTrue(body.get("data").get("success").asBoolean());
    }

    @DisplayName("실패한 모의 배치는 선택 칸을 null 로 두고 제외 코드를 낸다")
    @Test
    void failed_dry_run_keeps_null_keys_and_code() throws Exception {
        oneNode();
        long id = workloadRepository.saveAndFlush(new Workload(PREFIX + "big", 16, 64)).getId();
        JsonNode body = objectMapper.readTree(mockMvc.perform(post(BASE + "/" + id + "/schedule").param("dryRun", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertShape(body, "weighted");
        JsonNode data = body.get("data");
        assertFalse(data.get("success").asBoolean());
        assertTrue(data.get("selectedMdcId").isNull() && data.get("selectedNodeId").isNull(), "키는 있고 값은 null");
        assertEquals(0.0, data.get("finalScore").asDouble());
        assertEquals("INSUFFICIENT_RESOURCE", data.get("candidateScores").get(0).get("exclusionCode").asString());
    }

    @DisplayName("노드가 없으면 일곱 키와 빈 후보 목록을 낸다")
    @Test
    void no_nodes_keeps_seven_keys() throws Exception {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(List.of(), List.of()));
        long id = workloadRepository.saveAndFlush(new Workload(PREFIX + "none", 1, 64)).getId();
        JsonNode data = objectMapper.readTree(mockMvc.perform(post(BASE + "/" + id + "/schedule").param("dryRun", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        assertEquals(DATA_KEYS, names(data));
        assertFalse(data.get("success").asBoolean());
        assertTrue(data.get("candidateScores").isArray(), "빈 배열이지 null 이 아니다");
        assertEquals(0, data.get("candidateScores").size());
        assertTrue(data.get("policyName").isString());
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private void assertGolden(String name, SchedulingResult result) throws IOException {
        String actual = pretty(objectMapper.valueToTree(result));
        Path golden = GOLDEN_DIR.resolve(name + ".json");
        String expected = Files.exists(golden) ? pretty(objectMapper.readTree(Files.readString(golden, StandardCharsets.UTF_8))) : null;
        if (!actual.equals(expected)) {
            Files.createDirectories(CANDIDATE_DIR);
            Files.writeString(CANDIDATE_DIR.resolve(name + ".json"), actual, StandardCharsets.UTF_8);
        } else {
            Files.deleteIfExists(CANDIDATE_DIR.resolve(name + ".json"));
        }
        assertEquals(expected, actual, name + ": 기준 파일과 다르다(" + (expected == null ? "파일 없음" : "내용 차이") + "). 후보를 "
                + CANDIDATE_DIR + " 에 썼다 — 의도한 변경이면 검토 뒤 " + GOLDEN_DIR + " 로 복사하고 같은 커밋에서 이유를 적는다");
    }

    /** 같은 매퍼의 들여쓰기 출력(개행은 LF 로 통일 · 끝 개행 1). 키 순서·타입·값이 그대로 문자열에 드러난다. */
    private String pretty(JsonNode node) {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node).replace("\r\n", "\n").stripTrailing() + "\n";
    }

    private static void assertShape(JsonNode body, String engine) {
        assertEquals(TOP_KEYS, names(body));
        JsonNode data = body.get("data");
        assertEquals(DATA_KEYS, names(data), "일곱 키");
        assertEquals(1, data.get("candidateScores").size());
        for (JsonNode c : data.get("candidateScores")) {
            assertEquals(NODE_SCORE_KEYS, names(c), "열두 키");
            assertEquals(DETAILS_KEYS.get(engine), names(c.get("details")), engine + " 상세 키");
        }
    }

    private static List<String> excluded(SchedulingResult r, ExclusionReason code) {
        return r.candidateScores().stream().filter(c -> c.exclusionCode() == code).map(SchedulingResult.NodeScore::nodeId).toList();
    }

    private static List<Integer> detailCounts(SchedulingResult r) {
        return r.candidateScores().stream().map(c -> c.details().size()).toList();
    }

    private static String lastKey(Map<String, Double> details) {
        List<String> keys = new ArrayList<>(details.keySet());
        return keys.get(keys.size() - 1);
    }

    private static List<String> names(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.propertyNames().forEach(keys::add);
        return keys;
    }
}
