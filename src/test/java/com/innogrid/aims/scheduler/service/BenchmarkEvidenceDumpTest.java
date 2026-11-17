package com.innogrid.aims.scheduler.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.innogrid.aims.scheduler.benchmark.BenchmarkClusters;
import com.innogrid.aims.scheduler.benchmark.BenchmarkMetric;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules;
import com.innogrid.aims.scheduler.benchmark.BenchmarkWorkloads;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.PowerAwareScheduler;
import com.innogrid.aims.scheduler.engine.TemperatureAwareScheduler;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 증거 묶음 — 등록된 판 전부 × 워크로드 분포 2의 응답을 파일로 남긴다.
 *
 * <p>보고서의 문장이 지목할 원본이 지금은 "그때 돌린 응답"뿐이다. CI가 없어 로컬 테스트가 재현 산출물의 유일한 생산처이므로,
 * 결정론 테스트와 같은 경로({@code compareFresh})로 응답을 만들어 {@code build/benchmark-evidence/} 아래에
 * {@code <판>-<분포>-<판 다이제스트>-<워크로드 다이제스트>-<설정 다이제스트>.json}으로 쓰고, 매니페스트 한 장에 판·세
 * 다이제스트(판·워크로드·설정)·지표 키 목록과 다이제스트·규칙 축 다이제스트·JVM/OS/엔진 id·각 파일의 내용 해시를 모아 둔다.
 * 재생성: {@code ./gradlew test --tests '*BenchmarkEvidenceDumpTest*'} (매니페스트의 {@code regeneratedBy}에도 있다). 응답 시각({@code evaluatedAt})은 빼고 쓴다 — 같은 입력이면 파일이 바이트까지 같아야 대조가 된다.</p>
 *
 * <p>이 테스트는 값을 확인하지 않는다. 산출물이 실제로 쓰였고 매니페스트가 등록부와 일치하는지만 본다 — 값 판정은 규칙·계약
 * 테스트의 몫이다. 파일은 {@code build/} 아래라 저장소에 들어가지 않는다.</p>
 *
 * <p><b>판정 기준선 후보</b>도 여기서 같은 실행으로 쓴다({@code judgment-baseline-candidate.json}, 형식은 {@link JudgmentBaselines}).
 * 기준선 파일({@link BenchmarkRules#JUDGMENT_BASELINE_PATH})을 갱신하려면 이 후보를 그 경로로 복사하고 기준선 이력에 행을 더한다 —
 * 생산 경로가 하나여야 B와 N이 같은 조립에서 나온다. 매니페스트의 {@code entries}는 건드리지 않는다(옛 매니페스트와의 대조 축 보존).</p>
 */
class BenchmarkEvidenceDumpTest {

    private static final Path OUT = Path.of("build", "benchmark-evidence");
    private static final List<String> DISTRIBUTIONS = List.of("heavytail", "uniform");

    @DisplayName("등록된 판 전부 × 분포 2 의 응답을 파일로 남기고 매니페스트가 등록부와 일치한다")
    @Test
    void dumps_every_board_and_distribution_with_a_manifest() throws IOException {
        SchedulerProperties props = new SchedulerProperties();
        BenchmarkClusters clusters = new BenchmarkClusters();
        PlacementBenchmarkService service = new PlacementBenchmarkService(
                new EngineRegistry(List.of(new WeightedScoreScheduler(), new PowerAwareScheduler(), new TemperatureAwareScheduler())),
                props, clusters, new PowerModel(props), new ThermalModel(props));
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        Files.createDirectories(OUT);
        // 옛 리비전 산출물이 새 매니페스트 옆에 남지 않게 비운다 — 파일명에 다이제스트가 들어가 덮어써지지 않기 때문
        try (var old = Files.list(OUT)) {
            for (Path p : old.filter(p -> p.toString().endsWith(".json")).toList()) Files.delete(p);
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        List<String> engineIds = List.of();
        Map<String, PlacementBenchmarkResponse> judgmentDistributionResponses = new LinkedHashMap<>();
        for (String board : clusters.boardIds()) {
            for (String dist : DISTRIBUTIONS) {
                PlacementBenchmarkResponse r = service.compareFresh(board, dist);
                if (dist.equals(BenchmarkRules.JUDGMENT_DISTRIBUTION)) judgmentDistributionResponses.put(board, r);
                String configDigest = r.engineConfig().configDigest();
                String boardDigest = clusters.digest(board);
                assertEquals(boardDigest, r.boards().get(0).digest(), board + " 응답의 판 다이제스트가 등록부와 다르다");

                // evaluatedAt을 빼고 쓴다 — 같은 입력이면 바이트까지 같아야 한다
                PlacementBenchmarkResponse stable = new PlacementBenchmarkResponse(null, r.assumptions(), r.engineConfig(),
                        r.metrics(), r.boards(), r.limitation());
                engineIds = r.engineConfig().engineIds();
                String workloadDigest = BenchmarkWorkloads.digestOf("classpath:scheduler/benchmark-workloads-" + dist + ".yml");
                Path file = OUT.resolve(board + "-" + dist + "-" + boardDigest + "-" + workloadDigest + "-" + configDigest + ".json");
                String json = mapper.writeValueAsString(stable);
                Files.writeString(file, json, StandardCharsets.UTF_8);
                assertTrue(Files.size(file) > 10_000, file + " 가 너무 작다 — 응답이 비었다");

                Map<String, Object> e = new LinkedHashMap<>();
                e.put("board", board); e.put("distribution", dist); e.put("boardDigest", boardDigest);
                e.put("phaseMode", r.boards().get(0).phaseMode()); e.put("boardCount", r.boards().size());
                e.put("phaseSeeds", r.boards().stream().map(PlacementBenchmarkResponse.Board::phaseSeed).toList());
                e.put("workloadDigest", workloadDigest); e.put("configDigest", configDigest); e.put("file", file.getFileName().toString());
                e.put("sha256", BenchmarkClusters.digestOf(json));
                entries.add(e);
            }
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("generatedAt", OffsetDateTime.now().toString());
        manifest.put("gitHead", JudgmentBaselines.gitHead());
        manifest.put("gitHeadNote", "커밋되지 않은 변경이 있으면 이 해시가 산출 소스와 다를 수 있다 — 청결 여부는 이 시험이 확인하지 않는다");
        manifest.put("regeneratedBy", "./gradlew test --tests '*BenchmarkEvidenceDumpTest*'");
        manifest.put("representation", "재직렬화 표현 — ApiResponse 봉투 없음, 맵 키 정렬, evaluatedAt 제거. 서빙 응답과 바이트가 같지 않다");
        manifest.put("properties", "코드 기본 SchedulerProperties 로 생성 — 배포 env 의 덮어쓰기는 반영하지 않는다. 서빙 configDigest 와 다르면 그 차이다");
        manifest.put("engineIds", engineIds);
        manifest.put("jvm", System.getProperty("java.vendor") + " " + System.getProperty("java.runtime.version"));
        manifest.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " / " + java.util.TimeZone.getDefault().getID());
        Map<String, String> ruleDigests = new LinkedHashMap<>();
        BenchmarkRules.ruleAxes().forEach((k, v) -> ruleDigests.put(k, BenchmarkClusters.digestOf(v)));
        manifest.put("ruleAxisDigests", ruleDigests);
        manifest.put("boards", clusters.boardIds());
        List<String> metricKeys = BenchmarkRules.METRICS.stream().map(BenchmarkMetric::key).sorted().toList();
        manifest.put("metricKeys", metricKeys);
        manifest.put("metricKeyDigest", BenchmarkClusters.digestOf(String.join(",", metricKeys)));
        manifest.put("entries", entries);
        // 매니페스트 자신은 해시할 수 없으니(자기참조) entries의 정규 직렬화 해시를 싣는다 — 보고서는 gitHead + entriesDigest 쌍을 인용한다
        manifest.put("entriesDigest", BenchmarkClusters.digestOf(mapper.writeValueAsString(entries)));

        // 판정 기준선 후보 — entries와 별도의 최상위 키로. 기준선 파일 갱신은 이 후보를 저장소 경로로 복사하는 것이다
        String candidateJson = JudgmentBaselines.toJson(JudgmentBaselines.build(judgmentDistributionResponses, JudgmentBaselines.gitHead()));
        Path candidate = OUT.resolve("judgment-baseline-candidate.json");
        Files.writeString(candidate, candidateJson, StandardCharsets.UTF_8);
        Map<String, Object> baselineCandidate = new LinkedHashMap<>();
        baselineCandidate.put("file", candidate.getFileName().toString());
        baselineCandidate.put("sha256", BenchmarkClusters.digestOfContent(candidateJson.getBytes(StandardCharsets.UTF_8)));
        baselineCandidate.put("repositoryPath", BenchmarkRules.JUDGMENT_BASELINE_PATH);
        baselineCandidate.put("howToAdopt", "후보를 repositoryPath 로 복사하고 JudgmentBaselineTest.BASELINE_HISTORY 에 행(다이제스트·날짜·사유·B 리비전·직전 B 와의 중앙값 차)을 더한다 — 판정 보고를 게시한 뒤 별도 커밋으로");
        manifest.put("judgmentBaselineCandidate", baselineCandidate);
        Files.writeString(OUT.resolve("manifest.json"), mapper.writeValueAsString(manifest), StandardCharsets.UTF_8);

        assertEquals(clusters.boardIds().size() * DISTRIBUTIONS.size(), entries.size());
        assertEquals(entries.size(), entries.stream().map(e -> e.get("file")).distinct().count(), "산출 파일 이름이 겹친다 — 요청 축이 파일명에 빠졌다");
    }

}
