package com.innogrid.aims.scheduler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 스케줄러 운영 코드의 패키지 의존 방향을 소스 텍스트로 확인한다.
 *
 * <p>운영 소스(src/main)의 import·static import·와일드카드 import와 코드 안에 쓴 전체 이름을 모아 하위 패키지 단위 간선을 만든다.
 * 주석 안의 전체 이름은 의존이 아니므로 세지 않는다(javadoc 링크가 거짓 순환을 만든다). 문자열은 그대로 두어,
 * 잘못 걸리더라도 의존이 더 잡히는 쪽으로 틀린다.</p>
 *
 * <p>규칙은 둘이다. 패키지 사이에 순환이 없어야 하고, 아래 패키지(model·metrics·engine·benchmark·inventory)가
 * 위 패키지를 가리키지 않아야 한다. 의도한 새 방향이 생기면 {@link #FORBIDDEN}과 모듈 개요 문서(docs/15)의 의존 방향 문장을 함께 고친다.</p>
 */
class SchedulerPackageDependencyTest {

    private static final String PREFIX = "com.innogrid.aims.scheduler.";
    private static final Path MAIN = Path.of("src", "main", "java", "com", "innogrid", "aims", "scheduler");
    /** 패키지 마디(소문자로 시작) 다음에 대문자 클래스 이름이나 와일드카드가 오는 참조. */
    private static final Pattern REFERENCE =
            Pattern.compile("com\\.innogrid\\.aims\\.scheduler\\.((?:[a-z_]\\w*\\.)*[a-z_]\\w*)\\.(?:[A-Z]|\\*)");
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);

    /** 최상위 패키지별로 가리키면 안 되는 최상위 패키지. 하위 패키지(engine.constraint 등)는 최상위 마디로 판정한다. */
    static final Map<String, Set<String>> FORBIDDEN = Map.of(
            "model", Set.of("engine", "service", "controller", "dto", "repository", "benchmark", "metrics", "dispatch"),
            "metrics", Set.of("benchmark", "service", "controller", "dto", "repository"),
            "engine", Set.of("service", "controller", "dto", "repository", "benchmark"),
            "benchmark", Set.of("service", "controller", "dto", "repository"),
            "inventory", Set.of("engine", "service", "controller", "dto", "repository", "benchmark", "dispatch"));

    @DisplayName("스케줄러 운영 코드의 패키지 사이에 순환 의존이 없다")
    @Test
    void main_packages_have_no_cycle() throws IOException {
        Map<String, Map<String, Set<String>>> graph = graph(mainSources());
        List<Set<String>> cycles = cycles(graph);
        StringBuilder why = new StringBuilder();
        for (Set<String> cycle : cycles) {
            why.append("\n순환 ").append(cycle);
            for (String from : cycle) {
                graph.getOrDefault(from, Map.of()).forEach((to, files) -> {
                    if (cycle.contains(to)) why.append("\n  ").append(from).append(" → ").append(to).append(" : ").append(files);
                });
            }
        }
        assertEquals(List.of(), cycles, "패키지 순환이 있다 — 클래스를 짝과 같은 패키지로 옮기거나 의존을 한 방향으로 되돌린다" + why);
    }

    @DisplayName("model·metrics·engine·benchmark·inventory 패키지는 금지한 위 패키지를 가리키지 않는다")
    @Test
    void lower_packages_do_not_import_upper_packages() throws IOException {
        List<String> violations = new ArrayList<>();
        graph(mainSources()).forEach((from, targets) -> targets.forEach((to, files) -> {
            if (FORBIDDEN.getOrDefault(top(from), Set.of()).contains(top(to))) {
                violations.add(from + " → " + to + " : " + files);
            }
        }));
        assertEquals(List.of(), violations,
                "아래 패키지가 위 패키지를 가리킨다 — 의존을 되돌리거나, 의도한 새 방향이면 FORBIDDEN과 docs/15 의존 방향 문장을 함께 고친다");
    }

    @DisplayName("스캔이 스케줄러 운영 소스 전체를 읽고 이미 아는 의존을 찾는다")
    @Test
    void scan_reads_whole_scheduler_source() throws IOException {
        Map<String, String> sources = mainSources();
        assertTrue(sources.size() > 150, "스케줄러 운영 소스를 찾지 못했다. 작업 디렉터리가 프로젝트 루트인가: " + MAIN.toAbsolutePath());
        Map<String, Map<String, Set<String>>> graph = graph(sources);
        int edges = graph.values().stream().mapToInt(Map::size).sum();
        assertTrue(edges >= 30, "패키지 간선이 너무 적다 — 참조 규칙이 깨졌다: " + edges);
        for (String[] known : List.of(new String[] {"service", "model"}, new String[] {"engine", "model"},
                new String[] {"engine.constraint", "model"}, new String[] {"benchmark", "metrics"})) {
            assertTrue(graph.getOrDefault(known[0], Map.of()).containsKey(known[1]), known[0] + " → " + known[1] + " 간선을 찾아야 한다");
        }
    }

    @DisplayName("스캐너는 import·static import·와일드카드·코드 안 전체 이름을 세고 주석 안 이름은 세지 않으며 순환을 찾는다")
    @Test
    void scanner_finds_cycle_and_ignores_comments() {
        Map<String, String> sources = new TreeMap<>();
        sources.put("a/A.java", """
                package com.innogrid.aims.scheduler.a;

                import com.innogrid.aims.scheduler.b.B;

                /** {@link com.innogrid.aims.scheduler.c.C}는 주석이라 의존이 아니다. */
                class A {
                    // com.innogrid.aims.scheduler.c.C도 주석이다
                    String open = "/*";
                    Object f = com.innogrid.aims.scheduler.f.F.class;
                }
                """);
        sources.put("b/B.java", """
                package com.innogrid.aims.scheduler.b;

                import static com.innogrid.aims.scheduler.a.A.value;

                class B {}
                """);
        sources.put("c/C.java", """
                package com.innogrid.aims.scheduler.c;

                import com.innogrid.aims.scheduler.d.*;

                class C {
                    Object e = com.innogrid.aims.scheduler.e.E.class;
                    String block = \"""
                        // 텍스트 블록 안의 글은 지우지 않는다
                        \""";
                }
                """);
        Map<String, Map<String, Set<String>>> graph = graph(sources);
        assertEquals(Set.of("b", "f"), graph.get("a").keySet(), "import와 문자열 뒤 코드의 전체 이름은 세고 주석 안 c는 세지 않는다");
        assertEquals(Set.of("a"), graph.get("b").keySet(), "static import");
        assertEquals(Set.of("d", "e"), graph.get("c").keySet(), "와일드카드 import와 코드 안 전체 이름");
        assertEquals(List.of(Set.of("a", "b")), cycles(graph));

        graph.get("b").clear();
        assertEquals(List.of(), cycles(graph), "a → b 한 방향이면 순환이 아니다");
        assertTrue(withoutComments("String s = \"// 문자열\"; // 주석").contains("// 문자열"), "문자열 안 //는 주석이 아니다");
        assertFalse(withoutComments("int x; /* com.innogrid.aims.scheduler.z.Z */").contains("scheduler.z"));
    }

    /** 운영 소스: 스케줄러 패키지 아래 상대 경로 → 본문. */
    private static Map<String, String> mainSources() throws IOException {
        Map<String, String> sources = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(MAIN)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                sources.put(MAIN.relativize(p).toString().replace('\\', '/'), Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        return sources;
    }

    /** 하위 패키지 간선: 출발 패키지 → (도착 패키지 → 근거 파일). 같은 패키지 참조와 package 줄은 세지 않는다. */
    static Map<String, Map<String, Set<String>>> graph(Map<String, String> sources) {
        Map<String, Map<String, Set<String>>> graph = new TreeMap<>();
        sources.forEach((file, text) -> {
            Matcher pm = PACKAGE.matcher(text);
            if (!pm.find() || !pm.group(1).startsWith(PREFIX)) return;
            String from = pm.group(1).substring(PREFIX.length());
            String code = withoutComments(text).replaceFirst("(?m)^package\\s+[\\w.]+;", "");
            Matcher m = REFERENCE.matcher(code);
            while (m.find()) {
                String to = m.group(1);
                if (to.equals(from)) continue;
                graph.computeIfAbsent(from, k -> new TreeMap<>()).computeIfAbsent(to, k -> new TreeSet<>()).add(file);
            }
        });
        return graph;
    }

    /** 크기 2 이상인 강연결 묶음(Tarjan) — 패키지 이름 순으로 정렬해 돌려준다. */
    static List<Set<String>> cycles(Map<String, Map<String, Set<String>>> graph) {
        Set<String> nodes = new TreeSet<>(graph.keySet());
        graph.values().forEach(t -> nodes.addAll(t.keySet()));
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new TreeSet<>();
        List<Set<String>> found = new ArrayList<>();
        int[] counter = {0};
        for (String n : nodes) {
            if (!index.containsKey(n)) connect(n, graph, index, low, stack, onStack, found, counter);
        }
        found.sort((x, y) -> x.iterator().next().compareTo(y.iterator().next()));
        return found;
    }

    private static void connect(String v, Map<String, Map<String, Set<String>>> graph, Map<String, Integer> index, Map<String, Integer> low,
            Deque<String> stack, Set<String> onStack, List<Set<String>> found, int[] counter) {
        index.put(v, counter[0]);
        low.put(v, counter[0]);
        counter[0]++;
        stack.push(v);
        onStack.add(v);
        for (String w : graph.getOrDefault(v, Map.of()).keySet()) {
            if (!index.containsKey(w)) {
                connect(w, graph, index, low, stack, onStack, found, counter);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (onStack.contains(w)) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            Set<String> component = new TreeSet<>();
            String w;
            do {
                w = stack.pop();
                onStack.remove(w);
                component.add(w);
            } while (!w.equals(v));
            if (component.size() > 1) found.add(component);
        }
    }

    /** 주석만 지운다(줄바꿈은 남김). 문자열·문자·텍스트 블록 안의 글은 그대로 둔다. */
    static String withoutComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (src.startsWith("\"\"\"", i)) {
                int j = src.indexOf("\"\"\"", i + 3);
                j = j < 0 ? n : j + 3;
                out.append(src, i, j);
                i = j;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && src.charAt(j) != c && src.charAt(j) != '\n') j += src.charAt(j) == '\\' ? 2 : 1;
                j = Math.min(j + 1, n);
                out.append(src, i, j);
                i = j;
            } else if (src.startsWith("//", i)) {
                int j = src.indexOf('\n', i);
                i = j < 0 ? n : j;
            } else if (src.startsWith("/*", i)) {
                int j = src.indexOf("*/", i + 2);
                j = j < 0 ? n : j + 2;
                for (int k = i; k < j; k++) if (src.charAt(k) == '\n') out.append('\n');
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String top(String pkg) {
        int dot = pkg.indexOf('.');
        return dot < 0 ? pkg : pkg.substring(0, dot);
    }
}
