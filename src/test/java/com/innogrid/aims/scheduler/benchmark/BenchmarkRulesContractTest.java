package com.innogrid.aims.scheduler.benchmark;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.benchmark.BenchmarkMetric.Direction;
import com.innogrid.aims.scheduler.benchmark.BenchmarkMetric.Role;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.controller.PlacementBenchmarkController;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.Assumptions;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.Board;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.MetricValue;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.metrics.SyntheticCluster;

import io.swagger.v3.oas.annotations.Operation;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 판정 규칙이 <b>결과보다 먼저</b> 고정됐는지 지킨다.
 *
 * <p>이 테스트의 목적은 회귀 방지가 아니라 <b>사전 등록의 강제</b>다. 지표를 나중에 추가·삭제·
 * 방향 반전하면 여기가 빨간불이 되므로, 결과를 보고 기준을 조용히 바꿀 수 없다.</p>
 */
class BenchmarkRulesContractTest {

    /**
     * 사전 등록된 지표 키 집합의 다이제스트 — 키를 오름차순으로 정렬해 {@code ,} 로 이은 문자열의 SHA-256 앞 12자.
     *
     * <p>예전에는 키 11개를 여기 다시 나열했다(규칙 상수·라벨 상수·시험 목록의 3중 선언). 지표를 더하거나 빼면 규칙이
     * 바뀐 것이므로 이 값을 <b>먼저</b> 고쳐야 하고, 그 커밋이 결과 코드보다 앞에 있어야 한다는 사전 등록 요건은 그대로다.
     * 다이제스트가 바뀌는 커밋 메시지에 옛 값 → 새 값을 적는다.</p>
     */
    private static final String DECLARED_KEY_DIGEST = "039400bdc3c1";

    /** 사람이 읽는 사전 등록 목록(정렬). 다이제스트는 이 목록에서 계산해 리터럴과 맞춘다 — 두 줄이 같이 바뀌는 것이 이중 서명이다. */
    private static final List<String> DECLARED_KEYS = List.of(
            "activeNodeDelta", "c3InterventionCount", "engineBelievedCostW", "gapVsPabfdPercent", "generatorPowerW",
            "measuredTempStdDev", "perWorkloadPowerW", "placementRatePercent", "unmeasuredPlacementRate",
            "unmeasuredRateSpreadPercentPoints", "warnZoneNodeCount");

    /** 규칙 축 봉인 한 줄 — 다이제스트 · 날짜 · 사유. 이력의 마지막 행이 현재 값이다. */
    record AxisSeal(String digest, String date, String reason) {}

    /**
     * 판정 규칙 축별 다이제스트 <b>이력</b> — 지표 키 외의 규칙 값(부하율·지점 수·시드·판 상수·판정 문턱·금지 조각·절차 문장)도
     * 봉인한다. 축마다 행을 쌓고 마지막 행이 현재 값이어야 한다. 규칙을 바꾸는 커밋은 여기 행을 더하고(옛 → 새 값과 사유가
     * 남는다), 제목을 {@code rules:} 로 시작하며, 결과 코드 변경을 같은 커밋에 담지 않는다 — 이력 누락은 시험이 막고 커밋
     * 순서는 리뷰가 본다. 증거 묶음 매니페스트가 같은 값을 실으므로 옛 매니페스트의 다이제스트를 이 표로 해석한다.
     * {@code metrics} 축(키:방향:역할)은 키 집합만의 {@link #DECLARED_KEY_DIGEST} 와 목적이 달라 값이 다르다.
     */
    private static final Map<String, List<AxisSeal>> RULE_AXIS_HISTORY = Map.of(
            "metrics", List.of(new AxisSeal("0435607b4998", "2026-09-14", "축 봉인 도입 — 키:방향:역할")),
            "sweep", List.of(new AxisSeal("559301d27583", "2026-09-14", "축 봉인 도입(이전 값 없음 — 지표 키 다이제스트만 존재했다)")),
            "boards", List.of(new AxisSeal("2a0f6a76ba2f", "2026-09-14", "축 봉인 도입 — 볼록 지수·정격 불일치 비율"),
                    new AxisSeal("9ab95b89dc3e", "2026-09-14", "방법론 변경 사전 등록 — 오목 지수 0.625·오목/링크 판 id·위상 독립 판 2장·seed 수 3·SplitMix64 혼합 상수")),
            "measured", List.of(new AxisSeal("0189eec53480", "2026-09-14", "판 실측 불변조건을 규칙 문턱과 분리 — 이후 판정 시점 고정값(316) 으로 의미 확정, 현 실측은 축 밖")),
            "sensitivity", List.of(new AxisSeal("3741559c00dd", "2026-09-14", "축 신설 — 한계 전력 눈금 3값(250·1000·0)·역산 차단 열·링크 게이트 열")),
            "judgment", List.of(new AxisSeal("e1601b21aca2", "2026-09-14", "축 봉인 도입 — 판정 셀·문턱"),
                    new AxisSeal("cfb639b97e76", "2026-09-15", "판정 셀 분포(heavytail) 편입 — 소급 명문화: 값은 지금까지의 판정이 쓴 분포와 같고 앞으로의 선택 자유도만 닫는다. 판정 셀·문턱·격차 정의·판정 자격 불변"),
                    new AxisSeal("4d55e10c3eb8", "2026-09-15", "사후 개정(관측 계기)·다음 회차 사전 등록 — 지점 비교 무시 폭 0.0002(4자리 두 칸)와 다음 회차 판정 주어(JUDGMENT_TARGET) 편입. 한계 전력 곡선 평가점 정정의 증거 묶음 대조에서 실수 항등인 변경이 26칸을 한 칸씩 뒤집은 것(전부 순위 근거 아닌 표시 지표, 판정 근거 열은 바이트 동일)을 본 뒤 예방으로 세운 잡음 하한. 소비된 회차는 지점 배열 부재로 이미 판정 불가라 소급 영향이 없고, 다음 회차에 대해서는 결과 코드 앞의 사전 등록이며 판정을 더 엄격하게 하는 방향. 지목이 무효화 축에 있어 판정 PR 은 바꿀 수 없다. 판정 셀·중앙값 문턱·격차 정의·판정 자격 불변")),
            "banned", List.of(new AxisSeal("e621e7f43e72", "2026-09-14", "축 봉인 도입 — 금지 조각 10")),
            "prose", List.of(new AxisSeal("ae0fbae7a919", "2026-09-14", "여유 정의·격차 판정 절차 문장(공백 접음)"),
                    new AxisSeal("b37d0ec2006e", "2026-09-14", "폐쇄형 판정 자격 문장 추가, 민감도 검정·위상 구성·오목 판 결론 형식 문장 신설"),
                    new AxisSeal("d06e13cc090c", "2026-09-14", "총량 짝 조건 기준 집합 규칙 — override 없는 기본 설정 열로 고정. 배포 검증에서 변종 열 추가만으로 기존 열의 총량 표본이 5 → 1 로 줄어든 것을 확인한 뒤의 사후 개정. 판정 문턱·판정 셀·격차 정의는 불변"),
                    new AxisSeal("45be90af10f3", "2026-09-14", "배포 검증 수치를 본 뒤의 2차 사후 개정 — 민감도 검정을 두 행의 유효 지점 교집합 위 지점별 차로 정정하고 수준량/판정량 구분을 명시, 위상 구성 범위 보고 의무와 오목 판 결론의 차·역산 기여 슬롯 추가, 총량 짝 제외 지점의 응답 표기 의무. 판정 셀·판정 문턱·격차 정의·판정 자격은 불변"),
                    new AxisSeal("7f4153f1a489", "2026-09-15", "문장 명확화 — 민감도 '세 눈금' 을 기본 500W(power)·s250·s1000 으로 명시하고 s0 은 사다리 밖으로. 의미 변경 없음"),
                    new AxisSeal("4ecbed57aecb", "2026-09-15", "직전 배포 관측을 본 뒤의 사후 개정 — 기준선 보존(지점 배열째 저장소 파일·출처 인용·결측 적시), B·N 의 생산 경로를 저장소 시험 기본 설정 실행으로 고정(배포 응답은 참고), 기준선 재사용 규칙(판정 PR 은 파일 불변·갱신은 보고 게시 뒤 별도 커밋·이력 행에 중앙값 차), 사전 추정의 산출 방법 기재 의무. 판정 셀·문턱·격차 정의·판정 자격 불변"),
                    new AxisSeal("6f8bfe5c389e", "2026-09-15", "규칙 축 대조 등급 신설(사전 등록) — 판정 무효화 축(judgment·sweep·boards·measured)·부수 보고 무효화 축(sensitivity)·표기 축(metrics·banned·prose). 다음 판정의 출처 대조 절차이며 판정 셀·문턱·격차 정의·판정 자격 불변"),
                    new AxisSeal("8f6dfdcf02e2", "2026-09-15", "기준선 봉인 뒤 규칙 보강(관측 수치와 무관한 절차 보강) — 기준선 재취득 예외(무효화 축·판·워크로드를 바꾸는 PR 은 판정을 게시하지 않는 한 같은 PR 에서 기준선을 다시 뜬다), 이력 행 칸(엔진 동등 리비전·산출 HEAD) 명시, 판정량 비교는 4자리 반올림 차로(부동소수 잡음 제거), 기준선 경로를 값 대신 상수 이름으로 인용. 판정 셀·문턱·격차 정의·판정 자격 불변"),
                    new AxisSeal("e8659f43d2f9", "2026-09-15", "사후 개정(관측 계기)·다음 회차 사전 등록 — 3항 축소 지점에 무시 폭, 4항 방향 셀 중앙값 차에 그 두 배(중앙값 축은 반올림이 겹쳐 최대 두 칸 잡음 — 밴드는 두 칸 초과, 여유 한 칸을 더해 네 칸) 적용 문장. 판정 주어를 회차마다 rules: 커밋이 하나로 지목한 결함으로(다음 회차 지목은 JUDGMENT_TARGET 인용), 지목은 judgment 축에 있어 판정 PR 안에서 바꾸면 기준선 판정 불가, 등급 규칙에 같은 단서. 판정 셀·중앙값 문턱·격차 정의·판정 자격 불변")));

