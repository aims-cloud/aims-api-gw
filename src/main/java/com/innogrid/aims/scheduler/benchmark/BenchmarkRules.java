package com.innogrid.aims.scheduler.benchmark;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

import com.innogrid.aims.scheduler.benchmark.BenchmarkMetric.Direction;
import com.innogrid.aims.scheduler.benchmark.BenchmarkMetric.Role;

/**
 * 배치 비교의 <b>판정 규칙</b> — 결과보다 먼저 고정한다.
 *
 * <p>이 클래스가 커밋 이력에서 결과 코드보다 <b>앞에 있는 것</b>이 사전 등록의 증거다.
 * 기술적으로 이 클래스는 지표 열거와 판정 셀의 전원 가정 말고는 아무것도 참조하지 않아 어디에 놓아도 컴파일된다 — 그래서
 * <b>순서가 유일한 증거</b>이고, 계약 테스트가 이 상수들을 고정해 나중에 조용히 바뀌는 것을 막는다.</p>
 *
 * <p><b>승자를 지명하는 필드를 응답에 두지 않는다.</b> API가 승자를 계산해 주면 그 필드만 인용되고,
 * 판 이름·전원 상태 가정·부하율에서 분리된 순간 그 판정은 거짓이 된다. 대신 셀마다
 * {@code valid}·{@code reason}을 하네스가 계산해 싣고, 판정은 사람이 판 이름과 함께 쓰게 한다.</p>
 *
 * <p><b>개정 이력(정직한 사전 등록 서술).</b> 이 클래스는 2026-09-07에 첫 선언됐고(하네스 결과 코드보다 앞),
 * 그 뒤 여러 차례 개정됐다(횟수·내용 전량은 {@code git log --follow -- BenchmarkRules.java}에 남아 있다 — 세는 순간
 * 낡는 숫자는 여기 적지 않는다) — 난수원 교체, 지표 이름 정정("지상진실" → "생성식 기준"), <!-- ban-ok: 개정 이력의 옛 이름 인용 --> 열 계열 지표 보조 강등,
 * 통전·격차 판정 문턱 고정, 문구 사실성 정정 등. 판정 상수(격차 문턱·지점 수 비율)는 비교 API가 이미 실행 가능한
 * 상태에서 등록됐다. 따라서 "결과를 볼 수 없는 상태에서 규칙을 정했다" 가 아니라 <b>"규칙 클래스가 결과 코드보다
 * 먼저 존재했고 이후 개정은 전부 커밋 이력에 남아 있다"</b> 가 이 저장소가 주장할 수 있는 전부다.
 * 새 판이나 새 판정 셀을 더할 때는 그 규칙 커밋을 결과·판 파일 커밋보다 앞에 둔다. 2026-09-14 개정(방법론 변경 PR의
 * {@code rules:} 커밋): 민감도 눈금 3값·역산 차단 열·링크 판·위상 독립 판 2장·오목 판 상수, 실측 불변조건을 판정 시점
 * 고정값과 현 실측값으로 분리, 판정 자격을 폐쇄형 문장으로 — 여기까지는 해당 판 파일·결과 코드보다 앞선 커밋이다.
 * <b>사후 개정</b>(관측 수치를 본 뒤 고친 규칙 — 개수는 세지 않고 목록으로 둔다, 전량은 계약 테스트의 축 이력에 있다):
 * <ul>
 *   <li>2026-09-14 — 총량 짝 조건의 기준 집합({@link #PAIRING_RULE}). 배포 검증에서 변종 열 추가만으로 기존 열의 총량 표본이
 *       5 → 1로 줄어드는 것을 보고 고정.</li>
 *   <li>2026-09-14 — 민감도 검정의 추정량 정정·위상 범위 보고 의무·오목 판 결론 슬롯·짝 제외 표기 의무. 부수 검정의 추정량 정의와
 *       보고 의무만 바뀌었다.</li>
 *   <li>2026-09-15 — 직전 배포 관측을 본 뒤: 판정 셀 분포({@link #JUDGMENT_DISTRIBUTION})의 <b>소급 명문화</b>(값은 지금까지의 판정이
 *       쓴 분포와 같고 앞으로의 선택 자유도만 닫는다)와 기준선 보존·출처·재사용 의무({@link #GAP_JUDGMENT_RULE} 뒷부분). 소비된 W 눈금
 *       판정의 지점 배열이 어디에도 남지 않은 것을 관측이 드러낸 뒤의 개정이다.</li>
 * </ul>
 * 어느 경우에도 판정 셀·판정 문턱·격차 정의·판정 자격은 바뀌지 않았고, 결과 코드 커밋은 규칙 커밋 뒤에 온다.</p>
 */
public final class BenchmarkRules {

    private BenchmarkRules() {
    }

    // ── 실행 격자 ─────────────────────────────────────────────

    /**
     * 부하율 네 지점(%). 30%에서는 전 전략이 100% 성공해 표가 평평한 선이 되고,
     * 110%(수용 불가 부하)가 없으면 성공률이 전략을 구분하지 못한다.
     */
    public static final List<Integer> LOAD_RATIOS = List.of(30, 60, 90, 110);

    /**
     * 부하율의 <b>분모 정의</b>. 총 코어가 아니라 <b>정책 상한까지의 여유</b>다 —
     * 노드마다 구속 자원이 다르므로(어떤 노드는 메모리가, 어떤 노드는 CPU가 먼저 막힌다)
     * 두 축의 최솟값을 쓴다. 이 문구도 상수이고 응답에 그대로 싣는다.
     */
    public static final String HEADROOM_DEFINITION =
            "Σ max( 0, min( cores × (정책 CPU 상한% − 실효 CPU%) / 100, "
                    + "memoryMb × (정책 메모리 상한% − 실효 메모리%) / 100 / 코어당메모리MB ) ) "
                    + "— 음수는 0 으로 자른다(정책 상한을 이미 넘은 노드의 여유는 0)";

    /**
     * 한 주기를 훑는 시각 지점 수. 시각을 하나로 고정하면 결정론적 전략은 셀마다 숫자가
     * <b>하나</b>뿐이라 분산 추정치가 0이 되고, "3.2% 낮았다"에 오차 막대를 붙일 수 없다.
     * 지점마다 같은 스냅샷·같은 워크로드를 주어 <b>페어 차분</b>으로 표본을 만든다.
     */
    public static final int SWEEP_POINTS = 12;

    /**
     * 무작위 전략의 시드 목록. 시드 하나는 <b>표본 1개</b>라 격차를 주장할 수 없다.
     * 재현성은 "시드가 하나"에서 오지 않고 <b>"목록이 상수로 선언되어 커밋되어 있다"</b>에서 온다.
     * 홀수라 중앙값이 잘 정의된다.
     */
    public static final List<Long> RANDOM_SEEDS =
            IntStream.rangeClosed(1, 31).mapToObj(Long::valueOf).toList();

    /**
     * 시드 하나로 난수원을 만든다 — <b>{@link java.util.Random}을 쓰지 않는다.</b>
     *
     * <p>{@code java.util.Random}은 시드를 {@code (seed ^ 0x5DEECE66D)}로만 섞고 첫 난수에
     * <b>상위 비트</b>를 쓴다. 작은 시드는 상위 비트가 거의 같아 <b>1~31 전부 첫 값이 한 값으로 뭉친다</b> —
     * 실측하면 {@code nextInt(4)}의 첫 값이 31개 시드 전부 {@code 2}이고, {@code nextDouble()}의 첫 값은
     * 0.7298~0.7326 폭 0.003 안에 모인다. 그러면 <b>시드 31개를 돌려도 첫 뽑기의 표본은 1개</b>이고,
     * 배치 건수가 적은 판에서는 "무작위 31회"가 사실상 1회가 된다.</p>
     *
     * <p>{@link SplittableRandom}은 시드를 SplitMix64로 섞으므로 같은 시드 목록이 실제로 서로 다른
     * 열을 낸다(같은 실측에서 첫 값이 네 값에 9/3/12/7로 흩어진다). 재현성은 그대로다 — 시드가 같으면
     * 열도 같다. 이 선택을 여기 둔 이유는 {@link #RANDOM_SEEDS}와 <b>같은 자리에서 읽히게</b> 하기
     * 위해서다. 시드 목록만 보고 표본 수를 세면 틀린다.</p>
     */
    public static RandomGenerator randomFor(long seed) {
        return new SplittableRandom(seed);
    }

    /** 열 히스테리시스는 시계열 운영의 성질이라 단발 비교에 넣을 근거가 없다. */
    public static final int COOLDOWN_MINUTES_FOR_BENCHMARK = 0;

    /**
     * 통전 판정 문턱(CPU %) — <b>비교의 회계 규칙</b>이지 엔진의 노브가 아니다.
     *
     * <p>지금 값은 {@code SchedulerProperties.Power.activeCpuPercent} 기본값과 같은 5.0이다. 같은 숫자를
     * 여기 다시 적는 이유는 저쪽이 <b>엔진의 판단 노브</b>이기 때문이다 — 운영에서 그 값을 조정하면 통전
     * 판정이 따라 움직여 <b>같은 배치 결정의 회계가 달라지고</b>, "가동 노드를 몇 대 늘렸나"가 알고리즘이
     * 아니라 설정의 함수가 된다. 판정 규칙은 결과보다 먼저 고정되어야 하므로 이 상수는 여기 있고, 엔진
     * 노브와 벌어지면 응답의 설정 스냅샷이 두 값을 나란히 밝힌다.</p>
     *
     * <p>이것은 "엔진에서 독립했다"는 주장이 <b>아니다</b> — 값의 출처가 엔진이고, 주 결과판의 유휴 노드는
     * 이 문턱 아래 머물도록 설계됐다. 그리고 <b>민감도가 넓다는 주장도 할 수 없다.</b> 주 결과판에서 유휴
     * 5대의 사용률 상한은 3.0%(기준 2 · 진폭 1)이고 가동 9대의 <b>하한은 5.0%</b>(bm-07, 기준 20 · 진폭 15)라,
     * 통전 집합이 불변인 구간은 <b>3.0~5.0%</b>뿐이다. 지금 값 5.0은 그 <b>상단 경계에 붙어 있다</b> —
     * 판정이 {@code >}이므로 bm-07이 골에 닿는 시각에는 통전 집합에서 빠진다. 문턱을 조금이라도 올리면
     * 회계가 달라지므로, 이 상수를 바꾸려면 판을 다시 설계해야 한다.</p>
     *
     * <p>가동 노드의 "기준 사용률 20~70%"는 진폭 ±15%p를 더하기 전 값이다. 판 파일의 {@code swing}을
     * 한 칸만 키워도 이 문단이 조용히 거짓이 되므로, 계약 테스트가 유휴 상한 &lt; 이 상수 ≤ 가동 하한을 고정한다.</p>
     *
     * <p>엔진이 <b>스스로 믿은 비용</b>({@code PowerModel.realMarginalPower})은 여전히 엔진 노브로 계산한다 —
     * 그건 엔진의 믿음을 재는 값이므로 하네스가 대신 정해 주면 안 된다.</p>
     */
    public static final double POWERED_ON_CPU_PERCENT = 5.0;

    /** 워크로드 메모리 = 코어 × 이 값. 이 값을 틀리면 전력이 아니라 메모리 산술을 측정한다. */
    public static final int MEMORY_MB_PER_CORE = 4096;

    // ── 볼록·정격 어긋남 판의 사전 등록 ───────────────────────

    /**
     * 볼록 생성식 판의 지수 β — {@code 전력 = 유휴 + (정격 − 유휴) × (CPU 사용률/100)^β}.
     *
     * <p><b>실서버 곡선의 근사가 아니다.</b> 지수를 1보다 크게 둔 판은 "엔진 가정이 틀린 방향"으로
     * 세계를 기울인 <b>응력 시험</b>이고, 문헌에는 반대 방향(오목)의 보고도 있다. 1.4를 피한 이유는
     * 그 값이 문헌 계수와 같은 수라 "문헌 근거"로 오독되기 때문이고, 1.8은 엔진 테스트의
     * {@code curveAlpha}와 같은 수라 피했다. 값 자체에 물리적 근거는 없다.</p>
     *
     * <p><b>판 파일이 진실이고 이 상수는 그 사본이다.</b> 판이 실제로 쓰는 β는 클러스터 파일의
     * {@code powerExponent}이고, 여기 적힌 값은 결과보다 먼저 등록해 둔 사전 선언이다 — 계약 테스트가
     * 둘의 일치를 강제한다. <b>이 값은 뒤에 바뀔 수 있으며, 바뀌면 이 판으로 뜬 기준선은 무효다.</b></p>
     */
    public static final double CONVEX_POWER_EXPONENT = 1.6;

