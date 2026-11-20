package com.innogrid.aims.scheduler.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link NodeScore}의 {@code details}는 어느 생성자로 만들었든 <b>사후에 키를 더할 수 있어야</b> 한다.
 *
 * <p>전력 엔진은 후보 점수를 다 매긴 뒤 {@code details().put("통합여유", …)}로 진단 키를 더한다. 세 엔진은
 * 지금 {@code LinkedHashMap}을 직접 넘겨 문제가 없지만, details 없이 만드는 편의 생성자가 불변 맵을 채우면
 * 후처리를 공통 단계로 올리는 순간 그 경로에서 {@code UnsupportedOperationException}이 난다. 이 테스트가
 * 그 함정을 미리 막는다.</p>
 */
class NodeScoreDetailsMutabilityTest {

    @DisplayName("details 없이 만든 NodeScore 에도 진단 키를 사후에 더할 수 있다")
    @Test
    void convenience_constructor_yields_mutable_details() {
        NodeScore ns = new NodeScore("mdc-a", "node-1", 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, true, null);

        assertDoesNotThrow(() -> ns.details().put("통합여유", 0.1));
        assertEquals(0.1, ns.details().get("통합여유"));
        assertEquals(1, ns.details().size(), "빈 맵에서 시작한다");
    }
}