    @DisplayName("사전 등록된 지표 키 집합이 고정된다 — 정렬 키 목록과 그 다이제스트 둘 다로")
    @Test
    void metric_keys_are_fixed() {
        List<String> actual = BenchmarkRules.METRICS.stream().map(BenchmarkMetric::key).sorted().toList();
        assertEquals(BenchmarkRules.METRICS.size(), actual.stream().distinct().count(), "키 중복 없음");
        assertEquals(DECLARED_KEYS, actual, "지표 키 집합이 사전 등록 목록과 다르다 — 목록·다이제스트를 결과 코드보다 앞선 커밋에서 먼저 고친다");
        assertEquals(DECLARED_KEY_DIGEST, BenchmarkClusters.digestOf(String.join(",", DECLARED_KEYS)),
                "목록과 다이제스트가 어긋난다 — 둘을 같은 커밋에서 함께 고친다(옛 → 새 값을 메시지에)");
    }

    @DisplayName("판정 규칙의 축별 정규 문자열이 다이제스트 리터럴과 같다 — 규칙 변경은 결과 코드보다 앞선 커밋에서만")
    @Test
    void rule_axes_are_sealed() {
        Map<String, String> axes = BenchmarkRules.ruleAxes();
        assertEquals(RULE_AXIS_HISTORY.keySet(), axes.keySet(), "축 집합이 다르다");
        List<String> changed = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : axes.entrySet()) {
            List<AxisSeal> history = RULE_AXIS_HISTORY.get(e.getKey());
            String current = history.get(history.size() - 1).digest();
            String actual = BenchmarkClusters.digestOf(e.getValue());
            if (!actual.equals(current)) {
                changed.add(e.getKey() + ": " + current + " → " + actual + "  정규 문자열: " + e.getValue());
            }
        }
        assertTrue(changed.isEmpty(), () -> "규칙 축이 바뀌었다 — 이력에 행(새 값·날짜·사유)을 더하는 'rules:' 커밋을 결과 코드보다 앞에 둔다\n  "
                + String.join("\n  ", changed));
    }

    /** 판정 주어(회차 지목) 이력 — 회차 · 지목 문장 조각 · 등록한 rules: 커밋의 성격. 마지막 행이 현 규칙 문장에 그대로 있어야 한다. */
    record TargetSeal(String round, String fragment, String note) {}

    private static final List<TargetSeal> JUDGMENT_TARGET_HISTORY = List.of(
            new TargetSeal("소비된 회차", "깨우기 벌점이 유휴비로 고정되던 일률 편향", "W 눈금 전환(PR #49) — 사전 등록 커밋 뒤 결과 커밋"),
            new TargetSeal("다음 회차", BenchmarkRules.JUDGMENT_TARGET,
                    "무시 폭 편입과 함께 결과 코드 앞에 등록 — judgment 축에 편입되어 판정 PR 은 바꿀 수 없다"));

    @DisplayName("판정 주어의 회차 지목이 이력으로 봉인되고 현 규칙 문장에 그대로 있다 — 판정 PR 안에서 바뀌면 그 회차는 판정 불가")
    @Test
    void the_judgment_target_history_is_sealed() {
        String r = BenchmarkRules.GAP_JUDGMENT_RULE;
        for (TargetSeal s : JUDGMENT_TARGET_HISTORY) {
            assertTrue(r.contains(s.fragment()), s.round() + " 의 지목 문장이 규칙에서 사라졌다: " + s.fragment());
        }
        assertTrue(r.contains("기준선을 다시 뜨지 못한다"), "지목 변경이 기계로 막히는 이유(재취득 불가)가 규칙 문장에 있어야 한다");
        assertTrue(BenchmarkRules.ruleAxes().get("judgment").contains("target=" + BenchmarkRules.JUDGMENT_TARGET),
                "지목은 판정 무효화 축에 있어야 판정 PR 이 바꿀 수 없다 — 표기 축이면 명예 규정이다");
        assertTrue(BenchmarkRules.AXIS_GRADE_RULE.contains("판정 주어"), "등급 규칙에 판정 주어 단서가 있어야 한다");
    }

    @DisplayName("규칙 축 등급 세 집합은 서로 겹치지 않고 합이 축 전체와 같다 — 새 축은 등급을 정해야 생긴다")
    @Test
    void rule_axis_grades_partition_the_axes() {
        Set<String> all = new TreeSet<>(BenchmarkRules.INVALIDATING_AXES);
        assertTrue(all.addAll(BenchmarkRules.SECONDARY_REPORT_AXES), "부수 보고 축이 무효화 축과 겹친다");
        assertTrue(all.addAll(BenchmarkRules.NOTATION_AXES), "표기 축이 다른 등급과 겹친다");
        assertEquals(BenchmarkRules.INVALIDATING_AXES.size() + BenchmarkRules.SECONDARY_REPORT_AXES.size() + BenchmarkRules.NOTATION_AXES.size(),
                all.size(), "등급 집합이 겹친다");
        assertEquals(BenchmarkRules.ruleAxes().keySet(), all, "등급이 정해지지 않은 축이 있다 — 등급표(AXIS_GRADE_RULE)에 넣는 rules: 커밋이 먼저다");
        assertTrue(BenchmarkRules.INVALIDATING_AXES.contains("judgment"), "판정 셀·문턱이 든 축은 무효화 축이어야 한다");
        assertTrue(BenchmarkRules.SECONDARY_REPORT_AXES.contains("sensitivity"), "눈금 목록이 바뀌면 민감도 표가 대응하지 않는다");
        assertTrue(BenchmarkRules.AXIS_GRADE_RULE.contains("판정 불가") && BenchmarkRules.AXIS_GRADE_RULE.contains("부수 보고"), BenchmarkRules.AXIS_GRADE_RULE);
        assertTrue(BenchmarkRules.ruleAxes().get("prose").contains("axisGradeRule="), "등급표가 prose 축에 봉인돼야 한다");
        assertFreeOfBannedClaims(BenchmarkRules.AXIS_GRADE_RULE, "축 등급 규칙 문장");
    }

    @DisplayName("지표마다 정의와 단위와 유효성 조건이 비어 있지 않다")
    @Test
    void every_metric_is_fully_declared() {
        for (BenchmarkMetric m : BenchmarkRules.METRICS) {
            assertFalse(m.key().isBlank(), "key 가 비었다");
            assertFalse(m.label().isBlank(), m.key() + ": label 이 비었다");
            assertNotNull(m.unit(), m.key() + ": unit 은 무차원이어도 빈 문자열이어야 한다");
            assertNotNull(m.direction(), m.key() + ": 좋은 방향이 없다");
            assertNotNull(m.role(), m.key() + ": 역할이 없다");
            assertFalse(m.validity().isBlank(),
                    m.key() + ": 이 숫자가 언제 의미를 갖는지 적지 않으면 셀의 valid 판정 근거가 없다");
        }
    }

    /**
     * 엔진이 스스로 믿은 비용은 <b>전력 엔진 총점의 한 항을 그대로 합한 값</b>이다.
     * 그것을 순위 근거로 쓰면 "우리가 1등"은 발견이 아니라 정의가 된다.
     */
    @DisplayName("엔진이 믿은 비용은 순위 근거로 쓰지 않는다")
    @Test
    void engine_believed_cost_is_context_only() {
        BenchmarkMetric m = metric(BenchmarkRules.ENGINE_BELIEVED_COST);

        assertEquals(Direction.CONTEXT_ONLY, m.direction(),
                "엔진 목적함수와 같은 함수를 좋은 방향이 있는 지표로 두면 순환 논증이다");
        assertEquals(Role.SECONDARY, m.role(), "헤드라인이 될 수 없다");
    }

    /**
     * 평가자도 생성식을 쓴다. "지상진실"이라는 이름은 실측을 암시하고, 표만 잘라 인용되면 그 암시가 <!-- ban-ok: 이름 금지 사유 -->
     * 사실로 굳는다 — 이름·라벨 어디에도 그 단어가 없어야 한다.
     */
    @DisplayName("총전력의 주 지표는 엔진 점수가 아니라 생성식 기준값이고, 실측을 암시하는 이름을 쓰지 않는다")
    @Test
    void generator_power_is_primary_and_not_named_ground_truth() {
        BenchmarkMetric m = metric(BenchmarkRules.GENERATOR_POWER);

        assertEquals(Role.PRIMARY, m.role());
        assertEquals(Direction.LOWER_IS_BETTER, m.direction());
        assertFalse(m.key().toLowerCase().contains("groundtruth"), m.key());
        for (BenchmarkMetric each : BenchmarkRules.METRICS) {
            assertFreeOfBannedClaims(each.label(), each.key() + " 라벨");
            assertFalse(each.label().contains("실제"),
                    each.key() + ": '실제'도 같은 암시다 — " + each.label());
        }
    }

    /**
     * 열 계열 두 지표는 <b>헤드라인이 아니다</b>. 대조군은 열을 점수에 넣지 않으므로 이 값의 차이는
     * 알고리즘의 열 판단이 아니라 CPU 를 어디에 몰았는가의 부산물이고, 제약 개입 횟수는 게이트가
     * 몇 번 걸렸는지이지 좋고 나쁨이 아니다. "열 모델이 없어 비교 불가"라고 적지 않는다 — 대조군도
     * 온도 제약은 똑같이 받는다({@code every_baseline_scores_all_candidates} 가 그 사실을 증명한다).
     */
    @DisplayName("열 계열 두 지표는 헤드라인이 아니고 개입 횟수에는 좋은 방향이 없다")
    @Test
    void the_thermal_metrics_are_not_headline_numbers() {
        BenchmarkMetric warn = metric(BenchmarkRules.WARN_ZONE_NODES);
        BenchmarkMetric interventions = metric(BenchmarkRules.C3_INTERVENTIONS);

        assertEquals(Role.SECONDARY, warn.role(), "주 지표로 두면 이 판의 결론이 열에 실린다");
        assertEquals(Direction.LOWER_IS_BETTER, warn.direction(), "적은 쪽이 실제로 덜 뜨거운 것은 맞다");

        assertEquals(Role.SECONDARY, interventions.role());
        assertEquals(Direction.CONTEXT_ONLY, interventions.direction(),
                "덜 막힌 쪽이 낫다고 두면 열을 아예 보지 않는 전략이 상을 받는다");

        for (BenchmarkMetric m : List.of(warn, interventions)) {
            assertTrue(m.validity().contains("점수에 넣지 않"),
                    m.key() + ": 열을 점수에 넣지 않는 대조군이 있다는 사실이 유효성 조건에 있어야 한다");
            assertTrue(m.validity().contains("최저 온도 우선"),
                    m.key() + ": 열을 점수에 넣는 대조군이 있다는 사실도 있어야 한다 — 없으면 온도 축 참가자가 우리뿐이라는 옛 문구다");
            assertFalse(m.validity().contains("열 모델이 없"),
                    m.key() + ": 대조군도 온도 제약은 받는다 — 코드가 증명하는 사실과 어긋나는 문구다");
        }
    }

    /**
     * 열 대조군을 넣으면서 <b>결과보다 먼저</b> 고정하는 문구다. 대조군이 어떤 숫자를 내든 이 문장은 바뀌지 않는다 —
     * 열 대조군은 재평가가 없어 초기 온도로 정렬한 통합에 가깝고(임계 75℃ 에서는 배치 후 예상 온도 제약이 시원한
     * 노드에 작동하지 않는다), 그 열과 자원 최밀집 열의 차이가 정렬 기준에 온도를 넣은 효과의 전부다. 그래서 열 지표는
     * 여전히 헤드라인이 아니다. 대조군이 자원 최밀집과 같은 숫자를 내더라도 대응은 대조군 교체가 아니라 이 문구의
     * 강화다 — 결과를 보고 대조군을 바꾸면 사전 등록이 무의미해진다.
     */
    @DisplayName("한계 문구가 열 대조군의 성격과 자원 최밀집 대조 해석을 밝히고, 온도 축 결론을 여전히 주장하지 않는다")
    @Test
    void the_limitation_states_what_the_thermal_baseline_is_and_is_not() {
        String l = BenchmarkRules.LIMITATION;

        assertTrue(l.contains("최저 온도 우선"), "열 대조군의 이름이 한계 문구에 있어야 한다");
        assertTrue(l.contains("재평가 없는"), "재평가가 없다는 성질 — 문헌 TASA 와 갈리는 지점 — 을 밝혀야 한다");
        assertTrue(l.contains("bfd"), "자원 최밀집 열과의 차이로 해석하라는 지침이 있어야 한다");
        assertTrue(l.contains("열 지표는 헤드라인이 아니"), "대조군이 생겼다고 온도 축을 헤드라인으로 올리면 조용한 승격이다");
        assertTrue(l.contains("제약을 켠 전 전략"),
                "온도 제약을 '전 전략'이 받는다고 쓰면 제약 해제 변종의 셀 사유와 같은 응답 안에서 모순된다");
        assertTrue(l.contains("기본 임계 75℃"), "75℃ 는 정책 기본값이지 상수가 아니다 — 같은 파일의 '기본 65℃' 와 어조를 맞춘다");
        assertFreeOfBannedClaims(l,
                "한계 문구 — 온도 축 참가자가 우리뿐이라는 옛 문구가 남아 있으면 대조군을 넣고도 응답이 거짓을 말한다");
    }

    /**
     * 온도 분포 두 지표는 총량과 같은 조건에서만 유효하다 — 덜 배치한 전략은 CPU 를 덜 얹어 생성식 온도가
     * 낮으므로, 건수가 다른 지점을 그냥 세면 열이 아니라 배치량을 재게 된다. 개입 횟수는 <b>시도당</b> 계수라
     * (전 전략이 같은 워크로드를 한 번씩 시도한다) 배치량에 비례하지 않으므로 이 조건을 받지 않는다.
     */
    @DisplayName("온도 분포 두 지표는 배치 건수가 같은 지점에서만 유효하고 개입 횟수는 그 조건을 받지 않는다")
    @Test
    void the_temperature_distribution_metrics_share_the_pairing_condition_of_the_totals() {
        String pairing = "기본 설정 열 전부의 배치 건수가 같은 시각 지점에서만";

        for (String key : List.of(BenchmarkRules.WARN_ZONE_NODES, BenchmarkRules.TEMP_STDDEV)) {
            assertTrue(metric(key).validity().contains(pairing),
                    key + ": 총량 지표와 같은 짝짓기 조건이 유효성 문구에 있어야 한다 — 없으면 덜 배치한 전략이 열에서 이긴다");
        }
        assertFalse(metric(BenchmarkRules.C3_INTERVENTIONS).validity().contains(pairing),
                "개입 횟수는 시도당 계수라 배치량에 비례하지 않는다 — 같은 조건을 걸면 없는 결함을 고치는 것이다");
        assertTrue(metric(BenchmarkRules.GENERATOR_POWER).validity().contains("배치 건수가 같은 지점에서만"),
                "총량 쪽 조건이 사라졌다면 '같은 조건' 이라는 위 문구의 기준이 없어진다");
    }

    /**
     * 주 지표가 어떤 것들인지 목록으로 못 박는다 — 조용히 늘거나 줄면 결론의 근거가 바뀐 것이다.
     * 온도 축이 여기 없는 것은 의도다: 대조군이 열을 점수에 넣지 않아 온도 축에 참가자가 우리뿐이고,
     * 그 사실은 {@link BenchmarkRules#LIMITATION} 이 밝힌다.
     *
     * <p>격차 지표가 여기 <b>다섯째</b>로 들어 있는 것도 의도다 — 다음 PR 의 판정이 읽는 값이라 보조로 두면,
     * 판정에 쓰이는 순간 조용한 승격이 된다. ΔP 단독 대조군(pabfd)을 분모로 삼고 전 전략 행에 같은 식으로 실으며
     * 좋은 방향이 낮은 쪽이라, 주 지표로 올린다고 우리 열이 유리해지지 않는다.</p>
     */
    @DisplayName("주 지표는 성공률과 전력·가동 계열뿐이고 온도 축에는 없다")
    @Test
    void the_primary_metrics_are_the_placement_rate_and_the_power_family() {
        Set<String> primary = BenchmarkRules.METRICS.stream()
                .filter(m -> m.role() == Role.PRIMARY)
                .map(BenchmarkMetric::key).collect(Collectors.toCollection(TreeSet::new));

        assertEquals(new TreeSet<>(Set.of(BenchmarkRules.PLACEMENT_RATE, BenchmarkRules.GENERATOR_POWER,
                BenchmarkRules.PER_WORKLOAD_POWER, BenchmarkRules.ACTIVE_NODE_DELTA,
                BenchmarkRules.GAP_VS_PABFD)), primary);
        assertTrue(BenchmarkRules.LIMITATION.contains("열 지표는 헤드라인이 아니"),
                "주 지표에서 온도를 내렸으면 응답이 그 사실을 스스로 말해야 한다 — 아니면 조용한 강등이다");
    }

    @DisplayName("정직성 측정치는 방향이 없다 — 판정이 아니라 숫자다")
    @Test
    void honesty_metrics_have_no_direction() {
        List<BenchmarkMetric> honesty = BenchmarkRules.METRICS.stream()
                .filter(m -> m.role() == Role.HONESTY).toList();

        assertEquals(2, honesty.size(), "미계측 비율과 그 격차 두 개여야 한다");
        for (BenchmarkMetric m : honesty) {
            assertEquals(Direction.CONTEXT_ONLY, m.direction(),
                    m.key() + ": 정직성 측정치에 좋은 방향을 주면 그것이 목표가 된다");
        }
    }

    /**
     * 최고 온도를 주 지표에서 뺀 것은 의도다. 배치 1건의 상승분(약 1.4℃)보다 시각에 따른
     * 자연 변동(±4.5℃)이 3배 크고, 제약 C3 가 임계 이하로 잘라 한 비트짜리 열이 된다.
     */
    @DisplayName("최고 온도는 지표 목록에 없다")
    @Test
    void max_temperature_is_not_a_metric() {
        Set<String> keys = BenchmarkRules.METRICS.stream()
                .map(BenchmarkMetric::key).collect(Collectors.toSet());

        assertFalse(keys.contains("maxTemperatureCelsius"),
                "제약이 임계로 자르는 값은 알고리즘을 재지 않고 제약이 켜졌는지를 잰다");
        assertFalse(keys.contains("avgTemperatureCelsius"),
                "평균은 배치로 거의 움직이지 않는다 — 표준편차를 쓴다");
    }

    @DisplayName("승자를 지명하는 지표를 두지 않는다")
    @Test
    void no_winner_metric() {
        for (BenchmarkMetric m : BenchmarkRules.METRICS) {
            assertFalse(m.key().toLowerCase().contains("winner"),
                    "승자를 API 가 계산해 주면 그 필드만 인용되고, 판 이름에서 분리되면 거짓이 된다");
        }
    }

    @DisplayName("부하율은 네 지점이고 오름차순이며 수용 불가 부하를 포함한다")
    @Test
    void load_ratios_ascend_and_include_overload() {
        List<Integer> r = BenchmarkRules.LOAD_RATIOS;

        assertEquals(List.of(30, 60, 90, 110), r);
        assertTrue(r.get(r.size() - 1) > 100,
                "100%를 넘는 지점이 없으면 성공률이 전략을 구분하지 못한다");
        for (int i = 1; i < r.size(); i++) {
            assertTrue(r.get(i) > r.get(i - 1), "부하율이 오름차순이어야 집합이 접두부로 겹친다");
        }
    }

    @DisplayName("무작위 시드는 서로 다른 홀수 개다")
    @Test
    void random_seeds_are_odd_count_and_distinct() {
        List<Long> seeds = BenchmarkRules.RANDOM_SEEDS;

        assertEquals(31, seeds.size(), "시드 하나는 표본 1개라 격차를 주장할 수 없다");
        assertEquals(seeds.size(), Set.copyOf(seeds).size(), "시드가 중복되면 표본 수가 부풀려진다");
        assertEquals(1, seeds.size() % 2, "홀수라야 중앙값이 잘 정의된다");
    }

    /**
     * 시드 목록의 <b>길이가 곧 표본 수</b>라는 주장은 난수원이 시드를 제대로 섞을 때만 참이다.
     *
     * <p>{@code new java.util.Random(seed)} 로는 성립하지 않는다 — 실측하면 시드 1~31 의
     * {@code nextInt(4)} 첫 값이 <b>전부 2</b> 이고 {@code nextDouble()} 첫 값이 0.7298~0.7326
     * 폭 0.003 안에 뭉친다. 그 난수원으로는 "무작위 31회"가 배치 건수가 적은 판에서 사실상 1회다.</p>
     */
    @DisplayName("사전 등록된 시드가 실제로 서로 다른 난수열을 낸다")
    @Test
    void declared_seeds_actually_yield_distinct_streams() {
        Set<Integer> firstOfFour = new java.util.LinkedHashSet<>();
        Set<Long> firstBits = new java.util.LinkedHashSet<>();
        for (long seed : BenchmarkRules.RANDOM_SEEDS) {
            firstOfFour.add(BenchmarkRules.randomFor(seed).nextInt(4));
            firstBits.add(BenchmarkRules.randomFor(seed).nextLong());
        }

        assertEquals(BenchmarkRules.RANDOM_SEEDS.size(), firstBits.size(),
                "시드마다 난수열이 달라야 표본 수가 시드 수와 같다");
        assertTrue(firstOfFour.size() >= 3,
                "네 개 중 하나를 뽑는 첫 추첨이 " + firstOfFour + " 뿐이다 — "
                        + "시드를 섞지 않는 난수원을 쓰면 시드 목록이 표본을 늘려 주지 못한다");
    }

    @DisplayName("같은 시드는 같은 난수열을 낸다")
    @Test
    void same_seed_yields_the_same_stream() {
        long seed = BenchmarkRules.RANDOM_SEEDS.get(0);

        assertEquals(BenchmarkRules.randomFor(seed).nextLong(), BenchmarkRules.randomFor(seed).nextLong(),
                "재현성이 깨지면 커밋된 시드 목록이 아무것도 고정하지 못한다");
    }

    @DisplayName("시각 스윕 지점이 둘 이상이다")
    @Test
    void sweep_has_more_than_one_point() {
        assertTrue(BenchmarkRules.SWEEP_POINTS > 1,
                "시각을 하나로 고정하면 결정론적 전략의 분산 추정치가 0 이 되어 오차 막대를 붙일 수 없다");
    }

    @DisplayName("벤치마크는 냉각 대기를 쓰지 않는다")
    @Test
    void cooldown_is_disabled_for_benchmark() {
        assertEquals(0, BenchmarkRules.COOLDOWN_MINUTES_FOR_BENCHMARK,
                "히스테리시스는 시계열 운영의 성질이라 단발 비교에 넣으면 셀 실행에 걸린 실제 시간이 결과를 바꾼다");
    }

    /**
     * 통전 회계 문턱은 판정 규칙이다. 엔진 노브를 그대로 읽으면 운영에서 그 값을 조정할 때 같은 배치
     * 결정의 가동 노드 회계가 달라져, 두 실행의 표를 나란히 놓을 수 없다.
     */
    @DisplayName("통전 문턱이 판정 규칙으로 고정되어 있다")
    @Test
    void the_powered_on_threshold_is_pre_registered() {
        assertEquals(5.0, BenchmarkRules.POWERED_ON_CPU_PERCENT, 1e-9,
                "이 값을 바꾸면 같은 배치 결정의 가동 노드 회계가 달라진다 — 결과를 보고 바꿀 수 없게 여기서 고정한다");
    }

    /**
     * <b>좋은 방향</b>은 표를 읽는 사람이 승패를 정하는 축이다. 결과를 보고 이것을 뒤집으면 사전 등록이
     * 아무것도 고정하지 못하므로, 열한 개 전량을 여기서 못 박는다. 지금까지는 넷만 잠겨 있었고 주 지표
     * 셋(성공률·건당 전력·가동 노드 증감)의 방향이 뚫려 있었다.
     */
    @DisplayName("지표 열한 개의 좋은 방향이 전부 고정된다")
    @Test
    void every_metric_direction_is_pinned() {
        Map<String, Direction> expected = Map.ofEntries(
                Map.entry(BenchmarkRules.PLACEMENT_RATE, Direction.HIGHER_IS_BETTER),
                Map.entry(BenchmarkRules.GENERATOR_POWER, Direction.LOWER_IS_BETTER),
                Map.entry(BenchmarkRules.GAP_VS_PABFD, Direction.LOWER_IS_BETTER),
                Map.entry(BenchmarkRules.PER_WORKLOAD_POWER, Direction.LOWER_IS_BETTER),
                Map.entry(BenchmarkRules.ENGINE_BELIEVED_COST, Direction.CONTEXT_ONLY),
                Map.entry(BenchmarkRules.ACTIVE_NODE_DELTA, Direction.LOWER_IS_BETTER),
                Map.entry(BenchmarkRules.WARN_ZONE_NODES, Direction.LOWER_IS_BETTER),
                Map.entry(BenchmarkRules.C3_INTERVENTIONS, Direction.CONTEXT_ONLY),
                Map.entry(BenchmarkRules.TEMP_STDDEV, Direction.LOWER_IS_BETTER),
                Map.entry(BenchmarkRules.UNMEASURED_RATE, Direction.CONTEXT_ONLY),
                Map.entry(BenchmarkRules.UNMEASURED_SPREAD, Direction.CONTEXT_ONLY));

        for (BenchmarkMetric m : BenchmarkRules.METRICS) {
            assertEquals(expected.get(m.key()), m.direction(),
                    m.key() + ": 좋은 방향을 바꾸려면 이 시험을 먼저 고쳐야 한다");
        }
        assertEquals(expected.size(), BenchmarkRules.METRICS.size());
    }

    /**
     * 제약을 끈 변종은 막히는 자리가 없어 성공률이 정의상 최고가 된다. 개입 횟수에만 가드를 걸고
     * 성공률에 안 걸면 <b>반칙으로 나빠 보이는 칸만 가리고 좋아 보이는 칸은 그대로 두는</b> 셈이라,
     * 사전 등록 문구가 그 비대칭을 먼저 막는다.
     */
    @DisplayName("성공률은 '항상 유효'가 아니다 — 제약을 끈 변종은 비교 대상이 아님을 밝힌다")
    @Test
    void the_placement_rate_declares_that_ungated_variants_are_not_competitors() {
        String validity = metric(BenchmarkRules.PLACEMENT_RATE).validity();

        assertFalse(validity.contains("항상 유효"),
                "제약을 끈 변종이 정의상 1위가 되는데 '항상 유효'라고 적으면 그 열이 헤드라인이 된다: " + validity);
        assertTrue(validity.contains("천장"),
                "제약 해제 변종의 열이 경쟁자가 아니라 천장이라는 사실이 문구에 있어야 한다: " + validity);
    }

    @DisplayName("배치 건수가 같을 때만 총량을 비교한다")
    @Test
    void totals_comparable_only_when_placed_counts_match() {
        assertTrue(BenchmarkRules.totalsComparable(List.of(20, 20, 20)));
        assertFalse(BenchmarkRules.totalsComparable(List.of(20, 19, 20)),
                "적게 배치하고 전력을 덜 쓰는 것은 승리가 아니다");
        assertFalse(BenchmarkRules.totalsComparable(List.of()), "빈 입력으로 유효를 주장할 수 없다");
        assertFalse(BenchmarkRules.totalsComparable(null));
    }

    @DisplayName("총량을 비교할 수 없는 사유에 실제 건수 범위가 들어간다")
    @Test
    void incomparable_reason_carries_the_range() {
        String reason = BenchmarkRules.totalsIncomparableReason(Map.of("a", 18, "b", 20, "c", 19));

        assertTrue(reason.contains("18"), reason);
        assertTrue(reason.contains("20"), reason);
    }

    @DisplayName("미계측 격차 경고에 격차와 양쪽 전략이 들어간다")
    @Test
    void unmeasured_caution_names_the_gap_and_strategies() {
        String s = BenchmarkRules.unmeasuredCaution(31.5, "random", "power");

        assertTrue(s.contains("31.5"), s);
        assertTrue(s.contains("random"), s);
        assertTrue(s.contains("power"), s);
        assertTrue(s.contains("센서"), "왜 문제인지가 문장에 있어야 각주 없이 읽힌다: " + s);
    }

    @DisplayName("한계 문구가 실측이 아님과 노드 한 대와 무엇을 증명하지 않는지를 밝힌다")
    @Test
    void limitation_states_the_hard_limits_and_what_is_not_proven() {
        String l = BenchmarkRules.LIMITATION;

        assertTrue(l.contains("실측이 아니"), l);
        assertTrue(l.contains("생성식"), "평가자도 우리가 만든 식을 쓴다는 사실이 있어야 한다: " + l);
        assertTrue(l.contains("한 대"), "배포 환경에서 비교가 성립하지 않음이 있어야 한다: " + l);
        assertTrue(l.contains("엔진의 모델 가정과 생성식이 얼마나 다른가"),
                "무엇을 확인하고 무엇을 확인하지 못하는지가 한 문장으로 있어야 인용될 때 떨어져 나가지 않는다: " + l);
        assertTrue(l.contains("결론 수준으로 증명하지 않는다"),
                "온도 축에 결론 지표가 없고 전력 이득이 반사실 판에서만 보인다는 사실을 응답이 스스로 말해야 한다 — "
                        + "말하지 않으면 불리한 지표를 조용히 내린 것으로 읽힌다: " + l);
        assertFreeOfBannedClaims(l, "한계 문구 자체가 실측을 암시하면 안 된다");
    }

    @DisplayName("부하율 분모 정의가 총 코어가 아니라 정책 상한까지의 여유다")
    @Test
    void headroom_definition_uses_policy_limits() {
        String d = BenchmarkRules.HEADROOM_DEFINITION;

        assertTrue(d.contains("상한"), d);
        assertTrue(d.contains("min"), "노드마다 구속 자원이 달라 두 축의 최솟값을 써야 한다: " + d);
    }

    // ── 볼록·정격 어긋남 판의 사전 등록 ───────────────────────

    /**
     * 지수는 <b>결과를 보고 고를 수 없는 자리</b>에 있어야 한다. 판을 돌려 보고 β 를 조정하면 그 판은
     * 응력 시험이 아니라 원하는 결론을 내는 장치가 된다. 값과 "1 보다 크다"는 성질을 함께 못 박는다 —
     * 값만 잠그면 뜻이 사라지고, 성질만 잠그면 1.2 로 조용히 바꿀 수 있다.
     */
    @DisplayName("볼록 판의 지수가 사전 등록되어 있고 1 보다 크다")
    @Test
    void the_convex_exponent_is_pre_registered_and_greater_than_one() {
        assertEquals(1.6, BenchmarkRules.CONVEX_POWER_EXPONENT, 1e-9,
                "이 값을 바꾸면 이 판으로 뜬 기준선이 무효다 — 결과를 보고 바꿀 수 없게 여기서 고정한다");
        assertTrue(BenchmarkRules.CONVEX_POWER_EXPONENT > 1.0,
                "1 이하면 엔진 가정이 틀린 방향으로 기울지 않아 판이 응력 시험이 아니게 된다");
        assertNotEquals(1.4, BenchmarkRules.CONVEX_POWER_EXPONENT, "문헌 계수와 같은 수는 '문헌 근거' 로 오독된다");
    }

    /**
     * 어긋남 크기는 대칭이어야 한다 — 한쪽만 크면 판 전체의 정격 합이 기울어 전력 제약 여유가 함께 움직이고,
     * 그러면 어긋남의 효과와 여유의 효과를 가를 수 없다.
     */
    @DisplayName("정격 어긋남 비율이 사전 등록되어 있고 0 과 1 사이다")
    @Test
    void the_capacity_mismatch_ratio_is_pre_registered() {
        assertEquals(0.10, BenchmarkRules.CAPACITY_MISMATCH_RATIO, 1e-9,
                "판 파일의 과대·과소 정격이 이 비율을 따른다 — 값을 바꾸면 판을 다시 떠야 한다");
        assertTrue(BenchmarkRules.CAPACITY_MISMATCH_RATIO > 0 && BenchmarkRules.CAPACITY_MISMATCH_RATIO < 1,
                "0 이면 어긋남이 없고 1 이상이면 과소 믿음 노드의 정격이 0 이하가 된다");
    }

    /**
     * 판정 셀을 결과보다 먼저 하나로 고정한다. 셀을 나중에 고르면 열두 셀 중 유리한 칸을 뽑는 사후 선택이
     * 되고, 그 자유도는 어떤 사전 등록 문구로도 막히지 않는다. 판 id 가 실제로 등록된 판인지는 판이 생긴
     * 뒤의 시험이 확인한다 — 지금은 판이 없으므로 값만 고정한다.
     */
    @DisplayName("판정 셀의 판·부하율·전원 가정이 결과보다 먼저 고정된다")
    @Test
    void the_judgment_cell_is_pre_registered() {
        assertEquals("convex-misrated", BenchmarkRules.JUDGMENT_BOARD,
                "판정은 두 요인이 함께 걸린 판에서 한다 — 단일 요인 판은 귀속용 부록이다");
        assertEquals(60, BenchmarkRules.JUDGMENT_LOAD_RATIO,
                "30% 는 전 전략이 다 성공해 표가 평평하고 90·110% 는 전력 제약 문턱이 총량 열을 지운다");
        assertTrue(BenchmarkRules.LOAD_RATIOS.contains(BenchmarkRules.JUDGMENT_LOAD_RATIO),
                "실행하지 않는 부하율을 판정 셀로 두면 그 셀은 영원히 비어 있다");
        assertEquals(GroundTruthOracle.PowerControl.AVAILABLE, BenchmarkRules.JUDGMENT_POWER_CONTROL,
                "통합의 이득은 전원 제어를 가정한 판에서만 보인다 — 그래서 판정 결과는 현 시스템에서 재현되지 않는다");
    }

    // ── 격차 지표의 판정 문턱 ─────────────────────────────────

    /**
     * 문턱은 <b>결과를 보기 전</b>에만 정할 수 있다. 값 자체보다 값이 있을 수 있는 범위를 잠근다 —
     * 비율이 1 을 넘으면 어떤 실행도 만족할 수 없는 조건이 되고, 짝 하한이 스윕 지점 수를 넘으면
     * 판정이 영원히 불가가 되며, 축소 폭이 0 이면 부호만으로 성공이 되어 잡음이 결론이 된다.
     */
    @DisplayName("격차 판정 문턱 넷이 결과보다 먼저 고정되고 쓸 수 있는 범위 안에 있다")
    @Test
    void the_gap_judgment_thresholds_are_pre_registered() {
        assertEquals(10, BenchmarkRules.JUDGMENT_MIN_PAIRED_POINTS,
                "짝 하한을 바꾸면 판정 가능·불가의 경계가 움직인다 — 결과를 보고 바꿀 수 없게 여기서 고정한다");
        assertEquals(2.0, BenchmarkRules.JUDGMENT_MEDIAN_REDUCTION_PP, 1e-9);
        assertEquals(0.0002, BenchmarkRules.JUDGMENT_POINT_IGNORE_PP, 1e-12, "무시 폭은 4자리 눈금 두 칸 — 한 칸은 반올림 동점 뒤집힘과 구별되지 않는다");
        assertTrue(BenchmarkRules.JUDGMENT_POINT_IGNORE_PP <= BenchmarkRules.JUDGMENT_MEDIAN_REDUCTION_PP / 10000,
                "무시 폭은 잡음 하한이지 문턱이 아니다 — 문턱에 견줄 크기가 되면 진짜 축소를 버린다");
        assertTrue(BenchmarkRules.ruleAxes().get("judgment").contains("pointIgnorePp="), "무시 폭은 판정 함수의 문턱 상수라 judgment 축에 봉인한다");
        assertEquals(10.0 / 12, BenchmarkRules.JUDGMENT_REDUCED_POINT_RATIO, 1e-9,
                "개수가 아니라 비율이다 — 교집합이 하한과 같을 때 전 지점 축소를 요구하는 더 센 기준이 조용히 생기지 않게");

        assertTrue(BenchmarkRules.JUDGMENT_REDUCED_POINT_RATIO > 0
                        && BenchmarkRules.JUDGMENT_REDUCED_POINT_RATIO <= 1.0,
                "0 이면 축소 조건이 없고 1 을 넘으면 만족할 수 있는 실행이 없다");
        assertTrue(BenchmarkRules.JUDGMENT_MEDIAN_REDUCTION_PP > 0,
                "0 이면 부호만으로 성공이 되어 잡음이 곧 결론이 된다");
        assertTrue(BenchmarkRules.JUDGMENT_MIN_PAIRED_POINTS > 0
                        && BenchmarkRules.JUDGMENT_MIN_PAIRED_POINTS <= BenchmarkRules.SWEEP_POINTS,
                "스윕이 낼 수 있는 지점보다 많은 짝을 요구하면 어떤 실행도 영원히 판정 불가다");
    }

    /**
     * 상수 넷만으로는 "무엇을 무엇과 견주는가"가 사람의 기억에 남는다. 교집합을 먼저 잡는 것·중앙값을
     * 같은 집합에서 내는 것·축소의 부호·두 판이 어긋났을 때의 처리를 문장이 들고 있어야 하고, 그 네 조각이
     * 문장을 다시 써도 빠지지 않게 여기서 못 박는다.
     */
    @DisplayName("격차 판정 규칙이 판정 불가·같은 집합·축소의 부호·방향 일치를 문장으로 밝힌다")
    @Test
    void the_gap_judgment_rule_states_its_four_moving_parts() {
        String r = BenchmarkRules.GAP_JUDGMENT_RULE;

        assertTrue(r.contains("판정 불가"),
                "짝이 모자란 실행을 실패로 적으면 판을 못 쓴 것과 진 것이 같은 칸에 들어간다: " + r);
        assertTrue(r.contains("같은 집합"),
                "두 중앙값을 다른 지점 집합에서 내면 차이가 개선이 아니라 표본 차이일 수 있다: " + r);
        assertTrue(r.contains("부호 있는 값"),
                "축소를 절댓값으로 읽으면 더 나빠진 지점이 축소로 세어진다: " + r);
        assertTrue(r.contains("방향 일치"),
                "판정판만 좋아진 결과를 어떻게 적을지가 없으면 결과를 보고 정하게 된다: " + r);
        assertTrue(r.contains("엄격히"),
                "같은 값을 축소로 세면 아무것도 바뀌지 않은 지점이 성공 쪽에 들어간다: " + r);
        assertTrue(r.contains(BenchmarkRules.GAP_VS_PABFD),
                "어느 지표를 읽는지가 문장에 있어야 다음 PR 이 다른 열을 골라 올 수 없다: " + r);
        // 어휘만 보면 문장을 통째로 갈아치우는 변경이 통과한다. 문턱 셋의 이름과 올림·실패 처리까지
        // 요구해, 절차의 뼈대가 빠진 문장은 빨간불이 되게 한다.
        for (String part : List.of("JUDGMENT_MIN_PAIRED_POINTS", "JUDGMENT_MEDIAN_REDUCTION_PP",
                "JUDGMENT_REDUCED_POINT_RATIO", "JUDGMENT_POINT_IGNORE_PP", "ceil", "실패로 기록", "회차마다", "지목한 결함", "지목하지 않은 변경")) {
            assertTrue(r.contains(part),
                    "판정 절차에서 '" + part + "' 가 사라졌다 — 문턱을 어떻게 쓰는지가 문장에 없으면 "
                            + "다음 PR 이 같은 상수로 다른 절차를 만든다: " + r);
        }
        assertFreeOfBannedClaims(r,
                "격차 판정 규칙 — 금지 조각 순회는 한계 문구와 지표 유효성 조건만 보므로 이 상수는 여기서 따로 본다");
    }

    @DisplayName("판정 자격은 폐쇄형이다 — 두 판의 power 행 밖은 전부 자격 없음")
    @Test
    void the_judgment_qualification_is_closed_form() {
        String r = BenchmarkRules.GAP_JUDGMENT_RULE;
        assertTrue(r.contains("판정 자격(폐쇄형)"), r);
        assertTrue(r.contains("그 밖의 판·열·지표는 전부 자격 없음"), "판·열이 늘 때 규칙을 다시 고치지 않도록 폐쇄형이어야 한다: " + r);
    }

    /**
     * 기준선을 중앙값 두 개로만 남긴 판정은 3·4항을 영원히 재구성할 수 없었다. 문장이 지점 배열 보존·출처 인용·같은 생산 경로·
     * 재사용 순서를 들고 있어야 다음 판정이 같은 결손을 반복하지 않는다. 분포는 서비스 기본 분포와 같은 문자열이어야 한다 —
     * 규칙 클래스가 서비스를 참조하지 않으므로 둘의 일치는 여기서 묶는다.
     */
    @DisplayName("격차 판정 규칙이 기준선 보존·출처·생산 경로·재사용 순서를 문장으로 밝히고 판정 분포가 서비스 기본 분포와 같다")
    @Test
    void the_gap_judgment_rule_states_how_the_baseline_is_kept() {
        String r = BenchmarkRules.GAP_JUDGMENT_RULE;
        for (String part : List.of("기준선 보존", "지점 배열째", "JUDGMENT_BASELINE_PATH", "출처", "결측 항목",
                "같은 생산 경로", "1~4항 판정을 게시하는 PR 은 기준선 파일을 수정하지 않는다", "판정 보고를 게시한 뒤", "예외:", "판정을 게시하지 않는 한",
                "중앙값 차", "산출 HEAD", "4자리로 반올림한 값으로 문턱과 견준다", "산출 방법", "JUDGMENT_DISTRIBUTION")) {
            assertTrue(r.contains(part), "판정 절차에서 '" + part + "' 가 사라졌다: " + r);
        }
        assertEquals(com.innogrid.aims.scheduler.service.PlacementBenchmarkService.HEAVYTAIL, BenchmarkRules.JUDGMENT_DISTRIBUTION,
                "판정 분포가 서비스 기본 분포와 갈렸다 — 규칙 값이 관행과 다르면 소급 명문화가 아니다");
        assertTrue(BenchmarkRules.ruleAxes().get("judgment").contains("distribution=" + BenchmarkRules.JUDGMENT_DISTRIBUTION),
                "분포가 judgment 축에 실려야 보고자가 분포를 고를 수 없다");
        assertTrue(BenchmarkRules.JUDGMENT_BASELINE_PATH.startsWith("src/test/resources/"), "기준선은 시험 자원이다 — 배포 jar 에 실리지 않는다");
    }

    @DisplayName("방법론 변경 상수가 결과보다 먼저 등록되어 있다 — 오목 지수는 볼록 지수의 역수, 민감도 눈금 3값, 위상 독립 판 2장")
    @Test
    void the_methodology_constants_are_pre_registered() {
        assertEquals(1.0 / BenchmarkRules.CONVEX_POWER_EXPONENT, BenchmarkRules.CONCAVE_POWER_EXPONENT, 1e-12, "대칭 응력 — 한 문장 근거");
        assertTrue(BenchmarkRules.CONCAVE_POWER_EXPONENT < 1.0);
        assertEquals(3, BenchmarkRules.SENSITIVITY_MARGINAL_SCALE_WATTS.stream().distinct().count());
        assertTrue(BenchmarkRules.SENSITIVITY_MARGINAL_SCALE_WATTS.contains(0.0), "구 방식(정격 분모) 열이 있어야 눈금 도입 자체가 검증된다");
        assertTrue(BenchmarkRules.SENSITIVITY_MARGINAL_SCALE_WATTS.stream().anyMatch(s -> s > 0 && s < BenchmarkRules.MAX_REAL_MARGINAL_WATTS_AT_JUDGMENT),
                "포화를 발현시키는 눈금이 하나 있어야 한다");
        assertEquals(2, BenchmarkRules.PHASE_INDEPENDENT_BOARDS.size());
        assertTrue(BenchmarkRules.PHASE_INDEPENDENT_BOARDS.contains(BenchmarkRules.DIRECTION_CHECK_BOARD + "-independent"));
        assertTrue(BenchmarkRules.PHASE_INDEPENDENT_BOARDS.contains(BenchmarkRules.JUDGMENT_BOARD + "-independent"));
        assertEquals(316.0, BenchmarkRules.MAX_REAL_MARGINAL_WATTS_AT_JUDGMENT, 1e-9, "판정 시점 고정값은 판이 늘어도 바뀌지 않는다");
        for (String s : List.of(BenchmarkRules.SENSITIVITY_RULE, BenchmarkRules.PHASE_RULE, BenchmarkRules.CONCAVE_CONCLUSION_TEMPLATE)) {
            assertFreeOfBannedClaims(s, "방법론 규칙 문장");
        }
        assertTrue(BenchmarkRules.SENSITIVITY_RULE.contains("독립 증거가 아니다"));
        assertTrue(BenchmarkRules.PHASE_RULE.contains("검정하지 않는다"));
        assertTrue(BenchmarkRules.CONCAVE_CONCLUSION_TEMPLATE.contains("원인을 지목하지 않"));
        assertTrue(BenchmarkRules.PAIRING_RULE.contains("override 없는 기본 설정 열"), "짝 조건 기준 집합이 문장에 있어야 한다");
        assertTrue(BenchmarkRules.PAIRING_RULE.contains("totalsIncomparableReason"), "짝 제외 지점의 응답 표기 의무가 문장에 있어야 한다");
        assertTrue(BenchmarkRules.SENSITIVITY_RULE.contains("유효 지점 교집합"), "민감도 검정은 교집합 위 지점별 차로 정의돼야 한다");
        assertTrue(BenchmarkRules.SENSITIVITY_RULE.contains("수준 의존이 곧 판정 의존은 아니다"));
        assertTrue(BenchmarkRules.PHASE_RULE.contains("생략은 판정 보고의 결격 사유"), "위상 판 범위 보고 의무");
        assertTrue(BenchmarkRules.CONCAVE_CONCLUSION_TEMPLATE.contains("|X−Y| = Z%p"), "오목 판 결론은 차 Z 로 적는다");
        assertTrue(BenchmarkRules.CONCAVE_CONCLUSION_TEMPLATE.contains("power-idle-fixed − power = W%p"), "역산 기여 슬롯");
        assertFreeOfBannedClaims(BenchmarkRules.PAIRING_RULE, "짝 조건 규칙 문장");
    }

    /**
     * 판정은 <b>한 판</b>에서만 한다 — 진실 원본이 둘이 되면 유리한 판을 고르는 자유도가 생긴다.
     * 그래서 방향 확인판은 판정판과 달라야 하고, 둘 다 실제로 등록된 판이어야 한다. 등록되지 않은 판을
     * 적어 두면 그 확인은 영원히 수행되지 않는다.
     */
    @DisplayName("방향 확인판은 판정판이 아니고 둘 다 등록된 판이다")
    @Test
    void the_direction_check_board_differs_from_the_judgment_board() {
        List<String> boards = new BenchmarkClusters().boardIds();

        assertNotEquals(BenchmarkRules.JUDGMENT_BOARD, BenchmarkRules.DIRECTION_CHECK_BOARD,
                "같은 판이면 방향 확인이 판정을 한 번 더 하는 것일 뿐이라 아무것도 배제하지 못한다");
        assertTrue(boards.contains(BenchmarkRules.JUDGMENT_BOARD), boards.toString());
        assertTrue(boards.contains(BenchmarkRules.DIRECTION_CHECK_BOARD),
                "등록되지 않은 판을 방향 확인판으로 적으면 그 확인은 영원히 수행되지 않는다: " + boards);
        // 값도 못 박는다. "다른 판이고 등록돼 있다"만 보면 결과를 본 뒤 유리한 판으로 갈아 끼워도
        // 초록불이라, 사전 등록이 절반만 지켜진다. 방향 확인은 정격도 지수도 어긋나지 않은 판에서 한다.
        assertEquals(BenchmarkClusters.MAIN, BenchmarkRules.DIRECTION_CHECK_BOARD,
                "방향 확인판이 주 결과판이 아니다 — 두 판 모두 우리 엔진에 기운 판이면 방향 일치는 근거가 아니다");
    }

    /**
     * 격차의 <b>분모가 무엇인지</b>가 라벨에 있어야 한다. "총전력 격차"만 적으면 무엇 대비인지가 표에서
     * 사라지고, 그러면 이 열은 기준 없이 인용된다. 대조군 라벨을 반사로 읽어 맞춰 둔다 — 그쪽 이름이
     * 바뀌면 여기가 빨간불이 되어 두 이름이 조용히 갈리지 않는다.
     */
    @DisplayName("격차 지표의 라벨이 분모가 되는 대조군의 이름을 그대로 담는다")
    @Test
    void the_gap_metric_label_names_the_baseline_in_its_denominator() {
        String baselineLabel = BaselinePlacers.pabfd(new PowerModel(new SchedulerProperties())).label();
        int paren = baselineLabel.indexOf('(');
        assertTrue(paren > 0, "대조군 라벨의 형태가 바뀌어 이름 부분을 잘라낼 수 없다: " + baselineLabel);
        String namePart = baselineLabel.substring(0, paren);

        assertFalse(namePart.isBlank(), "대조군 라벨의 이름 부분이 비었다: " + baselineLabel);
        assertTrue(metric(BenchmarkRules.GAP_VS_PABFD).label().contains(namePart),
                "격차 라벨이 대조군 이름 '" + namePart + "' 을 담지 않는다 — 무엇 대비인지 없이 인용된다: "
                        + metric(BenchmarkRules.GAP_VS_PABFD).label());
    }

    @DisplayName("배치 건수가 다른 지점에는 격차가 없다")
    @Test
    void paired_gap_is_null_when_the_two_rows_placed_different_counts() {
        assertNull(BenchmarkRules.pairedGapPercent(19, 20, 5000.0, 4500.0),
                "적게 배치하고 전력을 덜 쓴 것을 격차로 세면 전력이 아니라 배치량을 재게 된다");
        assertNull(BenchmarkRules.pairedGapPercent(21, 20, 5000.0, 4500.0));
        assertNotNull(BenchmarkRules.pairedGapPercent(20, 20, 5000.0, 4500.0),
                "건수가 같은 지점까지 버리면 이 지표는 값을 낼 자리가 없다");
    }

    @DisplayName("기준 전략의 전력이 0 이하면 격차를 내지 않는다")
    @Test
    void paired_gap_is_null_when_the_baseline_power_is_not_positive() {
        assertNull(BenchmarkRules.pairedGapPercent(20, 20, 5000.0, 0.0),
                "0 으로 나누면 무한대가 나오고, 무한대는 반올림에서 0 이 되어 '가장 좋은 값'으로 읽힌다");
        assertNull(BenchmarkRules.pairedGapPercent(20, 20, 5000.0, -1.0),
                "음수 전력은 생성식이 낼 수 없는 값이라 비의 부호가 뒤집힌다");
    }

    @DisplayName("격차는 기준 전략의 전력을 분모로 한 백분율이다")
    @Test
    void paired_gap_divides_by_the_baseline_power() {
        assertEquals(10.0, BenchmarkRules.pairedGapPercent(20, 20, 110.0, 100.0), 1e-9);
        assertEquals(100.0, BenchmarkRules.pairedGapPercent(20, 20, 200.0, 100.0), 1e-9,
                "분모를 이 행의 전력으로 바꾸면 50 이 나온다 — 분모는 기준 전략이다");
        assertEquals(0.0, BenchmarkRules.pairedGapPercent(20, 20, 100.0, 100.0), 1e-9,
                "같은 전력이면 격차는 0 이다");
    }

    /** 좋은 방향이 낮은 쪽인 지표라, 기준보다 덜 쓴 행은 <b>음수</b>여야 한다. 절댓값을 실으면 개선이 악화로 읽힌다. */
    @DisplayName("기준보다 전력을 덜 쓴 행의 격차는 음수다")
    @Test
    void paired_gap_is_negative_when_the_row_uses_less_power_than_the_baseline() {
        assertEquals(-10.0, BenchmarkRules.pairedGapPercent(20, 20, 90.0, 100.0), 1e-9,
                "부호를 뒤집으면 개선한 전략이 나쁜 쪽에 실린다");
        assertTrue(BenchmarkRules.pairedGapPercent(20, 20, 90.0, 100.0) < 0);
        assertTrue(BenchmarkRules.pairedGapPercent(20, 20, 110.0, 100.0) > 0);
    }

    /**
     * 지수를 1 보다 크게 둔 판이 <b>실서버 근사가 아니라</b>는 사실은 응답이 스스로 말해야 한다.
     * 말하지 않으면 "볼록 판에서 격차가 벌어졌다"가 곧 "실서버에서 벌어진다"로 읽힌다.
     */
    @DisplayName("한계 문구가 지수 판이 응력 시험임과 판 비교의 성립 조건을 밝힌다")
    @Test
    void the_limitation_declares_the_exponent_board_is_a_stress_test() {
        String l = BenchmarkRules.LIMITATION;

        assertTrue(l.contains("응력 시험"), "실서버 근사가 아니라는 사실이 문구에 있어야 한다: " + l);
        assertTrue(l.contains("반대 방향의 보고도 있다"),
                "지수 방향이 문헌에서 합의된 것처럼 읽히면 안 된다: " + l);
        assertTrue(l.contains("노드 구성이 같아야 한다"),
                "판 사이의 차이를 원인으로 읽는 조건이 없으면 다른 모집단의 판끼리 비교하게 된다: " + l);
        assertTrue(l.contains("엔진에 알려 준 정격"),
                "엔진이 믿는 정격도 우리가 정한 가정이라는 사실이 계수 열거에 있어야 한다: " + l);
    }

    /**
     * 금지 조각은 <b>한 배열</b>에서만 관리한다. 조각을 시험마다 문자열로 적어 두면 어미 하나만 달라도
     * 검사가 빠져나가고, 상수와 그 사본(Swagger 설명 등)이 조용히 갈린다.
     */
    @DisplayName("금지 문장 조각이 한 배열로 관리되고 한계 문구에 없다")
    @Test
    void the_banned_claims_are_declared_once_and_absent() {
        assertTrue(BenchmarkRules.BANNED_CLAIMS.contains("지상진실"), "실측을 암시하는 이름이 목록에 있어야 한다");
        assertTrue(BenchmarkRules.BANNED_CLAIMS.stream().anyMatch(c -> c.startsWith("온도 축에는 결론을 지지하는 지표가 없")),
                "어미를 뺀 접두로 담아야 '없다'/'없습니다' 가 함께 걸린다");
        for (String banned : BenchmarkRules.BANNED_CLAIMS) {
            assertFalse(banned.isBlank(), "빈 조각은 모든 문자열에 걸려 시험을 무의미하게 만든다");
            assertFalse(banned.endsWith("다."), "어미까지 적으면 사본이 어미만 바꿔 빠져나간다: " + banned);
        }
        // 심사자가 인용할 과잉 주장 조각 — 실제 문장이 걸리는지 양성 확인(조각이 빠지면 여기서 잡힌다)
        for (String sentence : List.of(
                "실제 서버의 전력 곡선이 볼록하기 때문이다",
                "시원한 노드를 선호해 핫스팟을 막는다",
                "전력 인지 최적적합(문헌 기준선)",
                "후보 중 최적의 노드를 선택한다",
                "워크로드를 최적 노드에 배치한다",
                "열적 균형으로 핫스팟 방지",   // 괄호 삽입형 "핫스팟(hot spot) 방지" 는 조각이 못 잡는다 — 원문은 이미 회피 목표 어투로 고쳤다
                "냉각 비용이 비선형적으로 증가")) {
            assertThrows(AssertionError.class, () -> assertFreeOfBannedClaims(sentence, "표본 문장"),
                    "금지 조각이 이 문장을 잡아야 한다: " + sentence);
        }
        assertFreeOfBannedClaims(BenchmarkRules.LIMITATION, "한계 문구");
        for (BenchmarkMetric m : BenchmarkRules.METRICS) {
            assertFreeOfBannedClaims(m.validity(), m.key() + " 유효성 조건");
        }
    }

    /**
     * 금지 조각 검사가 <b>판정 규칙의 사본 전량</b>에 걸리게 한다. 판 설명과 전제 문구도 응답에 그대로 실리는
     * 사본이라, 한계 문구에서 지운 문장이 그쪽에 남아 있으면 읽는 사람에게는 그 문장이 여전히 살아 있다.
     * 실제로 옛 온도 문구가 사본 쪽에 남아 있던 적이 있다.
     */
    @DisplayName("등록된 판 전부의 설명과 전제 문구에도 금지 문장 조각이 없다")
    @Test
    void the_board_roles_and_the_assumption_notes_are_free_of_banned_claims() {
        BenchmarkClusters clusters = new BenchmarkClusters();

        assertFalse(clusters.boardIds().isEmpty(), "판 목록은 판 등록부가 정한다 — 판 수를 여기 다시 적지 않는다");
        for (String boardId : clusters.boardIds()) {
            assertFreeOfBannedClaims(clusters.roleOf(boardId), boardId + " 판 설명");
        }
        assertFreeOfBannedClaims(Assumptions.GENERATOR_FORMULA, "전제 블록의 생성식 문구");
        assertFreeOfBannedClaims(Assumptions.POWER_CONTROL_NOTE, "전제 블록의 전원 가정 문구");
    }

    /**
     * Swagger 설명은 한계 문구의 <b>사본</b>이다 — 상수에서 지운 문장이 사본에 남아 있으면 문서를 읽는
     * 사람에게는 그 문장이 여전히 살아 있다. 실제로 열 대조군이 생긴 뒤에도 옛 문구가 이 자리에 남아 있었다.
     *
     * <p>스프링을 띄우지 않고 애노테이션만 반사로 읽는다 — 이 검사는 요청 처리와 무관하고,
     * 컨텍스트를 띄우면 사본이 갈라졌는지를 훨씬 비싼 시험이 뒤늦게 알려 주게 된다.</p>
     */
    @DisplayName("배치 비교 설명에도 금지 문장 조각이 없다")
    @Test
    void the_swagger_description_is_free_of_banned_claims() throws Exception {
        Operation operation = PlacementBenchmarkController.class
                .getDeclaredMethod("comparePlacement", String.class, String.class)
                .getAnnotation(Operation.class);

        assertNotNull(operation, "배치 비교 엔드포인트에 @Operation 설명이 없으면 사본이 갈라졌는지 볼 자리가 없다");
        assertFreeOfBannedClaims(operation.description(), "배치 비교 Swagger 설명");
    }

    /**
     * 판의 생성식과 엔진의 전력 모델이 <b>서로를 모른다</b>. 엔진이 판의 지수를 알게 되면 "엔진 가정과
     * 생성식의 격차"는 정의상 0 이 되고, 이 비교가 재는 것이 사라진다.
     *
     * <p>구조로 이미 갈라져 있지만(양쪽에 import 0), 구조는 한 줄로 깨진다 — 그래서 선언을 반사로 읽어
     * 못 박는다. 엔진 노브 블록에 지수 이름의 필드가 생기거나 전력 모델의 공개 메서드가 판 객체를 받으면
     * 여기가 빨간불이 된다.</p>
     */
    @DisplayName("엔진의 전력 노브와 전력 모델은 판의 생성식 지수를 모른다")
    @Test
    void the_engine_does_not_know_the_generator_exponent() {
        for (Field f : SchedulerProperties.Power.class.getDeclaredFields()) {
            String name = f.getName().toLowerCase(Locale.ROOT);
            assertFalse(name.contains("exponent") || name.contains("beta"),
                    "엔진 노브에 생성식 지수로 읽힐 필드가 생겼다: " + f.getName()
                            + " — 판의 지수는 판 파일이 선언하고 응답의 boards[].powerExponent 가 싣는다");
        }
        for (Method m : PowerModel.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            for (Class<?> parameter : m.getParameterTypes()) {
                assertNotEquals(SyntheticCluster.class, parameter,
                        "전력 모델의 공개 메서드 " + m.getName() + " 이 판을 직접 받는다 — "
                                + "엔진이 생성식을 읽으면 격차가 정의상 0 이 된다");
            }
        }
    }

    /**
     * 판에 <b>필드를 더하면 설명도 따라오게</b> 만든다. 응답에 열이 하나 늘어도 Swagger 설명이 그대로면
     * 읽는 사람은 그 열이 무엇인지 모른 채 숫자만 보게 되고, 실제로 지수와 역산 점검표가 응답에 먼저 실리고
     * 설명에는 뒤늦게 들어왔다.
     *
     * <p>제외 목록을 두지 않는다 — "이건 자명하니 빼자"가 한 번 허용되면 그 목록이 설명의 구멍이 된다.
     * 이름 전부를 문자열로 요구하고, 설명이 길어지면 필드를 한 문장에 나열해 통과시킨다.</p>
     */
    @DisplayName("판의 필드 이름이 하나도 빠짐없이 배치 비교 설명에 등장한다")
    @Test
    void every_board_field_is_named_in_the_swagger_description() throws Exception {
        String description = PlacementBenchmarkController.class
                .getDeclaredMethod("comparePlacement", String.class, String.class)
                .getAnnotation(Operation.class)
                .description();

        for (RecordComponent component : Board.class.getRecordComponents()) {
            assertTrue(description.contains(component.getName()),
                    "판에 필드 " + component.getName() + " 이 늘었는데 설명이 그대로다 — "
                            + "응답에만 있고 문서에 없는 열은 읽는 사람에게는 정체 불명의 숫자다");
        }
    }

    /**
     * 지표 값 한 칸의 필드 이름도 설명에 전부 있어야 한다 — 판의 필드와 같은 이유다.
     *
     * <p>지점별 값(points)처럼 <b>뜻을 모르면 잘못 읽히는</b> 열이 늘었을 때, 응답에만 있고 문서에 없으면
     * 읽는 사람은 그 배열의 인덱스가 무엇인지·null 이 무엇을 뜻하는지 알 수 없다.</p>
     */
    @DisplayName("지표 값의 필드 이름이 하나도 빠짐없이 배치 비교 설명에 등장한다")
    @Test
    void every_metric_value_field_is_named_in_the_swagger_description() throws Exception {
        String description = PlacementBenchmarkController.class
                .getDeclaredMethod("comparePlacement", String.class, String.class)
                .getAnnotation(Operation.class)
                .description();

        for (RecordComponent component : MetricValue.class.getRecordComponents()) {
            assertTrue(description.contains(component.getName()),
                    "지표 값에 필드 " + component.getName() + " 이 늘었는데 설명이 그대로다 — "
                            + "응답에만 있고 문서에 없는 열은 읽는 사람에게는 정체 불명의 숫자다");
        }
        assertTrue(description.contains(BenchmarkRules.GAP_VS_PABFD),
                "격차 지표의 키가 설명에 없다 — 판정에 쓸 열이라 뜻이 문서에 있어야 한다");
    }

    /** 금지 조각이 어디에도 없는지 — 검사할 조각의 출처를 시험마다 다시 적지 않는다. */
    private static void assertFreeOfBannedClaims(String text, String where) {
        for (String banned : BenchmarkRules.BANNED_CLAIMS) {
            assertFalse(text.contains(banned), where + ": 금지 조각 '" + banned + "' 가 남아 있다 — " + text);
        }
    }

    private static BenchmarkMetric metric(String key) {
        return BenchmarkRules.METRICS.stream().filter(m -> m.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError(key + " 지표가 선언되지 않았다"));
    }
}
