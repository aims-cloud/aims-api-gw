package com.innogrid.aims.scheduler.controller;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스케줄러 API의 작업 목록(HTTP 메서드 + 경로)이 기준 파일과 같은지 확인한다.
 *
 * <p>{@code /v3/api-docs}의 {@code paths} 중 {@code /api/v1/scheduler/}로 시작하는 경로와 그 메서드를 모아 한 줄에
 * {@code METHOD /path}로 적은 목록을 {@code src/test/resources/scheduler/golden/api/operations.txt}와 비교한다.
 * 경로 오름차순, 같은 경로 안에서는 GET·POST·PUT·PATCH·DELETE 순이라 기준 파일의 N 번째 줄이 docs/15 6장 색인의 N 번째 행과
 * 같다. operationId·태그·스키마·파라미터는 보지 않는다 — operationId는 이름이 겹칠 때 springdoc이 등록 순서에 따라 접미사를
 * 붙여 값이 흔들리고, 스키마·파라미터는 docs/13이 맡는 필드 정의라 이 목록의 뜻(어떤 작업이 있는가)과
 * 다르기 때문이다.</p>
 *
 * <p><b>운용 규칙</b> — 기준 파일이 없거나 다르면 실제 목록을 {@code build/golden-candidates/api/operations.txt}에 쓰고
 * 실패한다. 작업이 추가·삭제된 것이면 후보를 검토한 뒤 기준 파일로 복사하고, 같은 PR에서 docs/15 8장 갱신 규약을 따라
 * 문서를 고친다. 순서나 중복만 다른 파일(손으로 고친 것으로 보임)도 실패로 보며, 이때는 후보로 바꿔 넣기만 하면 되고 문서 변경은
 * 없다. 통과하면 남아 있던 후보 파일을 지운다. 배치 응답 기준 파일({@link PlacementResponseGoldenTest})과 같은 운용이다.</p>
 *
 * <p>경로 필터와 메서드 집합은 {@link SchedulerResponseTypeApiTest}와 같은 정의다(그 테스트의 하한 75 확인은 작업을 하나도
 * 돌지 않고 통과하는 일을 막는 것이라 그대로 두고, 이 테스트가 두 번째 가드다). 테스트 컨텍스트는 다른 API 테스트와 공유하도록
 * 세 애너테이션만 붙이고 빈 교체·프로파일·속성 지정을 두지 않는다. springdoc 설정이나 버전이 바뀌어 경로 표기가 달라져도
 * 실패하며 그것은 의도한 동작이다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class SchedulerApiInventoryTest {

    private static final String BASE = "/api/v1/scheduler";
    private static final List<String> METHOD_ORDER = List.of("get", "post", "put", "patch", "delete");
    private static final Path GOLDEN = Path.of("src", "test", "resources", "scheduler", "golden", "api", "operations.txt");
    private static final Path CANDIDATE = Path.of("build", "golden-candidates", "api", "operations.txt");
    /** 추가·삭제 목록을 메시지에 보여 주는 상한 — 넘치면 「외 N건」으로 줄인다. */
    private static final int LIST_LIMIT = 20;
    /** {@link SchedulerResponseTypeApiTest}가 확인하는 작업 수 하한 — 작업 수가 이 밑으로 줄면 그 테스트도 고쳐야 한다. */
    private static final int LOWER_BOUND = 75;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @DisplayName("스케줄러 작업 목록이 기준 파일과 같다")
    @Test
    void scheduler_operations_match_golden_list() throws Exception {
        List<String> actualLines = operations(apiDocs());
        String actual = String.join("\n", actualLines) + "\n";
        String expected = Files.exists(GOLDEN) ? normalize(Files.readString(GOLDEN, StandardCharsets.UTF_8)) : "";
        if (actual.equals(expected)) {
            Files.deleteIfExists(CANDIDATE);
            return;
        }
        Files.createDirectories(CANDIDATE.getParent());
        Files.writeString(CANDIDATE, actual, StandardCharsets.UTF_8);
        fail(message(actualLines, expected));
    }

    /** {@code METHOD /path} 목록 — 경로 오름차순, 같은 경로 안에서는 GET·POST·PUT·PATCH·DELETE 순. */
    private static List<String> operations(JsonNode apiDocs) {
        Set<String> paths = new TreeSet<>();
        apiDocs.get("paths").properties().forEach(e -> { if (scheduler(e.getKey())) paths.add(e.getKey()); });
        List<String> lines = new ArrayList<>();
        for (String path : paths) {
            JsonNode item = apiDocs.get("paths").get(path);
            for (String method : METHOD_ORDER) {
                if (item.has(method)) lines.add(method.toUpperCase() + " " + path);
            }
        }
        return lines;
    }

    /** 세 경우로 갈라 안내한다 — 기준 파일 없음(또는 빈 파일) · 순서나 중복만 다름 · 작업 추가·삭제. */
    private static String message(List<String> actualLines, String expected) {
        long pathCount = actualLines.stream().map(l -> l.substring(l.indexOf(' ') + 1)).distinct().count();
        StringBuilder sb = new StringBuilder();
        String counts = "현재 경로 " + pathCount + " · 작업 " + actualLines.size() + " — 이 목록을 " + CANDIDATE + " 에 썼다.\n";
        if (expected.isEmpty()) {
            sb.append("스케줄러 작업 목록 기준 파일이 없거나 비었다: ").append(GOLDEN).append('\n').append(counts)
                    .append("실수로 지운 것이면 git 으로 되살리고, 처음 만드는 것이면 후보를 검토한 뒤 기준 파일로 복사한다.");
            return sb.toString();
        }
        Set<String> want = new TreeSet<>(List.of(expected.strip().split("\n")));
        Set<String> have = new TreeSet<>(actualLines);
        List<String> added = have.stream().filter(l -> !want.contains(l)).toList();
        List<String> removed = want.stream().filter(l -> !have.contains(l)).toList();
        if (added.isEmpty() && removed.isEmpty()) {
            sb.append("스케줄러 작업 목록이 기준 파일과 순서나 중복만 다르다(손으로 고친 것으로 보임).\n").append(counts)
                    .append("후보 파일을 기준 파일로 복사하면 된다(문서 변경 없음).");
            return sb.toString();
        }
        sb.append("스케줄러 작업 목록이 기준 파일과 다르다.\n");
        appendList(sb, "추가됨", added);
        appendList(sb, "삭제됨", removed);
        sb.append(counts);
        if (added.size() > LIST_LIMIT && removed.size() > LIST_LIMIT) {
            sb.append("추가·삭제가 각각 ").append(LIST_LIMIT).append("건을 넘는다 — 경로 표기가 바뀐 것일 수 있다. ")
                    .append("컨트롤러 매핑이 그대로면 후보 복사만 하면 된다(문서 변경 없음).\n");
        }
        sb.append("의도한 변경이면 후보를 검토한 뒤 ").append(GOLDEN).append(" 로 복사하고, 같은 PR 에서 docs/15 8장 갱신 규약을 따라 ")
                .append("docs/13 10장 변경 이력·docs/15 6장 색인·작업 수가 적힌 곳을 고친다. ");
        if (actualLines.size() < LOWER_BOUND) {
            sb.append("작업 수가 ").append(LOWER_BOUND).append(" 밑으로 줄었으므로 SchedulerResponseTypeApiTest 의 하한도 같은 PR 에서 고친다. ");
        }
        sb.append("의도하지 않은 변경이면 컨트롤러 매핑을 되돌린다. 스케줄러가 아닌 기능은 ").append(BASE).append("/ 아래에 두지 않는다.");
        return sb.toString();
    }

    private static void appendList(StringBuilder sb, String label, List<String> lines) {
        lines.stream().limit(LIST_LIMIT).forEach(l -> sb.append("  ").append(label).append(": ").append(l).append('\n'));
        if (lines.size() > LIST_LIMIT) sb.append("  ").append(label).append(" 외 ").append(lines.size() - LIST_LIMIT).append("건\n");
    }

    /** 기준 파일을 읽을 때 맨 앞 BOM·CRLF·앞뒤 공백·빈 줄만 정규화한다 — 순서와 중복은 그대로 비교한다. */
    private static String normalize(String text) {
        String body = text.startsWith("﻿") ? text.substring(1) : text;
        StringBuilder sb = new StringBuilder();
        for (String line : body.replace("\r\n", "\n").split("\n")) {
            String s = line.strip();
            if (!s.isEmpty()) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    private static boolean scheduler(String path) {
        return path.startsWith(BASE + "/");
    }

    private JsonNode apiDocs() throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