    /**
     * 오목 생성식 판의 지수 β = 1/{@link #CONVEX_POWER_EXPONENT}. 볼록 판이 엔진의 선형 가정을 한쪽으로 기울였다면 이 판은
     * 정확히 반대쪽으로 기울인다 — 한 문장으로 설명되는 대칭이 근거의 전부이고 <b>물리적 근거는 없다</b>. 이 판은 세 경로(생성
     * 전력·역산 유휴·오라클)가 함께 바뀌므로 판정·방향 판정 자격이 없고 요인 분해용이다({@link #CONCAVE_CONCLUSION_TEMPLATE}).
     * 판 파일이 진실이고 이 상수는 사본이다.
     */
    public static final double CONCAVE_POWER_EXPONENT = 0.625;

    /** 오목 판 id. 판 등록부의 같은 문자열과 계약 테스트가 대조한다. seed는 하나(legacy 위상) — β와 위상 두 축을 한 판에 걸지 않는다. */
    public static final String CONCAVE_BOARD = "concave";

    /**
     * 링크 판 id. 주 결과판의 노드 블록을 바이트 그대로 재사용하고 {@code links:} 절과 워크로드 데이터 MDC 순환 배정만 더한 판이다.
     * 링크 스코어를 노드 배치 스코어에 섞는 열은 링크 게이트를 켠 {@code weighted-affinity} 하나라, 비교는 판 사이가 아니라
     * <b>같은 판 안의 게이트 on/off 열 쌍</b>({@code weighted} · {@code weighted-affinity})으로 한다 — netScore 경로의 작동 확인이며
     * 전력 판정과 무관하다. 원격 MDC 배치 비율은 데이터 MDC를 배정한 이 판에서만 모든 열에 값이 있다.
     */
    public static final String LINK_BOARD = "link";

    /** 링크 게이트를 켠 가중 점수 열 id. override는 {@code affinityEnabled=true} 하나다. */
    public static final String WEIGHTED_AFFINITY_COLUMN = "weighted-affinity";

    /**
     * 스케줄 배치 순서 열 id — 기본 엔진(weighted)을 스케줄 실행의 운영 비교자로 정렬한 표본에 돌린 두 열이다. 등록 순 열은 우선순위 →
     * 등록 순(스케줄 기본), 크기 순 열은 우선순위 → 요구 CPU · 메모리 큰 순(스케줄 선택지)이다. override가 없지만 총량 짝 조건의 기준 집합에
     * 넣지 않는다({@link #PAIRING_RULE}). 설정 다이제스트와 민감도 축에도 넣지 않는다 — 순서는 엔진 노브가 아니고, 민감도 축에 넣으면
     * 기준선의 민감도 표가 판정 불가가 된다.
     */
    public static final String SCHEDULE_ORDER_AGE_COLUMN = "weighted-priority-age";
    public static final String SCHEDULE_ORDER_SIZE_COLUMN = "weighted-priority-size";
    public static final List<String> SCHEDULE_ORDER_COLUMNS = List.of(SCHEDULE_ORDER_AGE_COLUMN, SCHEDULE_ORDER_SIZE_COLUMN);

    /**
     * 온도 상한을 낮춘 열 id — 기본 엔진(weighted)에 기준 정책 사본의 온도 상한({@code maxTemperatureCelsius}) 하나만
     * {@link #LOWERED_TEMPERATURE_LIMIT_CELSIUS}로 낮춘 override 열이다. 운영의 스케줄 온도 상한도 정책 사본의 같은 필드 하나를 바꾸므로,
     * 그 기능이 배치를 어떻게 바꾸는지를 합성 판에서 보인다. 판정 자격이 없다. 규칙 문장은 {@link #TEMPERATURE_LIMIT_COLUMN_RULE}.
     */
    public static final String TEMPERATURE_LIMIT_COLUMN = "weighted-temp-65";

    /**
     * 온도 상한 열의 상한 — 결과를 보기 전부터 코드에 있던 기본 경고 온도(SchedulerProperties.Thermal의 defaultWarningCelsius)와 같은 값이다.
     * 판 결과를 보고 고른 값이 아니다. 계약 테스트가 「기본 경고 온도와 같고 정책 기본 임계보다 낮다」를 고정한다.
     */
    public static final double LOWERED_TEMPERATURE_LIMIT_CELSIUS = 65.0;

    /**
     * 온도 상한 열 규칙 — 열 정의 · 읽는 순서 · 결과 전 추정 · 축 등록을 결과보다 먼저 적는다. 표기 축(prose)에 봉인한다.
     *
     * <p>민감도 축이 아니라 표기 축에 두는 이유: 이 열은 전력 눈금 민감도 표에 속하지 않고, 민감도 축에 넣으면 기준선의 민감도 표가 판정
     * 불가가 된다. 판정 자격이 없는 관측 열이라 지목 정정의 노브 변경도 아니다.</p>
     */
    public static final String TEMPERATURE_LIMIT_COLUMN_RULE =
            "온도 상한 열 규칙. 온도 상한 열(" + TEMPERATURE_LIMIT_COLUMN + ")은 기본 엔진 weighted 에 기준 정책 사본의 온도 상한(maxTemperatureCelsius) "
                    + "하나만 " + LOWERED_TEMPERATURE_LIMIT_CELSIUS + "℃ 로 낮춘 override 열이다 — 운영의 스케줄 온도 상한이 바꾸는 필드와 같다. "
                    + "경고 온도(warningTemperatureCelsius)는 비워 두어 기본 경고 온도를 쓰므로 경고 구간 노드 수를 세는 기준 온도는 열마다 같다. "
                    + "상한 값은 결과 전부터 있던 코드 기본 경고 온도이고 판 결과를 보고 고르지 않는다. "
                    + "override 열이라 총량 짝 조건의 기준 집합 밖이며, 기준 집합과 배치 건수가 다른 지점은 다른 override 열 · 스케줄 배치 순서 열과 따로 "
                    + "셋째 문장으로 싣는다 — 기존 두 문장은 이 열이 생기기 전과 바이트가 같다. 판정 자격이 없다. "
                    + "열 차이는 온도 제약 C3 의 제외(현재 온도 초과와 배치 후 예상 온도 초과)와 weighted 점수의 온도 여유 항(상한이 분모)이 함께 움직인 값이며 "
                    + "둘을 가르지 않는다 — 운영의 스케줄 온도 상한도 둘을 함께 바꾼다. "
                    + "읽는 순서: 1. 온도 제약 개입 횟수(" + BenchmarkRules.C3_INTERVENTIONS + " — 현재 온도 초과 제외와 배치 후 예상 온도 초과 제외의 합) "
                    + "2. 배치 성공률(건수 · 코어) 3. 짝 지점의 경고 구간 노드 수와 총전력. "
                    + "결과 전 추정: 코어 손실은 작고, 대가는 고부하 셀의 GPU 워크로드 실패와 짝 지점의 생성식 총전력 증가로 나타난다 — "
                    + "반대로 나와도 이 규칙을 고치지 않고 그대로 적는다. 판의 온도는 생성식 값이라 실장비 온도 제어의 효과가 아니다. "
                    + "이 열 id 는 민감도 축에 넣지 않는다 — 전력 눈금 민감도 표에 속하지 않고, 넣으면 기준선의 민감도 표가 판정 불가가 된다.";

    /**
     * 위상 독립 판 2장 — 방향판·판정판과 노드 블록이 바이트 동일하고 {@code phaseMode: independent}·{@code seeds:}만 다르다.
     * legacy 위상은 {@code (seed·31^L + hash(nodeId)) mod 1000}이라 nodeId 길이가 같은 판에서 seed는 전 노드에 같은 상수만 더한다 —
     * seed를 늘려도 표본이 늘지 않는 결함이고, 판정 경로(두 판)에서 그 결함을 닫는 것이 이 두 판의 존재 이유다.
     */
    public static final List<String> PHASE_INDEPENDENT_BOARDS = List.of("main-independent", "convex-misrated-independent");

    /** 위상 독립 판이 선언하는 seed 수 — 판 파일 {@code seeds:}의 길이. 값 자체는 판 파일이 진실이고 여기는 개수만 사전 등록한다. */
    public static final int PHASE_SEED_COUNT = 3;

    /**
     * 위상 독립 혼합 — SplitMix64 마무리 함수를 <b>코드 안에</b> 둔다(JDK {@code SplittableRandom}의 값열에 기대지 않는다).
     * {@code z = seed × GOLDEN ^ hash(nodeId); z = (z ^ z>>>30) × M1; z = (z ^ z>>>27) × M2; z ^= z>>>31}의 하위 10진 3자리를 위상으로.
     * 상수를 여기 적어 규칙 축에 봉인한다 — 상수가 바뀌면 위상 독립 판의 값이 통째로 바뀌기 때문이다.
     */
    public static final long PHASE_MIX_GOLDEN = 0x9E3779B97F4A7C15L;
    public static final long PHASE_MIX_M1 = 0xBF58476D1CE4E5B9L;
    public static final long PHASE_MIX_M2 = 0x94D049BB133111EBL;

    /**
     * 위상 구성 규칙 — 결과보다 먼저. seed별 Board는 서로 <b>독립 복제</b>이고 판정 규칙(교집합 I·중앙값·지점 비율·방향)은
     * 한 Board 안의 지점 지수 i 위에서만 정의된다. 한 Board의 12지점을 seed 수만큼 겹쳐 세지 않는다. seed 사이의 결합은 Board별
     * 중앙값 수준에서만 하며, 보고는 중앙값 n값과 최소~최대 범위(기술통계, 검정 아님)로 한정한다 — n=3으로는 어떤 검정도 하지 않는다.
     */
    public static final String PHASE_RULE =
            "위상 구성 규칙. phaseMode=independent 판은 seeds 마다 Board 를 하나씩 내며(boards[].phaseSeed), 각 Board 는 독립 복제다. "
                    + "판정 규칙의 지점 지수 i 는 한 Board 안에서만 정의되고 Board 사이의 지점을 합쳐 세지 않는다. "
                    + "seed 사이의 결합은 Board 별 중앙값 수준에서만 하고, 보고는 중앙값 n값과 최소~최대 범위(기술통계)로 한정한다 — "
                    + "n=3 으로는 검정하지 않는다. legacy 판(phaseMode 없음)은 seed 가 전 노드 위상에 같은 상수만 더하므로 seed 를 늘려도 표본이 아니다. "
                    + "위상 독립 판은 판정·방향 판정 자격이 없고 판정을 뒤집지도 구제하지도 않는다. 다만 판정을 보고할 때 짝 판(main-independent · "
                    + "convex-misrated-independent)의 같은 좌표 셀 중앙값 3값과 그 범위를 함께 싣는 것은 의무이며 생략은 판정 보고의 결격 사유다 — "
                    + "방향 일치 항의 폭 참고로만 읽고 통과/실패 판정에는 넣지 않는다. 그 범위가 JUDGMENT_MEDIAN_REDUCTION_PP 를 넘으면 단일 legacy 판 값은 "
                    + "그 폭 안의 한 점이라는 사실을 판정 보고에 함께 적는다(문턱과 같은 크기의 변동이 위상 구성 하나에 붙어 있다는 뜻이다).";

    /**
     * 정격 어긋남 판에서 엔진에게 알려 주는 정격의 어긋남 크기 — 한쪽은 {@code ×(1+r)}로 과대,
     * 다른 쪽은 {@code ×(1−r)}로 과소 믿게 한다. 생성식이 쓰는 정격은 그대로다.
     *
     * <p><b>부호 규칙(판 파일이 따르고 계약 테스트가 파일만 보고 재계산한다)</b> — 노드를 두 층으로
     * 나눈다. 한 층은 {@link #SWEEP_POINTS} 지점 전부 {@link #POWERED_ON_CPU_PERCENT} 아래에 머무는
     * 계측 노드(깨우기 벌점이 붙는 유일한 층)이고, 나머지가 가동 층이다. 층마다 노드를 절반(홀수면 한쪽이
     * 하나 많다)으로 갈라 한쪽은 과대, 다른 쪽은 과소 믿게 한다. 어느 절반인지는 <b>층마다 한 가지 양을
     * 최소로 하는 분할</b>로 정한다 — 유휴 층은 엔진에 실제로 들어가는 양인 깨우기 벌점 평균의 편향
     * {@code |Σ r/(1+δ) − Σ r| / Σ r}(r = 선언 유휴/정격), 가동 층은 정격 합의 편중
     * {@code |Σ₊cap − Σ₋cap| / Σcap}. 동률이면 과대 믿음 집합의 {@code nodeId} 열을 사전순으로 비교해
     * 앞선 것. <b>손으로 고르지 않는다</b> — 손 배치는 연구자 자유도이고, 그 자유도가 판정 셀의 결론을 만든다.</p>
     *
     * <p><b>수용 기준</b> — 유휴 층 벌점 편향 ≤ 1%, 가동 층 정격 합 편중 ≤ 5%(전력 제약 여유와 전력 인지
     * 대조군만 영향을 받는다). 깨우기 벌점이 {@code wake/정격} 형이라 {@code 1/(1+δ)}로 비선형이므로
     * 개수나 정격 합이 같아도 벌점 평균은 기운다 — 주 결과판의 유휴 다섯 대는 정격 합을 맞추면 벌점 편향이
     * 2.6~4.6%가 되어 두 양을 한 층에서 동시에 맞출 수 없다. 그래서 층마다 하나만 최소로 한다. 수용 기준에
     * 못 미치면 어긋남 비율을 한 축으로 한 번 내리고 그 사실을 남긴다.</p>
     *
     * <p><b>이 배치는 중립이 아니다</b> — 주 결과판에서 부호와 코어당 생성식 전력의 상관은 유휴 층이 약 −0.8,
     * 가동 층은 양이다. 층마다 한 양만 최소로 했기 때문이고, 세 번째 양인 이 상관까지 동시에 0으로 만드는
     * 분할은 이 노드 구성에 없다. 그 사실을 감추지 않고 판 설명에 적는다 — 부호의 효과와 코어당 전력의
     * 효과를 갈라 읽으려면 상관이 있다는 것을 먼저 알아야 한다.</p>
     */
    public static final double CAPACITY_MISMATCH_RATIO = 0.10;

