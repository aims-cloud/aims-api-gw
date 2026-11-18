package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.innogrid.aims.scheduler.benchmark.BenchmarkMetric;

/**
 * 배치 전략 비교 — 우리 엔진과 대조군을 <b>같은 판·같은 워크로드·같은 제약</b>에서 돌린 결과.
 *
 * <p><b>승자를 지명하는 필드가 없다</b>(이유는 docs/14 1장). 대신 셀마다 {@code valid}·{@code invalidReason}을 실어
 * <b>어떤 조건에서 그 숫자가 의미를 갖는지</b>를 함께 주고, 판정은 사람이 판 이름과 함께 쓴다.</p>
 *
 * <p><b>주 지표는 엔진이 믿은 값이 아니다.</b> {@code engineBelievedCostW}로 순위를 매기면 전력 엔진의 목적함수를
 * 최대화한 전략이 정의상 이긴다(docs/14 2장). 총전력의 주 지표는 클러스터 파일 생성식의 <b>선언된 유휴 전력</b>으로
 * 계산하며 엔진은 그 값을 보지 못한다. 두 값의 차는 <b>엔진 가정과 생성식의 격차</b>이고 실측 서버와의 차이가 아니다.
 * 노드별 격차는 {@link Board#idleAssumptionGaps}, 생성식은 {@link Assumptions#GENERATOR_FORMULA}에 있다.</p>
 *
 * @param evaluatedAt  응답 생성 시각
 * @param assumptions  계산 전제 — 이 블록 없이 인용된 숫자는 근거가 없다
 * @param engineConfig 이 실행이 본 엔진 설정과 정책 변종 셋 — {@code policyBasis}가 "기본값으로 돌렸다"는
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
     * <p>결과를 바꾸는 노브가 전부 환경변수로 덮여, 값을 싣지 않으면 두 배포의 표가 숫자만 달라도 읽는 사람은
     * 설정이 다르다는 것을 알 수 없다. 여기 실린 값이 배포 환경과 다르면 이 표의 숫자는 그 환경의 숫자가 아니다.</p>
     *
     * <p>{@code configDigest}는 판 파일 다이제스트와 <b>같은 규칙</b>(SHA-256 앞 12자)으로 "어느 설정인가"를 지목한다.
     * 엔진이 추가되면 표에 열이 늘어 다른 설정이 되므로 등록된 엔진 id 목록도 다이제스트에 들어간다.</p>
     *
     * @param configDigest 엔진 id 목록, 아래 노브 세 블록과 정책 변종, 열별 override를 정규화한 문자열의
     *                     SHA-256 앞 12자
     * @param engineIds    비교에 참가한 엔진 id(정렬). 레지스트리가 자동 수집하므로 실행마다 확인해야 한다
     * @param power        전력 모델 노브. 배치 비교 실행이 회계에 쓰는 문턱을 엔진 노브와 나란히 싣는다
     * @param thermal      온도 모델 노브
     * @param network      네트워크(링크 점수) 노브. 링크를 선언한 판에서 결과에 반영되므로 다이제스트 성분이고,
     *                     성분은 응답에 노출된다는 규약에 따라 여기 싣는다
     * @param policies     전략이 보는 정책 변종 셋 — 저장되지 않는 사본이다
     */
    public record EngineConfigSnapshot(
            String configDigest,
            List<String> engineIds,
            PowerKnobs power,
            ThermalKnobs thermal,
            NetworkKnobs network,
            List<PolicySnapshot> policies
    ) {
        public EngineConfigSnapshot {
            engineIds = engineIds == null ? List.of() : List.copyOf(engineIds);
            policies = policies == null ? List.of() : List.copyOf(policies);
        }
    }

    /**
     * 네트워크(링크 점수) 노브 — {@code NetworkModel}이 읽는 넷.
     *
     * @param latencyScaleMs   지연을 1로 자르는 눈금(ms)
     * @param degradedPenalty  DEGRADED 링크 가산 비용
     * @param linkWeight       트래픽 점수와 링크 점수를 섞는 가중치
     * @param unknownLinkScore 원장에 없는 원격 쌍의 링크 점수
     */
    public record NetworkKnobs(double latencyScaleMs, double degradedPenalty, double linkWeight, double unknownLinkScore) {
    }

    /**
     * 전력 모델 노브.
     *
     * @param activeCpuPercent             <b>엔진</b>의 가동 판정 문턱. 엔진이 믿은 비용이 이 값을 본다
     * @param benchmarkPoweredOnCpuPercent 배치 비교 실행이 <b>회계</b>에 쓰는 문턱(사전 등록 상수). 위 값과 다르면
     *                                     "엔진이 믿은 비용"과 "가동 노드 증감"이 서로 다른 문턱 위에 서 있다는
     *                                     뜻이고, 그 사실이 여기서 보여야 한다
     * @param capacityByNode               노드별 정격 원장(설정). 비어 있으면 전 노드가 {@code defaultCapacityWatts}.
     *                                     <b>이 둘은 판 경로에서 쓰이지 않는다</b>(수집 경로 전용). 판 경로에서 엔진이
     *                                     보는 정격은 판이 준 {@link IdleGap#engineBelievedCapacityWatts}다.
     *                                     설정 다이제스트에는 들어가므로 싣는다
     * @param idleFromMeasurement          계측된 유휴 노드의 깨우기 비용을 실측에서 역산하는가. 모델 <b>형태</b>를
     *                                     바꾸는 노브라 다이제스트에 들어가며 값이 다르면 표 두 장은 다른 엔진의 표다.
     *                                     {@code idleRatio}는 그대로 진단 지표와 동적 범위의 기준선이고, 미계측 노드의
     *                                     깨우기 비용 대체값이다
     * @param marginalScaleWatts          실질 한계 전력 점수의 분모가 되는 W 눈금. 깨우기 벌점을 정격으로 상대화하느냐
     *                                     절대 W로 보느냐가 갈리므로 {@code idleFromMeasurement}처럼 다이제스트에
     *                                     들어간다. 0 이하면 노드 정격을 분모로 쓴다
     */
    public record PowerKnobs(
            double idleRatio, double activeCpuPercent, double benchmarkPoweredOnCpuPercent,
            double curveAlpha, double peiWeight, double wasteWeight, double marginalWeight,
            double peiNormalizer, double resourceShare, double defaultCapacityWatts,
            Map<String, Double> capacityByNode, boolean idleFromMeasurement,
            double marginalScaleWatts
    ) {
        public PowerKnobs {
            // 키 정렬 사본 — Map.copyOf는 JVM마다 순회 순서가 달라 응답 바이트가 실행마다 바뀐다
            capacityByNode = capacityByNode == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(capacityByNode));
        }
    }

    /**
     * 온도 모델 노브.
     *
     * @param inertiaK                 열관성 계수. 판의 {@code thermalRiseScaleCheck}가 이 값으로 계산된다
     * @param benchmarkCooldownMinutes 배치 비교 실행이 실제로 쓴 냉각 대기(0). {@code cooldownMinutes}와 다른 것이
     *                                 정상이다 — 히스테리시스는 단발 비교에 넣지 않는다
     */
    public record ThermalKnobs(
            double inertiaK, double defaultWarningCelsius,
            int cooldownMinutes, int benchmarkCooldownMinutes
    ) {
    }

    /** 전략이 보는 정책 변종 하나. {@code SchedulingPolicy}의 <b>비영속 사본</b>에서 뜬 값이다. */
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
     * @param engineIdleRatio      엔진이 가정하는 유휴 전력 비율. 파일 선언값과의 차가 엔진 가정과 생성식의 격차다 —
     *                             엔진 가정은 이 비율 × <b>엔진이 믿는 정격</b>({@code idleAssumptionGaps[].engineBelievedCapacityWatts})이다
     * @param generatorFormula     전력·온도를 만든 생성식 — 이 값들이 실측이 아니라 식의 출력임을 응답이 스스로 밝힌다
     * @param powerControlNote     전원 제어 두 판의 뜻
     * @param randomSeedCount      무작위 대조군에 쓴 시드 수
     * @param policyBasis          어느 정책으로 돌렸는가 — 배포된 활성 정책이 아니라는 사실을 밝힌다
     * @param workloadDataMdcAssignment 워크로드에 데이터 MDC를 어떻게 부여했는가 — 링크 판만 순환 배정이고 나머지는 부여하지 않아
     *                             링크 게이트가 꺼져 있다. 이 판의 워크로드가 어느 MDC에 붙었는지가 응답에서 보여야 한다
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
            String policyBasis,
            String workloadDataMdcAssignment,
            String workloadBandwidthAssignment
    ) {
        public Assumptions {
            workloadClasses = workloadClasses == null ? List.of() : List.copyOf(workloadClasses);
            loadRatioPercents = loadRatioPercents == null ? List.of() : List.copyOf(loadRatioPercents);
        }

        /**
         * 전력·온도 값을 만든 생성식. 전제 블록에 두는 이유 — 한계 문구는 응답 맨 끝이라 표만 잘라 인용하면
         * 빠지지만, 전제 블록은 숫자보다 앞에 있다. 계수(유휴 전력, 정격, 0.3)는 실데이터 없이 정한 값이다.
         */
        public static final String GENERATOR_FORMULA =
                "전력(W) = idleWatts + (capacityWatts − idleWatts) × (CPU%/100)^β · 온도(℃) = baseTemperature + 0.3 × CPU% — "
                        + "노드별 idleWatts·capacityWatts·baseTemperature 는 클러스터 파일에 선언한 값이고 실측이 아니다. "
                        + "β 는 판 파일이 선언하며 이 실행의 값은 boards[].powerExponent 다(1 이면 선형). "
                        + "엔진은 idleWatts 도 β 도 모르고, 정격은 판이 준 값(boards[].idleAssumptionGaps[].engineBelievedCapacityWatts)을 믿는다 — "
                        + "설정의 defaultCapacityWatts·capacityByNode 는 판 경로에서 쓰이지 않는다(수집 경로 전용이며 설정 다이제스트에만 실린다). "
                        + "계측된 유휴 노드에 한해 깨우기 비용은 실측 전력에서 (P − Pmax×u)/(1−u) 로 역산하고, "
                        + "그 외(미계측·가동)는 엔진이 믿는 정격 × idleRatio 를 쓴다. idleAssumptionGaps.engineAssumedIdleWatts 는 이 대체값 기준이다. "
                        + "β=1 이고 판의 정격이 엔진 정격과 같은 판에서는 그 역산이 선언값을 정확히 복원하므로 "
                        + "(미계측 노드는 대체값 그대로) 그 판에서의 격차 축소는 상한이며 알고리즘 개선의 증거가 아니다. "
                        + "β≠1 이거나 정격이 어긋난 판에서는 복원하지 않으며, 그 차는 boards[].wakeupReconstructionCheck 에 노드별로 있다";

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
     * @param digest             응답이 지목하는 <b>판 파일</b> 내용(개행 LF 정규화)의 해시 앞 12자. 표시 전용이고
     *                           계산에 쓰이지 않는다. 워크로드 분포 파일과 판정 규칙은 들어가지 않는다(docs/13 8장)
     * @param nodeCount          노드 수
     * @param measuredNodeCount  전력·온도가 계측되는 노드 수
     * @param powerExponent         이 판의 생성식 지수 β. 1이면 선형이고, 1보다 크면 저부하에서 전력이
     *                              선형보다 낮은 볼록 판이다. 전제 블록의 생성식이 가리키는 값이 이것이다
     * @param idleAssumptionGaps    엔진의 유휴 전력 가정과 파일 선언값의 노드별 격차
     * @param thermalRiseScaleCheck 노드별 코어당 온도 상승 — 엔진 식과 생성식을 나란히({@link ThermalRiseScale})
     * @param wakeupReconstructionCheck 유휴 노드의 깨우기 비용 역산 — 선언값과 엔진 역산의 범위를 나란히
     * @param temperatureLimitCheck 노드별 온도 상한 대조 — 엔진이 보는 온도가 낮춘 상한과 정책 상한을 넘은 지점 수
     * @param phaseMode             위상 방식 — {@code LEGACY}(옛 식) 또는 {@code INDEPENDENT}(seed·nodeId 혼합).
     *                              같은 판의 Board가 여럿이면 전부 INDEPENDENT이고 서로 독립 복제라, 지점 지수는
     *                              Board 안에서만 뜻이 있다
     * @param phaseSeed             이 Board의 위상 seed. legacy 판은 파일의 seed 하나
     * @param cells                 부하율 × 전원 상태 가정의 격자
     */
    public record Board(
            String boardId,
            String role,
            String clusterName,
            String digest,
            String phaseMode,
            long phaseSeed,
            int nodeCount,
            int measuredNodeCount,
            double powerExponent,
            List<IdleGap> idleAssumptionGaps,
            List<ThermalRiseScale> thermalRiseScaleCheck,
            List<WakeupReconstruction> wakeupReconstructionCheck,
            List<TemperatureLimitCheck> temperatureLimitCheck,
            List<Cell> cells
    ) {
        public Board {
            idleAssumptionGaps = idleAssumptionGaps == null ? List.of() : List.copyOf(idleAssumptionGaps);
            thermalRiseScaleCheck = thermalRiseScaleCheck == null ? List.of() : List.copyOf(thermalRiseScaleCheck);
            wakeupReconstructionCheck = wakeupReconstructionCheck == null
                    ? List.of() : List.copyOf(wakeupReconstructionCheck);
            temperatureLimitCheck = temperatureLimitCheck == null ? List.of() : List.copyOf(temperatureLimitCheck);
            cells = cells == null ? List.of() : List.copyOf(cells);
        }
    }

    /**
     * 노드 한 대의 <b>깨우기 비용 역산</b> — 선언된 유휴 전력과 엔진이 되돌린 값을 나란히.
     *
     * <p>{@link ThermalRiseScale}이 열 쪽에서 하는 일을 전력 쪽에서 하되, <b>판정·비율 열을 만들지 않는다.</b>
     * 엔진의 역산은 선형 생성식을 유휴 전력에 대해 푼 것이라, 판이 선형이고 판이 준 정격이 생성식 정격과
     * 같을 때만 선언값을 정확히 되돌린다. 이 표는 그 조건이 깨진 판에서 값이 얼마나 벌어지는지를 보일 뿐
     * 어느 쪽이 옳은지는 말하지 않는다. 역산은 그 순간의 사용률에 걸린 값이라 시각 12지점의 최솟값·최댓값을 준다.</p>
     *
     * <p>행은 <b>판의 회계 규칙</b>으로 고른다. 12지점 내내 통전 문턱 아래에 머무는 노드 전부다. 엔진 노브로 고르면
     * 노브만 돌려도 표가 달라진다.</p>
     *
     * @param measured             전력이 계측되는 노드인가. <b>거짓이면 역산 두 값(min·max)이 대체값 그대로</b>이고
     *                             역산이 아니다. 미계측 노드는 역산할 분자·분모가 없다
     * @param declaredIdleWatts    파일에 선언된 유휴 전력(W). 계측 여부와 무관하며 실측이 아니다
     * @param engineFallbackWatts  엔진의 대체값(믿는 정격 × idleRatio).
     *                             {@code idleAssumptionGaps[].engineAssumedIdleWatts}와 <b>같은 값</b>을 대조용으로
     *                             다시 싣는다. 역산 경로를 탔는지는 엔진 안의 일이라 배치 비교 실행이 셀 수 없고,
     *                             대신 값이 대체값과 같은지를 독자가 여기서 바로 본다
     * @param engineWakeupMinWatts 12지점 역산의 최솟값(W)
     * @param engineWakeupMaxWatts 12지점 역산의 최댓값(W)
     */
    public record WakeupReconstruction(
            String nodeId,
            boolean measured,
            double declaredIdleWatts,
            double engineFallbackWatts,
            double engineWakeupMinWatts,
            double engineWakeupMaxWatts
    ) {
    }

    /**
     * 노드 한 대의 <b>온도 상한 대조</b> — 엔진이 보는 온도가 두 상한을 넘은 지점 수와 생성식 온도의 범위를 나란히.
     *
     * <p>지점 수는 엔진이 실제로 보는 배치 전 스냅샷의 온도로 센다. 미계측 노드는 그 스냅샷에서 0℃라 어느 상한에도
     * 걸리지 않는다. 엔진의 현재 온도 검사처럼 상한과 같은 온도는 넘지 않은 것으로 센다. 배치 후 예상 온도로 거르는
     * 몫은 워크로드 크기와 앞선 배치에 걸려 이 표에 없고, 셀의 온도 제약 개입 횟수가 그 몫까지 센다.</p>
     *
     * <p>두 상한 값은 응답에 이미 있다 — 낮춘 상한은 온도 상한 열({@code weighted-temp-65})의 {@code engineConfigOverride},
     * 정책 상한은 {@code engineConfig.policies}의 BASE 행이다. 판정·비율 열은 만들지 않는다.</p>
     *
     * @param measured                온도가 계측되는 노드인가. 거짓이면 두 지점 수가 언제나 0이다
     * @param minTemperatureC         생성식 온도의 12지점 최솟값(℃). 계측 여부와 무관하다
     * @param maxTemperatureC         생성식 온도의 12지점 최댓값(℃). 판 파일 머리말의 최고 온도는 곡선 꼭짓점이라 이 값보다 조금 높을 수 있다
     * @param pointsAboveLoweredLimit 엔진이 보는 온도가 온도 상한 열의 낮춘 상한을 넘은 지점 수(0~12)
     * @param pointsAbovePolicyLimit  엔진이 보는 온도가 기준 정책의 온도 상한을 넘은 지점 수(0~12)
     */
    public record TemperatureLimitCheck(
            String nodeId,
            boolean measured,
            double minTemperatureC,
            double maxTemperatureC,
            int pointsAboveLoweredLimit,
            int pointsAbovePolicyLimit
    ) {
    }

    /**
     * 노드 한 대의 코어당 온도 상승 — 세 가정을 나란히.
     *
     * <p>{@link IdleGap}이 전력 쪽에서 하는 일을 열 쪽에서 하되, <b>격차·비율·판정을 내지 않는다.</b>
     * "엔진이 N배 과소평가"라는 문장은 생성식이 옳다는 전제가 필요하고 그 근거가 없다. 게다가 그 배수는
     * 단위 선택의 산물이다 — 같은 엔진 식을 ℃로 재면 생성식보다 작고 K로 재면 크다. 그래서 두 번째 열이 있다:
     * 물리적으로 같은 상태를 두 눈금으로 넣었을 때 값이 다르면, 그 식은 온도 차를 눈금 원점에 비례시키고 있는
     * 것이고 절대 척도 위에서 정의되지 않은 모델이다. 이 사실은 생성식과 무관하게 엔진 식만으로 성립한다.</p>
     *
     * @param engineRisePerCoreC        엔진 식 {@code inertiaK × T × (1/cores)}에 ℃ 값을 넣은 상승
     * @param engineRisePerCoreIfKelvin 같은 식에 같은 온도를 K로 넣은 상승
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
     * @param engineBelievedCapacityWatts 판이 엔진에게 알려 준 정격(W). 판이 정격을 어긋나게 주지 않으면
     *                              {@code capacityWatts}와 같다. 판 경로에서 엔진이 보는 정격은 설정이 아니라 이 값이다
     * @param declaredIdleWatts     파일에 선언된 유휴 전력(W). 실측이 아니다
     * @param generatorWattsPerCore 생성식 기준 코어당 동적 전력(W/코어) = (정격 − 선언 유휴) / 코어 —
     *                              0→100% 구간의 <b>평균</b>(시컨트) 기울기다. 볼록 판(β&gt;1)에서 사용률 u의
     *                              한계 기울기는 이 값의 {@code β·u^(β−1)} 배라 이 열과 다르다
     */
    public record IdleGap(
            String nodeId,
            double capacityWatts,
            double engineBelievedCapacityWatts,
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
     * @param activeNodesBefore       배치 전부터 부하가 돌던 노드 수(중앙값). {@code activeNodeDelta}가
     *                                무엇에 대한 증감인지 이 값 없이는 알 수 없다. 전원 제어가 있다는
     *                                가정에서도 <b>이 노드들은 끌 수 없으므로</b> 총전력의 바닥이 된다
     * @param totalsComparable        총량 열을 비교해도 되는가 — 기준 집합(override 없는 기본 설정 열) 전부의 배치 건수가 전 지점에서 같을 때만 참
     * @param totalsIncomparableReason 비교할 수 없을 때의 사유. <b>{@code totalsComparable=true} 여도 채워질 수 있다</b> — override 열이
     *                                기준 집합과 건수가 달라 총량 짝에서 빠진 지점이 있으면 그 열·지점 수·남은 표본을 여기에 적는다(짝 조건 규칙의 표기 의무)
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
            probeExclusionReasons = probeExclusionReasons == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(probeExclusionReasons));
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
     * @param engineConfigOverride 이 열만 다르게 준 노브(이름 → 값). 기본 설정 그대로인 열은 빈 맵. 여기 실린 값은 설정 다이제스트의
     *                             {@code overrides=} 세그먼트에 그대로 들어간다 — 응답이 자기 입력을 지목한다는 원칙을 열 단위로 지킨다
     * @param metrics       지표별 값
     */
    public record StrategyResult(
            String strategyId,
            String label,
            String kind,
            String policyVariant,
            Map<String, String> engineConfigOverride,
            List<MetricValue> metrics
    ) {
        public StrategyResult {
            engineConfigOverride = engineConfigOverride == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(engineConfigOverride));
            metrics = metrics == null ? List.of() : List.copyOf(metrics);
        }
    }

    /**
     * 지표 하나의 값 — 시각 지점들을 훑은 <b>분포</b>로 준다.
     *
     * <p>시각 하나로는 결정론적 전략의 숫자가 하나뿐이라(docs/14 5장) 한 주기를 훑어 중앙값과 최소·최대를 함께
     * 싣는다. 지점 간 폭이 전략 간 차이보다 크면 그 차이는 주장할 수 없고, 그 판단은 읽는 사람이 한다.</p>
     *
     * @param key           지표 키. {@code metrics} 선언의 key와 같다
     * @param median        시각 지점들의 중앙값. <b>유효하지 않으면 null</b>이다. 무효인 자리에 숫자를 실으면
     *                      {@code valid}를 보지 않은 인용자에게는 그냥 값이 된다
     * @param min           최솟값. 유효하지 않으면 null. <b>전략별 계열에서 독립으로 뽑은 값이라 전략마다 서로 다른
     *                      시각 지점의 숫자일 수 있다</b>. 두 전략 사이의 짝 비교(예: power.min − pabfd.min)에는 쓰지
     *                      않고, 짝 비교는 median과 samples로만 한다
     * @param max           최댓값. 유효하지 않으면 null. min과 같은 이유로 <b>전략 간 짝 비교에 쓰지 않는다</b>
     * @param samples       값이 나온 시각 지점 수. 무효여도 남겨, "지점이 없어 무효"와
     *                      "지점은 있었지만 판정이 무효"를 구분하는 유일한 자리다
     * @param points        지점별 값. <b>인덱스 = 시각 지점</b>이고 짝이 아닌 지점은 null이며 길이는 항상
     *                      스윕 지점 수다. 두 실행을 지점별로 대조하는 값이라, 값만 모아 압축하면 대조할 수 없다.
     *                      지점별 판정이 필요한 지표만 채우고 나머지는 null이다(전 지표에 실으면 응답이 배로 커진다)
     * @param valid         이 숫자를 순위 근거로 써도 되는가
     * @param invalidReason 쓸 수 없을 때의 사유
     */
    public record MetricValue(
            String key,
            Double median,
            Double min,
            Double max,
            int samples,
            List<Double> points,
            boolean valid,
            String invalidReason
    ) {
        public MetricValue {
            // null 요소를 담아야 하므로 List.copyOf를 쓸 수 없다 — 그쪽은 null 원소에서 NPE다.
            // 채우지 않은 지표는 null을 그대로 두어 "지점값이 없다"와 "지점값이 전부 null이다"를 가른다.
            points = points == null ? null : Collections.unmodifiableList(new ArrayList<>(points));
        }
    }
}
