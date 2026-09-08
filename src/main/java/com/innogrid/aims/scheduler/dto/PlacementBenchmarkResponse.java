package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.scheduler.benchmark.BenchmarkMetric;

/**
 * 배치 전략 비교 — 우리 엔진과 대조군을 <b>같은 판·같은 워크로드·같은 제약</b>에서 돌린 결과.
 *
 * <p><b>승자를 지명하는 필드가 없다.</b> API 가 승자를 계산해 주면 그 필드만 인용되고, 판 이름·
 * 전원 상태 가정·부하율에서 떨어진 순간 그 판정은 거짓이 된다. 대신 셀마다 {@code valid}·
 * {@code invalidReason} 을 실어 <b>어떤 조건에서 그 숫자가 의미를 갖는지</b>를 함께 준다.
 * 판정은 사람이 판 이름과 함께 쓴다.</p>
 *
 * <p><b>주 지표는 엔진이 믿은 값이 아니다.</b> {@code engineBelievedCostW} 는 전력 엔진 총점의 한 항을
 * 그대로 합한 값이라 그것으로 순위를 매기면 그 목적함수를 최대화한 전략이 정의상 이긴다. 총전력의
 * 주 지표는 클러스터 파일 생성식의 <b>선언된 유휴 전력</b>으로 계산한 값이고, 엔진은 그 값을 보지 못한다.
 * 두 값의 차는 <b>엔진 가정과 생성식의 격차</b>이며 {@link Board#idleAssumptionGaps} 에 노드별로 있다 —
 * 실측 서버와의 차이가 아니다. 생성식은 {@link Assumptions#GENERATOR_FORMULA} 에 그대로 적혀 있다.</p>
 *
 * @param evaluatedAt  계산 시각
 * @param assumptions  계산 전제 — 이 블록 없이 인용된 숫자는 근거가 없다
 * @param engineConfig 이 실행이 본 엔진 설정과 정책 변종 셋 — {@code policyBasis} 가 "기본값으로 돌렸다"는
 *                     <b>말</b>이라면 이것은 그 말의 <b>내용</b>이다
 * @param metrics      사전 등록된 지표 선언. 정의·단위·좋은 방향·유효성 조건이 결과보다 먼저 고정됐다
 * @param boards       판별 결과
 * @param limitation   응답이 스스로 밝히는 한계
 */
