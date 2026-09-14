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
 * <p><b>승자를 지명하는 필드를 응답에 두지 않는다.</b> API 가 승자를 계산해 주면 그 필드만 인용되고,
 * 판 이름·전원 상태 가정·부하율에서 분리된 순간 그 판정은 거짓이 된다. 대신 셀마다
 * {@code valid}·{@code reason} 을 하네스가 계산해 싣고, 판정은 사람이 판 이름과 함께 쓰게 한다.</p>
 *
 * <p><b>개정 이력(정직한 사전 등록 서술).</b> 이 클래스는 2026-09-07 에 첫 선언됐고(하네스 결과 코드보다 앞),
 * 그 뒤 여러 차례 개정됐다(횟수·내용 전량은 {@code git log --follow -- BenchmarkRules.java} 에 남아 있다 — 세는 순간
 * 낡는 숫자는 여기 적지 않는다) — 난수원 교체, 지표 이름 정정("지상진실" → "생성식 기준"), <!-- ban-ok: 개정 이력의 옛 이름 인용 --> 열 계열 지표 보조 강등,
 * 통전·격차 판정 문턱 고정, 문구 사실성 정정 등. 판정 상수(격차 문턱·지점 수 비율)는 비교 API 가 이미 실행 가능한
 * 상태에서 등록됐다. 따라서 "결과를 볼 수 없는 상태에서 규칙을 정했다" 가 아니라 <b>"규칙 클래스가 결과 코드보다
 * 먼저 존재했고 이후 개정은 전부 커밋 이력에 남아 있다"</b> 가 이 저장소가 주장할 수 있는 전부다.
 * 새 판이나 새 판정 셀을 더할 때는 그 규칙 커밋을 결과·판 파일 커밋보다 앞에 둔다. 2026-09-14 개정(방법론 변경 PR 의
 * {@code rules:} 커밋): 민감도 눈금 3값·역산 차단 열·링크 판·위상 독립 판 2장·오목 판 상수, 실측 불변조건을 판정 시점
 * 고정값과 현 실측값으로 분리, 판정 자격을 폐쇄형 문장으로 — 전부 해당 판 파일·결과 코드보다 앞선 커밋이다.</p>
 */
public final class BenchmarkRules {

    private BenchmarkRules() {
    }

    // ── 실행 격자 ─────────────────────────────────────────────

    /**
     * 부하율 네 지점(%). 30% 에서는 전 전략이 100% 성공해 표가 평평한 선이 되고,
     * 110%(수용 불가 부하)가 없으면 성공률이 전략을 구분하지 못한다.
     */
    public static final List<Integer> LOAD_RATIOS = List.of(30, 60, 90, 110);

    /**
     * 부하율의 <b>분모 정의</b>. 총 코어가 아니라 <b>정책 상한까지의 여유</b>다 —
     * 노드마다 구속 자원이 다르므로(어떤 노드는 메모리가, 어떤 노드는 CPU 가 먼저 막힌다)
     * 두 축의 최솟값을 쓴다. 이 문구도 상수이고 응답에 그대로 싣는다.
     */
    public static final String HEADROOM_DEFINITION =
            "Σ max( 0, min( cores × (정책 CPU 상한% − 실효 CPU%) / 100, "
                    + "memoryMb × (정책 메모리 상한% − 실효 메모리%) / 100 / 코어당메모리MB ) ) "
                    + "— 음수는 0 으로 자른다(정책 상한을 이미 넘은 노드의 여유는 0)";

