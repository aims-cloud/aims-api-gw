package com.innogrid.aims.scheduler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 스케줄러 엔티티가 만드는 테이블이 모두 {@code deploy/sql} 에 CREATE 로 남아 있는지 대조한다.
 *
 * <p><b>왜 필요한가</b> — 이 검사는 그동안 사람의 기억에만 있었고, 실제로 세 테이블
 * ({@code workloads}·{@code scheduling_policies}·{@code scheduling_history})의 CREATE 가 빠져 있었다.
 * 드러나지 않은 이유는 두 가지다.</p>
 *
 * <ul>
 *   <li>배포 VM 은 환경파일이 {@code ddl-auto=update} 로 덮어써 Hibernate 가 테이블을 자동으로 만든다.
 *       리포 기본값은 {@code validate} 라, <b>그 환경변수 한 줄이 빠진 새 DB 에서는 앱 전체가 기동하지 않는다.</b></li>
 *   <li>테스트는 H2 를 {@code create-drop} 으로 쓰므로 {@code deploy/sql} 을 <b>한 번도 읽지 않는다.</b>
 *       즉 DDL 부채를 잡아 줄 자동 방어선이 0건이었다.</li>
 * </ul>
 *
 * <p><b>이 테스트의 한계</b>를 분명히 해 둔다 — 대조는 <b>테이블 이름 수준</b>이다. 컬럼 타입·길이·NOT NULL
 * 이 엔티티와 맞는지는 여기서 알 수 없다. 그건 실제 PostgreSQL 에 붙여야 알 수 있는데 테스트가 H2 라 불가능하다.
 * 배포 뒤 {@code information_schema} 로 대조하는 절차가 그 자리를 대신한다.</p>
 *
 * <p>담당 범위가 스케줄러라서 <b>스케줄러 패키지의 엔티티만</b> 본다. 다른 패키지의 테이블은 대조 대상이 아니다.</p>
 */
class SchedulerDdlCoverageTest {

    private static final String ENTITY_PACKAGE = "com.innogrid.aims.scheduler.entity";
    private static final Path DDL_DIR = Path.of("deploy", "sql");

    /** 줄 주석({@code --}). 주석 안의 {@code CREATE TABLE} 을 실제 생성으로 세지 않으려고 먼저 걷어낸다. */
    private static final Pattern COMMENT = Pattern.compile("--.*");   // `.` 는 줄바꿈을 넘지 않으므로 줄 끝까지만 지운다

