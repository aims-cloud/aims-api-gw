package com.innogrid.aims.scheduler.dto;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실행 미리보기 응답과 모의 실행 응답의 앞 여덟 성분, 그리고 그 안의 열거형 셋(정책 · 엔진 출처 · 대상 0건 사유).
 *
 * <p>두 응답은 앞 여덟 키를 따로 선언한다(Swagger 설명이 서로 달라 합치지 않는다). 그래서 이름 · 타입 · 순서가 어긋나지
 * 않는지를 반사로 확인한다. 열거형은 타입 이름을 소스에 쓰지 않고 성분 타입에서 읽어, 열거형을 어느 패키지에 두든
 * 같은 테스트가 통과해야 한다.</p>
 */
class PreviewResponseShapeTest {

    private static final List<String> FIRST_EIGHT = List.of("id", "scheduleId", "enabled", "resourceType",
            "policyName", "policySource", "engine", "engineSource");

    @DisplayName("미리보기와 모의 실행 응답의 앞 여덟 성분은 이름 · 타입 · 순서가 같다")
    @Test
    void first_eight_components_match() {
        RecordComponent[] preview = ScheduleRunPreviewResponse.class.getRecordComponents();
        RecordComponent[] dryRun = ScheduleDryRunResponse.class.getRecordComponents();

        for (int i = 0; i < FIRST_EIGHT.size(); i++) {
            assertEquals(FIRST_EIGHT.get(i), preview[i].getName(), "미리보기 " + i + "번째");
            assertEquals(FIRST_EIGHT.get(i), dryRun[i].getName(), "모의 실행 " + i + "번째");
            assertEquals(preview[i].getGenericType(), dryRun[i].getGenericType(), FIRST_EIGHT.get(i) + " 타입");
        }
    }

    @DisplayName("두 응답의 정책 · 엔진 출처와 대상 0건 사유는 같은 열거형이고 상수 순서가 그대로다")
    @Test
    void enum_components_share_type_and_constant_order() {
        assertConstants("policySource", List.of("SCHEDULE", "RESOURCE_TYPE", "COMMON", "DEFAULT"));
        assertConstants("engineSource", List.of("SCHEDULE", "POLICY", "DEFAULT"));
        assertConstants("emptyReason", List.of("NONE", "WAITING", "EXHAUSTED"));
    }

    private static void assertConstants(String component, List<String> expected) {
        Class<?> preview = typeOf(ScheduleRunPreviewResponse.class, component);
        Class<?> dryRun = typeOf(ScheduleDryRunResponse.class, component);
        assertSame(preview, dryRun, component + " 는 두 응답이 같은 열거형을 쓴다");
        assertTrue(preview.isEnum(), component + " 는 열거형");
        List<String> names = Arrays.stream(preview.getEnumConstants()).map(c -> ((Enum<?>) c).name()).toList();
        assertEquals(expected, names, component + " 상수 순서");
    }

    private static Class<?> typeOf(Class<?> record, String component) {
        return Arrays.stream(record.getRecordComponents())
                .filter(c -> c.getName().equals(component))
                .findFirst()
                .orElseThrow(() -> new AssertionError(record.getSimpleName() + " 에 " + component + " 가 없다"))
                .getType();
    }
}
