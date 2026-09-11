package com.innogrid.aims.scheduler.benchmark;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.innogrid.aims.scheduler.metrics.SyntheticCluster;

/**
 * 비교용 가상 클러스터 여섯 벌 — 판(board)마다 <b>역할이 먼저 정해져 있다</b>.
 *
 * <p>판이 여럿인 이유는 결과를 여러 장으로 나눠 보이기 위해서가 아니라, 한 판의 결과만으로는
 * 반론을 막을 수 없기 때문이다. 주 결과판은 우리 엔진에 유리한 우연(미계측 배출구·유휴 비율
 * 0.3 가정·"큰 노드는 비효율" 편향)을 제거한 자리이고, <b>반증판은 우리 엔진이 지도록 설계</b>했고,
 * 계측 공백판은 주 결과판과 {@code ipmi} 플래그만 달라 두 판의 차이가 곧 센서 공백의 크기다.</p>
 *
 * <p>뒤의 세 판은 <b>엔진의 가정이 틀렸을 때</b>를 재는 자리다. 생성식 지수만 바꾼 볼록 판과 엔진에 알려 준
 * 정격만 어긋나게 둔 판이 요인 하나씩을 귀속시키는 부록이고, 둘을 함께 건 복합 판이 다음 엔진 변경을 판정할
 * 판이다. 셋 다 주 결과판과 <b>노드 구성이 같아</b>(그 값들을 한 글자도 바꾸지 않았다) 판 사이의 차이를
 * 요인으로 읽을 수 있다 — 모집단이 다르면 그 읽기가 성립하지 않는다.</p>
 *
 * <p>{@link #digest(String)} 가 파일 내용의 SHA-256 앞 12자를 준다. 응답이 자기 입력을 지목하게 하려는
 * 것이다 — 표에 적힌 숫자가 어느 파일에서 나왔는지 사람이 대조할 수 없으면, 파일을 고친 뒤 같은 표를
 * 다시 내놓아도 아무도 알 수 없다.</p>
 *
 * <p>지연 로딩 + 캐시 — 기동 시 파일을 읽지 않는다. 이 빈은 벤치마크 API 를 부를 때만 파일을 만지므로,
 * 운영 경로(스케줄링·수집)는 이 판들의 존재를 모른 채 돌아간다. {@code SchedulingEngine} 을 구현하지
 * 않으므로 엔진 레지스트리에도 잡히지 않는다.</p>
 */
@Component
public class BenchmarkClusters {

    /** 주 결과판 — 전략 순위를 이 판에서 보고한다. */
    public static final String MAIN = "main";

    /** 반증(적대)판 — 우리 엔진이 지도록 설계한 판. */
    public static final String ADVERSARIAL = "adversarial";

    /** 계측 공백판 — 주 결과판과 {@code ipmi} 플래그만 다른 판. */
    public static final String SPARSE = "sparse";

    /** 볼록 생성식판 — 주 결과판과 생성식 지수만 다른 판. */
    public static final String CONVEX = "convex";

    /** 정격 어긋남판 — 주 결과판과 엔진에 알려 준 정격만 다른 판. */
    public static final String MISRATED = "misrated";

    /** 복합판 — 볼록 생성식과 정격 어긋남이 함께 걸린 판. 판정 셀이 여기 있다. */
    public static final String CONVEX_MISRATED = "convex-misrated";

    /** 다이제스트로 남길 자릿수. 12자면 여섯 판을 구분하기에 충분하고 표에 그대로 실을 수 있다. */
    private static final int DIGEST_LENGTH = 12;

    /** 판 목록의 <b>고정 순서</b>. 표의 열 순서가 호출마다 달라지면 두 실행 결과를 눈으로 대조할 수 없다. */
    private static final List<String> ORDER =
            List.of(MAIN, ADVERSARIAL, SPARSE, CONVEX, MISRATED, CONVEX_MISRATED);

    private static final Map<String, String> LOCATIONS = Map.of(
            MAIN, "classpath:scheduler/benchmark-cluster-main.yml",
            ADVERSARIAL, "classpath:scheduler/benchmark-cluster-adversarial.yml",
            SPARSE, "classpath:scheduler/benchmark-cluster-sparse.yml",
            CONVEX, "classpath:scheduler/benchmark-cluster-convex.yml",
            MISRATED, "classpath:scheduler/benchmark-cluster-misrated.yml",
            CONVEX_MISRATED, "classpath:scheduler/benchmark-cluster-convex-misrated.yml");

