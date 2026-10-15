package com.innogrid.aims.scheduler.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.innogrid.aims.scheduler.benchmark.BenchmarkClusters;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules.GapCellVerdict;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules.GapOutcome;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules.GapVerdict;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules.PairedDifference;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.PowerAwareScheduler;
import com.innogrid.aims.scheduler.engine.TemperatureAwareScheduler;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.service.JudgmentBaselines.CellBlock;
import com.innogrid.aims.scheduler.service.JudgmentBaselines.Column;
import com.innogrid.aims.scheduler.service.JudgmentBaselines.JudgmentBaseline;
import com.innogrid.aims.scheduler.service.JudgmentBaselines.OutsideCell;
import com.innogrid.aims.scheduler.service.JudgmentBaselines.PhaseEntry;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 판정 절차를 <b>기준선 파일 B 대 현 실행 N</b> 으로 한 바퀴 돌린다 — 다음 판정 PR 이 그대로 쓰는 경로다.
 *
 * <p>보고 초안(0~8단계 전부)은 현 실행을 만든 직후 <b>단언보다 먼저</b> {@code build/benchmark-evidence/judgment-round-report.txt} 에 쓴다.
 * 판정 불가·실패는 규칙이 허용한 결말이라 초안이 가장 필요한 상황이고, 그때 테스트가 먼저 던지면 초안이 사라지기 때문이다.</p>
 *
 * <p>테스트 메서드가 단언하는 것은 <b>구조</b>다: 레코드가 채워지고, 배열 길이·seed 목록이 맞고, 현 실행의 열이 비어 있지 않다는 것.
 * 결말(판정 불가/성공/실패)과 판정 가능 여부는 단언하지 않는다 — 그 값은 기준선과 엔진이 바뀌면 바뀌는 결과이고, 리터럴로 박으면
 * 판정 PR 에서 "값을 고치면 초록불" 이 된다. 결과 값에 대한 유일한 단언은 산술 항등(B 를 B 와 견주면 축소 0·실패)이다.</p>
 *
 * <p>같은 좌표 셀의 열 다섯이 한 호출에서 나오므로 계산은 판 다섯 장에 한 번씩이다. 서비스는 클래스에 하나 — 테스트 메서드마다
 * 새로 만들면 캐시가 공유되지 않아 다섯 판을 메서드 수만큼 다시 계산한다.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JudgmentRoundTest {

    static final Path REPORT = Path.of("build", "benchmark-evidence", "judgment-round-report.txt");

    private JudgmentBaseline baseline;
    private JudgmentBaseline current;
    private GapCellVerdict judgment;
    private GapCellVerdict direction;
    private GapVerdict verdict;

    @BeforeAll
    void computeCurrentRunAndWriteTheReport() throws IOException {
        baseline = JudgmentBaselines.readRepositoryBaseline();
        assertEquals(JudgmentBaselines.SCHEMA_VERSION, baseline.schemaVersion(),
                "기준선 파일의 형식이 코드와 다르다 — 옛 형식으로 초안을 쓰면 표 G 가 전 셀을 짝 집합 변화로 잘못 적는다. 기준선을 새 형식으로 다시 뜬다");
        SchedulerProperties props = new SchedulerProperties();
        PlacementBenchmarkService service = new PlacementBenchmarkService(
                new EngineRegistry(List.of(new WeightedScoreScheduler(), new PowerAwareScheduler(), new TemperatureAwareScheduler())),
                props, new BenchmarkClusters(), new PowerModel(props), new ThermalModel(props));
        Map<String, PlacementBenchmarkResponse> byBoard = new LinkedHashMap<>();
        for (String board : JudgmentBaselines.boards()) {
            byBoard.put(board, service.compareFresh(board, BenchmarkRules.JUDGMENT_DISTRIBUTION));
        }
        current = JudgmentBaselines.build(byBoard, JudgmentBaselines.gitHead());

        judgment = BenchmarkRules.judgeCell(points(baseline, "judgment", "power"), points(current, "judgment", "power"));
        direction = BenchmarkRules.judgeCell(points(baseline, "direction", "power"), points(current, "direction", "power"));
        verdict = BenchmarkRules.judgeGap(judgment, direction);

        String report = report();
        System.out.println(report);
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report, StandardCharsets.UTF_8);
    }

    private static List<Double> points(JudgmentBaseline b, String cell, String column) {
        return b.cells().get(cell).columns().get(column).points();
    }

    /** 표 C 의 눈금 열 — 기본 눈금 power + 양수 눈금. s0(정격 분모)은 사다리 밖이라 들지 않는다. */
    static List<String> scaleColumns() {
        List<String> cols = new ArrayList<>(List.of("power"));
        for (double s : BenchmarkRules.SENSITIVITY_MARGINAL_SCALE_WATTS) if (s > 0) cols.add(BenchmarkRules.SENSITIVITY_COLUMN_PREFIX + (long) s);
        return cols;
    }

    @DisplayName("0단계 — 출처 대조는 등급별이다: 무효화 축·판·워크로드 불일치는 판정 불가, 부수 보고 축은 민감도 표만, 표기 축은 기록 후 진행")
    @Test
    void provenance_is_compared_by_axis_grade() {
        Map<String, String> b = baseline.provenance().ruleAxisDigests();
        Map<String, String> n = current.provenance().ruleAxisDigests();
        assertEquals(List.of(), JudgmentBaselineTest.axisDrift(b, n, BenchmarkRules.INVALIDATING_AXES),
                "판정 무효화 축이 다르다 — 이 기준선으로는 1~4항 판정 불가. 축을 바꾸는 판정 없는 PR 이면 같은 PR 에서 기준선을 다시 뜬다");
        assertEquals(baseline.provenance().distribution(), current.provenance().distribution());
        assertEquals(baseline.provenance().boardDigests(), current.provenance().boardDigests(), "판 다이제스트가 다르면 같은 판이 아니다 — 판정 불가");
        assertEquals(baseline.provenance().workloadDigest(), current.provenance().workloadDigest(), "워크로드 파일이 다르다 — 판정 불가");
        // 부수 보고·표기 축의 차이는 실패가 아니라 보고 항목이다 — 보고 초안 0단계가 싣는다. 설정 다이제스트는 규칙이 불변을 요구한다(노브로 얻은 축소 차단)
        assertNotNull(JudgmentBaselineTest.axisDrift(b, n, BenchmarkRules.SECONDARY_REPORT_AXES));
        assertEquals(baseline.provenance().configDigest(), current.provenance().configDigest(),
                "설정 다이제스트가 다르다 — 지목 정정은 기본 설정 불변으로 내며 노브 변경의 효과는 민감도 표로만 낸다(규칙 문장). 이 기준선으로는 1~4항 판정 불가");
    }

    @DisplayName("항등 — 기준선을 자기 자신과 견주면 축소 0·동률 전 지점·실패다 (기준선 값이 무엇이든 성립하는 산술)")
    @Test
    void the_baseline_against_itself_is_a_zero_reduction_failure() {
        GapCellVerdict j = BenchmarkRules.judgeCell(points(baseline, "judgment", "power"), points(baseline, "judgment", "power"));
        GapCellVerdict d = BenchmarkRules.judgeCell(points(baseline, "direction", "power"), points(baseline, "direction", "power"));

        assertTrue(j.judgeable() && d.judgeable(), "기준선 자신이 판정 불가면 어떤 N 과도 판정할 수 없다");
        assertEquals(0.0, j.reductionPp(), 1e-12);
        assertEquals(j.pairedCount(), j.tiedPoints());
        assertEquals(0, j.reducedPoints());
        assertEquals(baseline.cells().get("judgment").columns().get("power").median(), j.medianB(), "|I| = 12 라 I 제한 중앙값이 응답 중앙값과 같다");
        assertEquals(GapOutcome.FAIL, BenchmarkRules.judgeGap(j, d).outcome(), "변화 없음은 성공이 아니다");
        assertTrue(BenchmarkRules.judgeGap(j, d).reason().startsWith("2항"));
    }

    @DisplayName("1~4항 — 기준선 B 대 현 실행 N 의 결과 레코드가 채워지고 보고 파일이 남는다 (결말과 판정 가능 여부는 단언하지 않는다)")
    @Test
    void the_round_produces_a_complete_record_and_a_report_file() throws IOException {
        for (GapCellVerdict c : List.of(judgment, direction)) {
            assertTrue(c.pairedCount() + c.excludedB() <= BenchmarkRules.SWEEP_POINTS, "지점 회계가 맞지 않는다: " + c);
            assertTrue(c.pairedCount() + c.excludedN() <= BenchmarkRules.SWEEP_POINTS, "지점 회계가 맞지 않는다: " + c);
            assertTrue(c.requiredPoints() >= 0);
        }
        assertNotNull(verdict.outcome());
        assertFalse(verdict.reason().isBlank());
        assertTrue(Files.exists(REPORT), "보고 초안 파일이 없다 — 판정 PR 은 이 파일에서 표를 옮긴다");
        String written = Files.readString(REPORT, StandardCharsets.UTF_8);
        for (String section : List.of("0. 출처", "표 A", "표 B", "표 C", "표 D", "표 E", "표 F", "표 G", "결말", "지점별 차 중앙값", "최소 동률")) {
            assertTrue(written.contains(section), "보고 초안에 '" + section + "' 이 없다");
        }
    }

    @DisplayName("현 실행의 두 셀 × 열 5 는 길이 12 이고 비어 있지 않으며, 위상 짝 판은 seed 3 개, 오목 판 두 값이 있다")
    @Test
    void the_current_run_is_structurally_complete() {
        for (CellBlock cell : current.cells().values()) {
            for (Map.Entry<String, Column> e : cell.columns().entrySet()) {
                String where = cell.board() + " " + e.getKey();
                assertEquals(BenchmarkRules.SWEEP_POINTS, e.getValue().points().size(), where);
                assertTrue(e.getValue().samples() >= 1, where + ": 현 실행의 열이 비었다 — 판정 불가가 아니라 실행이 망가진 것이다");
            }
        }
        assertFalse(scaleColumns().contains(BenchmarkRules.SENSITIVITY_COLUMN_PREFIX + "0"), "s0 은 표 C 에 넣지 않는다 — 규칙이 사다리 밖으로 둔 열이다");
        assertEquals(3, scaleColumns().size(), "기본 눈금 + 양수 눈금 2");
        for (String board : BenchmarkRules.PHASE_INDEPENDENT_BOARDS) {
            List<PhaseEntry> b = baseline.phase().get(board);
            List<PhaseEntry> n = current.phase().get(board);
            assertEquals(BenchmarkRules.PHASE_SEED_COUNT, b.size(), board + " 기준선 seed 수");
            assertEquals(BenchmarkRules.PHASE_SEED_COUNT, n.size(), board + " 현 실행 seed 수");
            assertEquals(b.stream().map(PhaseEntry::phaseSeed).toList(), n.stream().map(PhaseEntry::phaseSeed).toList(), board + " seed 목록이 다르다");
            for (PhaseEntry e : n) assertNotNull(e.powerMedian(), board + " seed " + e.phaseSeed() + " 중앙값이 비었다");
        }
        assertNotNull(current.concave().powerMedian());
        assertNotNull(current.concave().idleFixedMedian());
        assertEquals(16, current.outside().size(), "판정 자격 두 판 × 부하율 4 × 전원 제어 2 = 16 셀");
        assertEquals(baseline.outside().keySet(), current.outside().keySet(), "기준선과 현 실행의 셀 키 집합이 다르다");
        for (OutsideCell c : current.outside().values()) {
            assertNotNull(c.power(), c.board() + " 셀의 power 열이 없다");
        }
    }

    /** 최소 동률 지점 수의 특수값 — 교집합이 하한 미달이라 판정 불가 / 지점이 너무 많아 전수 계산을 생략. */
    static final int TIE_UNJUDGEABLE = -1, TIE_TOO_MANY = -2;

    /** 2항이 미달이 되도록 동률(g_i(N) := g_i(B))로 돌릴 수 있는 지점의 최소 개수 — 모든 조합 중 최소. 이미 미달이면 null. */
    static Integer minTieCountToFail(List<Double> b, List<Double> n) {
        List<Integer> paired = new ArrayList<>();
        for (int i = 0; i < b.size(); i++) if (b.get(i) != null && n.get(i) != null) paired.add(i);
        if (paired.size() < BenchmarkRules.JUDGMENT_MIN_PAIRED_POINTS) return TIE_UNJUDGEABLE;
        if (paired.size() > 20) return TIE_TOO_MANY;
        Double medianB = BenchmarkRules.median(paired.stream().map(b::get).toList());
        int m = paired.size();
        for (int k = 0; k <= m; k++) {
            for (int mask = 0; mask < (1 << m); mask++) {
                if (Integer.bitCount(mask) != k) continue;
                List<Double> tied = new ArrayList<>();
                for (int j = 0; j < m; j++) tied.add(((mask >> j) & 1) == 1 ? b.get(paired.get(j)) : n.get(paired.get(j)));
                double reduction = Math.round((medianB - BenchmarkRules.median(tied)) * 10000.0) / 10000.0;
                if (reduction < BenchmarkRules.JUDGMENT_MEDIAN_REDUCTION_PP) return k == 0 ? null : k;
            }
        }
        return m;
    }

    @DisplayName("최소 동률 지점 수 — 항등은 해당 없음, 전 지점 10%p 축소는 7지점을 돌려야 미달, 교집합 하한 미달은 판정 불가")
    @Test
    void the_minimum_tie_count_behaves_at_the_edges() {
        List<Double> b = points(baseline, "judgment", "power");
        assertNull(minTieCountToFail(b, b), "항등은 2항이 이미 미달");
        List<Double> big = b.stream().map(v -> v == null ? null : v - 10.0).toList();
        // 12지점 전부 −10.0 이면 중앙값 차 10.0. k 지점을 B 로 되돌리면 되돌린 값들이 위쪽 절반을 차지해 중앙값이 오른다 — 7지점(절반 초과)에서 2.0 미달
        assertEquals(7, minTieCountToFail(b, big), "전 지점 10%p 축소면 절반을 넘는 7지점을 돌려야 미달");
        List<Double> sparse = new ArrayList<>(b);
        for (int i = 0; i < 3; i++) sparse.set(i, null);
        assertEquals(TIE_UNJUDGEABLE, minTieCountToFail(sparse, big), "교집합 9 < 하한 10 은 판정 불가로 따로 표기");
    }

    /** 표 G 분류 집계 — 판정 셀·방향 셀 자신은 제외(표 A·B 에 있다), 차 = B − N(격차는 낮을수록 좋음). */
    record OutsideSummary(int excluded, int worse, int better, int tied, int changedPairing, int unpaired, List<String> lines) {}

    static OutsideSummary classifyOutside(JudgmentBaseline b, JudgmentBaseline n) {
        int excluded = 0, worse = 0, better = 0, tied = 0, changedPairing = 0, unpaired = 0;
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, OutsideCell> e : new TreeMap<>(n.outside()).entrySet()) {
            OutsideCell nCell = e.getValue();
            OutsideCell bCell = b.outside().get(e.getKey());
            boolean isJudgment = nCell.loadRatioPercent() == BenchmarkRules.JUDGMENT_LOAD_RATIO
                    && BenchmarkRules.JUDGMENT_POWER_CONTROL.name().equals(nCell.powerControl());
            Double bm = bCell == null ? null : bCell.power().median();
            Double nm = nCell.power().median();
            int bn = bCell == null ? 0 : bCell.power().samples();
            int nn = nCell.power().samples();
            String tag;
            if (isJudgment) { tag = nCell.board().equals(BenchmarkRules.JUDGMENT_BOARD) ? "판정 셀(표 A)" : "방향 셀(표 B)"; excluded++; }
            else if (bm == null && nm == null) { tag = "둘 다 짝 없음"; unpaired++; }
            else if (bm == null || nm == null) { tag = "짝 집합 변화"; changedPairing++; }
            else {
                double d = Math.round((bm - nm) * 10000.0) / 10000.0;
                if (d <= -BenchmarkRules.JUDGMENT_POINT_IGNORE_PP) { tag = "악화 " + d; worse++; }
                else if (d >= BenchmarkRules.JUDGMENT_POINT_IGNORE_PP) { tag = "축소 " + d; better++; }
                else { tag = "동률"; tied++; }
                if (bCell.power().points() != null && nCell.power().points() != null) {
                    PairedDifference pd = BenchmarkRules.medianOfPairedDifferences(bCell.power().points(), nCell.power().points());
                    tag += " · I 제한 지점별 차 중앙값 " + pd.median() + " (|I| " + pd.pairedCount() + ")";
                }
            }
            lines.add("   " + e.getKey() + ": " + bm + "(n=" + bn + ") → " + nm + "(n=" + nn + ") · " + tag);
        }
        return new OutsideSummary(excluded, worse, better, tied, changedPairing, unpaired, lines);
    }

    /** 표 G 분류의 갈림 — 한쪽만 값이 없으면 짝 집합 변화(악화 아님), N 이 커지면 악화, 작아지면 축소. 항등 실행에는 없는 갈래를 합성 사본으로 고정한다. */
    @DisplayName("표 G 분류 — 한쪽만 값이 없는 셀은 짝 집합 변화이고, N 이 크면 악화·작으면 축소다")
    @Test
    void outside_classification_distinguishes_pairing_change_from_worsening() {
        String worseKey = JudgmentBaselines.outsideKey(BenchmarkRules.DIRECTION_CHECK_BOARD, 30, "AVAILABLE");
        String betterKey = JudgmentBaselines.outsideKey(BenchmarkRules.DIRECTION_CHECK_BOARD, 90, "AVAILABLE");
        String droppedKey = JudgmentBaselines.outsideKey(BenchmarkRules.JUDGMENT_BOARD, 30, "UNAVAILABLE");
        Map<String, OutsideCell> modified = new TreeMap<>(baseline.outside());
        OutsideCell w = modified.get(worseKey), be = modified.get(betterKey), d = modified.get(droppedKey);
        modified.put(worseKey, new OutsideCell(w.board(), w.loadRatioPercent(), w.powerControl(), new Column(w.power().points(), w.power().samples(), w.power().median() + 1.0)));
        modified.put(betterKey, new OutsideCell(be.board(), be.loadRatioPercent(), be.powerControl(), new Column(be.power().points(), be.power().samples(), be.power().median() - 1.0)));
        modified.put(droppedKey, new OutsideCell(d.board(), d.loadRatioPercent(), d.powerControl(), new Column(null, 0, null)));
        JudgmentBaseline n = new JudgmentBaseline(baseline.schemaVersion(), baseline.provenance(), baseline.cells(), baseline.phase(), baseline.concave(), modified);

        OutsideSummary s = classifyOutside(baseline, n);
        assertEquals(1, s.worse(), "N 이 1.0 크면 악화(격차는 낮을수록 좋음)");
        assertEquals(1, s.better(), "N 이 1.0 작으면 축소");
        assertEquals(1, s.changedPairing(), "B 에만 값이 있는 셀은 짝 집합 변화 — 악화로 세지 않는다");
        assertEquals(2, s.excluded());
        assertEquals(baseline.outside().size(), s.excluded() + s.worse() + s.better() + s.tied() + s.changedPairing() + s.unpaired(), "분류 회계");
    }

    @DisplayName("표 G 항등 — 기준선을 자기 자신과 견주면 판정 셀 둘 제외 · 동률 12 · 둘 다 짝 없음 2 · 악화·축소·짝 집합 변화 0")
    @Test
    void outside_classification_of_the_baseline_against_itself() {
        OutsideSummary s = classifyOutside(baseline, baseline);
        assertEquals(2, s.excluded(), "판정 셀·방향 셀 자신은 표 A·B 에 있어 뺀다");
        assertEquals(0, s.worse()); assertEquals(0, s.better()); assertEquals(0, s.changedPairing());
        assertEquals(baseline.outside().size() - 2 - s.unpaired(), s.tied(), "나머지는 전부 동률");
        assertEquals(baseline.outside().size(), s.excluded() + s.worse() + s.better() + s.tied() + s.changedPairing() + s.unpaired(), "분류 회계");
    }

    @DisplayName("관측 — 현 실행의 정격 분모 열(s0)을 B 대역, 기본 눈금 열을 N 대역으로 두면 소급 W 눈금 판정의 재료가 재구성되지만 판정이 아니라 관측이다")
    @Test
    void the_s0_column_reconstructs_the_retired_band_as_an_observation_only() {
        String s0 = BenchmarkRules.SENSITIVITY_COLUMN_PREFIX + "0";
        GapCellVerdict j = BenchmarkRules.judgeCell(points(current, "judgment", s0), points(current, "judgment", "power"));
        assertNotNull(j.reductionPp(), "두 열이 모두 채워졌다면 차가 계산돼야 한다");
        assertTrue(BenchmarkRules.SENSITIVITY_RULE.contains("power-s0 과 power-idle-fixed 는 같은 자료를 재확인하는 열이라 독립 증거가 아니다"),
                "규칙이 s0 을 독립 증거가 아니라고 규정하는 한 이 계산은 관측이다 — 문장이 바뀌면 이 시험의 전제도 다시 본다");
    }

    // ── 보고 초안 — 절차 0~8단계 전부. 값은 그대로, 판정 어휘는 결말 줄에만 ──

    private String report() {
        StringBuilder sb = new StringBuilder("[판정 보고 초안 — 시험 출력, 게시물이 아니다. 판정 PR 이 표를 PR 본문으로 옮기고 회차 행을 더한다]\n");
        Map<String, String> b = new TreeMap<>(baseline.provenance().ruleAxisDigests());
        Map<String, String> n = new TreeMap<>(current.provenance().ruleAxisDigests());
        sb.append("0. 출처 — B ").append(baseline.provenance().gitHead()).append(" / N ").append(current.provenance().gitHead()).append('\n')
          .append("   설정 ").append(baseline.provenance().configDigest()).append(" → ").append(current.provenance().configDigest())
          .append(" · 판 동일 ").append(baseline.provenance().boardDigests().equals(current.provenance().boardDigests()))
          .append(" · 워크로드 동일 ").append(baseline.provenance().workloadDigest().equals(current.provenance().workloadDigest())).append('\n')
          .append("   축 차이 — 무효화 ").append(JudgmentBaselineTest.axisDrift(b, n, BenchmarkRules.INVALIDATING_AXES))
          .append(" · 부수 보고 ").append(JudgmentBaselineTest.axisDrift(b, n, BenchmarkRules.SECONDARY_REPORT_AXES))
          .append(" · 표기 ").append(JudgmentBaselineTest.axisDrift(b, n, BenchmarkRules.NOTATION_AXES)).append('\n');
        sb.append("1. 교집합 — 판정 셀 |I| ").append(judgment.pairedCount()).append(" (결측 B ").append(judgment.excludedB()).append(" · N ").append(judgment.excludedN())
          .append(") · 방향 셀 |I| ").append(direction.pairedCount()).append(" (결측 B ").append(direction.excludedB()).append(" · N ").append(direction.excludedN())
          .append(") · 하한 ").append(BenchmarkRules.JUDGMENT_MIN_PAIRED_POINTS).append('\n');
        sb.append("2. 표 A 판정 셀 — I 제한 중앙값 ").append(judgment.medianB()).append(" → ").append(judgment.medianN()).append(" · 축소 ").append(judgment.reductionPp())
          .append("%p (문턱 ").append(BenchmarkRules.JUDGMENT_MEDIAN_REDUCTION_PP).append(") · 축소 지점 ").append(judgment.reducedPoints()).append('/')
          .append(judgment.requiredPoints()).append(" 필요 · 동률 ").append(judgment.tiedPoints()).append(" · 악화 ").append(judgment.worsenedPoints()).append('\n');
        PairedDifference pairedJudgment = BenchmarkRules.medianOfPairedDifferences(points(baseline, "judgment", "power"), points(current, "judgment", "power"));
        PairedDifference pairedDirection = BenchmarkRules.medianOfPairedDifferences(points(baseline, "direction", "power"), points(current, "direction", "power"));
        Integer minTie = minTieCountToFail(points(baseline, "judgment", "power"), points(current, "judgment", "power"));
        String tieText = minTie == null ? "해당 없음(2항이 이미 미달)" : minTie == TIE_UNJUDGEABLE ? "판정 불가(|I| 하한 미달)"
                : minTie == TIE_TOO_MANY ? "계산 생략(지점 수 초과)" : minTie + "/" + judgment.pairedCount();
        sb.append("   병기(관측) — 지점별 차 중앙값(B − N) 판정 셀 ").append(pairedJudgment.median()).append("%p · 방향 셀 ").append(pairedDirection.median())
          .append("%p · 2항 미달까지의 최소 동률 지점 수 ").append(tieText)
          .append(" · 지점은 한 주기의 시각 지점이라 독립 복제가 아니다\n");
        sb.append("3. 표 B 방향 셀 — I 제한 중앙값 ").append(direction.medianB()).append(" → ").append(direction.medianN())
          .append(" · 방향 조건(무시 폭 두 배 이상) ").append(direction.directionHolds() ? "충족" : "미충족").append('\n');
        sb.append("4. 표 C 민감도 — 눈금 열별 B→N (판정에 넣지 않음)\n");
        for (String col : scaleColumns()) {
            GapCellVerdict onScale = BenchmarkRules.judgeCell(points(baseline, "judgment", col), points(current, "judgment", col));
            sb.append("   ").append(col).append(": |I| ").append(onScale.pairedCount()).append(" · ").append(onScale.medianB()).append(" → ").append(onScale.medianN())
              .append(" · 차 ").append(onScale.reductionPp()).append("%p\n");
        }
        PairedDifference level = BenchmarkRules.medianOfPairedDifferences(points(current, "judgment", "power"),
                points(current, "judgment", BenchmarkRules.SENSITIVITY_COLUMN_PREFIX + "250"));
        sb.append("   수준량(현 실행, power − s250, I' 위 지점별 차 중앙값): ").append(level.median()).append("%p · |I'| ").append(level.pairedCount())
          .append(" · 제외 ").append(level.excludedA()).append('/').append(level.excludedB()).append('\n');
        sb.append("5. 표 D 위상 — 짝 판 seed 별 중앙값(현 실행) 과 범위\n");
        for (String board : BenchmarkRules.PHASE_INDEPENDENT_BOARDS) {
            List<PhaseEntry> entries = current.phase().get(board);
            double min = entries.stream().filter(e -> e.powerMedian() != null).mapToDouble(PhaseEntry::powerMedian).min().orElse(Double.NaN);
            double max = entries.stream().filter(e -> e.powerMedian() != null).mapToDouble(PhaseEntry::powerMedian).max().orElse(Double.NaN);
            sb.append("   ").append(board).append(": ").append(entries.stream().map(e -> e.phaseSeed() + "→" + e.powerMedian()).toList())
              .append(" · 범위 ").append(Math.round((max - min) * 10000.0) / 10000.0).append("%p")
              .append(max - min > BenchmarkRules.JUDGMENT_MEDIAN_REDUCTION_PP ? " (문턱 초과 — 단일 legacy 값은 이 폭 안의 한 점)" : "").append('\n');
        }
        Double x = current.concave().powerMedian();
        Double y = current.cells().get("direction").columns().get("power").median();
        Double idleConcave = current.concave().idleFixedMedian();
        Double idleMain = current.cells().get("direction").columns().get(BenchmarkRules.IDLE_RECONSTRUCTION_DISABLED_COLUMN).median();
        sb.append("6. 표 E 오목 — X ").append(x).append(" · Y ").append(y)
          .append(" · Z ").append(x == null || y == null ? null : Math.round(Math.abs(x - y) * 10000.0) / 10000.0)
          .append(" · 부호 ").append(x != null && y != null && Math.signum(x) == Math.signum(y) ? "같다" : "다르다")
          .append(" · W(오목) ").append(idleConcave == null || x == null ? null : Math.round((idleConcave - x) * 10000.0) / 10000.0)
          .append(" · W(β=1.0) ").append(idleMain == null || y == null ? null : Math.round((idleMain - y) * 10000.0) / 10000.0).append('\n');
        sb.append("7. 표 F 짝 제외 — 열별 null 지점 수(현 실행)\n");
        for (CellBlock cell : current.cells().values()) {
            for (Map.Entry<String, Column> e : cell.columns().entrySet()) {
                sb.append("   ").append(cell.board()).append(' ').append(e.getKey()).append(": ").append(BenchmarkRules.SWEEP_POINTS - e.getValue().samples()).append('\n');
            }
        }
        sb.append("7-1. 표 G 판정 셀 밖 — 두 판 power 행 격차 중앙값 B(n) → N(n), 차 = B − N(격차는 낮을수록 좋음 — 음수가 악화), 판정 셀·방향 셀 자신 제외\n");
        OutsideSummary outside = classifyOutside(baseline, current);
        for (String line : outside.lines()) sb.append(line).append('\n');
        sb.append("   집계 — 밖 셀 악화 ").append(outside.worse()).append(" · 축소 ").append(outside.better()).append(" · 동률 ").append(outside.tied())
          .append(" · 짝 집합 변화 ").append(outside.changedPairing()).append(" · 둘 다 짝 없음 ").append(outside.unpaired())
          .append(" · 제외(판정·방향 셀) ").append(outside.excluded()).append('\n');
        sb.append("8. 결말 — ").append(verdict.outcome()).append(": ").append(verdict.reason()).append('\n');
        sb.append("   판정 자격(폐쇄형): ").append(BenchmarkRules.JUDGMENT_BOARD).append(" · ").append(BenchmarkRules.DIRECTION_CHECK_BOARD).append(" 두 판의 power 행만\n");
        String s0 = BenchmarkRules.SENSITIVITY_COLUMN_PREFIX + "0";
        GapCellVerdict js = BenchmarkRules.judgeCell(points(current, "judgment", s0), points(current, "judgment", "power"));
        GapCellVerdict ds = BenchmarkRules.judgeCell(points(current, "direction", s0), points(current, "direction", "power"));
        sb.append("[관측 · 판정 아님] 한 실행 안 두 설정 열(s0 → power) 중앙값 차: 판정 셀 ").append(js.medianB()).append(" → ").append(js.medianN())
          .append(" (차 ").append(js.reductionPp()).append("%p, 차가 무시 폭 이상인 지점 ").append(js.reducedPoints()).append('/').append(js.pairedCount())
          .append("), 방향 셀 ").append(ds.medianB()).append(" → ").append(ds.medianN()).append(" (차 ").append(ds.reductionPp()).append("%p, 차가 무시 폭 이상인 지점 ")
          .append(ds.reducedPoints()).append('/').append(ds.pairedCount()).append("). 규칙의 B·N 은 두 실행이고 이것은 두 열이다 — 문턱·항 번호와 견주지 않는다.\n");
        return sb.toString();
    }
}
