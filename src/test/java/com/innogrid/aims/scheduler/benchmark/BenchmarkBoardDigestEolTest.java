package com.innogrid.aims.scheduler.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 판 다이제스트와 개행 문자 — 빌드 OS 에 따라 값이 갈리지 않는지.
 *
 * <p>판 다이제스트는 파일 내용의 SHA-256 앞 12자다. 저장소는 LF 로 보관하지만 {@code core.autocrlf=true} 인 Windows
 * 체크아웃은 작업 트리와 jar 안의 yml 을 CRLF 로 만들어, 예전에는 <b>같은 판이 빌드 OS 에 따라 다른 다이제스트</b>를
 * 냈다. 지금은 개행을 LF 로 정규화한 뒤 해시하므로 값은 저장소 바이트의 해시와 같고, 아래 리터럴은
 * {@code git show HEAD:파일 | sha256sum} 으로 누구나 재계산할 수 있다.</p>
 *
 * <p><b>2026-09-11 이전 Windows 빌드 jar 가 낸 표는 아래 옛 값을 가리킨다.</b> 계약 문서 §10 에도 같은 표가 있다.</p>
 * <pre>
 * 판                옛(CRLF 빌드)     새(= 저장소 LF)
 * main              add880e1f395      fb011848258b
 * adversarial       4ce3ca80c771      b89c82edff1c
 * sparse            cecd68498459      f2109dcf47e1
 * convex            84f26146719d      5e71beea2592
 * misrated          a1d780187595      e935750a1637
 * convex-misrated   bdbbfd0f5b06      d199d46fa3b0
 * </pre>
 *
 * <p><b>리터럴은 판 파일 바이트에 묶여 있다 — 의도다.</b> 판 파일을 한 글자라도 고치면(주석 문구 포함) 해당 단언이
 * 깨진다. 그때는 (1) 새 값으로 리터럴을 갱신하고 (2) 계약 문서 §10 에 옛→새 행을 추가하고 (3) 커밋 메시지에 판 이름을
 * 적는다. 판을 바꾸면 눈에 보이게 하려는 장치이므로 리터럴만 조용히 고치지 않는다.</p>
 */
class BenchmarkBoardDigestEolTest {

    private final BenchmarkClusters clusters = new BenchmarkClusters();

    @DisplayName("판 다이제스트는 개행을 LF 로 정규화한 내용의 해시라 CRLF 체크아웃에서도 같은 값이다")
    @Test
    void digest_is_the_hash_of_lf_normalized_content() throws IOException {
        byte[] raw = new ClassPathResource("scheduler/benchmark-cluster-main.yml").getInputStream().readAllBytes();
        String normalized = new String(raw, StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertEquals(sha12(normalized.getBytes(StandardCharsets.UTF_8)), clusters.digest(BenchmarkClusters.MAIN));
        assertEquals(clusters.digest(BenchmarkClusters.MAIN),
                BenchmarkClusters.digestOfContent(normalized.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)),
                "CRLF 로 바꿔 넣어도 같은 값");
    }

    @DisplayName("CRLF 와 LF 로 적은 같은 내용은 파일·OS 와 무관하게 같은 다이제스트다")
    @Test
    void crlf_and_lf_content_digest_alike_regardless_of_checkout() {
        // LF 체크아웃(Linux·CI)에서는 판 파일이 이미 LF 라 리터럴 테스트가 정규화 회귀를 못 잡는다 — 이 단언이 OS 무관 가드다
        byte[] lf = "name: x\nnodes:\n  - { mdcId: a }\n".getBytes(StandardCharsets.UTF_8);
        byte[] crlf = "name: x\r\nnodes:\r\n  - { mdcId: a }\r\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(BenchmarkClusters.digestOfContent(lf), BenchmarkClusters.digestOfContent(crlf));
        assertEquals(sha12(lf), BenchmarkClusters.digestOfContent(crlf), "정규화 결과는 LF 바이트의 해시");
        assertNotEquals(BenchmarkClusters.digestOfContent(lf),
                BenchmarkClusters.digestOfContent("name: x\nnodes:\n  - { mdcId: b }\n".getBytes(StandardCharsets.UTF_8)),
                "내용이 다르면 다르다");
    }

    /**
     * 판 id → 저장소 LF 바이트 해시 리터럴. <b>판 추가 절차</b>: (1) 규칙 커밋을 먼저(판정 상수가 바뀌면 그 커밋에서),
     * (2) 판 yml 과 등록부 레코드, (3) 이 맵에 한 줄, (4) 계약 §10 에 행, (5) 증거 묶음 재생성. 등록부와 이 맵의 키 집합이
     * 다르면 아래 테스트가 깨진다 — 판을 더하고 다이제스트를 고정하지 않은 채 지나갈 수 없다.
     */
    private static final Map<String, String> BOARD_DIGESTS = Map.of(
            BenchmarkClusters.MAIN, "fb011848258b",
            BenchmarkClusters.ADVERSARIAL, "b89c82edff1c",
            BenchmarkClusters.SPARSE, "f2109dcf47e1",
            BenchmarkClusters.CONVEX, "5e71beea2592",
            BenchmarkClusters.MISRATED, "e935750a1637",
            BenchmarkClusters.CONVEX_MISRATED, "d199d46fa3b0",
            BenchmarkClusters.LINK, "53a71b0de49f",
            BenchmarkClusters.MAIN_INDEPENDENT, "ef2d4c7b28b7",
            BenchmarkClusters.CONVEX_MISRATED_INDEPENDENT, "ad4eb53bbcad",
            BenchmarkClusters.CONCAVE, "8e45125a043f");

    @DisplayName("등록된 판 전부의 다이제스트가 저장소 LF 바이트의 해시 리터럴과 같다 — 판을 더하면 리터럴도 함께 늘어야 한다")
    @Test
    void every_registered_board_digest_matches_a_repository_literal() {
        assertEquals(Set.copyOf(clusters.boardIds()), BOARD_DIGESTS.keySet(), "등록부와 리터럴 맵의 판 집합이 다르다 — 판 추가 절차를 따른다");
        for (String id : clusters.boardIds()) {
            assertEquals(BOARD_DIGESTS.get(id), clusters.digest(id), id);
        }
    }

    @DisplayName("엔진 설정 다이제스트는 정규 문자열 그대로의 해시다 — 개행이 없는 입력이라 정규화 대상이 아니다")
    @Test
    void engine_config_digest_is_unchanged() {
        assertEquals(sha12("a|b|c".getBytes(StandardCharsets.UTF_8)), BenchmarkClusters.digestOf("a|b|c"));
        assertNotEquals(BenchmarkClusters.digestOf("nodes:\n  - a"), BenchmarkClusters.digestOf("nodes:\r\n  - a"),
                "문자열 다이제스트는 예전과 같이 바이트 그대로");
    }

    static String sha12(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
