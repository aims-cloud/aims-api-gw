package com.innogrid.aims.scheduler.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.benchmark.BenchmarkClusters;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.PowerAwareScheduler;
import com.innogrid.aims.scheduler.engine.TemperatureAwareScheduler;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.engine.model.NetworkModel;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 열관성 계수 민감도 — <b>부수 보고</b>(판정 아님). 배치 점수 열 항이 실효 온도를 읽으면서 판정량이 열관성 계수(엔진 노브)에
 * 의존하는 경로가 하나 늘었다(기존 경로: 배치 후 예상 온도 제약 C3). 규칙은 판정 실행을 기본 설정(설정 다이제스트 불변)으로 못박으므로,
 * 다른 계수의 값은 설정 스냅샷을 바꾼 별도 조립으로 내고 표로만 싣는다 — 각 행은 열 항 단독 효과가 아니라 계수를 읽는 모든 경로가
 * 함께 움직인 값이며, 대응하는 B가 없어 판정과 견주지 않는다. 비교 하네스의 override 열은 공유 {@link ThermalModel}을 그대로
 * 넘겨 열 노브 변종을 만들 수 없다.
 *
 * <p>기본 계수 행은 판정 보고 초안의 N 값(사전 등록 리터럴)과 같아야 한다 — 조립이 하네스 기본 경로와 어긋나면 여기서 잡힌다.
 * 나머지 두 행도 리터럴로 고정해 표의 수치가 문서에만 남지 않게 한다.</p>
 */
class ThermalInertiaSensitivityReportTest {

    private static final Path OUT = Path.of("build", "benchmark-evidence", "thermal-inertia-sensitivity.txt");
    private static final double DEFAULT_K = new SchedulerProperties().getThermal().getInertiaK();
    private static final double[] COEFFICIENTS = {0.10, 0.15, 0.20};              // 가운데가 기본 계수 — 기본값이 바뀌면 리터럴도 함께 고친다
    /**
     * 판정 자격 두 판 power 행 격차 중앙값(판정 셀 · 방향 셀) — 노드 배치 스코어 PEI 항에 코어 비율을 곱한 리비전에서 낸 값
     * (그 전 유휴 기준선 일원화 리비전 {11.2109, 14.8067}·{11.8396, 16.6866}·{12.6627, 16.8882},
     * 그 전 {11.0343, 14.4323}·{12.4476, 16.1995}·{13.1305, 17.4029}).
     */
    private static final double[][] EXPECTED = {{8.6348, 14.3785}, {9.7150, 14.2874}, {10.6035, 15.2761}};

    @DisplayName("열관성 계수 0.10 / 0.15 / 0.20 에서 판정 자격 두 판 power 행 격차 중앙값을 표로 남기고 기본 계수 행이 현 실행과 같다")
    @Test
    void reports_judgment_cells_for_three_inertia_coefficients() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("[열관성 계수 민감도 — 부수 보고, 판정 아님. 판정 실행은 기본 계수 " + DEFAULT_K + " 만이며 다른 행은 설정 스냅샷이 달라 1~4항 자격이 없다. "
                + "각 행은 계수를 읽는 모든 경로(열 항·배치 후 예상 온도 제약)가 함께 움직인 값이다]");
        lines.add("k · 판정 셀 power 중앙값(" + BenchmarkRules.JUDGMENT_BOARD + ") · 방향 셀 power 중앙값(" + BenchmarkRules.DIRECTION_CHECK_BOARD + ") · 설정 다이제스트");
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            double k = COEFFICIENTS[i];
            PlacementBenchmarkService service = assemble(k);
            PlacementBenchmarkResponse judgment = service.compareFresh(BenchmarkRules.JUDGMENT_BOARD, BenchmarkRules.JUDGMENT_DISTRIBUTION);
            PlacementBenchmarkResponse direction = service.compareFresh(BenchmarkRules.DIRECTION_CHECK_BOARD, BenchmarkRules.JUDGMENT_DISTRIBUTION);
            Double j = JudgmentBaselines.gap(JudgmentBaselines.judgmentCell(judgment.boards().get(0)), "power").median();
            Double d = JudgmentBaselines.gap(JudgmentBaselines.judgmentCell(direction.boards().get(0)), "power").median();
            lines.add(String.format("%.2f · %s · %s · %s", k, j, d, judgment.engineConfig().configDigest()));
            assertNotNull(j, "판정 셀 값이 비었다: k=" + k);
            assertNotNull(d, "방향 셀 값이 비었다: k=" + k);
            assertEquals(EXPECTED[i][0], j, BenchmarkRules.JUDGMENT_POINT_IGNORE_PP, "판정 셀 중앙값이 사전 등록 리터럴과 다르다: k=" + k);
            assertEquals(EXPECTED[i][1], d, BenchmarkRules.JUDGMENT_POINT_IGNORE_PP, "방향 셀 중앙값이 사전 등록 리터럴과 다르다: k=" + k);
            if (k == DEFAULT_K) {
                assertEquals("b8f222c123d1", judgment.engineConfig().configDigest(), "기본 계수 행은 기본 설정 다이제스트여야 한다");
            } else {
                assertNotEquals("b8f222c123d1", judgment.engineConfig().configDigest(), "다른 계수 행은 설정 다이제스트가 달라 판정 자격이 없어야 한다 — 계수가 다이제스트 성분에서 빠졌다");
            }
        }
        Files.createDirectories(OUT.getParent());
        Files.writeString(OUT, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private static PlacementBenchmarkService assemble(double inertiaK) {
        SchedulerProperties props = new SchedulerProperties();
        props.getThermal().setInertiaK(inertiaK);
        PowerModel powerModel = new PowerModel(props);
        ThermalModel thermalModel = new ThermalModel(props);
        NetworkModel networkModel = new NetworkModel(props);
        return new PlacementBenchmarkService(
                new EngineRegistry(List.of(
                        new WeightedScoreScheduler(powerModel, thermalModel, networkModel),
                        new PowerAwareScheduler(powerModel, thermalModel, networkModel, props),
                        new TemperatureAwareScheduler(thermalModel))),
                props, new BenchmarkClusters(), powerModel, thermalModel);
    }
}
