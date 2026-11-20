package com.innogrid.aims.scheduler.service;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.dto.ScheduleDryRunResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRunPreviewResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 미리보기 · 모의 실행이 쓰는 열거형(엔진 출처 · 대상 0건 사유)이 응답 DTO 안이 아니라 model 패키지 최상위에 있는지.
 *
 * <p>서비스의 결정({@link EngineChoice} · 대상 없음 판정)이 응답 DTO 안 타입에 기대면, 응답 모양을 바꿀 때 서비스 결정까지
 * 함께 바뀐다. 열거형 타입 이름은 소스에 쓰지 않고 성분 타입에서 읽는다.</p>
 */
class PreviewEnumLocationTest {

    @DisplayName("엔진 출처와 대상 0건 사유는 model 패키지 최상위 열거형이고 두 응답 안에는 열거형 선언이 없다")
    @Test
    void preview_enums_live_in_model_package() {
        Class<?> engineSource = componentType(EngineChoice.class, "source");
        Class<?> emptyReason = componentType(ScheduleExecutionService.EmptyTargetOutcome.class, "reason");
        for (Class<?> type : List.of(engineSource, emptyReason)) {
            assertTrue(type.isEnum(), type.getName());
            assertEquals("com.innogrid.aims.scheduler.model", type.getPackageName(), type.getName());
            assertNull(type.getEnclosingClass(), type.getName() + " 는 다른 클래스 안에 두지 않는다");
        }
        assertSame(engineSource, componentType(ScheduleRunPreviewResponse.class, "engineSource"));
        assertSame(engineSource, componentType(ScheduleDryRunResponse.class, "engineSource"));
        assertSame(emptyReason, componentType(ScheduleRunPreviewResponse.class, "emptyReason"));
        assertSame(emptyReason, componentType(ScheduleDryRunResponse.class, "emptyReason"));
        for (Class<?> response : List.of(ScheduleRunPreviewResponse.class, ScheduleDryRunResponse.class)) {
            List<String> nestedEnums = Arrays.stream(response.getDeclaredClasses()).filter(Class::isEnum).map(Class::getSimpleName).toList();
            assertEquals(List.of(), nestedEnums, response.getSimpleName() + " 안에 열거형을 두지 않는다");
        }
    }

    private static Class<?> componentType(Class<?> record, String component) {
        return Arrays.stream(record.getRecordComponents())
                .filter(c -> c.getName().equals(component))
                .findFirst()
                .orElseThrow(() -> new AssertionError(record.getSimpleName() + " 에 " + component + " 가 없다"))
                .getType();
    }
}