    /**
     * 한 주기를 훑는 시각 지점 수. 시각을 하나로 고정하면 결정론적 전략은 셀마다 숫자가
     * <b>하나</b>뿐이라 분산 추정치가 0 이 되고, "3.2% 낮았다"에 오차 막대를 붙일 수 없다.
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
     * 시드 하나로 난수원을 만든다 — <b>{@link java.util.Random} 을 쓰지 않는다.</b>
     *
     * <p>{@code java.util.Random} 은 시드를 {@code (seed ^ 0x5DEECE66D)} 로만 섞고 첫 난수에
     * <b>상위 비트</b>를 쓴다. 작은 시드는 상위 비트가 거의 같아 <b>1~31 전부 첫 값이 한 값으로 뭉친다</b> —
     * 실측하면 {@code nextInt(4)} 의 첫 값이 31개 시드 전부 {@code 2} 이고, {@code nextDouble()} 의 첫 값은
     * 0.7298~0.7326 폭 0.003 안에 모인다. 그러면 <b>시드 31개를 돌려도 첫 뽑기의 표본은 1개</b>이고,
     * 배치 건수가 적은 판에서는 "무작위 31회"가 사실상 1회가 된다.</p>
     *
     * <p>{@link SplittableRandom} 은 시드를 SplitMix64 로 섞으므로 같은 시드 목록이 실제로 서로 다른
     * 열을 낸다(같은 실측에서 첫 값이 네 값에 9/3/12/7 로 흩어진다). 재현성은 그대로다 — 시드가 같으면
     * 열도 같다. 이 선택을 여기 둔 이유는 {@link #RANDOM_SEEDS} 와 <b>같은 자리에서 읽히게</b> 하기
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
     * <p>지금 값은 {@code SchedulerProperties.Power.activeCpuPercent} 기본값과 같은 5.0 이다. 같은 숫자를
     * 여기 다시 적는 이유는 저쪽이 <b>엔진의 판단 노브</b>이기 때문이다 — 운영에서 그 값을 조정하면 통전
     * 판정이 따라 움직여 <b>같은 배치 결정의 회계가 달라지고</b>, "가동 노드를 몇 대 늘렸나"가 알고리즘이
     * 아니라 설정의 함수가 된다. 판정 규칙은 결과보다 먼저 고정되어야 하므로 이 상수는 여기 있고, 엔진
     * 노브와 벌어지면 응답의 설정 스냅샷이 두 값을 나란히 밝힌다.</p>
     *
     * <p>이것은 "엔진에서 독립했다"는 주장이 <b>아니다</b> — 값의 출처가 엔진이고, 주 결과판의 유휴 노드는
     * 이 문턱 아래 머물도록 설계됐다. 그리고 <b>민감도가 넓다는 주장도 할 수 없다.</b> 주 결과판에서 유휴
     * 5대의 사용률 상한은 3.0%(기준 2 · 진폭 1)이고 가동 9대의 <b>하한은 5.0%</b>(bm-07, 기준 20 · 진폭 15)라,
     * 통전 집합이 불변인 구간은 <b>3.0~5.0%</b> 뿐이다. 지금 값 5.0 은 그 <b>상단 경계에 붙어 있다</b> —
     * 판정이 {@code >} 이므로 bm-07 이 골에 닿는 시각에는 통전 집합에서 빠진다. 문턱을 조금이라도 올리면
     * 회계가 달라지므로, 이 상수를 바꾸려면 판을 다시 설계해야 한다.</p>
     *
     * <p>가동 노드의 "기준 사용률 20~70%"는 진폭 ±15%p 를 더하기 전 값이다. 판 파일의 {@code swing} 을
     * 한 칸만 키워도 이 문단이 조용히 거짓이 되므로, 계약 시험이 유휴 상한 &lt; 이 상수 ≤ 가동 하한을 고정한다.</p>
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
     * <p><b>실서버 곡선의 근사가 아니다.</b> 지수를 1 보다 크게 둔 판은 "엔진 가정이 틀린 방향"으로
     * 세계를 기울인 <b>응력 시험</b>이고, 문헌에는 반대 방향(오목)의 보고도 있다. 1.4 를 피한 이유는
     * 그 값이 문헌 계수와 같은 수라 "문헌 근거" 로 오독되기 때문이고, 1.8 은 엔진 시험의
     * {@code curveAlpha} 와 같은 수라 피했다. 값 자체에 물리적 근거는 없다.</p>
     *
     * <p><b>판 파일이 진실이고 이 상수는 그 사본이다.</b> 판이 실제로 쓰는 β 는 클러스터 파일의
     * {@code powerExponent} 이고, 여기 적힌 값은 결과보다 먼저 등록해 둔 사전 선언이다 — 계약 시험이
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

    /** 오목 판 id. 판 등록부의 같은 문자열과 계약 시험이 대조한다. seed 는 하나(legacy 위상) — β 와 위상 두 축을 한 판에 걸지 않는다. */
    public static final String CONCAVE_BOARD = "concave";

