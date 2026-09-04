package com.innogrid.aims.scheduler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.persistence.Entity;
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
 * 배포 뒤 {@code information_schema} 로 대조하는 절차(개발순서.md 8.4)가 그 자리를 대신한다.</p>
 *
 * <p>담당 범위가 스케줄러라서 <b>스케줄러 패키지의 엔티티만</b> 본다. 다른 패키지의 테이블은 대조 대상이 아니다.</p>
 */
class SchedulerDdlCoverageTest {

    private static final String ENTITY_PACKAGE = "com.innogrid.aims.scheduler.entity";
    private static final Path DDL_DIR = Path.of("deploy", "sql");

    /** {@code CREATE TABLE [IF NOT EXISTS] <이름>} 의 대상 이름. 따옴표로 감싼 이름도 받는다. */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?\"?([a-z_][a-z0-9_]*)\"?",
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

        List<Path> scripts;
        try (Stream<Path> files = Files.list(DDL_DIR)) {
            scripts = files.filter(p -> p.getFileName().toString().endsWith(".sql")).toList();
        }
        for (Path script : scripts) {
            Matcher matcher = CREATE_TABLE.matcher(Files.readString(script, StandardCharsets.UTF_8));
            while (matcher.find()) {
                created.add(matcher.group(1).toLowerCase());
            }
        }
        return created;
    }
}