    private static final Map<String, String> ROLES = Map.of(
            MAIN, "주 결과판 — 노드 14대 전수 계측(유휴 5대 · 가동 9대). 미계측 배출구를 없애고, 유휴 전력 비율을 "
                    + "0.20~0.45 로 흩뿌려 엔진 가정(정격의 30%)과 어긋나게 두고, 정격과 코어당 전력의 상관을 "
                    + "양방향(-0.21)으로 두었다. 전략 순위는 이 판에서 보고한다.",
            ADVERSARIAL, "반증(적대)판 — 우리 엔진이 지도록 설계했다. 유휴 전력 비율이 0.060~0.100 이라 엔진이 깨우기 "
                    + "비용을 3~5배 과대 추정하고, 코어당 생성식 전력이 PEI 순위를 거의 뒤집으며(가동 노드 상관 +0.97), "
                    + "정격이 큰 노드가 효율적이다(상관 -0.87). 여기서 이기면 판을 다시 설계해야 한다.",
            SPARSE, "계측 공백판(부록) — 주 결과판과 같은 14대인데 5대만 ipmi: false 다(가동 3 · 유휴 2). 다른 값은 "
                    + "하나도 다르지 않으므로 두 판의 결과 차이는 '센서가 있는가' 하나로만 설명된다. 그 차이가 센서 "
                    + "공백의 크기이고, 계측을 요구할 정량 근거다.",
            CONVEX, "볼록 생성식판(부록) — 주 결과판과 생성식 지수(powerExponent 1.6)만 다르다. 지수를 1 보다 크게 둔 것은 "
                    + "실서버 근사가 아니라 엔진의 선형 가정이 틀린 방향으로 기울인 응력 시험이고, 문헌에는 반대 방향의 보고도 "
                    + "있다. 엔진의 깨우기 역산은 선형식을 푼 것이라 이 판에서는 선언된 유휴 전력을 복원하지 못하고 낮게 본다 — "
                    + "노드별 차는 wakeupReconstructionCheck 에 있다. 유휴비가 높은 노드의 중·고부하에서 낭비 점수가 포화해 "
                    + "변별이 좁아질 수 있다.",
            MISRATED, "정격 어긋남판(부록) — 주 결과판과 엔진에게 알려 준 정격(believedCapacityWatts)만 다르고, 생성식이 쓰는 "
                    + "정격은 그대로다. 부호는 손으로 고르지 않는다 — 층마다 한 양을 최소로 하는 분할이 정하며(유휴 층은 깨우기 "
                    + "벌점 편향, 가동 층은 정격 합 편중) 계약 시험이 파일만 보고 재계산한다. 그래서 중립이 아니다: 부호는 코어당 "
                    + "생성식 전력과 상관이 있고(유휴 층 약 −0.8, 가동 층은 양), 상세 수치는 판 파일 머리말에 있다. 어긋남이 크게 "
                    + "흔드는 곳은 유휴 역산이 아니라 깨우기 벌점·전력 제약 여유·전력 인지 대조군의 선택이다.",
            CONVEX_MISRATED, "판정판 — 볼록 생성식과 정격 어긋남이 함께 걸린 판이고, 다음 엔진 변경의 효과를 판정할 셀(부하율 "
                    + "60% · 전원 제어 있음)이 여기 있다. 두 부록판이 요인 하나씩을 귀속시키고 판정은 이 판에서 한다. 두 오차항은 "
                    + "노드에 따라 상쇄하므로(과소 믿음 노드에서는 부호가 반대다) 유휴 역산이 선언값에 가까워지는 행을 '항등이 "
                    + "살아남았다' 로 읽으면 안 된다. 판정 셀은 현 시스템에 없는 전원 제어를 가정한 값이라 배포 환경에서 "
                    + "재현되지 않는다.");

    private final Map<String, SyntheticCluster> loaded = new ConcurrentHashMap<>();
    private final Map<String, String> digests = new ConcurrentHashMap<>();

    /** 판 목록(고정 순서: main, adversarial, sparse, convex, misrated, convex-misrated). */
    public List<String> boardIds() {
        return ORDER;
    }

    /** 판 하나. 모르는 {@code boardId} 는 {@link IllegalArgumentException}. */
    public SyntheticCluster get(String boardId) {
        return loaded.computeIfAbsent(require(boardId), id -> SyntheticCluster.load(LOCATIONS.get(id)));
    }

    /** 판의 역할 설명(응답에 싣는다). */
    public String roleOf(String boardId) {
        return ROLES.get(require(boardId));
    }

    /**
     * 파일 내용의 SHA-256 앞 12자 — 응답이 자기 입력을 지목한다.
     *
     * <p><b>개행을 LF 로 정규화한 뒤</b> 해시한다. 저장소는 LF 로 보관하지만 {@code core.autocrlf=true} 인 Windows
     * 체크아웃은 작업 트리와 jar 안의 yml 을 CRLF 로 만들어, 정규화가 없으면 같은 판이 빌드 OS 에 따라 다른
     * 다이제스트를 냈다(main: Windows 빌드 {@code add880e1f395} ↔ LF {@code fb011848258b}). 정규화한 값은 저장소
     * 바이트의 해시와 같으므로 LF 로 빌드한 기록과는 그대로 이어진다. {@code .gitattributes} 도 같은 경로를 LF 로
     * 고정하지만, 다이제스트는 체크아웃 설정에 기대지 않고 코드에서 보장한다.</p>
     */
    public String digest(String boardId) {
        return digests.computeIfAbsent(require(boardId), id -> sha256Prefix(LOCATIONS.get(id)));
    }

    private static String require(String boardId) {
        if (boardId == null || !LOCATIONS.containsKey(boardId)) {
            throw new IllegalArgumentException("모르는 비교판입니다: " + boardId + " (쓸 수 있는 판: " + ORDER + ")");
        }
        return boardId;
    }

    /**
     * 임의의 정규화 문자열의 SHA-256 앞 12자. 클러스터 파일과 <b>같은 규칙</b>으로 계산한다 — 응답에 실린
     * 두 다이제스트(입력 파일 · 엔진 설정)를 사람이 같은 자로 대조할 수 있어야 한다.
     */
    public static String digestOf(String canonical) {
        return prefixOf(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 판 파일 내용의 다이제스트 — 개행을 LF 로 정규화한 UTF-8 바이트의 해시. 시험이 같은 식으로 기대값을 만든다. */
    public static String digestOfContent(byte[] fileBytes) {
        String normalized = new String(fileBytes, java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
        return prefixOf(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String sha256Prefix(String location) {
        Resource resource = new DefaultResourceLoader().getResource(location);
        try (InputStream in = resource.getInputStream()) {
            return digestOfContent(in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalArgumentException("비교판 파일을 읽을 수 없습니다: " + location, e);
        }
    }

    private static String prefixOf(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(hash).substring(0, DIGEST_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);   // JDK 가 늘 제공한다
        }
    }
}
