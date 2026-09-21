package com.innogrid.aims.scheduler.dto;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 실행 이력 1행 응답의 <b>현행 형태 고정</b> — 클러스터 스냅샷을 더하는 작업이 기존 열을 건드리지 않았음을 보인다.
 *
 * <p>이 응답은 즉시 실행·실행 이력 페이지·실행 상세 세 경로가 같은 팩터리로 만든다. 열 하나가 바뀌면 세 화면이
 * 함께 움직이므로, 더하는 작업에서 기존 열의 <b>이름과 순서</b>가 그대로임을 값으로 못 박는다.</p>
 */
class ScheduleRunShapeTest {

    /** 실행 이력 1행의 15열. 이름과 순서를 그대로 적는다. */
    private static final List<String> BASE_COMPONENTS = List.of(
            "runId", "scheduleId", "scheduleKey", "triggerType", "status",
            "startedAt", "finishedAt", "message",
            "targetCount", "successCount", "failedCount", "truncated",
            "engineId", "policyName", "triggeredBy");

    @DisplayName("실행 이력 1행은 15열이고 이름과 순서가 그대로다")
    @Test
    void the_existing_columns_keep_their_names_and_order() {
        List<String> actual = Arrays.stream(ScheduleRunResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        // 접두만 보면 열을 뒤에 덧붙이는 변경을 막지 못한다. 클러스터 스냅샷을 목록이 아니라 상세에
        // 둔 것이 이번 결정이므로, 목록 1행은 늘어나지도 않아야 한다.
        assertEquals(BASE_COMPONENTS, actual,
                "실행 이력 1행의 열이 바뀌었다 — 목록은 한 화면에 수십 행이라 열을 더하지 않기로 했다");
    }

    @DisplayName("기존 열에 중복이 없다")
    @Test
    void the_existing_columns_have_no_duplicate() {
        Set<String> unique = new TreeSet<>(BASE_COMPONENTS);

        assertEquals(BASE_COMPONENTS.size(), unique.size());
    }
}
