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
 * 판 다이제스트와 개행 문자 — 현행 동작을 <b>결함째</b> 고정한다.
 *
 * <p>판 다이제스트는 파일 <b>원본 바이트</b>의 SHA-256 앞 12자다. 저장소는 LF 로 보관하지만 {@code core.autocrlf=true}
 * 인 Windows 체크아웃은 작업 트리와 jar 안의 yml 을 CRLF 로 만들고, {@code .gitattributes} 에 yml 규칙이 없어 이를
 * 막지 못한다. 그래서 <b>같은 판이 빌드 OS 에 따라 다른 다이제스트</b>를 낸다 — "응답이 자기 입력을 지목한다" 는
 * 성질이 빌드 PC 설정에 좌우된다. 이 시험은 그 사실(다이제스트 = 원본 바이트 해시)을 고정하고, 다음 커밋에서
 * 개행을 정규화한 값으로 뒤집힌다.</p>
 */
class BenchmarkBoardDigestEolTest {

    private final BenchmarkClusters clusters = new BenchmarkClusters();

    @DisplayName("현행 판 다이제스트는 원본 바이트 그대로의 해시라 개행 문자가 값에 들어간다")
    @Test
    void digest_today_is_the_hash_of_raw_bytes() throws IOException {
        byte[] raw = new ClassPathResource("scheduler/benchmark-cluster-main.yml").getInputStream().readAllBytes();
        String text = new String(raw, StandardCharsets.UTF_8);
        String normalized = text.replace("\r\n", "\n");

        assertEquals(sha12(raw), clusters.digest(BenchmarkClusters.MAIN), "원본 바이트 해시");
        if (!text.equals(normalized)) {
            // CRLF 체크아웃(Windows 작업 트리)에서만 관측되는 결함 — 같은 내용인데 값이 다르다
            assertNotEquals(sha12(normalized.getBytes(StandardCharsets.UTF_8)), clusters.digest(BenchmarkClusters.MAIN),
                    "CRLF 로 읽힌 판은 LF 판과 다른 다이제스트를 낸다 — 빌드 OS 에 따라 값이 갈린다");
        }
    }

    @DisplayName("같은 문자열도 개행만 다르면 다이제스트가 다르다 — 정규화가 없다는 뜻")
    @Test
    void line_endings_change_the_digest() {
        assertNotEquals(BenchmarkClusters.digestOf("nodes:\n  - a"), BenchmarkClusters.digestOf("nodes:\r\n  - a"));
    }

    static String sha12(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
