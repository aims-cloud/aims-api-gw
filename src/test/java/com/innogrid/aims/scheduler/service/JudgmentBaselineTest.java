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
 * 게시 뒤 별도 커밋)는 저장소 밖의 사실이라 이 테스트가 볼 수 없다 — 이력 행의 사유와 B 리비전 칸이 리뷰의 근거다.</p>
 *
 * <p>값은 판을 돌리지 않고 파일만 읽어 본다. 현 실행과의 대조는 판정 회전 테스트의 몫이다.</p>
 */
class JudgmentBaselineTest {

    /**
     * 기준선 이력 한 줄 — 파일 다이제스트(개행 LF 정규화 · SHA-256 앞 12자) · 날짜 · 사유 · 두 리비전 칸 · 직전 기준선과의 두 셀 중앙값 차(첫 행은 "없음").
     *
     * <p>리비전 칸은 둘이고 뜻이 다르다. {@code engineRevision} 은 엔진·판 생성 코드가 이 값을 낸 마지막 머지 리비전(리뷰 항목 — 저장소 안에서
     * 확인할 수 없다). {@code producedAt} 은 후보 파일을 실제로 산출한 HEAD 의 앞 7자로, 파일 출처의 {@code gitHead} 와 같아야 하며 테스트가 그것을 본다.
     * 브랜치 커밋 해시는 머지 방식에 따라 히스토리에 남지 않을 수 있다 — 파일 안의 값이 그 사실을 함께 적는다.</p>
     */
    record BaselineSeal(String digest, String date, String reason, String engineRevision, String producedAt, String medianShiftFromPrevious,
                        double judgmentMedian, double directionMedian) {}

