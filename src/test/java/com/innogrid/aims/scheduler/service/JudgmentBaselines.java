package com.innogrid.aims.scheduler.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.innogrid.aims.scheduler.benchmark.BenchmarkClusters;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules;
import com.innogrid.aims.scheduler.benchmark.BenchmarkWorkloads;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.Board;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.Cell;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.MetricValue;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.StrategyResult;

/**
 * 판정 기준선 파일의 <b>형식과 생산 경로</b> — 테스트 쪽에 둔다(파일 내용은 결과이므로 규칙 클래스는 값을 들지 않는다).
 *
 * <p>같은 코드가 세 곳에서 쓰인다: 증거 묶음 테스트가 후보 파일을 {@code build/}에 쓰고, 사람이 그 후보를
 * {@link BenchmarkRules#JUDGMENT_BASELINE_PATH}로 복사해 커밋하며(이력 행과 함께), 판정 회전 테스트가 현 실행을 같은 형식으로
 * 만들어 파일과 견준다. 생산 경로가 하나라 B와 N이 다른 조립으로 갈리지 않는다 — 규칙이 요구하는 "같은 생산 경로"다.</p>
 *
 * <p>담는 것: 판정 셀·방향 셀의 {@code power}·눈금 3열·역산 차단 열 {@code gapVsPabfdPercent} 지점 배열(길이 12, null 자리 보존)과 표본 수·중앙값,
 * 위상 독립 짝 판 2장의 seed별 판정 좌표 셀 중앙값, 오목 판의 같은 좌표 {@code power}·{@code power-idle-fixed} 중앙값, 그리고 출처.
 * 지점별 배치 건수는 응답에 없어 담지 않는다 — 짝이 안 된 지점은 points의 null 자리와 samples로 드러나되 "건수 불일치"와 "분모 0"은 갈리지 않는다.</p>
 */
final class JudgmentBaselines {

    private JudgmentBaselines() {
    }

    // 2: 판정 상수에 지점 비교 무시 폭(pointIgnorePp) 필드, 3: 판정 자격 두 판의 전 셀 power 행(outside)
    static final int SCHEMA_VERSION = 3;

    /** 기준선이 담는 열 — 판정 열과, 같은 셀에서 나오는 눈금·역산 변종. 열이 늘면 형식 버전을 올린다. */
    static final List<String> COLUMNS = List.of("power",
            BenchmarkRules.SENSITIVITY_COLUMN_PREFIX + "250", BenchmarkRules.SENSITIVITY_COLUMN_PREFIX + "1000",
            BenchmarkRules.SENSITIVITY_COLUMN_PREFIX + "0", BenchmarkRules.IDLE_RECONSTRUCTION_DISABLED_COLUMN);

    /** 판정에 필요한 판 — 판정판·방향판·위상 짝 2장·오목 판. 전부 {@link BenchmarkRules#JUDGMENT_DISTRIBUTION} 분포로 실행한다. */
    static List<String> boards() {
        List<String> boards = new ArrayList<>(List.of(BenchmarkRules.JUDGMENT_BOARD, BenchmarkRules.DIRECTION_CHECK_BOARD));
        boards.addAll(BenchmarkRules.PHASE_INDEPENDENT_BOARDS);
        boards.add(BenchmarkRules.CONCAVE_BOARD);
        return boards;
    }

    record Column(List<Double> points, int samples, Double median) {}

    record CellBlock(String board, int loadRatioPercent, String powerControl, Map<String, Column> columns) {}

    record PhaseEntry(long phaseSeed, Double powerMedian) {}

    record ConcaveBlock(String board, Double powerMedian, Double idleFixedMedian) {}

    record JudgmentConstants(String board, int loadRatio, String powerControl, String directionCheckBoard, String distribution,
                             int minPairedPoints, double medianReductionPp, double reducedPointRatio, int sweepPoints, double pointIgnorePp) {}

    record Provenance(String gitHead, String productionPath, String distribution, Map<String, String> boardDigests, String workloadDigest,
                      String configDigest, List<String> engineIds, Map<String, String> ruleAxisDigests, JudgmentConstants judgment) {}

    /** 판정 자격 두 판의 셀 하나 — power 행만. 키는 {@code 판:부하율:전원제어}. 판정 셀·방향 셀 자신도 들어 있고 표 G가 그 둘을 뺀다. */
    record OutsideCell(String board, int loadRatioPercent, String powerControl, Column power) {}