    /**
     * <b>판정 셀</b> — 다음 PR의 효과를 판정할 자리를 결과보다 먼저 하나로 못 박는다.
     * 셀을 결과를 보고 고르면 사후 선택이 된다. 기준선 값 자체는 여기 두지 않는다 — 값은 판을 실행해야
     * 나오고, 상수에 값을 박으면 실행 순서가 커밋 순서를 강제한다.
     *
     * <p>판 등록 쪽 상수 {@link BenchmarkClusters#CONVEX_MISRATED}와 <b>같은 문자열</b>이며, 계약 테스트가
     * 이 값이 등록된 판 목록에 들어 있는지를 본다.</p>
     */
    public static final String JUDGMENT_BOARD = "convex-misrated";

    /** 판정 셀의 부하율(%). {@link #LOAD_RATIOS} 안의 값이어야 한다. */
    public static final int JUDGMENT_LOAD_RATIO = 60;

    /**
     * 판정 셀의 전원 상태 가정. <b>이 판은 현 시스템에 없는 전원 제어를 가정한다</b> — 통합의 이득이
     * 그 판에서만 보이기 때문이고, 그래서 판정 결과는 현 시스템에서 재현되지 않는다. 전원 제어가 없는
     * 같은 부하율의 셀은 보고만 하는 2차 셀이다.
     */
    public static final GroundTruthOracle.PowerControl JUDGMENT_POWER_CONTROL =
            GroundTruthOracle.PowerControl.AVAILABLE;

    /**
     * 판정 셀의 워크로드 분포. 분포는 판정 결과를 바꾸는 입력인데 규칙 축 밖에 있어 보고자가 고를 수 있었다 — <b>소급 명문화</b>다(2026-09-15):
     * 값은 지금까지의 판정·관측이 전부 쓴 분포와 같고, 이 상수는 앞으로의 선택 자유도만 닫는다. 서비스의 기본 분포 상수와 같은 문자열이며
     * 계약 테스트가 둘의 일치를 본다(이 클래스는 서비스를 참조하지 않는다 — 의존 방향을 지키기 위해 테스트가 대신 묶는다).
     */
    public static final String JUDGMENT_DISTRIBUTION = "heavytail";

    /**
     * 판정 기준선 파일의 저장소 경로(테스트 자원). 값은 배포 jar에 실리지 않는다 — 판정은 저장소 테스트가 하는 일이고 배포 응답은 재현 참고다.
     * 파일 형식과 갱신 이력({@code BASELINE_HISTORY})은 테스트 쪽에 있다: 파일 내용은 결과이므로 규칙 클래스가 값을 들지 않는다.
     */
    public static final String JUDGMENT_BASELINE_PATH = "src/test/resources/scheduler/judgment/judgment-baseline.json";

    /**
     * <b>판정 규칙과 그 사본</b>(한계 문구·지표 문구·판 설명·전제 문구·Swagger 설명)에 있으면 안 되는
     * 문장 조각 — 상수와 그 사본이 갈라지지 않게 <!-- ban-ok-block: 금지 조각을 정의하는 자리 — 블록 전체가 인용이다 -->
     * <b>한 배열</b>로 둔다. 조각으로 적는 이유는 "…없다"/"…없습니다" 같은 어미 차이로 검사가
     * 빠져나가지 않게 하기 위해서다.
     *
     * <ul>
     *   <li>"지상진실" — 평가자도 생성식을 쓰므로 실측을 암시하는 이름은 거짓이다.</li>
     *   <li>"온도 축에는 결론을 지지하는 지표가 없" — 열 대조군이 생긴 뒤로는 옛 문구다.</li>
     *   <li>"볼록하기 때문" — 볼록 곡선은 모델의 가정이다. 한계 문구가 문헌에 반대 보고도 있다고 적는데
     *       같은 저장소가 사실로 단정하면 자기모순이다.</li>
     *   <li>"핫스팟을 막" — 배치 후 온도 관측 지표가 없어 "막았다"는 검증되지 않았다. "피하는 것이 목표"까지만.</li>
     *   <li>"문헌 기준선" — 대조군은 우리 모델의 절제(pabfd)와 단순 규칙(coolest-first)이고 인용할 문헌 구현이 없다.</li>
     *   <li>"최적의 노드" — 엔진은 제약을 통과한 후보 중 점수 최대를 고를 뿐 최적성을 증명하지 않는다.</li>
     *   <li>"비선형적으로 증가" — 냉각 비용의 제곱 모델은 가정이다.</li>
     * </ul>
     */
    public static final List<String> BANNED_CLAIMS =
            List.of("지상진실", "온도 축에는 결론을 지지하는 지표가 없",
                    "볼록하기 때문", "핫스팟을 막", "핫스팟 방지", "핫스팟을 방지",
                    "문헌 기준선", "최적의 노드", "최적 노드", "비선형적으로 증가");

    /**
     * 판정 보고 0단계(출처 대조)에서 규칙 축 8값을 <b>등급별로</b> 읽는다 — 전량 일치를 요구하면 절차 문장이 한 번만 고쳐져도 0단계가
     * 상시 발동해 무력화된다. 등급표 자체를 규칙 축(prose)에 봉인하고, 세 집합의 합이 {@link #ruleAxes()}의 키 집합과 같음을 계약 테스트가 본다 —
     * 축이 새로 생기면 등급을 정하지 않고는 테스트가 통과하지 않는다.
     *
     * <ul>
     *   <li>판정 무효화 축 — 불일치면 B와 N이 다른 규칙으로 산출된 것이라 1~4항 판정 불가.</li>
     *   <li>부수 보고 무효화 축 — 1~4항 판정은 유효하되 그 축이 정의하는 부수 보고(민감도 표)는 판정 불가로 적는다.</li>
     *   <li>표기 축 — 옛→새 다이제스트와 사유를 보고에 적고 진행한다.</li>
     * </ul>
     */
    public static final java.util.Set<String> INVALIDATING_AXES = java.util.Set.of("judgment", "sweep", "boards", "measured");
    public static final java.util.Set<String> SECONDARY_REPORT_AXES = java.util.Set.of("sensitivity");
    public static final java.util.Set<String> NOTATION_AXES = java.util.Set.of("metrics", "banned", "prose");

    /** 축 등급 규칙 문장 — prose 축에 봉인. 집합의 원소는 정렬해 적어 정규 문자열이 순서에 흔들리지 않게 한다. */
    public static final String AXIS_GRADE_RULE =
            "규칙 축 대조 등급. 판정 보고의 출처 대조는 기준선 파일의 규칙 축 다이제스트와 현 실행의 값을 축마다 견주되 등급별로 읽는다. "
                    + "판정 무효화 축 " + new java.util.TreeSet<>(INVALIDATING_AXES) + " 은 하나라도 다르면 1~4항 판정 불가(B 와 N 이 다른 규칙의 산출물). "
                    + "부수 보고 무효화 축 " + new java.util.TreeSet<>(SECONDARY_REPORT_AXES) + " 은 다르면 1~4항 판정은 유효하되 그 축이 정의하는 부수 보고(민감도 표)를 판정 불가로 적는다 — "
                    + "눈금 목록이 바뀌면 기준선의 눈금 열이 현 실행의 열과 대응하지 않기 때문이다. "
                    + "표기 축 " + new java.util.TreeSet<>(NOTATION_AXES) + " 은 다르면 옛→새 다이제스트와 사유를 보고에 적고 진행한다. "
                    + "세 집합의 합은 규칙 축 전체와 같아야 하며 새 축은 등급을 정해야 생긴다. "
                    + "단서: 판정 주어(회차 지목)는 judgment 축에 편입되어 있어 바뀌면 판정 무효화 축 불일치로 기준선이 판정 불가가 된다 — 판정을 게시하는 PR 은 "
                    + "기준선을 다시 뜨지 못하므로 그 PR 안에서 지목을 바꿀 수 없다.";

    /**
     * 판정 규칙의 <b>축별 정규 문자열</b> — 사전 등록을 기계로 봉인하기 위한 재료. 계약 테스트가 축마다 다이제스트 리터럴을
     * 두고, 증거 묶음 매니페스트가 같은 값을 싣는다. 축을 나눈 이유는 "어느 규칙이 바뀌었나"가 값 하나에 뭉개지지 않게
     * 하기 위해서다. 응답에는 싣지 않는다(형태 불변).
     *
     * <p><b>축이 바뀌는 커밋의 형식.</b> 제목을 {@code rules:}로 시작하고, 본문에 축·옛 값 → 새 값·사유를 적고, 결과 코드
     * 변경을 같은 커밋에 담지 않는다. 계약 테스트의 축 이력({@code RULE_AXIS_HISTORY})에 행을 더해야 테스트가 통과하므로
     * 이력 누락은 기계가 막고, 커밋 순서는 리뷰가 본다.</p>
     */
    public static Map<String, String> ruleAxes() {
        java.util.LinkedHashMap<String, String> axes = new java.util.LinkedHashMap<>();
        // metrics 축은 키:방향:역할 — 키 집합만의 다이제스트(계약 테스트 DECLARED_KEY_DIGEST)와는 목적이 달라 값이 같지 않다
        axes.put("metrics", METRICS.stream().map(m -> m.key() + ":" + m.direction() + ":" + m.role()).sorted()
                .collect(java.util.stream.Collectors.joining(",")));
        axes.put("sweep", "loadRatios=" + LOAD_RATIOS + "|sweepPoints=" + SWEEP_POINTS + "|randomSeeds=" + RANDOM_SEEDS
                + "|memoryMbPerCore=" + MEMORY_MB_PER_CORE + "|poweredOnCpuPercent=" + POWERED_ON_CPU_PERCENT
                + "|cooldownMinutes=" + COOLDOWN_MINUTES_FOR_BENCHMARK);
        // 판 파일 값의 사전 등록(교차 검증) — 지수·비율·위상 방식·seed 수·혼합 상수. 판 파일이 진실이고 계약 테스트가 대조한다
        axes.put("boards", "convexExponent=" + CONVEX_POWER_EXPONENT + "|capacityMismatchRatio=" + CAPACITY_MISMATCH_RATIO
                + "|concaveExponent=" + CONCAVE_POWER_EXPONENT + "|concaveBoard=" + CONCAVE_BOARD + "|linkBoard=" + LINK_BOARD
                + "|phaseIndependentBoards=" + PHASE_INDEPENDENT_BOARDS + "|phaseSeedCount=" + PHASE_SEED_COUNT
                + "|phaseMix=" + Long.toHexString(PHASE_MIX_GOLDEN) + "," + Long.toHexString(PHASE_MIX_M1) + "," + Long.toHexString(PHASE_MIX_M2));
        // 판정 시점 고정값 — 판정 전제가 걸리는 값. 현 실측(MAX_REAL_MARGINAL_WATTS)은 판 데이터라 축에 넣지 않는다
        axes.put("measured", "maxRealMarginalWatts=" + MAX_REAL_MARGINAL_WATTS_AT_JUDGMENT);
        axes.put("sensitivity", "marginalScaleWatts=" + SENSITIVITY_MARGINAL_SCALE_WATTS + "|idleReconstructionDisabledColumn="
                + IDLE_RECONSTRUCTION_DISABLED_COLUMN + "|weightedAffinityColumn=" + WEIGHTED_AFFINITY_COLUMN);
        axes.put("judgment", "board=" + JUDGMENT_BOARD + "|loadRatio=" + JUDGMENT_LOAD_RATIO + "|powerControl=" + JUDGMENT_POWER_CONTROL
                + "|minPairedPoints=" + JUDGMENT_MIN_PAIRED_POINTS + "|medianReductionPp=" + JUDGMENT_MEDIAN_REDUCTION_PP
                + "|reducedPointRatio=" + JUDGMENT_REDUCED_POINT_RATIO + "|directionCheckBoard=" + DIRECTION_CHECK_BOARD
                + "|unmeasuredSpreadCautionPp=" + UNMEASURED_SPREAD_CAUTION_PP + "|distribution=" + JUDGMENT_DISTRIBUTION
                + "|pointIgnorePp=" + JUDGMENT_POINT_IGNORE_PP + "|target=" + JUDGMENT_TARGET);
        axes.put("banned", String.join(",", BANNED_CLAIMS));
        // 절차·정의 문장은 공백을 하나로 접어 따로 봉인한다 — 서식 편집 소음은 걸러지고, 문장을 바꿔 쓰면 이 축만 깨진다
        axes.put("prose", "headroom=" + HEADROOM_DEFINITION.replaceAll("\\s+", " ") + "|gapRule=" + GAP_JUDGMENT_RULE.replaceAll("\\s+", " ")
                + "|sensitivityRule=" + SENSITIVITY_RULE.replaceAll("\\s+", " ") + "|phaseRule=" + PHASE_RULE.replaceAll("\\s+", " ")
                + "|concaveConclusion=" + CONCAVE_CONCLUSION_TEMPLATE.replaceAll("\\s+", " ")
                + "|pairingRule=" + PAIRING_RULE.replaceAll("\\s+", " ")
                + "|axisGradeRule=" + AXIS_GRADE_RULE.replaceAll("\\s+", " ")
                + "|temperatureLimitRule=" + TEMPERATURE_LIMIT_COLUMN_RULE.replaceAll("\\s+", " "));
        return axes;
    }