    /** {@code CREATE TABLE [IF NOT EXISTS] <이름>} 의 대상 이름. 따옴표로 감싼 이름도 받는다. */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?\"?([a-z_][a-z0-9_]*)\"?",
            Pattern.CASE_INSENSITIVE);

    /** {@code CREATE INDEX [IF NOT EXISTS] <이름> ON <표> (<컬럼들>)}. 부분 인덱스의 WHERE 절은 보지 않는다. */
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "create\\s+(?:unique\\s+)?index\\s+(?:if\\s+not\\s+exists\\s+)?(\\w+)\\s+on\\s+(\\w+)\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);

    @DisplayName("스케줄러 엔티티의 테이블이 모두 deploy/sql 에 CREATE 로 있다")
    @Test
    void every_scheduler_table_has_create_script() throws Exception {
        Set<String> entityTables = schedulerEntityTables();
        Set<String> created = tablesCreatedByDdl();

        assertFalse(entityTables.isEmpty(), "스케줄러 엔티티를 하나도 찾지 못했다 — 스캔 대상 패키지를 확인하라");

        Set<String> missing = new TreeSet<>(entityTables);
        missing.removeAll(created);

        assertTrue(missing.isEmpty(),
                "deploy/sql 에 CREATE 가 없는 스케줄러 테이블: " + missing
                        + "\n  → 리포 기본값(ddl-auto=validate)인 새 DB 에서는 앱이 기동하지 않는다."
                        + "\n  → 배포 VM 이 update 로 덮어써서 드러나지 않을 뿐이다."
                        + "\n  엔티티 테이블 " + entityTables
                        + "\n  DDL CREATE  " + new TreeSet<>(created));
    }

    @DisplayName("DDL 디렉터리를 실제로 읽었다 — 경로가 어긋나면 위 검사가 조용히 통과한다")
    @Test
    void ddl_directory_is_actually_read() throws Exception {
        assertTrue(Files.isDirectory(DDL_DIR),
                "deploy/sql 을 찾지 못했다. 테스트 작업 디렉터리가 프로젝트 루트가 아니면 위 대조가 무의미해진다");
        assertFalse(tablesCreatedByDdl().isEmpty(), "deploy/sql 에서 CREATE TABLE 을 하나도 읽지 못했다");
    }

    @DisplayName("엔티티가 선언한 인덱스가 모두 deploy/sql 에도 같은 이름·같은 컬럼으로 있다")
    @Test
    void every_entity_index_has_matching_ddl() throws Exception {
        Map<String, String> declared = entityIndexes();
        Map<String, String> inDdl = indexesCreatedByDdl();

        assertFalse(declared.isEmpty(), "스케줄러 엔티티에서 @Index 를 하나도 찾지 못했다");

        Set<String> missing = new TreeSet<>(declared.keySet());
        missing.removeAll(inDdl.keySet());
        assertTrue(missing.isEmpty(),
                "deploy/sql 에 CREATE INDEX 가 없는 엔티티 인덱스: " + missing
                        + "\n  ddl-auto=update 인 환경은 Hibernate 가 만들어 주지만, validate 인 DB 에는 생기지 않는다."
                        + "\n  DDL 인덱스 " + inDdl.keySet());

        declared.forEach((name, columns) -> assertEquals(columns, inDdl.get(name),
                "인덱스 " + name + " 의 대상이 엔티티 선언과 DDL 에서 다르다"));
    }

    /** 스케줄러 엔티티의 {@code @Index} → {@code 이름 -> "표(컬럼,컬럼)"}. 자바 속성명을 컬럼명으로 바꿔 비교한다. */
    private Map<String, String> entityIndexes() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        Map<String, String> found = new LinkedHashMap<>();
        for (var candidate : scanner.findCandidateComponents(ENTITY_PACKAGE)) {
            Table table = Class.forName(candidate.getBeanClassName()).getAnnotation(Table.class);
            if (table == null) continue;
            for (Index index : table.indexes()) {
                found.put(index.name().toLowerCase(),
                        table.name().toLowerCase() + "(" + toColumns(index.columnList()) + ")");
            }
        }
        return found;
    }

    /** {@code deploy/sql} 의 {@code CREATE INDEX} → 같은 형식의 {@code 이름 -> "표(컬럼,컬럼)"}. */
    private Map<String, String> indexesCreatedByDdl() throws IOException {
        Map<String, String> found = new LinkedHashMap<>();
        for (Path script : ddlScripts()) {
            Matcher matcher = CREATE_INDEX.matcher(readWithoutComments(script));
            while (matcher.find()) {
                found.put(matcher.group(1).toLowerCase(),
                        matcher.group(2).toLowerCase() + "(" + toColumns(matcher.group(3)) + ")");
            }
        }
        return found;
    }

    /** {@code "workloadId, createdAt"} → {@code "workload_id,created_at"}. 공백·대소문자·표기 차이를 없앤다. */
    private static String toColumns(String list) {
        StringBuilder sb = new StringBuilder();
        for (String raw : list.split(",")) {
            String col = raw.trim().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
            if (col.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(col);
        }
        return sb.toString();
    }

    /** 스케줄러 패키지의 {@code @Entity} 가 쓰는 테이블 이름. {@code @Table} 이 없으면 클래스 이름을 쓴다. */
    private Set<String> schedulerEntityTables() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        Set<String> tables = new TreeSet<>();
        for (var candidate : scanner.findCandidateComponents(ENTITY_PACKAGE)) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            Table table = type.getAnnotation(Table.class);
            String name = (table != null && !table.name().isBlank())
                    ? table.name()
                    : type.getSimpleName();
            tables.add(name.toLowerCase());
        }
        return tables;
    }

    /** {@code deploy/sql} 의 모든 스크립트가 만드는 테이블 이름. */
    private Set<String> tablesCreatedByDdl() throws IOException {
        Set<String> created = new TreeSet<>();
        if (!Files.isDirectory(DDL_DIR)) return created;

        for (Path script : ddlScripts()) {
            Matcher matcher = CREATE_TABLE.matcher(readWithoutComments(script));
            while (matcher.find()) {
                created.add(matcher.group(1).toLowerCase());
            }
        }
        return created;
    }

    private List<Path> ddlScripts() throws IOException {
        if (!Files.isDirectory(DDL_DIR)) return List.of();
        try (Stream<Path> files = Files.list(DDL_DIR)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sql")).toList();
        }
    }

    /** 주석을 먼저 지운다 — 그러지 않으면 "-- TODO: CREATE TABLE foo" 한 줄로 이 검사를 통과시킬 수 있다. */
    private static String readWithoutComments(Path script) throws IOException {
        return COMMENT.matcher(Files.readString(script, StandardCharsets.UTF_8)).replaceAll(" ");
    }
}