    record JudgmentBaseline(int schemaVersion, Provenance provenance, Map<String, CellBlock> cells, Map<String, List<PhaseEntry>> phase,
                            ConcaveBlock concave, Map<String, OutsideCell> outside) {}

    static String outsideKey(String board, int loadRatioPercent, String powerControl) {
        return board + ":" + loadRatioPercent + ":" + powerControl;
    }

    /** 응답(판 → 응답, 전부 판정 분포)에서 기준선 형식을 만든다. */
    static JudgmentBaseline build(Map<String, PlacementBenchmarkResponse> byBoard, String gitHead) {
        for (String b : boards()) {
            if (!byBoard.containsKey(b)) throw new IllegalArgumentException("판정 기준선에 필요한 판이 없다: " + b);
        }
        Map<String, CellBlock> cells = new LinkedHashMap<>();
        cells.put("judgment", cellBlock(BenchmarkRules.JUDGMENT_BOARD, byBoard.get(BenchmarkRules.JUDGMENT_BOARD).boards().get(0)));
        cells.put("direction", cellBlock(BenchmarkRules.DIRECTION_CHECK_BOARD, byBoard.get(BenchmarkRules.DIRECTION_CHECK_BOARD).boards().get(0)));

        Map<String, List<PhaseEntry>> phase = new TreeMap<>();
        for (String b : BenchmarkRules.PHASE_INDEPENDENT_BOARDS) {
            List<PhaseEntry> entries = new ArrayList<>();
            for (Board board : byBoard.get(b).boards()) {
                entries.add(new PhaseEntry(board.phaseSeed(), gap(judgmentCell(board), "power").median()));
            }
            phase.put(b, entries);
        }
        Cell concaveCell = judgmentCell(byBoard.get(BenchmarkRules.CONCAVE_BOARD).boards().get(0));
        ConcaveBlock concave = new ConcaveBlock(BenchmarkRules.CONCAVE_BOARD, gap(concaveCell, "power").median(),
                gap(concaveCell, BenchmarkRules.IDLE_RECONSTRUCTION_DISABLED_COLUMN).median());

        Map<String, String> boardDigests = new TreeMap<>();
        for (String b : boards()) boardDigests.put(b, byBoard.get(b).boards().get(0).digest());
        Map<String, String> ruleDigests = new TreeMap<>();
        BenchmarkRules.ruleAxes().forEach((k, v) -> ruleDigests.put(k, BenchmarkClusters.digestOf(v)));
        PlacementBenchmarkResponse any = byBoard.get(BenchmarkRules.JUDGMENT_BOARD);
        Provenance provenance = new Provenance(gitHead,
                "저장소 시험 · 코드 기본 SchedulerProperties · compareFresh(캐시 우회) — 배포 env 의 덮어쓰기는 반영하지 않는다",
                BenchmarkRules.JUDGMENT_DISTRIBUTION, boardDigests,
                BenchmarkWorkloads.digestOf("classpath:scheduler/benchmark-workloads-" + BenchmarkRules.JUDGMENT_DISTRIBUTION + ".yml"),
                any.engineConfig().configDigest(), any.engineConfig().engineIds(), ruleDigests,
                new JudgmentConstants(BenchmarkRules.JUDGMENT_BOARD, BenchmarkRules.JUDGMENT_LOAD_RATIO, BenchmarkRules.JUDGMENT_POWER_CONTROL.name(),
                        BenchmarkRules.DIRECTION_CHECK_BOARD, BenchmarkRules.JUDGMENT_DISTRIBUTION, BenchmarkRules.JUDGMENT_MIN_PAIRED_POINTS,
                        BenchmarkRules.JUDGMENT_MEDIAN_REDUCTION_PP, BenchmarkRules.JUDGMENT_REDUCED_POINT_RATIO, BenchmarkRules.SWEEP_POINTS,
                        BenchmarkRules.JUDGMENT_POINT_IGNORE_PP));
        Map<String, OutsideCell> outside = new TreeMap<>();
        for (String b : List.of(BenchmarkRules.JUDGMENT_BOARD, BenchmarkRules.DIRECTION_CHECK_BOARD)) {
            for (Cell c : byBoard.get(b).boards().get(0).cells()) {
                MetricValue m = gap(c, "power");
                outside.put(outsideKey(b, c.loadRatioPercent(), c.powerControl()),
                        new OutsideCell(b, c.loadRatioPercent(), c.powerControl(), new Column(m.points(), m.samples(), m.median())));
            }
        }
        return new JudgmentBaseline(SCHEMA_VERSION, provenance, cells, phase, concave, outside);
    }