    // ── 지표 ─────────────────────────────────────────────────

    public static final String PLACEMENT_RATE = "placementRatePercent";
    /**
     * 클러스터 파일 <b>생성식</b>으로 계산한 총전력. "지상진실"이라 부르지 않는다<!-- ban-ok: 옛 이름 인용 --> — 평가자도 그 생성식을 쓰므로
     * 이 값은 실측이 아니고, 확인되는 것은 "엔진 가정과 생성식이 얼마나 다른가"뿐이다.
     */
    public static final String GENERATOR_POWER = "generatorPowerW";
    public static final String PER_WORKLOAD_POWER = "perWorkloadPowerW";
    public static final String ENGINE_BELIEVED_COST = "engineBelievedCostW";
    public static final String ACTIVE_NODE_DELTA = "activeNodeDelta";
    public static final String WARN_ZONE_NODES = "warnZoneNodeCount";
    public static final String C3_INTERVENTIONS = "c3InterventionCount";
    public static final String TEMP_STDDEV = "measuredTempStdDev";
    public static final String UNMEASURED_RATE = "unmeasuredPlacementRate";
    public static final String UNMEASURED_SPREAD = "unmeasuredRateSpreadPercentPoints";

    /**
     * 전력 인지 최적적합 대비 총전력 격차(%). <b>지점마다</b> 두 전략을 짝지어 낸 비라서, 두 전략의 총전력
     * 중앙값을 나눈 수와 같지 않다 — 짝짓기가 지점 안에서 끝나므로 어느 지점이 빠져도 남은 지점의 비는 그대로다.
     */
    public static final String GAP_VS_PABFD = "gapVsPabfdPercent";

    /**
     * 데이터 MDC가 아닌 MDC의 노드에 놓인 배치의 비율(%). 판 파일의 사실(노드 MDC · 워크로드 데이터 MDC)만으로 센다 —
     * 엔진이 매기는 링크 스코어로 세면 링크 게이트를 켠 엔진이 자기 스코어로 평가받는다.
     */
    public static final String REMOTE_MDC_RATE = "remoteMdcPlacementRate";

    /**
     * 노드마다 남은 여유 가운데 분포의 가장 큰 계층을 하나 더 담지 못하는 자투리가 전체 여유에서 차지하는 비율(%).
     * 짝 지점에서만 낸다 — 적게 배치한 열이 남긴 여유를 같은 자리에서 견주면 조각화가 아니라 배치량을 재게 된다.
     */
    public static final String FRAGMENTED_HEADROOM = "fragmentedHeadroomPercent";

    /**
     * 배치한 워크로드의 요구 코어 합이 그 시각 지점 표본의 요구 코어 합에서 차지하는 비율(%). 건수로 센 배치 성공률과 한 쌍으로 읽는다 —
     * 건수 기준은 작은 워크로드를 많이 놓을수록, 이 값은 큰 워크로드를 놓을수록 높아진다.
     */
    public static final String CORE_PLACEMENT_RATE = "corePlacementRatePercent";

    /**
     * 배치를 시도할 때마다 후보 노드 가운데 제외 까닭이 전력 계열({@code ExclusionReason.isPower()})인 노드 수의 합. 시도당 계수라
     * 온도 제약 개입 횟수와 같이 전 시각 지점에서 낸다 — 배치 전부터 막힌 노드는 열과 무관하게 시도마다 더해지므로 전략의 몫은
     * 같은 셀의 열 사이 차이로만 읽는다.
     */
    public static final String POWER_INTERVENTIONS = "powerInterventionCount";

    /**
     * 사전 등록된 지표 전량. 응답이 이 목록을 그대로 싣는다.
     *
     * <p><b>최고 온도가 없는 이유</b> — 배치 1건의 온도 상승분은 열관성 계수 × 코어 비율 × 기준 온도라
     * 16코어 노드에 2코어를 얹으면 약 +1.4℃인데, 시각에 따른 자연 변동이 ±4.5℃다(배치 효과보다 3배 크다).
     * 게다가 제약 C3가 "배치 후 예상 온도 &gt; 임계"를 막으므로 <b>제약이 켜진 전략은 최고 온도가 전부
     * 임계 이하로 잘린다</b> — 한 비트짜리 열이 된다. 대신 경고 구간 노드 수·제약 개입 횟수·표준편차를 쓴다.</p>
     */
    public static final List<BenchmarkMetric> METRICS = List.of(
            new BenchmarkMetric(PLACEMENT_RATE, "배치 성공률", "%",
                    Direction.HIGHER_IS_BETTER, Role.PRIMARY,
                    "분모(요구 총량)가 전략과 무관하게 고정되어야 한다. 제약을 끈 변종은 막히는 자리가 없어 "
                            + "성공률이 정의상 최고가 되므로 비교 대상이 아니다 — 그 열은 경쟁자가 아니라 "
                            + "'제약이 없었다면 얼마나 배치됐을까'를 보여주는 천장이다"),

            new BenchmarkMetric(GENERATOR_POWER, "총전력(생성식 기준)", "W",
                    Direction.LOWER_IS_BETTER, Role.PRIMARY,
                    "기본 설정 열(override 없는 열 — 엔진·자원전용·대조군, 스케줄 배치 순서 열은 뺀다) 전부의 배치 건수가 같은 지점에서만 총량을 비교한다. "
                            + "override 열과 스케줄 배치 순서 열은 그 지점에서 자기 건수가 같을 때만 낸다. 건수가 다르면 건당 값을 쓴다. "
                            + "클러스터 파일 생성식으로 계산한 값이라 실측 서버의 전력이 아니다"),

            new BenchmarkMetric(GAP_VS_PABFD, "총전력 격차(전력 인지 최적적합 대비)", "%",
                    Direction.LOWER_IS_BETTER, Role.PRIMARY,
                    "두 전략(이 행·pabfd)의 배치 건수가 같은 지점만 값을 내며 pabfd 총전력이 0 이면 제외한다 — "
                            + "짝 정의가 총량 열(기본 설정 열 전부 일치)보다 느슨해 표본 수가 총량 열 이상일 수 있다. "
                            + "pabfd 행 자신은 0 이 값이 아니라 기준이므로 무효. "
                            + "지점별 비의 중앙값이라 '총전력 중앙값의 비' 와 다른 숫자다"),

            new BenchmarkMetric(PER_WORKLOAD_POWER, "건당 전력(생성식 기준)", "W/건",
                    Direction.LOWER_IS_BETTER, Role.PRIMARY,
                    "배치 건수가 다를 때의 대체 지표. 배치 0건이면 null"),

            new BenchmarkMetric(ENGINE_BELIEVED_COST, "엔진이 믿은 비용", "W",
                    Direction.CONTEXT_ONLY, Role.SECONDARY,
                    "전력 엔진 총점의 한 항을 그대로 합한 값이라 순위 근거로 쓸 수 없다. "
                            + "생성식 기준값과의 차가 엔진 가정과 생성식의 격차다"),

            new BenchmarkMetric(ACTIVE_NODE_DELTA, "가동 노드 증감", "대",
                    Direction.LOWER_IS_BETTER, Role.PRIMARY,
                    "유휴 노드가 충분히 많은 판에서만 판별력이 있다. 유휴가 한 대뿐이면 0/1 이진값이 된다"),

            new BenchmarkMetric(WARN_ZONE_NODES, "경고 구간 노드 수(생성식 온도 · 정책 경계)", "대",
                    Direction.LOWER_IS_BETTER, Role.SECONDARY,
                    "계측 노드가 있을 때만, 그리고 총량 지표와 같이 기본 설정 열 전부의 배치 건수가 같은 시각 지점에서만 — "
                            + "덜 배치한 전략은 CPU 를 덜 얹어 생성식 온도가 낮으므로 건수가 다르면 열이 아니라 배치량을 "
                            + "재게 된다. 미계측 노드는 온도 판단 근거가 없어 셈에서 뺀다. "
                            + "온도는 클러스터 생성식으로 내지만 경고 경계는 정책·설정값(기본 65℃)이라 "
                            + "이 값은 반이 생성식·반이 엔진 노브다 — 경계를 바꾸면 세계가 그대로인데 숫자가 움직인다. "
                            + "열을 점수에 넣는 대조군이 있다(최저 온도 우선 — 재평가 없이 실측 온도로만 고른다). "
                            + "나머지 대조군은 열을 점수에 넣지 않으므로 그들과의 차이는 알고리즘의 열 판단이 아니라 "
                            + "CPU 를 어디에 몰았는가의 부산물이다 — 헤드라인으로 쓰지 않는다"),

            new BenchmarkMetric(C3_INTERVENTIONS, "온도 제약 개입 횟수", "건",
                    Direction.CONTEXT_ONLY, Role.SECONDARY,
                    "제약이 몇 번 막았는지이지 전략의 열 성능이 아니다. 열을 점수에 넣지 않는 대조군은 "
                            + "뜨거운 노드를 피하지 못하고 그만큼 더 막히므로, 값이 큰 쪽이 나쁜 것도 작은 쪽이 "
                            + "좋은 것도 아니다. 열을 보는 대조군(최저 온도 우선)도 임계 근처 노드를 피할 뿐 배치 후 "
                            + "예상 온도는 보지 않는다. 온도 제약이 꺼진 전략에서는 항상 0 이라 그 전략과는 비교하지 않는다"),

            new BenchmarkMetric(TEMP_STDDEV, "계측 노드 온도 표준편차(생성식)", "℃",
                    Direction.LOWER_IS_BETTER, Role.SECONDARY,
                    "계측 노드가 둘 이상일 때만, 그리고 총량 지표와 같이 기본 설정 열 전부의 배치 건수가 같은 시각 지점에서만 — "
                            + "덜 배치한 전략은 CPU 를 덜 얹어 분포가 덜 벌어지므로 건수가 다르면 열이 아니라 배치량을 재게 된다. "
                            + "값은 배치 후 CPU 분포를 생성식에 넣어 낸 것이지 센서 판독이 아니다. 분모는 계측 노드 수 n(모집단 표준편차)이다. "
                            + "평균은 배치로 거의 안 움직이지만 분산은 움직인다"),

            new BenchmarkMetric(UNMEASURED_RATE, "미계측 노드 배치 비율", "%",
                    Direction.CONTEXT_ONLY, Role.HONESTY,
                    "미계측 노드는 전력·온도 지표에 기여하지 않으므로, 이 비율이 전략마다 다르면 "
                            + "전력·온도 비교의 유효성이 떨어진다"),

            new BenchmarkMetric(UNMEASURED_SPREAD, "미계측 비율의 전략 간 최대 격차", "%p",
                    Direction.CONTEXT_ONLY, Role.HONESTY,
                    "이 값이 크면 전력 순위가 알고리즘이 아니라 센서 공백의 산물일 수 있다"),

            new BenchmarkMetric(REMOTE_MDC_RATE, "원격 MDC 배치 비율", "%",
                    Direction.LOWER_IS_BETTER, Role.SECONDARY,
                    "워크로드에 데이터 MDC 를 배정한 판(링크 판)에서만 낸다 — 데이터 MDC 가 있는 성공 배치 가운데 노드의 MDC 가 "
                            + "데이터 MDC 와 다른 배치의 비율이다. 노드 MDC 가 없는 배치는 분모와 분자에서 뺀다. 데이터 MDC 를 배정하지 않은 판은 "
                            + "원격이 정의되지 않아 무효다. 판 파일의 MDC 사실로만 세고 엔진이 매기는 링크 스코어는 쓰지 않는다. 링크 스코어를 노드 배치 스코어에 섞는 "
                            + "엔진은 weighted 하나라 효과는 weighted · weighted-affinity 두 열의 쌍으로 읽고, 다른 열의 값은 링크를 보지 않은 "
                            + "배치의 지역성이다. 원격 배치는 자원·전력 제약 때문에 불가피할 수 있으므로 전력 판정과 섞지 않는다"),

            new BenchmarkMetric(FRAGMENTED_HEADROOM, "조각난 여유 비율", "%",
                    Direction.LOWER_IS_BETTER, Role.SECONDARY,
                    "배치를 마친 뒤 노드마다 기준 정책 상한까지 남은 여유 h(코어 · 부하율 분모와 같은 식) 가운데, 분포 파일에서 가장 큰 "
                            + "계층의 요구 코어 R 을 하나 더 담지 못하는 자투리(h − R × ⌊h / R⌋)가 여유 합에서 차지하는 비율이다. 총량 지표와 같이 "
                            + "기본 설정 열 전부의 배치 건수가 같은 시각 지점에서만 낸다 — 적게 배치한 열이 남긴 여유를 같은 자리에서 견주면 "
                            + "조각화가 아니라 배치량을 재게 된다. 그래서 짝 지점이 없는 고부하 셀은 무효이고, 그 셀에서 조각화가 배치를 막은 정도는 "
                            + "건수 · 코어 두 배치 성공률을 한 쌍으로 읽는다. R 코어 배치는 그 노드의 자투리를 바꾸지 않고 여유 합만 줄여 비율을 올리며, 자투리를 바꾸는 것은 "
                            + "R 보다 작은 배치다. GPU · 전력 · 온도 제약은 보지 않는다. 엔진 열과 그 변종은 표본 순서로 배치한다 — 운영의 크기 순 배치 선택지 효과는 "
                            + "스케줄 배치 순서 두 열(우선순위 → 등록 순 · 우선순위 → 크기 순)의 같은 지점 차로 합성 판에서만 읽는다"),

            new BenchmarkMetric(CORE_PLACEMENT_RATE, "코어 기준 배치 성공률", "%",
                    Direction.HIGHER_IS_BETTER, Role.SECONDARY,
                    "배치한 워크로드의 요구 코어 합을 그 시각 지점 표본의 요구 코어 합으로 나눈 비율이다. 분모는 전략과 무관하게 고정이고 "
                            + "배치 성공률과 같이 전 시각 지점에서 낸다. 건수로 센 배치 성공률은 작은 워크로드를 많이 놓을수록, 이 값은 큰 워크로드를 "
                            + "놓을수록 높아지므로 두 성공률은 한 쌍으로만 읽고 어느 쪽도 혼자 고부하 셀의 순위로 쓰지 않는다. 제약을 끈 변종은 "
                            + "배치 성공률과 같은 이유로 비교 대상이 아니라 천장이다. GPU 요구는 세지 않으며, 판의 워크로드는 메모리가 코어 수에 "
                            + "비례해 메모리로 센 값과 같다. 무작위 대조군의 값은 배치 건수로 고른 중앙 뽑기 하나의 값이라 이 값의 중앙값이 아니다"),

            new BenchmarkMetric(POWER_INTERVENTIONS, "전력 제약 개입 횟수", "건",
                    Direction.CONTEXT_ONLY, Role.SECONDARY,
                    "배치를 시도할 때마다 후보 노드 가운데 제외 까닭이 전력 계열(전력 여유 부족 · 배치 후 예상 전력 초과 등)인 노드 수를 더한 값이다. "
                            + "온도 제약 개입 횟수와 같이 시도당 계수라 전 시각 지점에서 낸다. 앞 제약(자원 · CPU · 온도)에서 먼저 걸린 노드는 세지 않는다. "
                            + "배치 전부터 전력으로 막힌 노드는 열과 무관하게 시도마다 더해지므로 전략의 효과는 같은 셀의 열 사이 차이로만 읽고, "
                            + "판 사이 차이는 프로브의 배치 전 사유와 함께 읽는다. 값이 판의 노드 수와 시도 수에 비례하므로 부하율 사이나 노드 구성이 "
                            + "다른 판(반증판)과는 견주지 않는다. 모든 열이 열 override 를 받지 않는 운영 설정 평가기로 판정하므로 눈금 · 역산 차단 "
                            + "열의 차이는 그 노브의 효과가 아니라 배치 위치의 차이다. 제약이 막은 횟수이지 전략의 전력 성능이 아니라 값이 큰 쪽이 "
                            + "나쁜 것도 작은 쪽이 좋은 것도 아니다. 전력 제약이 꺼진 전략에서는 항상 0 이라 그 전략과는 비교하지 않는다"));