    /**
     * 링크 판 id. 주 결과판의 노드 블록을 바이트 그대로 재사용하고 {@code links:} 절과 워크로드 데이터 MDC 순환 배정만 더한 판이다.
     * 링크 점수를 섞는 엔진은 {@code weighted} 하나뿐이라 이 판에서 값이 달라지는 열은 {@code weighted} 계열뿐이며, 비교는 판 사이가
     * 아니라 <b>같은 판 안의 게이트 on/off 열 쌍</b>({@code weighted} · {@code weighted-affinity})으로 한다 — netScore 경로의
     * 작동 확인이며 전력 판정과 무관하다.
     */
    public static final String LINK_BOARD = "link";

    /** 링크 게이트를 켠 가중 점수 열 id. override 는 {@code affinityEnabled=true} 하나다. */
    public static final String WEIGHTED_AFFINITY_COLUMN = "weighted-affinity";

    /**
     * 위상 독립 판 2장 — 방향판·판정판과 노드 블록이 바이트 동일하고 {@code phaseMode: independent}·{@code seeds:} 만 다르다.
     * legacy 위상은 {@code (seed·31^L + hash(nodeId)) mod 1000} 이라 nodeId 길이가 같은 판에서 seed 는 전 노드에 같은 상수만 더한다 —
     * seed 를 늘려도 표본이 늘지 않는 결함이고, 판정 경로(두 판) 에서 그 결함을 닫는 것이 이 두 판의 존재 이유다.
     */
    public static final List<String> PHASE_INDEPENDENT_BOARDS = List.of("main-independent", "convex-misrated-independent");

    /** 위상 독립 판이 선언하는 seed 수 — 판 파일 {@code seeds:} 의 길이. 값 자체는 판 파일이 진실이고 여기는 개수만 사전 등록한다. */
    public static final int PHASE_SEED_COUNT = 3;

    /**
     * 위상 독립 혼합 — SplitMix64 마무리 함수를 <b>코드 안에</b> 둔다(JDK {@code SplittableRandom} 의 값열에 기대지 않는다).
     * {@code z = seed × GOLDEN ^ hash(nodeId); z = (z ^ z>>>30) × M1; z = (z ^ z>>>27) × M2; z ^= z>>>31} 의 하위 10진 3자리를 위상으로.
     * 상수를 여기 적어 규칙 축에 봉인한다 — 상수가 바뀌면 위상 독립 판의 값이 통째로 바뀌기 때문이다.
     */
    public static final long PHASE_MIX_GOLDEN = 0x9E3779B97F4A7C15L;
    public static final long PHASE_MIX_M1 = 0xBF58476D1CE4E5B9L;
    public static final long PHASE_MIX_M2 = 0x94D049BB133111EBL;

    /**
     * 위상 구성 규칙 — 결과보다 먼저. seed 별 Board 는 서로 <b>독립 복제</b>이고 판정 규칙(교집합 I·중앙값·지점 비율·방향)은
     * 한 Board 안의 지점 지수 i 위에서만 정의된다. 한 Board 의 12지점을 seed 수만큼 겹쳐 세지 않는다. seed 사이의 결합은 Board 별
     * 중앙값 수준에서만 하며, 보고는 중앙값 n값과 최소~최대 범위(기술통계, 검정 아님) 로 한정한다 — n=3 으로는 어떤 검정도 하지 않는다.
     */
    public static final String PHASE_RULE =
            "위상 구성 규칙. phaseMode=independent 판은 seeds 마다 Board 를 하나씩 내며(boards[].phaseSeed), 각 Board 는 독립 복제다. "
                    + "판정 규칙의 지점 지수 i 는 한 Board 안에서만 정의되고 Board 사이의 지점을 합쳐 세지 않는다. "
                    + "seed 사이의 결합은 Board 별 중앙값 수준에서만 하고, 보고는 중앙값 n값과 최소~최대 범위(기술통계)로 한정한다 — "
                    + "n=3 으로는 검정하지 않는다. legacy 판(phaseMode 없음)은 seed 가 전 노드 위상에 같은 상수만 더하므로 seed 를 늘려도 표본이 아니다.";

