package com.innogrid.aims.scheduler.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 판 다이제스트와 개행 문자 — 빌드 OS 에 따라 값이 갈리지 않는지.
 *
 * <p>판 다이제스트는 파일 내용의 SHA-256 앞 12자다. 저장소는 LF 로 보관하지만 {@code core.autocrlf=true} 인 Windows
 * 체크아웃은 작업 트리와 jar 안의 yml 을 CRLF 로 만들어, 예전에는 <b>같은 판이 빌드 OS 에 따라 다른 다이제스트</b>를
 * 냈다(main: Windows 빌드 {@code add880e1f395} ↔ LF {@code fb011848258b}). 지금은 개행을 LF 로 정규화한 뒤 해시하므로
 * 값은 저장소 바이트의 해시와 같고, 아래 리터럴은 {@code git show HEAD:파일 | sha256sum} 으로 누구나 재계산할 수 있다.</p>
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

    @DisplayName("여섯 판의 다이제스트는 저장소 LF 바이트의 해시 리터럴과 같다 — 빌드 PC 가 바뀌어도 표를 대조할 수 있다")
    @Test
    void six_board_digests_match_repository_literals() {
        assertEquals("fb011848258b", clusters.digest(BenchmarkClusters.MAIN));
        assertEquals("b89c82edff1c", clusters.digest(BenchmarkClusters.ADVERSARIAL));
        assertEquals("f2109dcf47e1", clusters.digest(BenchmarkClusters.SPARSE));
        assertEquals("5e71beea2592", clusters.digest(BenchmarkClusters.CONVEX));
        assertEquals("e935750a1637", clusters.digest(BenchmarkClusters.MISRATED));
        assertEquals("d199d46fa3b0", clusters.digest(BenchmarkClusters.CONVEX_MISRATED));
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