    // ── 판정 ─────────────────────────────────────────────────

    /**
     * 총량 짝 조건의 <b>기준 집합</b> 규칙 — 결과보다 먼저. 짝 조건과 미계측 격차는 override 없는 열(기본 설정 열: 엔진 전량·자원전용 2·
     * 대조군 6 — 판정 자격 집합을 포함한다)의 배치 건수로만 정한다. override 열(링크 게이트·눈금·역산 차단)은 그 마스크를 받아 자기
     * 건수가 기준 집합의 건수와 같은 지점에서만 총량을 낸다. 이유: 짝 조건을 전 열로 잡으면 <b>열을 더하는 것만으로 기존 열의 값이
     * 바뀐다</b>(반증판·계측 공백판에서 실제로 표본 수가 5 → 1로 줄었다) — 기존 표와의 대조가 깨진다.
     *
     * <p>스케줄 배치 순서 열({@link #SCHEDULE_ORDER_COLUMNS})은 override가 없지만 기준 집합에 넣지 않는다 — 넣으면 같은 이유로 열을 더하는 것만으로
     * 기존 열의 짝 지점이 바뀐다. override 열과 같은 마스크를 받고, 제외 지점은 override 열과 따로 문장으로 싣는다.</p>
     */
    public static final String PAIRING_RULE =
            "총량 짝 조건 규칙. 총량 열(" + GENERATOR_POWER + "·" + ACTIVE_NODE_DELTA + "·" + WARN_ZONE_NODES + "·" + TEMP_STDDEV + ")의 짝 조건과 미계측 격차는 "
                    + "override 없는 기본 설정 열(엔진 전량·자원전용 2·대조군 6) 전부의 배치 건수가 같은 시각 지점으로 정한다. "
                    + "override 열은 그 마스크를 받아 자기 건수가 기준 집합의 건수와 같은 지점에서만 총량을 낸다. "
                    + "스케줄 배치 순서 열(" + SCHEDULE_ORDER_AGE_COLUMN + "·" + SCHEDULE_ORDER_SIZE_COLUMN + ")은 override 가 없어도 기준 집합에 넣지 않는다 — "
                    + "기본 엔진의 배치 순서만 운영 비교자로 바꾼 열이라 override 열과 같은 마스크를 받고, 제외 지점은 override 열과 따로 문장으로 싣는다. "
                    + "자원 조각화 비율(" + FRAGMENTED_HEADROOM + ")도 같은 마스크로 짝 지점에서만 낸다 — 총량은 아니지만 적게 배치한 열이 "
                    + "남긴 여유를 견주면 배치량을 재게 되기 때문이다. "
                    + "열을 더하는 것이 기존 열의 값을 바꾸지 않게 하기 위한 규칙이며, 격차 열(pabfd 짝)은 이 규칙과 무관하다. "
                    + "override 열이 기준 집합과 건수가 달라 총량을 내지 못한 지점이 있으면 그 사실을 응답에 싣는다 — 전 지점 제외면 그 열 총량 지표의 "
                    + "invalidReason 에, 일부 제외면 셀의 totalsIncomparableReason 에 제외 열과 지점 수를 적는다. 표본이 줄어든 사실이 응답에 보이지 않으면 이 규칙은 검증 불가다.";

    /**
     * 총량 비교가 유효한가 — <b>기준 집합(기본 설정 열) 전부의 배치 건수가 같을 때만</b>. 호출자가 기준 집합의 건수만 넘긴다.
     *
     * <p>적게 배치하고 전력을 덜 쓰는 것은 승리가 아니다. 건수가 다르면 총량 열을
     * {@code valid=false}로 내리고 건당 값으로 비교한다.</p>
     */
    public static boolean totalsComparable(Collection<Integer> placedByStrategy) {
        return placedByStrategy != null && !placedByStrategy.isEmpty()
                && placedByStrategy.stream().distinct().count() == 1;
    }

    /** 총량을 비교할 수 없을 때 셀에 적는 사유. */
    public static String totalsIncomparableReason(Map<String, Integer> placedByStrategy) {
        int min = placedByStrategy.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        int max = placedByStrategy.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return String.format("전략 간 배치 건수 상이 (%d~%d건) — 총량 대신 건당 값으로 비교한다", min, max);
    }

    /**
     * 전력 인지 최적적합(pabfd) 대비 <b>이 지점의</b> 총전력 격차(%). 짝이 아니거나 기준 전력이 0 이하면 null.
     *
     * <p><b>왜 판정 규칙에 두는가.</b> 이 식이 다음 PR의 성공 문턱이 읽을 값을 만든다. 서비스 안에 두면
     * 결과 코드와 같은 커밋에서 바뀔 수 있어 "문턱을 결과보다 먼저 등록했다"는 주장이 식에는 걸리지 않는다.
     * 여기 두면 식 자체가 사전 등록의 대상이 되고, 단위 테스트가 짝 조건·분모·부호를 잠근다 — 분모를 이 행의
     * 전력으로 바꾸거나 부호를 뒤집거나 짝 조건을 지우면 판을 돌려 보기 전에 빨간불이 된다.</p>
     *
     * <p>짝 조건은 총량 열보다 <b>느슨하다</b>. 총량 열은 전 전략의 배치 건수가 같은 지점만 쓰지만 여기는
     * 이 행과 pabfd <b>둘</b>만 본다 — 그래서 이 지표의 표본 수가 총량 열 이상일 수 있다.</p>
     *
     * @param placedA 이 행의 배치 건수
     * @param placedB 기준(pabfd) 행의 배치 건수
     * @param powerA  이 행의 생성식 기준 총전력
     * @param powerB  기준(pabfd) 행의 생성식 기준 총전력 — 분모다
     */
    public static Double pairedGapPercent(int placedA, int placedB, double powerA, double powerB) {
        if (placedA != placedB) return null;
        if (!(powerB > 0)) return null;
        return (powerA - powerB) / powerB * 100.0;
    }

    /**
     * 판정에 쓸 수 있는 <b>짝 지점의 최소 개수</b>. 짝이 몇 곳 안 되면 중앙값이 한 지점의 이동으로 뒤집혀
     * 판정이 표본이 아니라 우연을 읽는다. {@link #SWEEP_POINTS} 이하여야 한다 — 스윕이 낼 수 있는 지점보다
     * 많은 짝을 요구하면 어떤 실행도 영원히 판정 불가다.
     */
    public static final int JUDGMENT_MIN_PAIRED_POINTS = 10;

    /**
     * 격차 중앙값이 줄어야 하는 최소 폭(%p). 이 지표가 <b>이미 백분율</b>이라 단위는 %p다.
     * 값이 0이면 부호만으로 성공이 되어 잡음이 곧 결론이 된다.
     *
     * <p>이 값은 {@link #SENSITIVITY_RULE}(수준 의존 판단)과 {@link #PHASE_RULE}(위상 폭 경고)에도 <b>같은 문턱</b>으로 쓰인다 —
     * 바꾸면 세 문장이 동시에 움직인다. 재사용 근거: 우리가 개선이라 부르는 크기만큼 노브·위상이 흔들면 계기가 교란된 것이다.</p>
     */
    public static final double JUDGMENT_MEDIAN_REDUCTION_PP = 2.0;

    /**
     * 축소한 지점이 교집합에서 차지해야 하는 최소 <b>비율</b>. 개수로 적으면 교집합이 하한과 같은 실행에서
     * "열두 곳 중 열 곳"이 조용히 "열 곳 중 열 곳"(전 지점 축소)이 되어, 적어 둔 것보다 센 기준이 된다.
     * 비율로 두면 교집합이 몇 곳이든 같은 엄격도다.
     */
    public static final double JUDGMENT_REDUCED_POINT_RATIO = 10.0 / 12;