    /**
     * 정격 어긋남 판에서 엔진에게 알려 주는 정격의 어긋남 크기 — 한쪽은 {@code ×(1+r)} 로 과대,
     * 다른 쪽은 {@code ×(1−r)} 로 과소 믿게 한다. 생성식이 쓰는 정격은 그대로다.
     *
     * <p><b>부호 규칙(판 파일이 따르고 계약 시험이 파일만 보고 재계산한다)</b> — 노드를 두 층으로
     * 나눈다. 한 층은 {@link #SWEEP_POINTS} 지점 전부 {@link #POWERED_ON_CPU_PERCENT} 아래에 머무는
     * 계측 노드(깨우기 벌점이 붙는 유일한 층)이고, 나머지가 가동 층이다. 층마다 노드를 절반(홀수면 한쪽이
     * 하나 많다)으로 갈라 한쪽은 과대, 다른 쪽은 과소 믿게 한다. 어느 절반인지는 <b>층마다 한 가지 양을
     * 최소로 하는 분할</b>로 정한다 — 유휴 층은 엔진에 실제로 들어가는 양인 깨우기 벌점 평균의 편향
     * {@code |Σ r/(1+δ) − Σ r| / Σ r}(r = 선언 유휴/정격), 가동 층은 정격 합의 편중
     * {@code |Σ₊cap − Σ₋cap| / Σcap}. 동률이면 과대 믿음 집합의 {@code nodeId} 열을 사전순으로 비교해
     * 앞선 것. <b>손으로 고르지 않는다</b> — 손 배치는 연구자 자유도이고, 그 자유도가 판정 셀의 결론을 만든다.</p>
     *
     * <p><b>수용 기준</b> — 유휴 층 벌점 편향 ≤ 1%, 가동 층 정격 합 편중 ≤ 5%(전력 제약 여유와 전력 인지
     * 대조군만 영향을 받는다). 깨우기 벌점이 {@code wake/정격} 형이라 {@code 1/(1+δ)} 로 비선형이므로
     * 개수나 정격 합이 같아도 벌점 평균은 기운다 — 주 결과판의 유휴 다섯 대는 정격 합을 맞추면 벌점 편향이
     * 2.6~4.6% 가 되어 두 양을 한 층에서 동시에 맞출 수 없다. 그래서 층마다 하나만 최소로 한다. 수용 기준에
     * 못 미치면 어긋남 비율을 한 축으로 한 번 내리고 그 사실을 남긴다.</p>
     *
     * <p><b>이 배치는 중립이 아니다</b> — 주 결과판에서 부호와 코어당 생성식 전력의 상관은 유휴 층이 약 −0.8,
     * 가동 층은 양이다. 층마다 한 양만 최소로 했기 때문이고, 세 번째 양인 이 상관까지 동시에 0 으로 만드는
     * 분할은 이 노드 구성에 없다. 그 사실을 감추지 않고 판 설명에 적는다 — 부호의 효과와 코어당 전력의
     * 효과를 갈라 읽으려면 상관이 있다는 것을 먼저 알아야 한다.</p>
     */
    public static final double CAPACITY_MISMATCH_RATIO = 0.10;

