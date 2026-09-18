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
 * 전력 최적화 추세 응답의 <b>현행 형태 고정</b> — 구간 에너지를 더하는 작업이 기존 열을 건드리지 않았음을 보인다.
 *
 * <p>기존 계약 시험({@code PowerOptimizationTrendApiTest})은 필드 집합을 {@code Set} 으로 견준다. 집합은 순서를
 * 모르고, 열을 뒤에 덧붙여도 통과한다. 여기서는 <b>이름과 순서</b>를 리터럴로 적어 못 박는다 — 지점 배열은 화면이
 * 그대로 그리는 자리라 순서가 바뀌면 그래프가 어긋난다.</p>
 */
class PowerOptimizationTrendShapeTest {

    /** 구간 에너지를 더하기 전의 최상위 5열. */
    private static final List<String> TOP_COMPONENTS = List.of("from", "to", "step", "points", "message");

    /** 지점 11열. 이 열들은 이번 작업에서 하나도 바뀌지 않는다. */
    private static final List<String> POINT_COMPONENTS = List.of(
            "at", "totalNodes", "activeNodes", "idleNodes",
            "totalPowerWatts", "reclaimableIdleWatts",
            "avgPeiRaw", "minPeiRaw", "maxPeiRaw",
            "evaluableNodes", "measured");

    @DisplayName("추세 응답의 기존 5열이 이름과 순서 그대로 앞에 있다")
    @Test
    void the_existing_top_level_columns_keep_their_names_and_order() {
        assertEquals(TOP_COMPONENTS, componentsOf(PowerOptimizationTrendResponse.class).subList(0, TOP_COMPONENTS.size()),
                "기존 열의 이름이나 순서가 바뀌었다 — 추세 화면이 그리는 재료가 통째로 어긋난다");
    }

    @DisplayName("지점은 11열이고 이름과 순서가 그대로다")
    @Test
    void a_point_keeps_all_eleven_columns() {
        // 지점은 늘어나도 안 된다. 구간 에너지는 구간 전체의 요약이지 시각마다의 값이 아니다.
        assertEquals(POINT_COMPONENTS, componentsOf(PowerOptimizationTrendResponse.Point.class),
                "지점에 열이 늘었다 — 구간 요약은 지점이 아니라 최상위에 둔다");
    }

    @DisplayName("두 열 목록에 중복이 없다")
    @Test
    void the_column_lists_have_no_duplicate() {
        assertEquals(TOP_COMPONENTS.size(), new TreeSet<>(TOP_COMPONENTS).size());
        assertEquals(POINT_COMPONENTS.size(), new TreeSet<>(POINT_COMPONENTS).size());
        Set<String> shared = new TreeSet<>(TOP_COMPONENTS);
        shared.retainAll(POINT_COMPONENTS);
        assertEquals(Set.of(), shared, "같은 이름이 두 자리에 있으면 읽는 쪽이 어느 것인지 헷갈린다");
    }

    private static List<String> componentsOf(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }
}
