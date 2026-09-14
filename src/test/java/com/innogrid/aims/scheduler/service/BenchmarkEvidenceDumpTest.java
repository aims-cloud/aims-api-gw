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

        List<Map<String, Object>> entries = new ArrayList<>();
        for (String board : clusters.boardIds()) {
            for (String dist : DISTRIBUTIONS) {
                PlacementBenchmarkResponse r = service.compareFresh(board, dist);
                String configDigest = r.engineConfig().configDigest();
                String boardDigest = clusters.digest(board);
                assertEquals(boardDigest, r.boards().get(0).digest(), board + " 응답의 판 다이제스트가 등록부와 다르다");

                // evaluatedAt 을 빼고 쓴다 — 같은 입력이면 바이트까지 같아야 한다
                PlacementBenchmarkResponse stable = new PlacementBenchmarkResponse(null, r.assumptions(), r.engineConfig(),
                        r.metrics(), r.boards(), r.limitation());
                Path file = OUT.resolve(board + "-" + dist + "-" + boardDigest + "-" + configDigest + ".json");
                Files.writeString(file, mapper.writeValueAsString(stable), StandardCharsets.UTF_8);
                assertTrue(Files.size(file) > 10_000, file + " 가 너무 작다 — 응답이 비었다");

                Map<String, Object> e = new LinkedHashMap<>();
                e.put("board", board); e.put("distribution", dist); e.put("boardDigest", boardDigest);
                e.put("configDigest", configDigest); e.put("file", file.getFileName().toString());
                entries.add(e);
            }
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("generatedAt", OffsetDateTime.now().toString());
        manifest.put("boards", clusters.boardIds());
        manifest.put("metricKeyDigest", BenchmarkClusters.digestOf(
                BenchmarkRules.METRICS.stream().map(BenchmarkMetric::key).sorted().reduce((a, b) -> a + "," + b).orElse("")));
        manifest.put("entries", entries);
        Files.writeString(OUT.resolve("manifest.json"), mapper.writeValueAsString(manifest), StandardCharsets.UTF_8);

        assertEquals(clusters.boardIds().size() * DISTRIBUTIONS.size(), entries.size());
    }
}