public record PlacementBenchmarkResponse(
        OffsetDateTime evaluatedAt,
        Assumptions assumptions,
        EngineConfigSnapshot engineConfig,
        List<BenchmarkMetric> metrics,
        List<Board> boards,
        String limitation
) {
    public PlacementBenchmarkResponse {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        boards = boards == null ? List.of() : List.copyOf(boards);
    }

    /**
     * 이 실행이 본 <b>엔진 설정과 정책 사본 전량</b>.
     *
     * <p>결과를 바꾸는 노브 열 개 남짓이 전부 환경변수로 덮이므로, 값을 싣지 않으면 두 배포에서
     * <b>같은 모양·다른 숫자</b>의 표가 나와도 읽는 사람이 그것을 알 수 없다. 여기 실린 값이 배포 환경과
     * 다르면 이 표의 숫자는 그 환경의 숫자가 아니다.</p>
     *
     * <p>{@code configDigest} 는 클러스터 파일 다이제스트와 <b>같은 규칙</b>(SHA-256 앞 12자)으로 낸다.
     * 판 다이제스트가 "어느 입력 파일인가"를 지목하듯 이것이 "어느 설정인가"를 지목한다. 등록된 엔진 id
     * 목록도 다이제스트에 들어간다 — 엔진이 추가되면 표에 열이 늘고, 그것은 다른 설정이다.</p>
     *
     * @param configDigest 엔진 id 목록과 아래 세 블록을 정규화한 문자열의 SHA-256 앞 12자
     * @param engineIds    비교에 참가한 엔진 id(정렬). 레지스트리가 자동 수집하므로 실행마다 확인해야 한다
     * @param power        전력 모델 노브. 하네스가 회계에 쓰는 문턱을 엔진 노브와 나란히 싣는다
     * @param thermal      온도 모델 노브
     * @param policies     전략이 보는 정책 변종 셋 — 저장되지 않는 사본이다
     */
    public record EngineConfigSnapshot(
            String configDigest,
            List<String> engineIds,
            PowerKnobs power,
            ThermalKnobs thermal,
            List<PolicySnapshot> policies
    ) {
        public EngineConfigSnapshot {
            engineIds = engineIds == null ? List.of() : List.copyOf(engineIds);
            policies = policies == null ? List.of() : List.copyOf(policies);
        }
    }

    /**
     * 전력 모델 노브.
     *
     * @param activeCpuPercent             <b>엔진</b>의 가동 판정 문턱. 엔진이 믿은 비용이 이 값을 본다
     * @param benchmarkPoweredOnCpuPercent 하네스가 <b>회계</b>에 쓰는 문턱(사전 등록 상수). 위 값과 다르면
     *                                     "엔진이 믿은 비용"과 "가동 노드 증감"이 서로 다른 문턱 위에 서 있다는
     *                                     뜻이고, 그 사실이 여기서 보여야 한다
     * @param capacityByNode               노드별 정격 원장(설정). 비어 있으면 전 노드가 {@code defaultCapacityWatts}
     */
    public record PowerKnobs(
            double idleRatio, double activeCpuPercent, double benchmarkPoweredOnCpuPercent,
            double curveAlpha, double peiWeight, double wasteWeight, double marginalWeight,
            double peiNormalizer, double resourceShare, double defaultCapacityWatts,
            Map<String, Double> capacityByNode
    ) {
        public PowerKnobs {
            capacityByNode = capacityByNode == null ? Map.of() : Map.copyOf(capacityByNode);
        }
    }

    /**
     * 온도 모델 노브.
     *
     * @param inertiaK                 열관성 계수. 판의 {@code thermalRiseScaleCheck} 가 이 값으로 계산된다
     * @param benchmarkCooldownMinutes 하네스가 실제로 쓴 냉각 대기(0). {@code cooldownMinutes} 와 다른 것이
     *                                 정상이다 — 히스테리시스는 단발 비교에 넣지 않는다
     */
    public record ThermalKnobs(
            double inertiaK, double defaultWarningCelsius,
            int cooldownMinutes, int benchmarkCooldownMinutes
    ) {
    }

    /** 전략이 보는 정책 변종 하나. {@code SchedulingPolicy} 의 <b>비영속 사본</b>에서 뜬 값이다. */
    public record PolicySnapshot(
            String variant,
            double cpuWeight, double memoryWeight, double powerWeight,
            double temperatureWeight, double networkWeight,
            double maxCpuUsagePercent, double maxMemoryUsagePercent,
            double maxTemperatureCelsius, double maxPowerUsagePercent,
            Double warningTemperatureCelsius,
            boolean powerAwareEnabled, boolean temperatureAwareEnabled, boolean affinityEnabled
    ) {
    }

    /**
     * 계산 전제.
     *
     * @param workloadDistribution 워크로드 분포 이름
     * @param workloadSeed         분포 추출 시드
     * @param workloadClasses      계층별 구성비
     * @param loadRatioPercents    부하율 지점
     * @param headroomDefinition   부하율의 분모 정의 — 총 코어가 아니라 정책 상한까지의 여유다
     * @param sweepPoints          한 주기를 훑은 시각 지점 수
     * @param periodMinutes        파동 주기
     * @param cooldownMinutes      벤치마크에서 쓴 냉각 대기(0). 히스테리시스는 시계열 운영의 성질이라 단발 비교에서 뺐다
     * @param memoryMbPerCore      워크로드 메모리 = 코어 × 이 값
     * @param engineIdleRatio      엔진이 가정하는 유휴 전력 비율. 파일 선언값과의 차가 엔진 가정과 생성식의 격차다
     * @param generatorFormula     전력·온도를 만든 생성식 — 이 값들이 실측이 아니라 식의 출력임을 응답이 스스로 밝힌다
     * @param powerControlNote     전원 제어 두 판의 뜻
     * @param randomSeedCount      무작위 대조군에 쓴 시드 수
     * @param policyBasis          어느 정책으로 돌렸는가 — 배포된 활성 정책이 아니라는 사실을 밝힌다
     */
    public record Assumptions(
            String workloadDistribution,
            long workloadSeed,
            List<WorkloadClassShare> workloadClasses,
            List<Integer> loadRatioPercents,
            String headroomDefinition,
            int sweepPoints,
            int periodMinutes,
            int cooldownMinutes,
            int memoryMbPerCore,
            double engineIdleRatio,
            String generatorFormula,
            String powerControlNote,
            int randomSeedCount,
            String policyBasis
    ) {
        public Assumptions {
            workloadClasses = workloadClasses == null ? List.of() : List.copyOf(workloadClasses);
            loadRatioPercents = loadRatioPercents == null ? List.of() : List.copyOf(loadRatioPercents);
        }

        /**
         * 전력·온도 값을 만든 생성식. 전제 블록에 두는 이유 — 한계 문구는 응답 맨 끝이라 표만 잘라 인용하면
         * 떨어져 나가지만, 전제 블록은 숫자보다 앞에 있다. 계수(유휴 전력·정격·0.3)는 실데이터 없이 정한 값이다.
         */
        public static final String GENERATOR_FORMULA =
                "전력(W) = idleWatts + (capacityWatts − idleWatts) × CPU%/100 · 온도(℃) = baseTemperature + 0.3 × CPU% — "
                        + "노드별 idleWatts·capacityWatts·baseTemperature 는 클러스터 파일에 선언한 값이고 실측이 아니다";

        /**
          * 정책의 출처. <b>배포된 활성 정책을 읽지 않는다</b> — 활성 정책 조회 경로는 정책이 없으면
          * 기본 정책을 <b>새로 넣는</b> 부작용이 있어, 진단 호출이 데이터를 만들게 된다. 대신 코드에
          * 선언된 기본값으로 돌리고 그 사실을 여기 적는다. 배포 환경의 임계값이 기본값과 다르면
          * 이 결과는 그 환경의 결과가 아니다.
          */
        public static final String POLICY_BASIS =
                "코드에 선언된 기본 정책 값으로 돌렸다 — 배포된 활성 정책을 읽지 않는다(조회 경로에 정책을 "
                        + "새로 넣는 부작용이 있어 진단이 데이터를 만들게 된다). 배포 환경의 가중치·임계값이 "
                        + "기본값과 다르면 이 숫자는 그 환경의 숫자가 아니다.";

        /** 전원 제어 두 판의 뜻. 결과를 이 문장 없이 인용하면 통합 이득의 근거가 사라진다. */
        public static final String POWER_CONTROL_NOTE =
                "AVAILABLE 은 쓰지 않는 노드가 꺼져 있다는 가정이고, UNAVAILABLE 은 현 시스템의 사실이다 — "
                        + "노드 전원을 끄는 수단도 도는 워크로드를 옮기는 수단도 없어 유휴 노드도 계속 유휴 전력을 먹는다. "
                        + "UNAVAILABLE 판에서 통합으로 아낄 수 있는 전력은 0 이므로, 통합 이득은 AVAILABLE 판에서만 읽어야 한다.";
    }

    /** 워크로드 계층 하나의 구성비. */
    public record WorkloadClassShare(String name, int weightPercent, int cores, int gpus, String priority) {
    }

    /**
     * 판 하나의 결과.
     *
     * @param boardId            판 식별자
     * @param role               이 판을 만든 의도
     * @param clusterName        클러스터 파일이 밝히는 이름
     * @param digest             파일 내용 해시 앞 12자 — 응답이 자기 입력을 지목한다
     * @param nodeCount          노드 수
     * @param measuredNodeCount  전력·온도가 계측되는 노드 수
     * @param idleAssumptionGaps    엔진의 유휴 전력 가정과 파일 선언값의 노드별 격차
     * @param thermalRiseScaleCheck 노드별 코어당 온도 상승 — 엔진 식(℃·K 두 눈금)과 생성식을 나란히.
     *                              격차·비율·판정은 없다. 두 엔진 값이 다르다는 것이 척도 의존의 증거다
     * @param cells                 부하율 × 전원 상태 가정의 격자
     */
    public record Board(
            String boardId,
            String role,
            String clusterName,
            String digest,
            int nodeCount,
            int measuredNodeCount,
            List<IdleGap> idleAssumptionGaps,
            List<ThermalRiseScale> thermalRiseScaleCheck,
            List<Cell> cells
    ) {
        public Board {
            idleAssumptionGaps = idleAssumptionGaps == null ? List.of() : List.copyOf(idleAssumptionGaps);
            thermalRiseScaleCheck = thermalRiseScaleCheck == null ? List.of() : List.copyOf(thermalRiseScaleCheck);
            cells = cells == null ? List.of() : List.copyOf(cells);
        }
    }

    /**
     * 노드 한 대의 코어당 온도 상승 — 세 가정을 나란히.
     *
     * <p>{@link IdleGap} 이 전력 쪽에서 하는 일을 열 쪽에서 하되, <b>격차·비율·판정을 내지 않는다.</b>
     * "엔진이 N배 과소평가"라는 문장은 생성식이 옳다는 전제가 필요하고 그 근거가 없다. 게다가 그 배수는
     * 단위 선택의 산물이다 — 같은 엔진 식을 ℃ 로 재면 생성식보다 작고 K 로 재면 크다. 그래서 두 번째 열이 있다:
     * 물리적으로 같은 상태를 두 눈금으로 넣었을 때 값이 다르면, 그 식은 온도 차를 눈금 원점에 비례시키고 있는
     * 것이고 절대 척도 위에서 정의되지 않은 모델이다. 이 사실은 생성식과 무관하게 엔진 식만으로 성립한다.</p>
     *
     * @param engineRisePerCoreC        엔진 식 {@code inertiaK × T × (1/cores)} 에 ℃ 값을 넣은 상승
     * @param engineRisePerCoreIfKelvin 같은 식에 같은 온도를 K 로 넣은 상승
     * @param generatorRisePerCoreC     생성식 {@code 0.3 × 100/cores} — 온도 무관 가산형
     */
    public record ThermalRiseScale(
            String nodeId,
            int cores,
            double baseTemperatureC,
            double engineRisePerCoreC,
            double engineRisePerCoreIfKelvin,
            double generatorRisePerCoreC
    ) {
    }

    /**
     * 노드 한 대의 유휴 전력 — 엔진 가정 대 파일 선언값.
     *
     * <p>이 표가 <b>엔진의 가정과 생성식이 얼마나 벌어져 있는지</b>를 보이는 자리다. 엔진은 유휴 전력을 정격의
     * 상수배로 가정하고, 평가자는 클러스터 파일에 선언된 값을 쓴다. 두 값이 노드마다 얼마나 다른지 밝혀 두면
     * 생성식 기준 전력이 엔진 점수의 재탕이 아니라는 것이 표에서 바로 보인다 — 어느 쪽이 실물에 가까운지는
     * 이 표가 말하지 않는다.</p>
     *
     * @param declaredIdleWatts     파일에 선언된 유휴 전력(W). 실측이 아니다
     * @param generatorWattsPerCore 생성식 기준 코어당 동적 전력(W/코어)
     */
    public record IdleGap(
            String nodeId,
            double capacityWatts,
            double declaredIdleWatts,
            double engineAssumedIdleWatts,
            double gapWatts,
            double declaredIdleRatio,
            double generatorWattsPerCore
    ) {
    }

    /**
     * 격자 한 칸 — 부하율 하나와 전원 상태 가정 하나.
     *
     * @param loadRatioPercent        정책 상한까지의 여유 대비 요구 총량
     * @param powerControl            전원 상태 가정
     * @param requestedWorkloads      요구 워크로드 수(시각 지점 간 중앙값)
     * @param requestedCores          요구 코어 합(중앙값)
     * @param headroomCores           분모로 쓴 여유(중앙값)
     * @param activeNodesBefore       배치 전부터 부하가 돌던 노드 수(중앙값). {@code activeNodeDelta} 가
     *                                무엇에 대한 증감인지 이 값 없이는 알 수 없다. 전원 제어가 있다는
     *                                가정에서도 <b>이 노드들은 끌 수 없으므로</b> 총전력의 바닥이 된다
     * @param totalsComparable        총량 열을 비교해도 되는가 — 전 전략의 배치 건수가 같을 때만 참
     * @param totalsIncomparableReason 비교할 수 없을 때의 사유
     * @param unmeasuredCaution       미계측 비율이 전략 간에 크게 갈릴 때의 경고. 아니면 null
     * @param probeExclusionReasons   표본 첫 워크로드로 재 본 제외 사유 분포 — 이 판이 왜 막히는지
     * @param strategies              전략별 결과
     */
    public record Cell(
            int loadRatioPercent,
            String powerControl,
            double requestedWorkloads,
            double requestedCores,
            double headroomCores,
            double activeNodesBefore,
            boolean totalsComparable,
            String totalsIncomparableReason,
            String unmeasuredCaution,
            Map<String, Integer> probeExclusionReasons,
            List<StrategyResult> strategies
    ) {
        public Cell {
            probeExclusionReasons = probeExclusionReasons == null ? Map.of() : Map.copyOf(probeExclusionReasons);
            strategies = strategies == null ? List.of() : List.copyOf(strategies);
        }
    }

    /**
     * 전략 하나의 결과.
     *
     * @param strategyId    전략 식별자
     * @param label         사람이 읽는 이름
     * @param kind          {@code ENGINE}(운영 엔진) 또는 {@code BASELINE}(비교용 대조군)
     * @param policyVariant 이 전략이 본 정책 변종
     * @param metrics       지표별 값
     */
    public record StrategyResult(
            String strategyId,
            String label,
            String kind,
            String policyVariant,
            List<MetricValue> metrics
    ) {
        public StrategyResult {
            metrics = metrics == null ? List.of() : List.copyOf(metrics);
        }
    }

    /**
     * 지표 하나의 값 — 시각 지점들을 훑은 <b>분포</b>로 준다.
     *
     * <p>시각을 하나로 고정하면 결정론적 전략은 숫자가 하나뿐이라 "3.2% 낮았다"에 오차 막대를
     * 붙일 수 없다. 그래서 한 주기를 훑어 중앙값과 최소·최대를 함께 싣는다 — 지점 간 폭이
     * 전략 간 차이보다 크면 그 차이는 주장할 수 없다는 뜻이고, 그 판단을 읽는 사람이 할 수 있다.</p>
     *
     * @param key           지표 키. {@code metrics} 선언의 key 와 같다
     * @param median        시각 지점들의 중앙값. <b>유효하지 않으면 null</b> — 순위 근거가 아닌 칸에 숫자를
     *                      실으면 {@code valid} 를 보지 않은 인용자에게는 그냥 값이다
     * @param min           최솟값. 유효하지 않으면 null
     * @param max           최댓값. 유효하지 않으면 null
     * @param samples       값이 나온 시각 지점 수. 무효여도 남긴다 — "지점이 없어 무효"와 "지점은 있었지만
     *                      판정이 무효"를 구분하는 유일한 자리다
     * @param valid         이 숫자를 순위 근거로 써도 되는가
     * @param invalidReason 쓸 수 없을 때의 사유
     */
    public record MetricValue(
            String key,
            Double median,
            Double min,
            Double max,
            int samples,
            boolean valid,
            String invalidReason
    ) {
    }
}