    /** 마지막 행이 현재 파일이다. 파일을 갱신하는 커밋은 여기 행을 더한다 — 후보는 증거 묶음 테스트가 만든다. */
    static final List<BaselineSeal> BASELINE_HISTORY = List.of(
            new BaselineSeal("3fef93c9862b", "2026-09-15",
                    "기준선 봉인 도입 — 소비된 W 눈금 판정 뒤 처음으로 지점 배열째 보존. 판정 셀·방향 셀 × 열 5(power·눈금 3·역산 차단)·위상 짝 판 seed 3·오목 판. "
                            + "값은 이 브랜치의 규칙 커밋 뒤·파일 커밋 전 HEAD 에서 산출했고 규칙 축 다이제스트는 그 시점 값이다 — 브랜치 커밋 해시라 머지 방식에 따라 히스토리에 남지 않을 수 있다",
                    "294dae4 (develop, 배치 비교 방법론 PR 머지 — 이후 엔진·판 생성 코드 변경 없음)", "f4094c4", "없음(첫 기준선)", 13.4697, 17.5399),
            new BaselineSeal("ab38cd38ad62", "2026-09-15",
                    "판정 무효화 축 변경에 따른 재산출(재취득 예외 — 이 PR 은 판정을 게시하지 않는다). 지점 비교 무시 폭 0.0002 와 다음 회차 판정 주어가 judgment 축에 편입되고 판정 상수 블록에 무시 폭 칸이 생겼다. "
                            + "첫 기준선(3fef93c9862b)은 judgment 축이 달라 더는 B 로 쓸 수 없다. 계산 코드는 그대로라 지점 배열은 첫 기준선과 같다 — 산출 HEAD 는 규칙·형식 커밋 뒤·파일 커밋 전의 "
                            + "브랜치 커밋이지만 이 저장소는 머지 커밋 방식이라 머지 뒤에도 도달 가능하다(첫 기준선의 f4094c4 가 develop 조상으로 남아 있음)",
                    "812e253 (develop, 한계 전력 곡선 평가점 실효 기저 PR 머지 — 판정 근거 열 값 불변)", "ca62b5f", "0.0000 / 0.0000", 13.4697, 17.5399),
            new BaselineSeal("27d5b1373690", "2026-09-16",
                    "판정 무효화 축 변경에 따른 재산출(재취득 예외 — 이 PR 은 판정을 게시하지 않는다)이며 회차 1 판정 게시 뒤의 정례 재취득을 겸한다(정례 의무의 직전 머지 리비전은 엔진 동등 리비전 칸). 회차 2 판정 주어 교체로 judgment 축이 돌아 ab38cd38ad62 는 더는 B 로 쓸 수 없다. "
                            + "두 셀 중앙값 이동(13.4697 → 10.7247 / 17.5399 → 14.9318)은 전부 회차 1 정정(배치 점수 PEI·비낭비 항의 실효 기저, PASS)에서 온 것이고 이 PR 의 규칙 변경 기여는 0 — "
                            + "두 셀 power 행 지점 배열이 회차 1 회전 초안의 N 배열과 지점별로 같고 증거 묶음 entriesDigest 1753d94dad6b 도 그대로다. 산출 HEAD 는 이 PR 의 규칙 커밋 자체(그 커밋에서 낸 값이며 파일 커밋 전)로 브랜치 커밋이지만 머지 커밋 방식이라 머지 뒤에도 도달 가능",
                    "5e2da67 (develop, 배치용 PEI 가드 PR 머지 — 기본 설정 값 불변; 그 아래 모니터링 PR 머지 55720c8 은 엔진 무관, 회차 1 정정 PR 머지 2f6af39)", "f5b7d4c", "2.7450 / 2.6081", 10.7247, 14.9318),
            new BaselineSeal("17c8e496a4b5", "2026-09-16",
                    "판정 무효화 축 변경에 따른 재산출(재취득 예외 — 이 PR 은 판정을 게시하지 않는다)이며 열 항 판정 게시 뒤의 정례 재취득을 겸한다. 다음 판정 주어 교체로 judgment 축이 돌아 27d5b1373690 은 더는 B 로 쓸 수 없다. "
                            + "두 셀 중앙값 이동(10.7247 → 12.4476 / 14.9318 → 16.1995, 확대)은 전부 열 항 실효 온도 기저 정정(FAIL)에서 온 것이고 이 PR 의 규칙·형식 변경 기여는 0 — "
                            + "두 셀 power 행 지점 배열이 그 판정의 회전 초안 N 배열과 지점별로 같다. 형식 3(두 판의 전 셀 power 행 배열 outside)은 파일 다이제스트를 바꾸되 값은 바꾸지 않는다. 산출 HEAD 는 이 PR 의 형식 커밋 뒤 브랜치 커밋(머지 커밋 방식이라 머지 뒤에도 도달 가능)",
                    "0f1eb88 (develop, 열 항 실효 온도 기저 PR 머지 — 이후 엔진·판 생성 코드 변경 없음)", "c543089", "-1.7229 / -1.2677", 12.4476, 16.1995),
            new BaselineSeal("8696f4da2e4d", "2026-10-06",
                    "판정 무효화 축 변경에 따른 재산출(재취득 예외 — 이 PR 은 판정을 게시하지 않는다)이며 한계 전력 식 유휴 기준선 판정 게시 뒤의 정례 재취득을 겸한다. 그 지목을 소비 목록에 내리고 다음 판정 주어를 공석으로 두어 judgment 축이 돌아 17c8e496a4b5 는 더는 B 로 쓸 수 없다. "
                            + "두 셀 중앙값 이동(12.4476 → 11.8396 / 16.1995 → 16.6866, 판정 셀 축소·방향 셀 확대)은 전부 그 판정의 정정(FAIL)에서 온 것이고 이 PR 의 규칙 변경 기여는 0 — "
                            + "증거 묶음 entriesDigest 0cafa69fbcc9 가 그 판정 게시 시점과 같고 두 셀 중앙값이 게시된 N 값과 같다. 산출 HEAD 는 이 PR 의 규칙 커밋 자체(그 커밋에서 낸 값이며 파일 커밋 전)로 브랜치 커밋이지만 머지 커밋 방식이라 머지 뒤에도 도달 가능",
                    "757a6b4 (develop, 스케줄 편집 지정 해제 PR 머지 — 유휴 기준선 정정 PR 머지 뒤 엔진·판 생성 코드 변경 없음)", "616ea09", "0.6080 / -0.4871", 11.8396, 16.6866));

    private static JudgmentBaseline load() throws IOException {
        return JudgmentBaselines.readRepositoryBaseline();
    }