    /**
     * 지점 비교의 <b>무시 폭</b>(%p) — 4자리 눈금 두 칸. 지점값 g_i는 응답과 같은 4자리라 차는 0.0001의 배수인데, 실수에서 항등인 변경이
     * binary64 마지막 비트로 4자리 반올림 동점을 뒤집어 정확히 한 칸(0.0001)을 움직이는 것이 관측됐다(한계 전력 곡선 평가점 정정의 증거 묶음 대조에서
     * 26칸 — 전부 순위 근거가 아닌 표시 지표였고 판정 근거 열은 바이트 동일이었다. 판정량에 같은 뒤집힘이 오는 것은 같은 기전의 외삽이라 이 폭은
     * 관측이 아니라 예방으로 세운다).
     * 지점값 g_i는 반올림 전 전력에서 한 번만 4자리로 양자화되므로(단일 양자화가 이 폭의 전제다) 한 칸 차는 반올림 경계 하나로 설명되고
     * 두 칸부터는 그렇지 않다 — 두 칸 미만의 차는 축소도 악화도 아닌 동률로 본다. 3항(축소 지점 수)에 이 폭을, 4항(방향 셀 중앙값 차)에는
     * 그 두 배를 적용한다: 중앙값은 짝수 개 지점의 평균에 반올림이 한 번 더 겹쳐 지점 한 칸 잡음이 중앙값을 최대 두 칸 움직이므로 방향 밴드는 두 칸을
     * 넘어야 하고(타이트 하한 세 칸), 여유 한 칸을 더해 네 칸(폭의 두 배)으로 둔다 — "두 배"는 규칙이 아니라 이 유도의 결과다. 2항(중앙값 문턱
     * 2.0%p)에는 적용하지 않는다 — 문턱이 이 폭의 만 배라 무의미하다. 이 값은 잡음 하한이지 문턱이 아니며, 판정을 더 엄격하게 만드는 방향이라
     * 결과를 보고 넓힐 수 없다. 관측을 계기로 세운 값이라 소비된 회차에 대해서는 사후 개정이고(그 회차는 이미 지점 배열 부재로 판정 불가라
     * 소급 영향 없음) 다음 회차에 대해서는 사전 등록이다.
     */
    public static final double JUDGMENT_POINT_IGNORE_PP = 0.0002;

    /**
     * 다음 회차의 <b>판정 주어</b>(고치는 결함의 지목). judgment 축(판정 무효화 축)에 편입한다 — 지목이 바뀌면 기준선이 판정 불가가 되고,
     * 판정을 게시하는 PR은 기준선을 다시 뜨지 못하므로 그 PR 안에서 주어를 바꾸는 길이 기계로 막힌다. 지목을 바꾸는 것은 판정 없는 PR의 재취득 예외
     * 경로다. 규칙 문장이 이 값을 그대로 인용한다.
     *
     * <p>현재 공석. 회차 4(노드 배치 스코어 PEI 항의 코어 수 무시)는 결과가 게시되어 소비된 회차 목록으로 내려갔고, 다음 결함은 아직 지목하지 않았다.
     * 공석인 동안 판정 PR은 열 수 없다 — 판정 주어는 결과 코드보다 앞선 {@code rules:} 커밋이 하나로 지목해 이 값을 채워야 하며, 그 커밋은
     * judgment 축을 돌려 기준선 재취득을 강제한다. 품질 수정(음수 설정 가드 등 기본 설정에서 결과가 같은 변경)은 지목 없이도 낼 수 있으나
     * 판정을 게시하지 않는다. 공석 문장에 소비한 회차를 넣어 앞 공석(회차 3 소비 뒤)과 judgment 축 지문이 겹치지 않게 한다 — 겹치면 그때의 기준선이
     * 무효화 축 대조를 통과한다.</p>
     */
    public static final String JUDGMENT_TARGET = "지목 없음 — 회차 4 소비 뒤 공석이며, 다음 회차의 판정 주어는 결과 코드보다 앞선 rules: 커밋이 하나로 지목해 등록한다";

    /**
     * <b>방향만</b> 확인하는 판 — 주 결과판이다. 판정 자체는 {@link #JUDGMENT_BOARD} 한 곳에서만 하고
     * (진실 원본이 둘이 되면 유리한 판을 고르는 자유도가 생긴다), 이 판에서는 무시 폭의 두 배(중앙값 축 잡음 하한) 밖에서 부호가 같은지만 본다.
     * 판정판만 좋아진 결과는 판이 우리 엔진 쪽으로 기울었을 가능성을 배제하지 못하므로 <b>실패</b>로 적는다.
     */
    public static final String DIRECTION_CHECK_BOARD = BenchmarkClusters.MAIN;

    /**
     * 격차 지표로 다음 PR을 판정하는 <b>절차</b> — 결과보다 먼저 문장으로 고정한다.
     *
     * <p>상수 넷만 두면 "무엇을 무엇과 견주는가"가 사람의 기억에 남고, 기억은 결과를 본 뒤에 바뀐다.
     * 교집합을 먼저 잡는 것·중앙값을 같은 집합에서 내는 것·축소의 부호·불일치의 처리를 여기 적어 두고,
     * 계약 테스트가 이 문장에서 네 조각이 사라지지 않게 지킨다.</p>
     */
    public static final String GAP_JUDGMENT_RULE =
            "격차 판정 규칙. 판정 셀은 JUDGMENT_BOARD·JUDGMENT_LOAD_RATIO·JUDGMENT_POWER_CONTROL 로 정해지며, "
                    + "판정 값은 그 셀 power 행의 " + GAP_VS_PABFD + ".points 다. "
                    + "지점 i(0 ≤ i < SWEEP_POINTS)의 값 g_i 는 두 전략의 배치 건수가 같고 pabfd 총전력이 0 보다 "
                    + "클 때만 정의되고 아니면 null 이다. "
                    + "두 실행(이 PR 의 기준선 B, 다음 PR 의 N)에 대해 교집합 I = { i : g_i(B) ≠ null ∧ g_i(N) ≠ null } "
                    + "을 먼저 잡는다. "
                    + "1. 판정 가능 — |I| ≥ JUDGMENT_MIN_PAIRED_POINTS 가 아니면 판정 불가(성공도 실패도 아님). "
                    + "한 판이라도 |I| 가 JUDGMENT_MIN_PAIRED_POINTS 에 못 미치면 그 판은 판정 불가이고, 두 판 모두 "
                    + "통과가 성립하지 않으므로 전체를 성공으로 쓰지 않는다. "
                    + "2. 중앙값 — median{g_i(N): i∈I} ≤ median{g_i(B): i∈I} − JUDGMENT_MEDIAN_REDUCTION_PP. "
                    + "두 중앙값은 같은 집합 I 위에서 낸다. 보고는 2항 옆에 지점별 차의 중앙값 median{g_i(B) − g_i(N): i∈I} 을 병기한다 — "
                    + "판정은 사전 등록된 두 중앙값의 차를 유지하고 병기는 관측이다(추정량 교체는 결과를 본 뒤의 선택이 된다 — 두 추정량은 지점별 차의 분포가 치우치면 갈린다). "
                    + "보고는 2항이 미달이 되도록 동률(g_i(N) := g_i(B))로 돌릴 수 있는 지점의 최소 개수 k 도 함께 적는다(모든 조합 중 최소 — 2항의 민감도). "
                    + "3. 지점 — round4(g_i(B) − g_i(N)) ≥ JUDGMENT_POINT_IGNORE_PP 인 i 가 ceil(JUDGMENT_REDUCED_POINT_RATIO × |I|) 이상. "
                    + "'축소' 는 부호 있는 값이 작아지는 것이며(LOWER_IS_BETTER), 절댓값이 아니다 — 음수가 되는 것도 "
                    + "축소다. 기준선보다 무시 폭 이상 엄격히 작은 지점만 센다 — 무시 폭 미만의 차(4자리 한 칸)는 반올림 동점 뒤집힘과 구별되지 않아 "
                    + "동률로 본다(같으면 축소가 아니다). SWEEP_POINTS 개 지점은 한 주기를 훑는 시각 지점이라 독립 복제가 아니며 복제 축은 위상 독립 판(seed 3)뿐이다 — "
                    + "3항의 지점 수는 독립 표본 수로 읽지 않는다. "
                    + "4. 방향 일치 — DIRECTION_CHECK_BOARD(주 결과판) 의 같은 셀에서 1 을 만족하고 "
                    + "round4(median{g_i(B)} − median{g_i(N)}) ≥ 2 × JUDGMENT_POINT_IGNORE_PP(같은 I 위) 이어야 한다 — 중앙값은 반올림이 한 번 더 겹쳐 최대 두 칸 잡음이 "
                    + "가능하므로 밴드는 두 칸을 넘어야 하고(최소 세 칸) 여유 한 칸을 더해 폭의 두 배(네 칸)로 둔다. 그 밖의 크기 문턱은 두지 않는다. "
                    + "불일치면 실패로 기록한다(판정판만 좋아진 결과는 판이 우리 엔진 쪽으로 기울었을 가능성을 "
                    + "배제하지 못한다). "
                    + "고치는 대상은 회차마다 그 회차의 rules: 커밋이 하나로 지목한 결함이며, 포화는 어느 회차에서도 판정 대상이 아니다. "
                    + "회차별 지목 — 소비된 회차: 깨우기 벌점이 유휴비로 고정되던 일률 편향; 회차 1(PASS): 배치 점수의 PEI·비낭비 두 항이 실측 CPU·실측 W 로 고정되어 같은 점수 안에서 예약을 반영하는 항(isActive·동적항·실효 여유율)과 기저가 갈리던 결함. "
                    + "회차 2(FAIL): 전력 엔진 배치 점수의 열 항만 실측 온도로 고정되어 같은 점수 안에서 예약을 반영하는 항(전력 3항의 사용률·전력 기저와 자원 여유 2항)과 기저가 갈리던 결함. "
                    + "회차 3(FAIL): 전력 엔진 한계 전력 식에서 유휴 전력의 기준선이 둘이라(동적 범위의 유휴 절편은 선언 유휴 정격 × 유휴 비율, 깨우기 비용은 실측 역산) 계측된 유휴 노드의 유휴가 한 식의 두 자리에서 서로 다른 값으로 쓰이던 결함. "
                    + "정정은 계측된 유휴 노드에 한했고 미계측 노드·가동 노드·진단 지표는 지목 밖이었으며, 실측 상수의 갱신은 지목의 산출물로 판정 전제는 판정 시점 고정값에만 걸렸다. "
                    + "회차 4(PASS): 전력 엔진 노드 배치 스코어의 PEI 항이 실효 사용률을 실효 전력으로만 나눠 노드의 코어 수를 보지 않아, 코어 수가 다른 노드 사이에서 같은 사용률을 같은 양의 일로 셈하던 결함. "
                    + "정정은 배치 스코어용 PEI 의 분자에 한했고 실효 사용률에 (그 노드의 코어 수 ÷ 그 배치 호출 후보 노드 중 코어 수가 0 보다 큰 노드의 평균 코어 수 — 제외 판정·미계측 노드 포함) 비율을 곱했으며, "
                    + "코어 수가 0 이하인 노드는 비율 1, 새 노브는 없었고, 진단 API 의 PEI·비낭비·한계 전력 항·제약·대조군은 지목 밖이었다. "
                    + "다음 회차(JUDGMENT_TARGET): " + JUDGMENT_TARGET + ". "
                    + "지목 정정은 기본 설정 불변으로 낸다 — B 와 N 의 설정 다이제스트가 다르면 1~4항 판정 불가이고, 노브 변경의 효과는 민감도 표로만 낸다. "
                    + "지목하지 않은 변경은 같은 회차의 판정에 얹지 않는다. "
                    + "지목은 judgment 축(판정 무효화 축)에 편입되어 있어 판정을 게시하는 PR 안에서 바꾸면 기준선이 판정 불가가 되고 그 PR 은 기준선을 다시 뜨지 못한다 — "
                    + "지목을 바꾸는 것은 판정 없는 PR 의 재취득 경로다. 지목 이력은 계약 시험이 봉인한다. "
                    + "기준선 값은 상수에 넣지 않는다 — "
                    + "값은 판을 실행해야 나오고, 상수에 박으면 실행 순서가 커밋 순서를 강제한다. "
                    + "이 지표의 정의와 문턱 상수는 이 PR 에서만 바뀌고 다음 PR 은 참조만 한다. "
                    + "판정 자격(폐쇄형): 판정·방향 판정은 JUDGMENT_BOARD·DIRECTION_CHECK_BOARD 두 판의 power 행에서만 성립하며, "
                    + "그 밖의 판·열·지표는 전부 자격 없음이다(응답의 진단 항목도 같다) — 판이나 열이 늘어도 이 문장은 바뀌지 않는다. "
                    + "부수 보고: 판정 자격 두 판 power 행의 판정 셀 밖 셀 — 부하율 × 전원 제어 전 조합에서 판정 셀·방향 셀 자신을 뺀 것 — 의 격차 중앙값을 기준선 B 와 대조하고, "
                    + "차(B − N)가 −JUDGMENT_POINT_IGNORE_PP 이하면 악화, JUDGMENT_POINT_IGNORE_PP 이상이면 축소로 세어 두 수를 표본 수와 함께 적는다. B·N 한쪽만 값이 있는 셀은 짝 집합 변화로 따로 적고 악화·축소로 세지 않는다. "
                    + "기준선 파일(형식 3)은 두 판의 전 셀 power 행 배열을 담으므로 이 대조는 판정과 같은 파일에서 나오되 자격은 그대로이고 보고 의무만 더한 것이며 판정에 넣지 않는다. "
                    + "지점별 차 중앙값·2항 미달까지의 최소 동률 지점 수·셀 밖 대조 셋은 회전 초안이 2단계와 표 G 로 낸다. "
                    + "셀 밖 대조의 지점별 차 중앙값도 판정량과 같은 I 제한(B·N 양쪽에 값이 있는 지점)으로 내고 표본 수를 함께 적는다 — 짝이 다른 두 수의 차를 지점별 차로 적으면 셀 밖 관측이 판정량과 다른 잣대를 쓰게 된다. "
                    + "증거 묶음 매니페스트의 gitHead 가 unknown 이면 동일성은 entriesDigest 로 확인한다. "
                    + "판정 셀의 워크로드 분포는 JUDGMENT_DISTRIBUTION 이다. "
                    + "기준선 보존: B 는 두 셀의 지점 배열째(null 자리 보존) 저장소 파일 JUDGMENT_BASELINE_PATH 에 두고, 상수·커밋 본문·중앙값만으로 "
                    + "대신하지 않는다 — 중앙값만 남으면 3·4항은 영원히 재구성 불가다. 파일은 출처(소스 리비전·판/워크로드/설정 다이제스트·규칙 축 다이제스트)를 "
                    + "함께 담고, 판정 보고는 파일과 출처를 인용한다. 판정 불가로 적을 때는 결측 항목(어느 실행의 어느 배열이 없는지)을 적시한다. "
                    + "B 와 N 은 같은 생산 경로 — 저장소 시험의 코드 기본 설정 실행 — 에서 낸 값이며 배포 응답은 재현 확인용 참고다. 두 값이 다르면 차를 "
                    + "보고에 적되 판정은 저장소 실행으로 한다. "
                    + "기준선 재사용: 1~4항 판정을 게시하는 PR 은 기준선 파일을 수정하지 않는다(B 는 N 이전에 이미 머지된 파일이어야 한다 — 저장소 밖 검사라 리뷰 항목이다). "
                    + "기준선 갱신은 판정 보고를 게시한 뒤 별도 커밋으로 하고 판정의 성패와 무관하게 직전 머지 리비전으로 한다. "
                    + "예외: 판정 무효화 축이나 판·워크로드 정의를 바꾸는 PR, 또는 override 열을 더해 설정 다이제스트만 바뀌고 두 셀 power 행 지점 배열이 "
                    + "직전 기준선과 지점별로 같은 PR 은 그 변경으로 기존 기준선이 이미 판정 불가가 되므로, 그 PR 이 1~4항 판정을 게시하지 않는 한 "
                    + "같은 PR 에서 기준선을 다시 뜨고 이력 행에 재산출 사유와 옛 기준선과의 두 셀 중앙값 차를 적는다 — 판정과 기준선 갱신이 한 PR 에 함께 있으면 안 된다는 것이 "
                    + "이 규칙의 목적이고 판정 없는 재산출은 그 목적을 건드리지 않는다. 부수 보고 무효화 축의 변경은 기준선을 무효화하지 않는다(민감도 표만 판정 불가). "
                    + "이력 행에는 봉인 다이제스트·날짜·사유·엔진 동등 리비전·산출 HEAD·직전 B 와의 두 셀 중앙값 차를 적는다 — 판정 없는 변경이 기준을 얼마나 밀었는지가 보여야 하고, "
                    + "산출 HEAD 가 브랜치 커밋이면 머지 방식에 따라 사라질 수 있음을 함께 적는다. "
                    + "판정량 비교는 I 제한 중앙값(응답과 같은 4자리)의 차를 4자리로 반올림한 값으로 문턱과 견준다 — 두 중앙값이 이미 4자리라 차는 0.0001 의 배수이고 "
                    + "이 반올림은 부동소수 잡음만 없앤다(반올림 없이 견주면 정확히 문턱만큼 줄어든 실행이 잡음으로 미달 판정될 수 있다). "
                    + "사전 추정을 적을 때는 산출 방법(해석적 추정인지 변경 코드 실행값인지)을 함께 적는다.";