    /**
     * <b>판정 셀</b> — 다음 PR 의 효과를 판정할 자리를 결과보다 먼저 하나로 못 박는다.
     * 셀을 결과를 보고 고르면 사후 선택이 된다. 기준선 값 자체는 여기 두지 않는다 — 값은 판을 실행해야
     * 나오고, 상수에 값을 박으면 실행 순서가 커밋 순서를 강제한다.
     *
     * <p>판 등록 쪽 상수 {@link BenchmarkClusters#CONVEX_MISRATED} 와 <b>같은 문자열</b>이며, 계약 시험이
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
     * 판정 규칙의 <b>축별 정규 문자열</b> — 사전 등록을 기계로 봉인하기 위한 재료. 계약 시험이 축마다 다이제스트 리터럴을
     * 두고, 증거 묶음 매니페스트가 같은 값을 싣는다. 축을 나눈 이유는 "어느 규칙이 바뀌었나" 가 값 하나에 뭉개지지 않게
     * 하기 위해서다. 응답에는 싣지 않는다(형태 불변).
     *
     * <p><b>축이 바뀌는 커밋의 형식.</b> 제목을 {@code rules:} 로 시작하고, 본문에 축·옛 값 → 새 값·사유를 적고, 결과 코드
     * 변경을 같은 커밋에 담지 않는다. 계약 시험의 축 이력({@code RULE_AXIS_HISTORY})에 행을 더해야 시험이 통과하므로
     * 이력 누락은 기계가 막고, 커밋 순서는 리뷰가 본다.</p>
     */
    public static Map<String, String> ruleAxes() {
        java.util.LinkedHashMap<String, String> axes = new java.util.LinkedHashMap<>();
        // metrics 축은 키:방향:역할 — 키 집합만의 다이제스트(계약 시험 DECLARED_KEY_DIGEST)와는 목적이 달라 값이 같지 않다
        axes.put("metrics", METRICS.stream().map(m -> m.key() + ":" + m.direction() + ":" + m.role()).sorted()
                .collect(java.util.stream.Collectors.joining(",")));
        axes.put("sweep", "loadRatios=" + LOAD_RATIOS + "|sweepPoints=" + SWEEP_POINTS + "|randomSeeds=" + RANDOM_SEEDS
                + "|memoryMbPerCore=" + MEMORY_MB_PER_CORE + "|poweredOnCpuPercent=" + POWERED_ON_CPU_PERCENT
                + "|cooldownMinutes=" + COOLDOWN_MINUTES_FOR_BENCHMARK);
        // 판 파일 값의 사전 등록(교차 검증) — 지수·비율·위상 방식·seed 수·혼합 상수. 판 파일이 진실이고 계약 시험이 대조한다
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
                + "|unmeasuredSpreadCautionPp=" + UNMEASURED_SPREAD_CAUTION_PP);
        axes.put("banned", String.join(",", BANNED_CLAIMS));
        // 절차·정의 문장은 공백을 하나로 접어 따로 봉인한다 — 서식 편집 소음은 걸러지고, 문장을 바꿔 쓰면 이 축만 깨진다
        axes.put("prose", "headroom=" + HEADROOM_DEFINITION.replaceAll("\\s+", " ") + "|gapRule=" + GAP_JUDGMENT_RULE.replaceAll("\\s+", " ")
                + "|sensitivityRule=" + SENSITIVITY_RULE.replaceAll("\\s+", " ") + "|phaseRule=" + PHASE_RULE.replaceAll("\\s+", " ")
                + "|concaveConclusion=" + CONCAVE_CONCLUSION_TEMPLATE.replaceAll("\\s+", " ")
                + "|pairingRule=" + PAIRING_RULE.replaceAll("\\s+", " "));
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
     * 사전 등록된 지표 전량. 응답이 이 목록을 그대로 싣는다.
     *
     * <p><b>최고 온도가 없는 이유</b> — 배치 1건의 온도 상승분은 열관성 계수 × 코어 비율 × 기준 온도라
     * 16코어 노드에 2코어를 얹으면 약 +1.4℃ 인데, 시각에 따른 자연 변동이 ±4.5℃ 다(배치 효과보다 3배 크다).
     * 게다가 제약 C3 가 "배치 후 예상 온도 &gt; 임계"를 막으므로 <b>제약이 켜진 전략은 최고 온도가 전부
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
                    "기본 설정 열(override 없는 열 — 엔진·자원전용·대조군) 전부의 배치 건수가 같은 지점에서만 총량을 비교한다. "
                            + "override 열은 그 지점에서 자기 건수가 같을 때만 낸다. 건수가 다르면 건당 값을 쓴다. "
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
                    "이 값이 크면 전력 순위가 알고리즘이 아니라 센서 공백의 산물일 수 있다"));

    // ── 판정 ─────────────────────────────────────────────────

    /**
     * 총량 짝 조건의 <b>기준 집합</b> 규칙 — 결과보다 먼저. 짝 조건과 미계측 격차는 override 없는 열(기본 설정 열: 엔진 전량·자원전용 2·
     * 대조군 6 — 판정 자격 집합을 포함한다)의 배치 건수로만 정한다. override 열(링크 게이트·눈금·역산 차단)은 그 마스크를 받아 자기
     * 건수가 기준 집합의 건수와 같은 지점에서만 총량을 낸다. 이유: 짝 조건을 전 열로 잡으면 <b>열을 더하는 것만으로 기존 열의 값이
     * 바뀐다</b>(반증판·계측 공백판에서 실제로 표본 수가 5 → 1 로 줄었다) — 기존 표와의 대조가 깨진다.
     */
    public static final String PAIRING_RULE =
            "총량 짝 조건 규칙. 총량 열(generatorPowerW·activeNodeDelta·warnZoneNodeCount·measuredTempStdDev)의 짝 조건과 미계측 격차는 "
                    + "override 없는 기본 설정 열(엔진 전량·자원전용 2·대조군 6) 전부의 배치 건수가 같은 시각 지점으로 정한다. "
                    + "override 열은 그 마스크를 받아 자기 건수가 기준 집합의 건수와 같은 지점에서만 총량을 낸다. "
                    + "열을 더하는 것이 기존 열의 값을 바꾸지 않게 하기 위한 규칙이며, 격차 열(pabfd 짝)은 이 규칙과 무관하다.";

    /**
     * 총량 비교가 유효한가 — <b>기준 집합(기본 설정 열) 전부의 배치 건수가 같을 때만</b>. 호출자가 기준 집합의 건수만 넘긴다.
     *
     * <p>적게 배치하고 전력을 덜 쓰는 것은 승리가 아니다. 건수가 다르면 총량 열을
     * {@code valid=false} 로 내리고 건당 값으로 비교한다.</p>
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
     * <p><b>왜 판정 규칙에 두는가.</b> 이 식이 다음 PR 의 성공 문턱이 읽을 값을 만든다. 서비스 안에 두면
     * 결과 코드와 같은 커밋에서 바뀔 수 있어 "문턱을 결과보다 먼저 등록했다"는 주장이 식에는 걸리지 않는다.
     * 여기 두면 식 자체가 사전 등록의 대상이 되고, 단위 시험이 짝 조건·분모·부호를 잠근다 — 분모를 이 행의
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
     * 격차 중앙값이 줄어야 하는 최소 폭(%p). 이 지표가 <b>이미 백분율</b>이라 단위는 %p 다.
     * 값이 0 이면 부호만으로 성공이 되어 잡음이 곧 결론이 된다.
     */
    public static final double JUDGMENT_MEDIAN_REDUCTION_PP = 2.0;

    /**
     * 축소한 지점이 교집합에서 차지해야 하는 최소 <b>비율</b>. 개수로 적으면 교집합이 하한과 같은 실행에서
     * "열두 곳 중 열 곳"이 조용히 "열 곳 중 열 곳"(전 지점 축소)이 되어, 적어 둔 것보다 센 기준이 된다.
     * 비율로 두면 교집합이 몇 곳이든 같은 엄격도다.
     */
    public static final double JUDGMENT_REDUCED_POINT_RATIO = 10.0 / 12;

    /**
     * <b>방향만</b> 확인하는 판 — 주 결과판이다. 판정 자체는 {@link #JUDGMENT_BOARD} 한 곳에서만 하고
     * (진실 원본이 둘이 되면 유리한 판을 고르는 자유도가 생긴다), 이 판에서는 크기 문턱 없이 부호가 같은지만 본다.
     * 판정판만 좋아진 결과는 판이 우리 엔진 쪽으로 기울었을 가능성을 배제하지 못하므로 <b>실패</b>로 적는다.
     */
    public static final String DIRECTION_CHECK_BOARD = BenchmarkClusters.MAIN;

    /**
     * 격차 지표로 다음 PR 을 판정하는 <b>절차</b> — 결과보다 먼저 문장으로 고정한다.
     *
     * <p>상수 넷만 두면 "무엇을 무엇과 견주는가"가 사람의 기억에 남고, 기억은 결과를 본 뒤에 바뀐다.
     * 교집합을 먼저 잡는 것·중앙값을 같은 집합에서 내는 것·축소의 부호·불일치의 처리를 여기 적어 두고,
     * 계약 시험이 이 문장에서 네 조각이 사라지지 않게 지킨다.</p>
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
                    + "두 중앙값은 같은 집합 I 위에서 낸다. "
                    + "3. 지점 — g_i(B) − g_i(N) > 0 인 i 가 ceil(JUDGMENT_REDUCED_POINT_RATIO × |I|) 이상. "
                    + "'축소' 는 부호 있는 값이 작아지는 것이며(LOWER_IS_BETTER), 절댓값이 아니다 — 음수가 되는 것도 "
                    + "축소다. 기준선보다 엄격히 작은 지점만 센다(같으면 축소가 아니다). "
                    + "4. 방향 일치 — DIRECTION_CHECK_BOARD(주 결과판) 의 같은 셀에서 1 을 만족하고 "
                    + "median{g_i(N)} < median{g_i(B)}(같은 I 위) 이어야 한다. 크기 문턱은 두지 않는다. "
                    + "불일치면 실패로 기록한다(판정판만 좋아진 결과는 판이 우리 엔진 쪽으로 기울었을 가능성을 "
                    + "배제하지 못한다). "
                    + "고치는 대상은 일률 편향 하나이며 포화는 판정 대상이 아니다. 기준선 값은 상수에 넣지 않는다 — "
                    + "값은 판을 실행해야 나오고, 상수에 박으면 실행 순서가 커밋 순서를 강제한다. "
                    + "이 지표의 정의와 문턱 상수는 이 PR 에서만 바뀌고 다음 PR 은 참조만 한다. "
                    + "판정 자격(폐쇄형): 판정·방향 판정은 JUDGMENT_BOARD·DIRECTION_CHECK_BOARD 두 판의 power 행에서만 성립하며, "
                    + "그 밖의 판·열·지표는 전부 자격 없음이다 — 판이나 열이 늘어도 이 문장은 바뀌지 않는다.";

    /**
     * 한계 전력 눈금 민감도 3열의 {@code marginalScaleWatts} — 250(포화 발현: {@link #MAX_REAL_MARGINAL_WATTS_AT_JUDGMENT} 아래) ·
     * 1000(무포화) · 0(0 이하 = 노드 정격 분모, 구 방식). 한 열은 "달라졌다" 까지만 말하고 방향을 가르지 못한다 — 세 열이어야
     * 포화 때문인지 눈금 자체 때문인지 갈리고, 0 열은 눈금 도입 변경 자체의 사후 검증이 된다.
     */
    public static final List<Double> SENSITIVITY_MARGINAL_SCALE_WATTS = List.of(250.0, 1000.0, 0.0);

    /** 민감도 열 id 접두 — {@code power-s250}·{@code power-s1000}·{@code power-s0}. */
    public static final String SENSITIVITY_COLUMN_PREFIX = "power-s";

    /**
     * 역산 차단 열 id — {@code idleFromMeasurement=false} 만 다른 전력 모델을 쓴 {@code power} 엔진. 같은 판·같은 지점에서 역산
     * 유무만 갈린 짝 비교가 된다(판을 나누면 짝이 깨진다). {@link #SENSITIVITY_MARGINAL_SCALE_WATTS} 의 0 열과 함께 <b>독립 증거가
     * 아니다</b> — 이미 소비된 판정을 같은 자료로 재확인하는 열이라 라벨에 그 사실을 적는다.
     */
    public static final String IDLE_RECONSTRUCTION_DISABLED_COLUMN = "power-idle-fixed";

    /**
     * 민감도 검정 문장 — 문턱은 하나({@link #JUDGMENT_MEDIAN_REDUCTION_PP} 와 같은 2.0pp): 우리가 개선이라 부르는 크기만큼 노브가
     * 흔들면 계기가 교란된 것이다. 이하는 "영향의 증거를 못 찾음" 이고 부재 주장이 아니다(귀무 채택 금지).
     */
    public static final String SENSITIVITY_RULE =
            "민감도 검정. 판정 셀에서 power 행과 power-s250 행의 " + GAP_VS_PABFD + " 중앙값 차가 JUDGMENT_MEDIAN_REDUCTION_PP 를 넘으면 "
                    + "한계 전력 눈금이 결론에 영향을 준 것으로 읽는다. 넘지 않으면 이 판에서 영향의 증거를 못 찾은 것이며 영향이 없다는 주장이 아니다. "
                    + "power-s1000 은 포화 없는 눈금, power-s0 은 노드 정격 분모(구 방식)이고 power-idle-fixed 는 역산 차단이다 — "
                    + "power-s0 과 power-idle-fixed 는 같은 자료를 재확인하는 열이라 독립 증거가 아니다.";

    /**
     * 오목 판 결론 문안 — 결과를 보기 전에 형식을 고정한다. 이 판은 원인을 지목하지 않는다.
     */
    public static final String CONCAVE_CONCLUSION_TEMPLATE =
            "오목 판 결론 형식. β=" + CONCAVE_POWER_EXPONENT + " 판의 판정 셀 power 행 " + GAP_VS_PABFD + " 중앙값은 X%p(β=1.0 판 Y%p)이고 "
                    + "부호가 [같다/다르다]. 이 판은 생성 전력·역산 유휴·오라클 세 경로가 함께 바뀌므로 원인을 지목하지 않으며, "
                    + "결론의 적용 범위가 β<1 생성식까지 뻗는지만 서술한다. power-idle-fixed 열이 역산 경로의 기여를 분리한다.";

    /**
     * <b>판정 시점</b>의 등록 판 집합에서 잰 실질 한계 전력 최댓값(W) — 고정값. 판정 전제({@code S ≥ 이 값})는 이 값에만 걸린다.
     * 판이 늘어도 바뀌지 않으며, 바꾸는 것은 판정 규칙 개정이다. 현재 전 판의 실측은 {@link #MAX_REAL_MARGINAL_WATTS} 가 따로 든다.
     */
    public static final double MAX_REAL_MARGINAL_WATTS_AT_JUDGMENT = 316.0;

    /**
     * 등록된 판 전부에서 실측한 {@code PowerModel.realMarginalPower} 의 <b>최댓값(W)</b>.
     *
     * <p>등록된 판 전부 × 12지점 × 전 노드 × 두 워크로드 파일이 쓰는 요구 코어 집합을 모두 훑어 잰 값이다.
     * 계약 시험이 이 수를 실측과 대조하므로, 판이나 생성식이 바뀌면 시험이 먼저 빨간불이 된다.</p>
     *
     * <p><b>무엇을 위한 수인가</b> — 한계 전력 점수의 분모를 노드 정격에서 고정 W 눈금 S 로 바꿀 때,
     * {@code S} 가 이 값보다 작으면 판에서 {@code realMarginalPower > S} 인 지점이 생겨 점수가 0 으로
     * 포화한다. 포화는 노드를 구별하지 못하게 만드는 <b>다른 결함</b>이라, 일률 편향 하나만 고치는
     * 변경이 되려면 {@code S ≥ MAX_REAL_MARGINAL_WATTS} 여야 한다. 운영에서는 정격이 큰 미계측 노드의
     * 대체값이 S 를 넘을 수 있고 그때는 점수가 0 으로 포화한다 — 노브로 올려 대응한다.</p>
     *
     * <p><b>이 값은 판 데이터의 사실이고 판정 규칙이 아니다.</b> 판이 늘면 실측과 함께 갱신되며 규칙 축에는 들어가지 않는다 —
     * 판정 전제가 걸리는 고정값은 {@link #MAX_REAL_MARGINAL_WATTS_AT_JUDGMENT} 다. 갱신값이 기본 {@code marginalScaleWatts}(500W)를
     * 넘으면 포화가 기본 설정에서 발현한다는 뜻이므로 그 판의 도입을 보류하고, 기본 S 변경은 판정 규칙 개정으로 별도 처리한다.</p>
     */
    public static final double MAX_REAL_MARGINAL_WATTS = 345.5437131040917;   // 오목 판(β=0.625) bm-14 4코어 t=2400s. 오목 판 이전 316.0

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
     * 문장을 다시 써도 이 사실들은 빠질 수 없다. 그리고 이 API 가 <b>무엇을 증명하지 않는지</b>를 한 문장으로
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
                    + "한 종뿐이고(다른 대조군은 열을 점수에 넣지 않으며, 온도 제약 C3 는 제약을 켠 전 전략이 똑같이 받는다 — "
                    + "제약 해제 변종은 그 사실을 열마다 밝힌다), "
                    + "기본 임계 75℃ 에서는 배치 후 예상 온도 제약이 시원한 노드에 작동하지 않아 그 전략은 초기 온도로 정렬한 "
                    + "통합에 가깝다 — 자원 최밀집(bfd) 열과의 차이가 곧 정렬 기준에 온도를 넣은 효과의 전부다. "
                    + "따라서 열 지표는 헤드라인이 아니며, 이 API 는 "
                    + "전력 기반 배치·온도 제어의 효과를 결론 수준으로 증명하지 않는다. "
                    + "배포 환경은 실존 노드가 한 대라 실환경 비교는 성립하지 않는다.";
}
