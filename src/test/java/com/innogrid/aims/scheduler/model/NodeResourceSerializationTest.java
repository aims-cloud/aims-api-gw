package com.innogrid.aims.scheduler.model;

import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 노드 조회 응답의 필드 계약을 고정한다.
 *
 * <p>{@code SchedulerController} 는 {@code NodeResource} 를 DTO 없이 그대로 응답으로 내보낸다.
 * 그래서 이 모델에 {@code get} 접두어 메서드를 하나 추가하면 {@code GET /api/v1/scheduler/nodes}
 * 응답에 <b>필드가 조용히 늘어난다.</b> 스케줄링 내부 계산용 메서드를 추가하다가 API 계약을
 * 깨뜨리는 일을 막기 위해, 여기서 키 집합을 통째로 못 박는다.</p>
 *
 * <p>계산용 메서드는 {@code get} 없이 이름 짓는다 — Jackson 이 {@code get}/{@code is} 접두어만
 * 자동 인식하므로 그것만으로 직렬화 대상에서 빠진다. 이 규칙이 실제로 지켜지는지도 함께 확인한다.</p>
 *
 * <p>스프링이 구성한 {@code ObjectMapper} 를 그대로 주입받아 쓴다. 직접 만든 매퍼로는 실제
 * 응답과 설정이 어긋날 수 있어 계약 검증이 되지 않는다. 이 프로젝트는 Jackson 2 와 3 이
 * 함께 올라와 있고 <b>HTTP 응답을 만드는 것은 Jackson 3({@code tools.jackson})</b> 이다 —
 * {@code com.fasterxml} 쪽 매퍼는 예외 처리·프로메테우스 파싱에서 직접 생성해 쓰는 별개
 * 인스턴스라 응답 계약과 무관하다.</p>
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
        Set<String> actual = fieldsOf(sample());

        assertEquals(EXPECTED_FIELDS, actual,
                "노드 조회 응답의 필드가 바뀌었다. 의도한 변경이면 EXPECTED_FIELDS 를 고치고 프런트에 공지한다");
    }

    /**
     * 계산용 메서드를 {@code get} 없이 지으면 응답에 나타나지 않는다는 전제를 고정한다.
     * {@code powerHeadroomPercent()} 는 제약 평가에 쓰지만 응답에는 없어야 한다.
     */
    @Test
    void get_접두어가_없는_계산_메서드는_직렬화되지_않는다() {
        Set<String> actual = fieldsOf(sample());

        assertFalse(actual.contains("powerHeadroomPercent"),
                "get 접두어 없는 메서드가 직렬화됐다. 계산용 메서드 명명 규칙의 전제가 깨졌다");
    }

    /** 예약이 잡혀 있어도 필드 구성은 그대로여야 한다. 값만 달라진다. */
    @Test
    void 예약이_있어도_필드_구성은_같다() {
        NodeResource node = sample();
        node.reserve(2, 4096, 1);

        assertEquals(EXPECTED_FIELDS, fieldsOf(node));
    }

    private Set<String> fieldsOf(NodeResource node) {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> map = objectMapper.convertValue(node, java.util.Map.class);
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
