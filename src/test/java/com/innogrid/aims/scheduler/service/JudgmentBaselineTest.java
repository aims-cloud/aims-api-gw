package com.innogrid.aims.scheduler.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.benchmark.BenchmarkClusters;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules;
import com.innogrid.aims.scheduler.service.JudgmentBaselines.CellBlock;
import com.innogrid.aims.scheduler.service.JudgmentBaselines.Column;
import com.innogrid.aims.scheduler.service.JudgmentBaselines.JudgmentBaseline;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 판정 기준선 파일을 <b>봉인</b>한다 — 파일 내용의 다이제스트가 이력의 마지막 행과 같아야 통과한다.
 *
 * <p>규칙 축 이력({@code RULE_AXIS_HISTORY})과 같은 장치다. 기준선 값은 결과라 규칙 클래스에 두지 않지만, 파일을 고치는 커밋이
 * 여기 행을 더해야만 초록불이 되므로 "결과 보고를 고치는 자유도" 는 diff 에 드러난다. 갱신 순서(판정 PR 은 파일 불변, 갱신은 보고
 * 게시 뒤 별도 커밋)는 저장소 밖의 사실이라 이 시험이 볼 수 없다 — 이력 행의 사유와 B 리비전 칸이 리뷰의 근거다.</p>
 *
 * <p>값은 판을 돌리지 않고 파일만 읽어 본다. 현 실행과의 대조는 판정 회전 시험의 몫이다.</p>
 */
class JudgmentBaselineTest {

    /**
     * 기준선 이력 한 줄 — 파일 다이제스트(개행 LF 정규화 · SHA-256 앞 12자) · 날짜 · 사유 · 기준선이 대표하는 B 리비전(직전 머지 리비전) ·
     * 직전 기준선과의 두 셀 중앙값 차(첫 행은 "없음").
     */
    record BaselineSeal(String digest, String date, String reason, String baselineRevision, String medianShiftFromPrevious) {}

    /** 마지막 행이 현재 파일이다. 파일을 갱신하는 커밋은 여기 행을 더한다 — 후보는 증거 묶음 시험이 만든다. */
    static final List<BaselineSeal> BASELINE_HISTORY = List.of(
            new BaselineSeal("3fef93c9862b", "2026-09-15",
                    "기준선 봉인 도입 — 소비된 W 눈금 판정 뒤 처음으로 지점 배열째 보존. 판정 셀·방향 셀 × 열 5(power·눈금 3·역산 차단)·위상 짝 판 seed 3·오목 판",
                    "294dae4 (develop, 배치 비교 방법론 PR 머지 — 이후 엔진 코드 변경 없음)", "없음(첫 기준선)"));

    private static JudgmentBaseline load() throws IOException {
        return JudgmentBaselines.readRepositoryBaseline();
    }

    @DisplayName("기준선 파일의 내용 다이제스트가 이력의 마지막 행과 같다 — 파일을 고치는 커밋은 이력 행을 더한다")
    @Test
    void the_baseline_file_is_sealed_by_its_history() throws IOException {
        String actual = JudgmentBaselines.digestOf(JudgmentBaselines.repositoryFile());
        BaselineSeal current = BASELINE_HISTORY.get(BASELINE_HISTORY.size() - 1);
        assertEquals(current.digest(), actual,
                "기준선 파일이 바뀌었다 — 이력에 행(다이제스트·날짜·사유·B 리비전·직전 B 와의 중앙값 차)을 더하는 커밋을 따로 두고, "
                        + "판정 PR 안에서는 이 파일을 고치지 않는다");
        for (BaselineSeal s : BASELINE_HISTORY) {
            assertEquals(12, s.digest().length(), s.toString());
            assertFalse(s.reason().isBlank() || s.baselineRevision().isBlank() || s.medianShiftFromPrevious().isBlank(),
                    "이력 행의 사유·B 리비전·중앙값 차가 비었다: " + s);
        }
        assertEquals(BASELINE_HISTORY.size(), BASELINE_HISTORY.stream().map(BaselineSeal::digest).distinct().count(), "같은 다이제스트가 두 행에 있다");
    }

    @DisplayName("기준선 파일이 판정 셀·방향 셀 × 열 5 의 지점 배열(길이 12, null 자리 보존)과 표본 수·중앙값을 담고 판정 셀은 판정 가능하다")
    @Test
    void the_baseline_carries_point_arrays_for_both_cells() throws IOException {
        JudgmentBaseline b = load();
        assertEquals(JudgmentBaselines.SCHEMA_VERSION, b.schemaVersion());
        assertEquals(List.of("direction", "judgment"), new ArrayList<>(new java.util.TreeMap<>(b.cells()).keySet()));

        CellBlock judgment = b.cells().get("judgment");
        CellBlock direction = b.cells().get("direction");
        assertEquals(BenchmarkRules.JUDGMENT_BOARD, judgment.board());
        assertEquals(BenchmarkRules.DIRECTION_CHECK_BOARD, direction.board());
        for (CellBlock cell : List.of(judgment, direction)) {
            assertEquals(BenchmarkRules.JUDGMENT_LOAD_RATIO, cell.loadRatioPercent(), cell.board());
            assertEquals(BenchmarkRules.JUDGMENT_POWER_CONTROL.name(), cell.powerControl(), cell.board());
            assertEquals(new java.util.TreeSet<>(JudgmentBaselines.COLUMNS), new java.util.TreeSet<>(cell.columns().keySet()), cell.board() + " 의 열 집합");
            for (Map.Entry<String, Column> e : cell.columns().entrySet()) {
                Column c = e.getValue();
                String where = cell.board() + " " + e.getKey();
                assertNotNull(c.points(), where + ": points 가 없다 — 중앙값만 남은 기준선은 3·4항을 재구성할 수 없다");
                assertEquals(BenchmarkRules.SWEEP_POINTS, c.points().size(), where + ": 지점 배열 길이(null 자리 보존)");
                long present = c.points().stream().filter(p -> p != null).count();
                assertEquals(c.samples(), present, where + ": samples 가 null 아닌 지점 수와 다르다");
                assertEquals(c.median(), BenchmarkRules.median(c.points()), where + ": 중앙값이 지점 배열과 어긋난다 — 손으로 고친 값이다");
            }
        }
        // 판정 가능 — 기준선 자신의 유효 지점이 하한에 못 미치면 어떤 N 과도 판정할 수 없다
        for (CellBlock cell : List.of(judgment, direction)) {
            assertTrue(cell.columns().get("power").samples() >= BenchmarkRules.JUDGMENT_MIN_PAIRED_POINTS,
                    cell.board() + " power 행의 유효 지점이 " + cell.columns().get("power").samples() + " — 하한 미달이라 기준선으로 쓸 수 없다");
        }
    }