    // ── 판정 계산 함수 — 규칙 문장의 1~4항을 식으로. 축 성분이 아니라 다이제스트는 불변이고, 단위 테스트가 경계값을 잠근다 ──

    /** 격차 판정의 결말 — 판정 불가는 성공도 실패도 아니다. */
    public enum GapOutcome { UNJUDGEABLE, PASS, FAIL }

    /**
     * 셀 하나의 판정 계산 결과({@link #judgeCell}). 규칙 1~3항의 재료를 전부 실어 보고 표가 손계산으로 돌아가지 않게 한다.
     *
     * @param pairedCount     |I| — 두 배열 모두 null이 아닌 지점 수
     * @param excludedB       B에서 null인 지점 수
     * @param excludedN       N에서 null인 지점 수
     * @param medianB         median{g_i(B): i∈I} — <b>I 제한</b> 중앙값. 응답의 {@code median}(그 행의 유효 지점 전부)과 다를 수 있다
     * @param medianN         median{g_i(N): i∈I}
     * @param reductionPp     round4(medianB − medianN) (양수가 축소). 규칙 2항은 이 값 ≥ JUDGMENT_MEDIAN_REDUCTION_PP — 두 중앙값이 이미 4자리라
     *                        차도 0.0001의 배수이고, 반올림은 부동소수 잡음만 없앤다(예: 2.0012 − 0.0012 = 1.9999999999999998을 2.0으로)
     * @param reducedPoints   round4(g_i(B) − g_i(N)) ≥ JUDGMENT_POINT_IGNORE_PP인 i의 수(무시 폭 이상 작아진 지점만)
     * @param tiedPoints      |round4(g_i(B) − g_i(N))| &lt; JUDGMENT_POINT_IGNORE_PP인 i의 수 — 무시 폭 안은 동률이고 축소가 아니다
     * @param worsenedPoints  round4(g_i(B) − g_i(N)) ≤ −JUDGMENT_POINT_IGNORE_PP인 i의 수 — reduced + tied + worsened = pairedCount
     * @param requiredPoints  ceil(JUDGMENT_REDUCED_POINT_RATIO × |I|)
     * @param judgeable       1항 — |I| ≥ JUDGMENT_MIN_PAIRED_POINTS
     * @param medianReduced   2항 — judgeable이고 reductionPp ≥ 문턱
     * @param pointsReduced   3항 — judgeable이고 reducedPoints ≥ requiredPoints
     * @param directionHolds  4항의 셀 쪽 조건 — judgeable이고 round4(medianB − medianN) ≥ 2 × JUDGMENT_POINT_IGNORE_PP(그 밖의 크기 문턱 없음)
     */
    public record GapCellVerdict(int pairedCount, int excludedB, int excludedN, Double medianB, Double medianN, Double reductionPp,
                                 int reducedPoints, int tiedPoints, int worsenedPoints, int requiredPoints,
                                 boolean judgeable, boolean medianReduced, boolean pointsReduced, boolean directionHolds) {
    }

    /** 두 셀을 결합한 격차 판정({@link #judgeGap}). {@code reason}은 결말에 이른 조항을 한 줄로 적는다. */
    public record GapVerdict(GapOutcome outcome, String reason, GapCellVerdict judgment, GapCellVerdict direction) {
    }

    /**
     * 두 행의 유효 지점 교집합 위 <b>지점별 차의 중앙값</b>({@link #SENSITIVITY_RULE}의 추정량, {@link #medianOfPairedDifferences}).
     * 두 중앙값의 차({@link GapCellVerdict#reductionPp})와 다른 양이다.
     */
    public record PairedDifference(int pairedCount, int excludedA, int excludedB, Double median) {
    }

    /**
     * 규칙 1~3항과 4항의 셀 쪽 조건을 한 셀에서 계산한다. 입력은 {@link #GAP_VS_PABFD}의 {@code points}(길이 {@link #SWEEP_POINTS}, null 자리 보존) 둘이다.
     *
     * <p>중앙값은 교집합 I 위에서 내고 응답과 같은 4자리 반올림을 한다 — 응답의 {@code median}은 그 행의 유효 지점 전부로 낸 값이라
     * 유효 지점 집합이 다르면 여기 값과 다르다. 판정은 이 값으로 한다.</p>
     *
     * @throws IllegalArgumentException 배열이 null이거나 길이가 SWEEP_POINTS가 아니면 — 지점 지수 i가 대응하지 않는 배열은 판정 자료가 아니다
     */
    public static GapCellVerdict judgeCell(List<Double> baseline, List<Double> next) {
        requireSweep(baseline, "B");
        requireSweep(next, "N");
        List<Double> bOnI = new java.util.ArrayList<>();
        List<Double> nOnI = new java.util.ArrayList<>();
        int excludedB = 0, excludedN = 0, reduced = 0, tied = 0, worsened = 0;
        for (int i = 0; i < SWEEP_POINTS; i++) {
            Double b = baseline.get(i), n = next.get(i);
            if (b == null) excludedB++;
            if (n == null) excludedN++;
            if (b == null || n == null) continue;
            bOnI.add(b);
            nOnI.add(n);
            double d = round4(b - n);
            if (d >= JUDGMENT_POINT_IGNORE_PP) reduced++;
            else if (Math.abs(d) < JUDGMENT_POINT_IGNORE_PP) tied++;
            else worsened++;
        }
        int paired = bOnI.size();
        boolean judgeable = paired >= JUDGMENT_MIN_PAIRED_POINTS;
        Double medianB = median(bOnI), medianN = median(nOnI);
        Double reduction = (medianB == null || medianN == null) ? null : round4(medianB - medianN);
        int required = (int) Math.ceil(JUDGMENT_REDUCED_POINT_RATIO * paired);
        boolean medianReduced = judgeable && reduction != null && reduction >= JUDGMENT_MEDIAN_REDUCTION_PP;
        boolean pointsReduced = judgeable && reduced >= required;
        boolean directionHolds = judgeable && reduction != null && reduction >= 2 * JUDGMENT_POINT_IGNORE_PP;
        return new GapCellVerdict(paired, excludedB, excludedN, medianB, medianN, reduction, reduced, tied, worsened, required,
                judgeable, medianReduced, pointsReduced, directionHolds);
    }

    /**
     * 규칙 1~4항의 결합. 한 셀이라도 판정 불가면 전체 판정 불가(성공으로 쓰지 않는다). 판정 셀이 2·3항을 모두 만족하고 방향 셀에서
     * round4(medianB − medianN) ≥ 2 × JUDGMENT_POINT_IGNORE_PP이면 성공, 그 밖은 실패 — 판정판만 좋아진 결과는 실패로 기록한다.
     */
    public static GapVerdict judgeGap(GapCellVerdict judgment, GapCellVerdict direction) {
        if (judgment == null || direction == null) throw new IllegalArgumentException("판정 셀과 방향 셀의 결과가 모두 필요하다");
        if (!judgment.judgeable() || !direction.judgeable()) {
            return new GapVerdict(GapOutcome.UNJUDGEABLE, "1항 — 교집합 |I| 가 " + JUDGMENT_MIN_PAIRED_POINTS + " 미만인 셀이 있다(판정 셀 "
                    + judgment.pairedCount() + ", 방향 셀 " + direction.pairedCount() + "). 성공도 실패도 아니다", judgment, direction);
        }
        if (!judgment.medianReduced()) {
            return new GapVerdict(GapOutcome.FAIL, "2항 — 판정 셀 중앙값 축소 " + judgment.reductionPp() + "%p 가 문턱 " + JUDGMENT_MEDIAN_REDUCTION_PP + "%p 에 못 미친다",
                    judgment, direction);
        }
        if (!judgment.pointsReduced()) {
            return new GapVerdict(GapOutcome.FAIL, "3항 — 판정 셀 축소 지점 " + judgment.reducedPoints() + " 이 필요 " + judgment.requiredPoints() + " 에 못 미친다(동률 "
                    + judgment.tiedPoints() + " · 악화 " + judgment.worsenedPoints() + ")", judgment, direction);
        }
        if (!direction.directionHolds()) {
            return new GapVerdict(GapOutcome.FAIL, "4항 — 방향 셀 중앙값이 무시 폭의 두 배 이상 줄지 않았다(B " + direction.medianB() + " → N " + direction.medianN()
                    + "). 판정판만 좋아진 결과는 판이 우리 엔진 쪽으로 기울었을 가능성을 배제하지 못한다", judgment, direction);
        }
        return new GapVerdict(GapOutcome.PASS, "1~4항 충족 — 판정 셀 축소 " + judgment.reductionPp() + "%p(지점 " + judgment.reducedPoints() + "/" + judgment.pairedCount()
                + "), 방향 셀 " + direction.medianB() + " → " + direction.medianN(), judgment, direction);
    }

    /**
     * {@link #SENSITIVITY_RULE}의 추정량 — 두 행의 유효 지점 교집합 I' 위 지점별 차 {@code a_i − b_i}의 중앙값. 교집합이 비면 median은 null.
     * 판정량(두 중앙값의 차)이 아니라 수준량이다.
     */
    public static PairedDifference medianOfPairedDifferences(List<Double> a, List<Double> b) {
        requireSweep(a, "a");
        requireSweep(b, "b");
        List<Double> diffs = new java.util.ArrayList<>();
        int excludedA = 0, excludedB = 0;
        for (int i = 0; i < SWEEP_POINTS; i++) {
            Double x = a.get(i), y = b.get(i);
            if (x == null) excludedA++;
            if (y == null) excludedB++;
            if (x != null && y != null) diffs.add(x - y);
        }
        return new PairedDifference(diffs.size(), excludedA, excludedB, median(diffs));
    }

