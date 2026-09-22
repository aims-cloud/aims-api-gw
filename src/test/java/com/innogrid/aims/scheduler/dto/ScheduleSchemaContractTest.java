package com.innogrid.aims.scheduler.dto;

import java.lang.reflect.RecordComponent;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.service.CronSupport;
import io.swagger.v3.oas.annotations.media.Schema;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 스케줄 화면 DTO 의 <b>설명·예시·필수 표기가 코드와 갈리지 않는지</b> — 컨텍스트를 띄우지 않고 반사로만 본다.
 *
 * <p>이 아홉 record 는 프런트가 문자 그대로 읽는 계약이다. "설명이 있는지" 만 보는 검사는 한 단어로도
 * 통과하므로, 무게는 예시·허용값·필수 표기를 <b>코드의 사실과 대조</b>하는 쪽에 둔다 — 스케줄 ID 예시는
 * 서버 정규식에 맞아야 하고, cron 예시는 서버 검증을 통과해야 하며, 상태 허용값은 전이표가 실제로 받는
 * 값과 같아야 한다. 어느 하나가 갈리면 문서가 서버보다 넓거나 좁아져 프런트는 400 을 만난다.</p>
 *
 * <p>대상 아홉 개는 리터럴로 적는다. 새 DTO 를 이 계약에 편입하는 것은 결정이라 명시가 맞다.</p>
 */
class ScheduleSchemaContractTest {

    private static final List<Class<?>> TARGETS = List.of(
            ScheduleRequest.class, ScheduleResponse.class, ScheduleRunResponse.class,
            ScheduleRunDetailResponse.class, ScheduleRunDetailResponse.ClusterSnapshot.class,
            ScheduleRunDetailResponse.Decision.class,
            CronPreviewResponse.class, CronPreviewResponse.NextRun.class, StatusChangeRequest.class);

    @DisplayName("아홉 record 의 모든 필드에 설명이 있고, 설명은 필드 이름의 반복이 아니다")
    @Test
    void every_component_carries_a_description_that_is_not_its_own_name() {
        int seen = 0;
        for (Class<?> type : TARGETS) {
            assertTrue(type.isRecord(), type.getSimpleName() + " 은 record 가 아니다");
            for (RecordComponent c : type.getRecordComponents()) {
                Schema schema = schemaOf(c);
                String where = type.getSimpleName() + "." + c.getName();
                assertNotNull(schema, where + ": @Schema 가 없다 — 응답에만 있고 문서에 없는 열은 정체 불명의 숫자다");
                assertFalse(schema.description().isBlank(), where + ": 설명이 비었다");
                assertNotEquals(c.getName(), schema.description().trim(), where + ": 설명이 필드 이름의 반복이다");
                assertTrue(schema.name().isEmpty(), where + ": name 으로 JSON 키를 바꾸면 응답과 문서가 갈린다");
                assertFalse(schema.hidden(), where + ": 숨긴 필드가 응답에 실린다");
                seen++;
            }
        }
        assertEquals(86, seen, "컴포넌트 수가 바뀌었다 — 필드를 늘렸으면 이 숫자와 설명을 함께 갱신한다");
    }

    @DisplayName("아홉 record 전부 클래스 레벨 설명이 있다")
    @Test
    void every_record_carries_a_type_level_description() {
        for (Class<?> type : TARGETS) {
            Schema schema = type.getAnnotation(Schema.class);
            assertNotNull(schema, type.getSimpleName() + ": 클래스 레벨 @Schema 가 없다 — 스키마 목록에 이름만 뜬다");
            assertFalse(schema.description().isBlank(), type.getSimpleName() + ": 클래스 설명이 비었다");
        }
    }

    @DisplayName("생성 요청의 필수 표기는 서버가 400 을 내는 세 필드와 정확히 같다")
    @Test
    void required_marks_on_the_request_match_the_server_side_checks() {
        Set<String> required = requiredNames(ScheduleRequest.class);
        assertEquals(Set.of("scheduleId", "name", "cronExpression"), required,
                "생성(POST) 기준 — 편집(PUT)은 부분 갱신이라 필수가 없다. 서버 검증과 표기가 갈리면 "
                        + "프런트는 필수라 믿고 보내는데 서버가 받아 주거나, 선택이라 믿고 뺐는데 400 을 만난다");
        assertEquals(Set.of("status"), requiredNames(StatusChangeRequest.class));
    }

    @DisplayName("응답 record 에는 필수 표기가 없다")
    @Test
    void response_records_carry_no_required_marks() {
        for (Class<?> type : List.of(ScheduleResponse.class, ScheduleRunResponse.class,
                ScheduleRunDetailResponse.class, ScheduleRunDetailResponse.ClusterSnapshot.class,
                ScheduleRunDetailResponse.Decision.class,
                CronPreviewResponse.class, CronPreviewResponse.NextRun.class)) {
            assertTrue(requiredNames(type).isEmpty(),
                    type.getSimpleName() + ": 응답에 필수 표기가 있다 — 응답은 서버가 채우는 것이라 필수 개념이 없다");
        }
    }