    @DisplayName("기준선 파일의 내용 다이제스트가 이력의 마지막 행과 같다 — 파일을 고치는 커밋은 이력 행을 더한다")
    @Test
    void the_baseline_file_is_sealed_by_its_history() throws IOException {
        String actual = JudgmentBaselines.digestOf(JudgmentBaselines.repositoryFile());
        BaselineSeal current = BASELINE_HISTORY.get(BASELINE_HISTORY.size() - 1);
        assertEquals(current.digest(), actual,
                "기준선 파일이 바뀌었다 — 이력에 행(다이제스트·날짜·사유·엔진 동등 리비전·산출 HEAD·직전 B 와의 중앙값 차)을 더하는 커밋을 따로 두고, "
                        + "1~4항 판정을 게시하는 PR 안에서는 이 파일을 고치지 않는다(무효화 축·판·워크로드를 바꾸는 판정 없는 PR 만 예외)");
        for (BaselineSeal s : BASELINE_HISTORY) {
            assertEquals(12, s.digest().length(), s.toString());
            assertFalse(s.reason().isBlank() || s.engineRevision().isBlank() || s.medianShiftFromPrevious().isBlank(),
                    "이력 행의 사유·엔진 동등 리비전·중앙값 차가 비었다: " + s);
            assertTrue(s.producedAt().matches("[0-9a-f]{7,40}"), "산출 HEAD 는 커밋 해시 앞자리다: " + s);
        }
        // 산출 HEAD 는 저장소 안 사실이라 기계로 잠긴다 — 파일 출처의 gitHead 가 이력 행이 말하는 커밋에서 나왔어야 한다
        assertTrue(load().provenance().gitHead().startsWith(current.producedAt()),
                "기준선 파일의 출처 gitHead(" + load().provenance().gitHead() + ")가 이력 행의 산출 HEAD(" + current.producedAt() + ")와 다르다 — 어느 쪽이 거짓인지 가른다");
        // 이력 행이 적은 두 셀 중앙값은 파일 값과 같아야 한다 — "값이 그대로/얼마나 밀렸다" 는 행의 주장이 손으로 적힌 채 남지 않게
        assertEquals(current.judgmentMedian(), load().cells().get("judgment").columns().get("power").median(), 1e-12, "이력 행의 판정 셀 중앙값이 파일과 다르다");
        assertEquals(current.directionMedian(), load().cells().get("direction").columns().get("power").median(), 1e-12, "이력 행의 방향 셀 중앙값이 파일과 다르다");
        assertEquals(BASELINE_HISTORY.size(), BASELINE_HISTORY.stream().map(BaselineSeal::digest).distinct().count(), "같은 다이제스트가 두 행에 있다");
    }

    @DisplayName("기준선 파일이 판정 셀·방향 셀 × 열 5 의 지점 배열(길이 12, null 자리 보존)과 표본 수·중앙값을 담고 판정 셀은 판정 가능하다")
    @Test
    void the_baseline_carries_point_arrays_for_both_cells() throws IOException {
        JudgmentBaseline b = load();
        assertEquals(JudgmentBaselines.SCHEMA_VERSION, b.schemaVersion());
        assertEquals(List.of("direction", "judgment"), new ArrayList<>(new java.util.TreeMap<>(b.cells()).keySet()));
        assertNotNull(b.outside(), "형식 3 은 두 판의 전 셀 power 행을 담는다");
        assertEquals(16, b.outside().size(), "판정 자격 두 판 × 부하율 4 × 전원 제어 2");
        assertTrue(b.outside().containsKey(JudgmentBaselines.outsideKey(BenchmarkRules.JUDGMENT_BOARD, BenchmarkRules.JUDGMENT_LOAD_RATIO, BenchmarkRules.JUDGMENT_POWER_CONTROL.name())));
        for (String cellKey : List.of("judgment", "direction")) {
            CellBlock block = b.cells().get(cellKey);
            Column outsidePower = b.outside().get(JudgmentBaselines.outsideKey(block.board(), block.loadRatioPercent(), block.powerControl())).power();
            assertEquals(block.columns().get("power").points(), outsidePower.points(), cellKey + " 셀의 power 지점 배열이 두 블록에서 다르다");
            assertEquals(block.columns().get("power").median(), outsidePower.median(), cellKey + " 셀의 power 중앙값이 두 블록에서 다르다");
        }
        for (Map.Entry<String, JudgmentBaselines.OutsideCell> e : b.outside().entrySet()) {
            Column c = e.getValue().power();
            String where = "outside " + e.getKey();
            if (c.points() != null) {
                assertEquals(BenchmarkRules.SWEEP_POINTS, c.points().size(), where + ": 지점 배열 길이");
                assertEquals(c.samples(), c.points().stream().filter(p -> p != null).count(), where + ": samples 가 null 아닌 지점 수와 다르다");
                assertEquals(c.median(), BenchmarkRules.median(c.points()), where + ": 중앙값이 지점 배열과 어긋난다");
            } else {
                assertEquals(0, c.samples(), where + ": 배열 없는 셀은 표본 0");
                assertNull(c.median(), where + ": 배열 없는 셀은 중앙값 없음");
            }
        }

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
        assertEquals(BenchmarkRules.JUDGMENT_POINT_IGNORE_PP, j.pointIgnorePp(), 1e-12, "기준선이 산출된 시점의 무시 폭 — 판정 함수 문턱이 파일에 남아야 회차 대조가 된다");

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
