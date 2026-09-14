package com.innogrid.aims.scheduler.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 금지 문구 조각({@link BenchmarkRules#BANNED_CLAIMS})이 <b>스케줄러 소스 자체</b>에 남아 있지 않은지 훑는다. <!-- ban-ok: 아래 예시 인용 -->
 *
 * <p>기존 계약 시험은 판정 규칙의 사본(한계 문구·지표 라벨·판 설명·전제 문구·Swagger 설명)만 본다. 그래서
 * 주석이나 어노테이션 설명에 "볼록하기 때문이다" 같은 단정이 되돌아와도 아무 시험이 깨지지 않았다 — 문구 정정
 * PR 의 효과가 다음 편집에서 조용히 사라질 수 있었다. 이 시험은 {@code src/main/java} 의 스케줄러 소스를
 * 텍스트로 읽어 <b>주석 블록</b>(javadoc 블록 · 일반 블록 주석 · 줄 주석)과 <b>설명 어노테이션</b>
 * ({@code @Tag}·{@code @Operation}·{@code @Schema})의 문자열을 검사한다. 예외 메시지 같은 일반 문자열 리터럴은
 * 대상이 아니다 — 그것은 사용자에게 가는 문장이 아니라 코드 안의 값이다.</p>
 *
 * <p><b>줄바꿈으로 갈린 조각도 잡는다.</b> 이 저장소는 긴 문장을 여러 줄 javadoc 이나 {@code +} 로 이어 붙이므로
 * 블록 단위로 이어 붙인 뒤 공백을 하나로 접어 검사한다.</p>
 *
 * <p><b>의도된 인용은 {@code ban-ok} 마커로 건너뛴다.</b> 마커가 있는 <b>줄과 그 앞뒤 한 줄</b>만 제외한다 — 금지 조각을
 * 정의하거나 "이렇게 부르지 않는다" 고 설명하는 자리가 그 예다. 블록 전체를 면제하면 연구 서술이 가장 긴 클래스 javadoc 이
 * 통째로 사각지대가 되므로 줄 단위로 좁혔고, 면제된 줄 수에는 상한을 둔다. 금지 조각을 열거하는 자리 하나만
 * {@code ban-ok-block} 으로 블록 전체를 면제한다.</p>
 *
 * <p>대상은 {@code src/main}·{@code src/test} 의 스케줄러 패키지 전부다 — 시험 주석의 옛 표기도 같은 규칙으로 잡는다.</p>
 */
class BannedClaimsSourceScanTest {

    private static final List<Path> ROOTS = List.of(
            Path.of("src", "main", "java", "com", "innogrid", "aims", "scheduler"),
            Path.of("src", "test", "java", "com", "innogrid", "aims", "scheduler"));
    private static final Path ROOT = ROOTS.get(0);
    /** 면제 줄 수 상한 — 마커가 번지면 여기서 멈춘다. */
    private static final int MAX_EXEMPT_LINES = 60;
    /** 블록 전체 면제 — 금지 조각을 정의·열거하는 자리 한 곳만을 위한 형태. 면제 줄 수는 전부 상한에 든다. */
    private static final String BLOCK_MARKER = "ban-ok-block";
    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\".*?\"\"\"", Pattern.DOTALL);
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\r\\n]*");
    private static final Pattern DESCRIPTION_ANNOTATION = Pattern.compile("@(Tag|Operation|Schema|Parameter|ApiResponse)\\s*\\(");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern DESCRIPTION_ARG = Pattern.compile("\\b(description|summary)\\s*=\\s*");
    private static final String MARKER = "ban-ok";

    /** 검사에 걸린 자리 — 파일·줄·조각·앞뒤 문맥. */
    record Hit(String file, int line, String fragment, String context) {
        @Override public String toString() {
            return file + ":" + line + " — '" + fragment + "' … " + context;
        }
    }

    @DisplayName("스케줄러 소스의 주석과 설명 어노테이션에 금지 문구 조각이 없다 (ban-ok 마커 블록 제외)")
    @Test
    void scheduler_sources_are_free_of_banned_claims() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path root : ROOTS) {
            try (Stream<Path> walk = Files.walk(root)) {
                files.addAll(walk.filter(p -> p.toString().endsWith(".java")).sorted().toList());
            }
        }
        assertTrue(files.size() > 50, "스케줄러 소스를 찾지 못했다 — 작업 디렉터리가 프로젝트 루트인가: " + ROOT.toAbsolutePath());

        List<Hit> hits = new ArrayList<>();
        int scannedBlocks = 0;
        int exemptLines = 0;
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String name = file.toString().replace('\\', '/').replaceFirst("^src/", "");
            for (Block b : blocks(text)) {
                scannedBlocks++;
                String body = b.text();
                if (body.contains(BLOCK_MARKER)) {
                    exemptLines += body.split("\r?\n", -1).length;
                    continue;
                }
                if (body.contains(MARKER)) {
                    String[] lines = body.split("\\r?\\n", -1);
                    boolean[] drop = new boolean[lines.length];
                    for (int i = 0; i < lines.length; i++) {
                        if (!lines[i].contains(MARKER)) continue;
                        for (int j = Math.max(0, i - 1); j <= Math.min(lines.length - 1, i + 1); j++) drop[j] = true;
                    }
                    StringBuilder kept = new StringBuilder();
                    for (int i = 0; i < lines.length; i++) {
                        if (drop[i]) exemptLines++; else kept.append(lines[i]).append('\n');
                    }
                    body = kept.toString();
                }
                // 인라인 태그와 HTML 태그를 지운다. 줄바꿈만 지운 본문(joinedLines)은 줄바꿈으로 갈린 짧은 조각용,
                // 공백을 전부 지운 본문(squeezed)은 어절 경계를 넘어 오탐하므로 6자 이상 조각에만 쓴다
                String untagged = body.replaceAll("\\{@\\w+\\s+", " ").replaceAll("<[^>]*>", " ");
                String flat = untagged.replaceAll("\\s+", " ");
                String joinedLines = untagged.replaceAll("[ \\t]*\\r?\\n[ \\t]*", "").replaceAll("[ \\t]+", " ");
                String squeezed = flat.replace(" ", "");
                for (String banned : BenchmarkRules.BANNED_CLAIMS) {
                    String tight = banned.replace(" ", "");
                    boolean squeezedHit = tight.length() >= 6 && squeezed.contains(tight);
                    if (flat.contains(banned) || joinedLines.contains(banned) || squeezedHit) {
                        hits.add(new Hit(name, lineOf(text, b.start()), banned, excerpt(flat, banned)));
                    }
                }
            }
        }
        assertTrue(scannedBlocks > 500, "검사한 블록이 너무 적다 — 추출 규칙이 깨졌다: " + scannedBlocks);
        assertTrue(exemptLines <= MAX_EXEMPT_LINES, "ban-ok 면제 줄이 너무 많다(" + exemptLines + " > " + MAX_EXEMPT_LINES + ") — 마커가 번지고 있다");
        assertTrue(hits.isEmpty(), () -> "금지 문구 조각이 소스에 남아 있다 — 고치거나, 의도된 인용이면 그 블록에 'ban-ok: 사유' 를 적는다\n  "
                + String.join("\n  ", hits.stream().map(Hit::toString).toList()));
    }

    // ── 블록 추출 ──────────────────────────────────────────────

    record Block(int start, String text) {}

    /** 주석 블록·줄 주석·설명 어노테이션의 문자열을 블록 단위로 모은다. */
    static List<Block> blocks(String text) {
        List<Block> out = new ArrayList<>();
        // 문자열 리터럴 안의 /* 가 가짜 주석 블록을 열어 뒤 코드를 삼키지 않도록, 블록 주석은 리터럴을 가린 텍스트에서 찾는다.
        // 텍스트 블록(""")을 먼저 가린다 — 안에 홀수 개의 " 가 있으면 일반 리터럴 마스킹이 어긋나 뒤 주석이 검사에서 빠진다
        String textBlocksMasked = TEXT_BLOCK.matcher(text).replaceAll(m -> " ".repeat(m.group().length()));
        String literalsMasked = STRING_LITERAL.matcher(textBlocksMasked).replaceAll(m -> " ".repeat(m.group().length()));
        Matcher bc = BLOCK_COMMENT.matcher(literalsMasked);
        while (bc.find()) out.add(new Block(bc.start(), text.substring(bc.start(), bc.end()).replace("*", " ")));
        String withoutBlocks = BLOCK_COMMENT.matcher(literalsMasked).replaceAll(m -> " ".repeat(m.group().length()));
        String withoutStrings = withoutBlocks;
        Matcher lc = LINE_COMMENT.matcher(withoutStrings);
        while (lc.find()) out.add(new Block(lc.start(), lc.group()));
        // 설명 어노테이션: 여는 괄호부터 짝이 맞는 닫는 괄호까지의 문자열 리터럴을 이어 붙인다
        Matcher an = DESCRIPTION_ANNOTATION.matcher(withoutBlocks);
        while (an.find()) {
            int open = an.end() - 1;
            int close = matchingParen(withoutBlocks, open);
            if (close < 0) continue;
            // 리터럴은 원문에서 읽는다(가린 텍스트는 위치만 같다). description·summary 인자만 문장이다 — example 같은 값 표본은 대상이 아니다
            String span = text.substring(open, close + 1);
            StringBuilder joined = new StringBuilder();
            Matcher arg = DESCRIPTION_ARG.matcher(span);
            while (arg.find()) {
                int from = arg.end();
                int to = span.indexOf('\n', from);
                String tail = span.substring(from);
                Matcher sl = STRING_LITERAL.matcher(tail);
                int cursor = 0;
                while (sl.find(cursor)) {
                    // 인자 값은 리터럴을 + 로 잇는다. 다음 인자 이름(쉼표 뒤 식별자 =) 이 나오기 전까지만 취한다
                    String between = tail.substring(cursor, sl.start());
                    if (between.matches("(?s).*,\\s*\\w+\\s*=.*")) break;
                    joined.append(sl.group(1)).append(' ');
                    cursor = sl.end();
                }
            }
            if (joined.length() > 0) out.add(new Block(an.start(), joined.toString()));
        }
        return out;
    }

    private static int matchingParen(String s, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    private static String excerpt(String flat, String banned) {
        int at = flat.indexOf(banned);
        if (at < 0) at = flat.replace(" ", "").indexOf(banned.replace(" ", ""));
        int from = Math.max(0, at - 30), to = Math.min(flat.length(), Math.max(at, 0) + banned.length() + 30);
        return flat.substring(from, to).trim();
    }
}