    /** null을 뺀 값들의 중앙값을 응답과 같은 4자리 반올림으로. 비면 null. 테스트의 손 구현을 대신하는 단일 정의다. */
    public static Double median(List<Double> values) {
        List<Double> sorted = values.stream().filter(java.util.Objects::nonNull).sorted().toList();
        if (sorted.isEmpty()) return null;
        int mid = sorted.size() / 2;
        double m = sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
        return round4(m);
    }

    private static Double round4(double v) {
        if (!Double.isFinite(v)) return null;
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static void requireSweep(List<Double> points, String name) {
        if (points == null || points.size() != SWEEP_POINTS) {
            throw new IllegalArgumentException(name + " 의 지점 배열은 길이 " + SWEEP_POINTS + "(null 자리 보존) 이어야 한다: "
                    + (points == null ? "null" : "길이 " + points.size()));
        }
    }

    /**
     * 한계 전력 눈금 민감도 3열의 {@code marginalScaleWatts} — 250(포화 발현: {@link #MAX_REAL_MARGINAL_WATTS_AT_JUDGMENT} 아래) ·
     * 1000(무포화) · 0(0 이하 = 노드 정격 분모, 구 방식). 한 열은 "달라졌다"까지만 말하고 방향을 가르지 못한다 — 세 열이어야
     * 포화 때문인지 눈금 자체 때문인지 갈리고, 0 열은 눈금 도입 변경 자체의 사후 검증이 된다.
     */
    public static final List<Double> SENSITIVITY_MARGINAL_SCALE_WATTS = List.of(250.0, 1000.0, 0.0);

    /** 민감도 열 id 접두 — {@code power-s250}·{@code power-s1000}·{@code power-s0}. */
    public static final String SENSITIVITY_COLUMN_PREFIX = "power-s";

    /**
     * 역산 차단 열 id — {@code idleFromMeasurement=false}만 다른 전력 모델을 쓴 {@code power} 엔진. 같은 판·같은 지점에서 역산
     * 유무만 갈린 짝 비교가 된다(판을 나누면 짝이 깨진다). {@link #SENSITIVITY_MARGINAL_SCALE_WATTS}의 0 열과 함께 <b>독립 증거가
     * 아니다</b> — 이미 소비된 판정을 같은 자료로 재확인하는 열이라 라벨에 그 사실을 적는다.
     */
    public static final String IDLE_RECONSTRUCTION_DISABLED_COLUMN = "power-idle-fixed";

    /**
     * 민감도 검정 문장 — 문턱은 하나({@link #JUDGMENT_MEDIAN_REDUCTION_PP}와 같은 2.0pp): 우리가 개선이라 부르는 크기만큼 노브가
     * 흔들면 계기가 교란된 것이다. 이하는 "영향의 증거를 못 찾음"이고 부재 주장이 아니다(귀무 채택 금지).
     */
    public static final String SENSITIVITY_RULE =
            "민감도 검정. 판정 셀에서 power 행과 power-s250 행의 " + GAP_VS_PABFD + " 유효 지점 교집합 I' = { i : g_i(power) ≠ null ∧ g_i(s250) ≠ null } 을 "
                    + "먼저 잡고, median{ g_i(power) − g_i(s250) : i∈I' } 가 JUDGMENT_MEDIAN_REDUCTION_PP 를 넘으면 격차 수준이 눈금에 의존한다고 읽는다 "
                    + "(두 행의 중앙값을 따로 내어 빼지 않는다 — 유효 지점 집합이 다르면 서로 다른 지점을 견주게 된다). |I'| 와 각 행의 제외 지점 수를 함께 적는다. "
                    + "넘지 않으면 이 판에서 영향의 증거를 못 찾은 것이며 영향이 없다는 주장이 아니다. "
                    + "판정량은 두 실행의 중앙값 차(Δ)이고 이 검정은 한 실행의 수준량을 재므로, 수준 의존이 곧 판정 의존은 아니다 — 판정의 눈금 의존은 "
                    + "기준선 B 와 새 실행 N 을 같은 눈금 열에서 각각 내어 Δ 를 두 눈금에서 견주어야 하며, 그 자료가 없으면 '판정량에 대해서는 재지 않았다'로 적는다. "
                    + "세 눈금 — 기본 눈금 500W(power 열, 엔진 설정 기본값)·power-s250·power-s1000 — 의 중앙값이 눈금에 대해 단조이면 그 사실과 전 구간 폭을 적고, "
                    + "수준의 눈금 의존과 부호의 눈금 무관을 갈라 서술한다. power-s0(정격 분모)은 이 사다리 밖이다. "
                    + "power-s1000 은 포화 없는 눈금, power-s0 은 노드 정격 분모(구 방식)이고 power-idle-fixed 는 역산 차단이다 — "
                    + "power-s0 과 power-idle-fixed 는 같은 자료를 재확인하는 열이라 독립 증거가 아니다.";

    /**
     * 오목 판 결론 문안 — 결과를 보기 전에 형식을 고정한다. 이 판은 원인을 지목하지 않는다.
     */
    public static final String CONCAVE_CONCLUSION_TEMPLATE =
            "오목 판 결론 형식. β=" + CONCAVE_POWER_EXPONENT + " 판의 판정 셀과 같은 좌표 셀 power 행 " + GAP_VS_PABFD + " 중앙값은 X%p(β=1.0 판 Y%p), "
                    + "차는 |X−Y| = Z%p 로 JUDGMENT_MEDIAN_REDUCTION_PP [이상/미만]이며 부호가 [같다/다르다] — 관측 범위에서 부호 일치는 통과하기 쉬운 조건이므로 결론은 Z 로 적는다. "
                    + "역산 경로 기여는 같은 셀의 power-idle-fixed − power = W%p 로 적고 β=1.0 판의 같은 차와 나란히 둔다(부호가 판마다 다를 수 있어 크기와 부호를 함께 적는다). "
                    + "이 판은 생성 전력·역산 유휴·오라클 세 경로가 함께 바뀌므로 원인을 지목하지 않으며, 결론의 적용 범위가 β<1 생성식까지 뻗는지만 서술한다.";

    /**
     * <b>판정 시점</b>의 등록 판 집합에서 잰 실질 한계 전력 최댓값(W) — 고정값. 판정 전제({@code S ≥ 이 값})는 이 값에만 걸린다.
     * 판이 늘어도 바뀌지 않으며, 바꾸는 것은 판정 규칙 개정이다. 현재 전 판의 실측은 {@link #MAX_REAL_MARGINAL_WATTS}가 따로 든다.
     */
    public static final double MAX_REAL_MARGINAL_WATTS_AT_JUDGMENT = 316.0;

    /**
     * 등록된 판 전부에서 실측한 {@code PowerModel.realMarginalPower}의 <b>최댓값(W)</b>.
     *
     * <p>등록된 판 전부 × 12지점 × 전 노드 × 두 워크로드 파일이 쓰는 요구 코어 집합을 모두 훑어 잰 값이다.
     * 계약 테스트가 이 수를 실측과 대조하므로, 판이나 생성식이 바뀌면 테스트가 먼저 빨간불이 된다.</p>
     *
     * <p><b>무엇을 위한 수인가</b> — 한계 전력 점수의 분모를 노드 정격에서 고정 W 눈금 S로 바꿀 때,
     * {@code S}가 이 값보다 작으면 판에서 {@code realMarginalPower > S}인 지점이 생겨 점수가 0으로
     * 포화한다. 포화는 노드를 구별하지 못하게 만드는 <b>다른 결함</b>이라, 일률 편향 하나만 고치는
     * 변경이 되려면 {@code S ≥ MAX_REAL_MARGINAL_WATTS}여야 한다. 운영에서는 정격이 큰 미계측 노드의
     * 대체값이 S를 넘을 수 있고 그때는 점수가 0으로 포화한다 — 노브로 올려 대응한다.</p>
     *
     * <p><b>이 값은 판 데이터의 사실이고 판정 규칙이 아니다.</b> 기본 설정 모델(역산 켬) 기준이라 역산 차단 열의 값은 보증 밖이다. 판이 늘면 실측과 함께 갱신되며 규칙 축에는 들어가지 않는다 —
     * 판정 전제가 걸리는 고정값은 {@link #MAX_REAL_MARGINAL_WATTS_AT_JUDGMENT}다. 갱신값이 기본 {@code marginalScaleWatts}(500W)를
     * 넘으면 포화가 기본 설정에서 발현한다는 뜻이므로 그 판의 도입을 보류하고, 기본 S 변경은 판정 규칙 개정으로 별도 처리한다.</p>
     */
    public static final double MAX_REAL_MARGINAL_WATTS = 348.22028482806877;   // 오목 판(β=0.625) bm-14 4코어 t=2400s — 유휴 기준선 일원화 뒤. 그 전 345.5437131040917, 오목 판 이전 316.0

    /**
     * 미계측 비율의 전략 간 격차가 이 값(%p)을 넘으면 전력·온도 비교의 유효성을 의심한다.
     *
     * <p>이 임계로 <b>판정을 저장하지 않는다</b> — 임계가 바뀌면 이미 캡처된 자료가 어느 규칙으로
     * 판정된 것인지 알 수 없게 된다. 격차는 측정치로 싣고, 이 상수는 해석 문장을 만들 때만 쓴다.</p>
     */
    public static final double UNMEASURED_SPREAD_CAUTION_PP = 10.0;

    /** 미계측 격차가 임계를 넘을 때 셀이 스스로 만드는 해석 문장. 각주에 의존하지 않는다. */
    public static String unmeasuredCaution(double spreadPercentPoints, String highest, String lowest) {
        return String.format(
                "미계측 노드 배치 비율이 전략 간 %.1f%%p 차이난다 (%s 최고, %s 최저) — "
                        + "전력·온도 순위가 알고리즘이 아니라 센서 공백을 반영할 수 있다",
                spreadPercentPoints, highest, lowest);
    }

    /**
     * 응답이 스스로 밝히는 한계.
     *
     * <p>세 조각을 반드시 담는다 — "실측이 아니", "생성식", "한 대". 계약 테스트가 이 세 조각을 강제하므로
     * 문장을 다시 써도 이 사실들은 빠질 수 없다. 그리고 이 API가 <b>무엇을 증명하지 않는지</b>를 한 문장으로
     * 적는다 — 각주로 밀어 두면 인용될 때 떨어져 나간다.</p>
     */
    public static final String LIMITATION =
            "이 비교의 전력·온도 값은 실측이 아니다 — 합성 클러스터 생성식의 출력이며, 그 계수(노드별 유휴 전력·정격·"
                    + "엔진에 알려 준 정격·생성식 지수·온도 계수 0.3)는 실데이터 없이 정한 가정이다. 평가자도 같은 생성식을 "
                    + "쓰므로 확인되는 것은 '엔진의 모델 가정과 생성식이 얼마나 다른가'이지 '생성식이 실제 서버와 같은가'가 아니다. "
                    + "지수를 1 보다 크게 둔 판은 실서버 근사가 아니라 엔진 가정이 틀린 방향의 응력 시험이고, 문헌에는 반대 "
                    + "방향의 보고도 있다. 판 사이의 차이를 원인으로 읽으려면 그 판들이 주 결과판과 노드 구성이 같아야 한다. "
                    + "전원 제어가 없는 현 시스템(UNAVAILABLE)에서 통합으로 아낄 전력은 정의상 0 이고, 아낄 수 있는 "
                    + "판(AVAILABLE)은 존재하지 않는 전원 제어를 가정한다. 온도 축의 대조군은 재평가 없는 최저 온도 우선 "
                    + "한 종뿐이고(다른 대조군은 열을 점수에 넣지 않으며, 온도 제약 C3 는 제약을 켠 전 전략이 같은 상한으로 받는다 — "
                    + "온도 상한을 낮춘 열 하나만 낮춘 상한으로 받고, 제약 해제 변종은 그 사실을 열마다 밝힌다), "
                    + "기본 임계 75℃ 에서는 배치 후 예상 온도 제약이 시원한 노드에 작동하지 않아 그 전략은 초기 온도로 정렬한 "
                    + "통합에 가깝다 — 자원 최밀집(bfd) 열과의 차이가 곧 정렬 기준에 온도를 넣은 효과의 전부다. "
                    + "온도 상한을 기본 경고 온도 65℃ 로 낮춘 열은 그 제약이 작동하는 자리와 배치 대가를 보이지만, 판의 온도가 생성식 값이라 "
                    + "실장비 온도 제어의 효과가 아니며 판정 자격이 없다. "
                    + "따라서 열 지표는 헤드라인이 아니며, 이 API 는 "
                    + "전력 기반 배치·온도 제어의 효과를 결론 수준으로 증명하지 않는다. "
                    + "배포 환경은 실존 노드가 한 대라 실환경 비교는 성립하지 않는다.";
}
