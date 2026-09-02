package com.innogrid.aims.scheduler.model;

import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.innogrid.aims.scheduler.dto.NodeResponse;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 노드 조회 응답의 필드 계약을 고정한다.
 *
 * <p>응답 형태의 주인은 이제 {@link NodeResponse} 다. 예전에는 {@code NodeResource} 를 DTO 없이 그대로
 * 내보내 모델에 {@code get} 메서드를 하나 추가하면 {@code GET /api/v1/scheduler/nodes} 응답에 필드가
 * 조용히 늘었다. DTO 도입 후에는 그 위험이 사라졌지만, 계약 자체(21필드)는 프런트와의 약속이라
 * 여기서 여전히 못 박는다 — DTO 에 컴포넌트를 추가하면 이 테스트가 막는다.</p>
 *
 * <p>계산용 메서드를 {@code get} 없이 짓는 규율은 유지한다. 이제는 API 보호 수단이 아니라
 * "모델을 조회 응답과 혼동하지 않게 하는 관례"다. 그 전제가 지켜지는지도 함께 확인한다.</p>
 *
 * <p>스프링이 구성한 {@code ObjectMapper} 를 그대로 주입받아 쓴다. 이 프로젝트는 Jackson 2 와 3 이
 * 함께 올라와 있고 <b>HTTP 응답을 만드는 것은 Jackson 3({@code tools.jackson})</b> 이다.</p>
 */
@SpringBootTest
class NodeResourceSerializationTest {

    /** 현재 노드 조회 응답이 담는 필드 전체. 늘리거나 줄이려면 프런트와 합의가 필요하다. */
    private static final Set<String> EXPECTED_FIELDS = new TreeSet<>(Set.of(
            "mdcId", "nodeId",
            "totalCpu", "totalMemoryMb", "totalGpu",
            "cpuUsagePercent", "memoryUsagePercent", "diskUsagePercent",
            "powerWatts", "powerCapacityWatts", "temperatureCelsius",
            "networkRxBps", "networkTxBps",
            "reservedCpu", "reservedMemoryMb", "reservedGpu",
            "thermalState", "powerSource",
            "availableCpu", "availableMemoryMb", "availableGpu"
    ));

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void 노드_조회_응답_필드가_변하지_않는다() {
        Set<String> actual = fieldsOf(NodeResponse.from(sample()));

        assertEquals(EXPECTED_FIELDS, actual,
                "노드 조회 응답의 필드가 바뀌었다. 의도한 변경이면 EXPECTED_FIELDS 를 고치고 프런트에 공지한다");
    }

    /**
     * 모델의 계산용 메서드를 {@code get} 없이 지으면 직렬화되지 않는다는 관례를 고정한다.
     * {@code powerHeadroomPercent()}·{@code snapshot()}·{@code effectiveCpuUsagePercent()} 가 그 예다.
     */
    @Test
    void 모델의_get_접두어_없는_계산_메서드는_직렬화되지_않는다() {
        Set<String> actual = fieldsOf(sample());

        assertFalse(actual.contains("powerHeadroomPercent"));
        assertFalse(actual.contains("snapshot"));
        assertFalse(actual.contains("effectiveCpuUsagePercent"));
        assertEquals(EXPECTED_FIELDS, actual, "모델 자체의 직렬화도 아직 21필드다 — DTO 와 어긋나면 동치 테스트가 깨진다");
    }

    /** 예약이 잡혀 있어도 필드 구성은 그대로여야 한다. 값만 달라진다. */
    @Test
    void 예약이_있어도_필드_구성은_같다() {
        NodeResource node = sample();
        node.reserve(2, 4096, 1);

        assertEquals(EXPECTED_FIELDS, fieldsOf(NodeResponse.from(node)));
    }

    private Set<String> fieldsOf(Object value) {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> map = objectMapper.convertValue(value, java.util.Map.class);
        return new TreeSet<>(map.keySet());
    }

    private NodeResource sample() {
        return new NodeResource("mdc-01", "node-1",
                8, 16384, 2,
                40, 50, 30,
                250, 500, 55,
                1000, 500);
    }
}