    @DisplayName("스케줄 ID 예시는 서버 정규식에 맞고, cron 예시는 서버 검증을 통과한다")
    @Test
    void examples_pass_the_same_checks_the_server_applies() {
        String idExample = schemaOf(component(ScheduleRequest.class, "scheduleId")).example();
        assertTrue(idExample.matches(Schedule.ID_PATTERN), "스케줄 ID 예시가 서버 정규식에 맞지 않는다: " + idExample);
        assertEquals(idExample, schemaOf(component(ScheduleResponse.class, "scheduleId")).example(),
                "요청·응답의 스케줄 ID 예시가 다르다");

        CronSupport cron = new CronSupport(new SchedulerProperties());
        for (Class<?> type : List.of(ScheduleRequest.class, ScheduleResponse.class, CronPreviewResponse.class)) {
            String example = schemaOf(component(type, "cronExpression")).example();
            assertDoesNotThrow(() -> cron.validate(example),
                    type.getSimpleName() + ": cron 예시가 서버 검증에서 400 이 된다: " + example);
        }

        String tz = schemaOf(component(CronPreviewResponse.class, "timezone")).example();
        assertDoesNotThrow(() -> ZoneId.of(tz), "시간대 예시가 유효한 지역 id 가 아니다: " + tz);
        assertEquals(new SchedulerProperties().getTimezone(), tz, "시간대 예시가 서버 기본값과 다르다");
    }

    @DisplayName("배치 순서 허용값은 열거형 이름과 같고 예시는 그 안에 있다")
    @Test
    void placement_order_allowable_values_match_the_enum() {
        Schema schema = schemaOf(component(ScheduleRequest.class, "placementOrder"));
        Set<String> declared = Set.of(schema.allowableValues());
        Set<String> actual = Arrays.stream(Schedule.PlacementOrder.values()).map(Enum::name).collect(Collectors.toSet());
        assertEquals(actual, declared, "배치 순서 허용값이 열거형과 갈렸다");
        assertTrue(declared.contains(schema.example()), "배치 순서 예시가 허용값에 없다: " + schema.example());
    }

    @DisplayName("상태 전이 허용값은 열거형 전부가 아니라 전이표가 실제로 받는 값과 같다")
    @Test
    void status_allowable_values_are_the_reachable_targets_not_the_whole_enum() {
        Schema schema = schemaOf(component(StatusChangeRequest.class, "status"));
        Set<String> declared = new TreeSet<>(Arrays.asList(schema.allowableValues()));

        // 오라클은 열거형 목록이 아니라 전이표다 — 어떤 출발 상태에서든 도달 가능한 target 만 이 API 가 받는다.
        Set<String> reachable = new TreeSet<>();
        for (Workload.WorkloadStatus from : Workload.WorkloadStatus.values()) {
            for (Workload.WorkloadStatus to : Workload.WorkloadStatus.values()) {
                if (from.canTransitionTo(to)) reachable.add(to.name());
            }
        }
        assertEquals(reachable, declared,
                "허용값이 전이표와 갈렸다 — 열거형 전부를 적으면 이 API 로 절대 갈 수 없는 상태가 선택지로 노출된다");
        assertFalse(declared.contains(Workload.WorkloadStatus.SCHEDULED.name()),
                "배치 상태는 엔진의 일이라 이 API 로 갈 수 없다");
        assertTrue(declared.contains(schema.example()), "상태 예시가 허용값에 없다: " + schema.example());
        assertEquals(EnumSet.allOf(Workload.WorkloadStatus.class).size() - 1, declared.size(),
                "전이표가 바뀌었다 — 설명 문구의 예외 서술(FAILED → PENDING)도 함께 확인한다");
    }

    // ── 도우미 ─────────────────────────────────────────────────

    /**
     * record 컴포넌트의 애너테이션은 대상에 RECORD_COMPONENT 가 없으면 컴포넌트에 남지 않고 접근자·필드로만
     * 전파된다. 그래서 접근자에서 읽고, 없으면 컴포넌트 자체를 본다.
     */
    private static Schema schemaOf(RecordComponent c) {
        Schema onAccessor = c.getAccessor().getAnnotation(Schema.class);
        return onAccessor != null ? onAccessor : c.getAnnotation(Schema.class);
    }

    private static RecordComponent component(Class<?> type, String name) {
        return Arrays.stream(type.getRecordComponents()).filter(c -> c.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(type.getSimpleName() + " 에 " + name + " 이 없다"));
    }

    private static Set<String> requiredNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .filter(c -> schemaOf(c) != null && schemaOf(c).requiredMode() == Schema.RequiredMode.REQUIRED)
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }
}