    @DisplayName("기준선 파일이 출처(리비전·판/워크로드/설정 다이제스트·규칙 축 8값·판정 상수)를 담고 판정 무효화 축은 현 규칙과 같다")
    @Test
    void the_baseline_carries_its_provenance_and_matches_the_invalidating_axes() throws IOException {
        JudgmentBaseline b = load();
        var p = b.provenance();
        assertFalse(p.gitHead() == null || p.gitHead().isBlank() || p.gitHead().startsWith("unknown"), "소스 리비전이 없다: " + p.gitHead());
        assertEquals(BenchmarkRules.JUDGMENT_DISTRIBUTION, p.distribution());
        assertEquals(new java.util.TreeSet<>(JudgmentBaselines.boards()), new java.util.TreeSet<>(p.boardDigests().keySet()), "판 다이제스트 집합");
        assertEquals(12, p.workloadDigest().length());
        assertEquals(12, p.configDigest().length());
        assertFalse(p.engineIds().isEmpty());
        assertEquals(BenchmarkRules.ruleAxes().keySet(), p.ruleAxisDigests().keySet(), "규칙 축 8값이 전부 있어야 등급별 대조가 된다");

        var j = p.judgment();
        assertEquals(BenchmarkRules.JUDGMENT_BOARD, j.board());
        assertEquals(BenchmarkRules.DIRECTION_CHECK_BOARD, j.directionCheckBoard());
        assertEquals(BenchmarkRules.JUDGMENT_MIN_PAIRED_POINTS, j.minPairedPoints());
        assertEquals(BenchmarkRules.JUDGMENT_MEDIAN_REDUCTION_PP, j.medianReductionPp(), 1e-12);
        assertEquals(BenchmarkRules.JUDGMENT_REDUCED_POINT_RATIO, j.reducedPointRatio(), 1e-12);
        assertEquals(BenchmarkRules.SWEEP_POINTS, j.sweepPoints());

        // 등급별 대조 — 무효화 축은 같아야 한다. 부수 보고 축·표기 축은 다르면 보고 목록에 올릴 뿐 실패시키지 않는다(등급 규칙과 같은 엄격도)
        Map<String, String> current = new java.util.TreeMap<>();
        BenchmarkRules.ruleAxes().forEach((k, v) -> current.put(k, BenchmarkClusters.digestOf(v)));
        for (String axis : BenchmarkRules.INVALIDATING_AXES) {
            assertEquals(p.ruleAxisDigests().get(axis), current.get(axis),
                    "판정 무효화 축 '" + axis + "' 가 기준선 산출 시점과 다르다 — 이 기준선으로는 1~4항 판정 불가다. 기준선을 새 규칙으로 다시 뜨고 이력 행에 사유를 적는다");
        }
        List<String> secondaryDrift = axisDrift(p.ruleAxisDigests(), current, BenchmarkRules.SECONDARY_REPORT_AXES);
        List<String> notationDrift = axisDrift(p.ruleAxisDigests(), current, BenchmarkRules.NOTATION_AXES);
        if (!secondaryDrift.isEmpty()) System.out.println("[기준선 대조] 부수 보고 축 차이 — 이 기준선의 민감도 표는 판정 불가로 적는다: " + secondaryDrift);
        if (!notationDrift.isEmpty()) System.out.println("[기준선 대조] 표기 축 차이 — 보고에 옛→새 값을 적고 진행한다: " + notationDrift);
    }

    /** 등급 집합에 속한 축 중 다이제스트가 다른 것 — "축: 옛 → 새" 목록. 판정 보고의 0단계가 그대로 인용한다. */
    static List<String> axisDrift(Map<String, String> baseline, Map<String, String> current, java.util.Set<String> axes) {
        List<String> drift = new ArrayList<>();
        for (String axis : new java.util.TreeSet<>(axes)) {
            if (!java.util.Objects.equals(baseline.get(axis), current.get(axis))) drift.add(axis + ": " + baseline.get(axis) + " → " + current.get(axis));
        }
        return drift;
    }

    @DisplayName("기준선 파일은 LF 개행이고 저장소 다이제스트 규칙이 개행에 무관하다")
    @Test
    void the_baseline_file_is_lf_and_its_digest_is_eol_independent() throws IOException {
        byte[] raw = Files.readAllBytes(JudgmentBaselines.repositoryFile());
        String lf = new String(raw, java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals(BenchmarkClusters.digestOfContent(raw), BenchmarkClusters.digestOfContent(lf.replace("\n", "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "CRLF 로 체크아웃되어도 다이제스트가 같아야 한다 — .gitattributes 의 LF 규칙과 함께 빌드 OS 무관성을 지킨다");
        assertFalse(lf.startsWith("﻿"), "BOM 이 붙었다");
    }
}
