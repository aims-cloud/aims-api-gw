package com.innogrid.aims.scheduler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 스케줄러 엔티티의 <b>컬럼</b>이 배포 SQL에 있는지 본다.
 *
 * <p>기존 {@code SchedulerDdlCoverageTest}는 <b>테이블 이름 수준</b>만 대조한다(그 테스트가 스스로
 * 한계를 밝혀 뒀다). 그래서 컬럼을 더하고 DDL을 빠뜨려도 아무 테스트가 빨간불이 되지 않고,
 * {@code ddl-auto=validate}인 새 DB에서 <b>기동 실패</b>로만 드러난다. 배포 VM은 {@code update}라
 * Hibernate가 자동으로 만들어 주므로 그때도 안 드러난다.</p>
 *
 * <p>여기서는 엔티티의 필드 이름을 스네이크 케이스로 바꿔 해당 테이블을 만드는·고치는 SQL
 * 어딘가에 그 이름이 나오는지 본다. 타입·길이·제약은 보지 않는다 — 그건 실제 PostgreSQL에
 * 붙어야 알 수 있고, 이 테스트의 목적은 <b>누락</b>을 잡는 것이다.</p>
 */
class SchedulerDdlColumnTest {

    private static final Path SQL_DIR = Path.of("deploy", "sql");

    /** 이름을 SQL에서 찾을 때 쓰는 스네이크 케이스 변환. */
    private static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static String allSql() throws IOException {
        StringBuilder sb = new StringBuilder();
        try (var paths = Files.list(SQL_DIR)) {
            for (Path p : paths.filter(p -> p.toString().endsWith(".sql")).toList()) {
                sb.append(Files.readString(p, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT)).append('\n');
            }
        }
        return sb.toString();
    }

    @DisplayName("스케줄러 엔티티의 모든 컬럼이 배포 SQL 에 있다")
    @Test
    void every_entity_column_appears_in_the_deploy_sql() throws Exception {
        String sql = allSql();
        List<String> missing = new ArrayList<>();
        Set<String> checked = new LinkedHashSet<>();

        for (Class<?> type : schedulerEntities()) {
            String table = tableOf(type);
            for (var field : type.getDeclaredFields()) {
                if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (field.isAnnotationPresent(Transient.class)) continue;
                Column column = field.getAnnotation(Column.class);
                String name = column != null && !column.name().isBlank() ? column.name() : snake(field.getName());
                checked.add(table + "." + name);
                if (!sql.contains(name)) missing.add(table + "." + name + " (" + type.getSimpleName() + "." + field.getName() + ")");
            }
        }

        assertFalse(checked.isEmpty(), "확인한 컬럼이 없다 — 엔티티 목록을 읽지 못했다");
        assertTrue(missing.isEmpty(),
                "배포 SQL 에 없는 컬럼이 있다. 새 컬럼을 더했으면 deploy/sql 에 스크립트를 함께 낸다:\n  "
                        + String.join("\n  ", missing));
    }

    static List<Class<?>> schedulerEntities() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        List<Class<?>> types = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("com.innogrid.aims.scheduler.entity")) {
            types.add(Class.forName(candidate.getBeanClassName()));
        }
        return types;
    }

    private static String tableOf(Class<?> type) {
        Table table = type.getAnnotation(Table.class);
        if (table != null && !table.name().isBlank()) return table.name().toLowerCase(Locale.ROOT);
        return snake(type.getSimpleName());
    }

    /** 이 테스트가 실제로 무언가를 보고 있는지 — 엔티티가 한 개도 없으면 위 테스트는 항상 초록불이다. */
    @DisplayName("대상 엔티티가 비어 있지 않다")
    @Test
    void the_entity_list_is_not_empty() throws Exception {
        List<Class<?>> entities = schedulerEntities();
        assertTrue(entities.size() >= 5, "스케줄러 엔티티가 " + entities.size() + "개뿐이다 — 목록 수집이 깨졌다");
        assertTrue(entities.stream().allMatch(c -> c.isAnnotationPresent(Entity.class)));
        assertTrue(entities.stream().anyMatch(c -> java.util.Arrays.stream(c.getDeclaredFields())
                .anyMatch(f -> f.isAnnotationPresent(Id.class))), "식별자 필드를 가진 엔티티가 없다");
    }

}