    private static CellBlock cellBlock(String boardId, Board board) {
        Cell c = judgmentCell(board);
        Map<String, Column> columns = new TreeMap<>();   // 파일의 열 순서는 키 정렬이 정한다 — 코드의 COLUMNS 순서가 아니다
        for (String col : COLUMNS) {
            MetricValue m = gap(c, col);
            columns.put(col, new Column(m.points(), m.samples(), m.median()));
        }
        return new CellBlock(boardId, c.loadRatioPercent(), c.powerControl(), columns);
    }

    /** 판정 셀과 같은 좌표(부하율·전원 가정)의 셀 — 어느 판에서든 같은 좌표를 읽는다. */
    static Cell judgmentCell(Board board) {
        return board.cells().stream()
                .filter(c -> c.loadRatioPercent() == BenchmarkRules.JUDGMENT_LOAD_RATIO
                        && BenchmarkRules.JUDGMENT_POWER_CONTROL.name().equals(c.powerControl()))
                .findFirst().orElseThrow(() -> new IllegalStateException(board.boardId() + " 에 판정 좌표 셀이 없다"));
    }

    static MetricValue gap(Cell cell, String strategyId) {
        StrategyResult s = cell.strategies().stream().filter(x -> x.strategyId().equals(strategyId)).findFirst()
                .orElseThrow(() -> new IllegalStateException(strategyId + " 열이 없다"));
        return s.metrics().stream().filter(m -> m.key().equals(BenchmarkRules.GAP_VS_PABFD)).findFirst()
                .orElseThrow(() -> new IllegalStateException(strategyId + " 에 " + BenchmarkRules.GAP_VS_PABFD + " 가 없다"));
    }

    /** 개행 LF · 들여쓰기 2 · 맵 키 정렬 — OS와 무관하게 같은 바이트가 나오게. */
    static ObjectMapper mapper() {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                .withArrayIndenter(new DefaultIndenter("  ", "\n"));
        return new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).setDefaultPrettyPrinter(printer);
    }

    static String toJson(JudgmentBaseline baseline) throws IOException {
        return mapper().writerWithDefaultPrettyPrinter().writeValueAsString(baseline) + "\n";
    }

    /** 저장소 기준선을 읽는다 — 파일이 없으면 건너뛰지 않고 규칙이 요구하는 보존처가 비었다고 실패한다. */
    static JudgmentBaseline readRepositoryBaseline() throws IOException {
        Path file = repositoryFile();
        if (!Files.exists(file)) {
            throw new AssertionError("기준선 파일이 없다: " + file.toAbsolutePath() + " — 규칙이 요구하는 B 의 유일한 보존처다. 작업 디렉터리가 프로젝트 루트인지도 확인한다");
        }
        return read(file);
    }

    /** 셸 없이 .git/HEAD를 읽는다 — 산출물이 "어느 소스 리비전의 값인가"를 지목할 수 있게. 작업 트리 청결 여부는 알 수 없다. */
    static String gitHead() {
        try {
            Path git = Path.of(".git");
            String head = Files.readString(git.resolve("HEAD"), StandardCharsets.UTF_8).trim();
            if (!head.startsWith("ref:")) return head;
            String ref = head.substring(4).trim();
            Path refFile = git.resolve(ref);
            if (Files.exists(refFile)) return Files.readString(refFile, StandardCharsets.UTF_8).trim() + " (" + ref + ")";
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

    static JudgmentBaseline read(Path file) throws IOException {
        return mapper().readValue(Files.readString(file, StandardCharsets.UTF_8), JudgmentBaseline.class);
    }

    static JudgmentBaseline read(InputStream in) throws IOException {
        return mapper().readValue(in, JudgmentBaseline.class);
    }

    /** 저장소의 기준선 파일 경로 — 작업 디렉터리가 프로젝트 루트라는 테스트 실행의 전제를 그대로 쓴다(증거 묶음의 {@code build/}와 같다). */
    static Path repositoryFile() {
        return Path.of(BenchmarkRules.JUDGMENT_BASELINE_PATH);
    }

    /** 파일 내용 다이제스트 — 판 파일과 같은 규칙(개행 LF 정규화 · SHA-256 앞 12자). */
    static String digestOf(Path file) throws IOException {
        return BenchmarkClusters.digestOfContent(Files.readAllBytes(file));
    }
}
