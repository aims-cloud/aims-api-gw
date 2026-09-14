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
 * 증거 묶음 — 등록된 판 전부 × 워크로드 분포 2 의 응답을 파일로 남긴다.
 *
 * <p>보고서의 문장이 지목할 원본이 지금은 "그때 돌린 응답" 뿐이다. CI 가 없어 로컬 시험이 재현 산출물의 유일한 생산처이므로,
 * 결정론 시험과 같은 경로({@code compareFresh})로 응답을 만들어 {@code build/benchmark-evidence/} 아래에
 * {@code <판>-<분포>-<판 다이제스트>-<설정 다이제스트>.json} 으로 쓰고, 매니페스트 한 장에 판·두 다이제스트·지표 키
 * 다이제스트를 모아 둔다. 응답 시각({@code evaluatedAt})은 빼고 쓴다 — 같은 입력이면 파일이 바이트까지 같아야 대조가 된다.</p>
 *
 * <p>이 시험은 값을 단언하지 않는다. 산출물이 실제로 쓰였고 매니페스트가 등록부와 일치하는지만 본다 — 값 판정은 규칙·계약
 * 시험의 몫이다. 파일은 {@code build/} 아래라 저장소에 들어가지 않는다.</p>
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
        for (String board : clusters.boardIds()) {
            for (String dist : DISTRIBUTIONS) {
                PlacementBenchmarkResponse r = service.compareFresh(board, dist);
                String configDigest = r.engineConfig().configDigest();
                String boardDigest = clusters.digest(board);
                assertEquals(boardDigest, r.boards().get(0).digest(), board + " 응답의 판 다이제스트가 등록부와 다르다");

                // evaluatedAt 을 빼고 쓴다 — 같은 입력이면 바이트까지 같아야 한다
                PlacementBenchmarkResponse stable = new PlacementBenchmarkResponse(null, r.assumptions(), r.engineConfig(),
                        r.metrics(), r.boards(), r.limitation());
                engineIds = r.engineConfig().engineIds();
                String workloadDigest = BenchmarkWorkloads.digestOf("classpath:scheduler/benchmark-workloads-" + dist + ".yml");
                Path file = OUT.resolve(board + "-" + dist + "-" + boardDigest + "-" + workloadDigest + "-" + configDigest + ".json");
                Files.writeString(file, mapper.writeValueAsString(stable), StandardCharsets.UTF_8);
                assertTrue(Files.size(file) > 10_000, file + " 가 너무 작다 — 응답이 비었다");

                Map<String, Object> e = new LinkedHashMap<>();
                e.put("board", board); e.put("distribution", dist); e.put("boardDigest", boardDigest);
                e.put("workloadDigest", workloadDigest); e.put("configDigest", configDigest); e.put("file", file.getFileName().toString());
                entries.add(e);
            }
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("generatedAt", OffsetDateTime.now().toString());
        manifest.put("gitHead", gitHead());
        manifest.put("representation", "재직렬화 표현 — ApiResponse 봉투 없음, 맵 키 정렬, evaluatedAt 제거. 서빙 응답과 바이트가 같지 않다");
        manifest.put("properties", "코드 기본 SchedulerProperties 로 생성 — 배포 env 의 덮어쓰기는 반영하지 않는다. 서빙 configDigest 와 다르면 그 차이다");
        manifest.put("engineIds", engineIds);
        manifest.put("jvm", System.getProperty("java.vendor") + " " + System.getProperty("java.runtime.version"));
        manifest.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " / " + java.util.TimeZone.getDefault().getID());
        Map<String, String> ruleDigests = new LinkedHashMap<>();
        BenchmarkRules.ruleAxes().forEach((k, v) -> ruleDigests.put(k, BenchmarkClusters.digestOf(v)));
        manifest.put("ruleAxisDigests", ruleDigests);
        manifest.put("boards", clusters.boardIds());
        manifest.put("metricKeyDigest", BenchmarkClusters.digestOf(
                BenchmarkRules.METRICS.stream().map(BenchmarkMetric::key).sorted().reduce((a, b) -> a + "," + b).orElse("")));
        manifest.put("entries", entries);
        Files.writeString(OUT.resolve("manifest.json"), mapper.writeValueAsString(manifest), StandardCharsets.UTF_8);

        assertEquals(clusters.boardIds().size() * DISTRIBUTIONS.size(), entries.size());
    }

    /** 셸 없이 .git/HEAD 를 읽는다 — 보고서가 "어느 소스 리비전의 표인가" 를 지목할 수 있게. 작업 트리 청결 여부는 알 수 없다. */
    static String gitHead() {
        try {
            Path git = Path.of(".git");
            String head = Files.readString(git.resolve("HEAD"), StandardCharsets.UTF_8).trim();
            if (!head.startsWith("ref:")) return head;
            String ref = head.substring(4).trim();
            Path refFile = git.resolve(ref);
            if (Files.exists(refFile)) return Files.readString(refFile, StandardCharsets.UTF_8).trim() + " (" + ref + ", 작업 트리 청결 여부 미확인)";
            Path packed = git.resolve("packed-refs");
            if (Files.exists(packed)) {
                for (String line : Files.readAllLines(packed, StandardCharsets.UTF_8)) {
                    if (line.endsWith(" " + ref)) return line.substring(0, line.indexOf(' ')) + " (" + ref + ", packed)";
                }
            }
            return "unknown (" + ref + ")";
        } catch (IOException e) {
            return "unknown";
        }
    }
}
