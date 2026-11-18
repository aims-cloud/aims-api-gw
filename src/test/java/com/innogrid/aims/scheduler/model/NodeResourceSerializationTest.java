package com.innogrid.aims.scheduler.model;

import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.innogrid.aims.scheduler.dto.NodeResponse;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 노드 조회 응답의 필드 목록을 확인한다.
 *
 * <p>응답 형태는 {@link NodeResponse}가 정한다. 필드 목록(27필드)은 프런트와의 약속이라 여기서 못 박는다.
 * DTO에 컴포넌트를 추가하면 이 테스트가 막는다.</p>
 *
 * <p>디스크 축 3필드({@code diskCapacityGb}·{@code reservedDiskGb}·{@code availableDiskGb})는 DTO에만 있고, 모델은
 * 그 셋을 {@code get} 없는 이름으로 두므로 모델 직렬화는 24필드다. 두 집합의 차가 정확히 그 세 키인지와, 계산용
 * 메서드를 {@code get} 없이 짓는 관례(모델을 조회 응답과 혼동하지 않게 함)가 지켜지는지도 함께 확인한다.</p>
 *
 * <p>스프링이 구성한 {@code ObjectMapper}를 그대로 주입받아 쓴다. 이 프로젝트는 Jackson 2와 3이
 * 함께 올라와 있고 <b>HTTP 응답을 만드는 것은 Jackson 3({@code tools.jackson})</b>이다.</p>
 */
@SpringBootTest
class NodeResourceSerializationTest {

    /** 모델이 {@code get}으로 내보내는 필드 — 디스크 축을 뺀 24개. */
    private static final Set<String> MODEL_FIELDS = new TreeSet<>(Set.of(
            "mdcId", "nodeId",
            "totalCpu", "totalMemoryMb", "totalGpu",
            "cpuUsagePercent", "memoryUsagePercent", "diskUsagePercent",
            "powerWatts", "powerCapacityWatts", "temperatureCelsius",
            "networkRxBps", "networkTxBps",
            "gpuUsagePercent", "gpuTemperatureCelsius", "gpuPowerWatts",
            "reservedCpu", "reservedMemoryMb", "reservedGpu",
            "thermalState", "powerSource",
            "availableCpu", "availableMemoryMb", "availableGpu"
    ));

    /** DTO에만 있는 디스크 축 3필드. 모델은 이 셋을 {@code get} 없는 이름으로 둔다. */
    private static final Set<String> DISK_FIELDS = new TreeSet<>(Set.of(
            "diskCapacityGb", "reservedDiskGb", "availableDiskGb"
    ));

    /** 현재 노드 조회 응답이 담는 필드 전체(27). 늘리거나 줄이려면 프런트와 합의가 필요하다. */
    private static final Set<String> EXPECTED_FIELDS = union(MODEL_FIELDS, DISK_FIELDS);

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.addAll(b);
        return out;
    }

    @Autowired
    ObjectMapper objectMapper;

    @DisplayName("노드 조회 응답 필드가 변하지 않는다")
    @Test
    void response_fields_unchanged() {
        Set<String> actual = fieldsOf(NodeResponse.from(sample()));

        assertEquals(27, EXPECTED_FIELDS.size(), "모델 24 + 디스크 3 — 겹치는 이름이 생기면 여기서 잡힌다");
        assertEquals(EXPECTED_FIELDS, actual,
                "노드 조회 응답의 필드가 바뀌었다. 의도한 변경이면 EXPECTED_FIELDS 를 고치고 프런트에 공지한다");
    }

    /**
     * 모델의 계산용 메서드를 {@code get} 없이 지으면 직렬화되지 않는다는 관례를 확인한다.
     * {@code powerHeadroomPercent()}·{@code snapshot()}·{@code effectiveCpuUsagePercent()}가 그 예다.
     */
    @DisplayName("모델의 get 접두어 없는 계산 메서드는 직렬화되지 않는다")
    @Test
    void non_get_methods_not_serialized() {
        Set<String> actual = fieldsOf(sample());

        assertFalse(actual.contains("powerHeadroomPercent"));
        assertFalse(actual.contains("snapshot"));
        assertFalse(actual.contains("effectiveCpuUsagePercent"));
        assertEquals(MODEL_FIELDS, actual, "모델 자체의 직렬화는 디스크 축을 뺀 24필드다 — DTO 와 어긋나면 동치 테스트가 깨진다");
        assertFalse(actual.contains("diskCapacityGb"));
        assertFalse(actual.contains("availableDiskGb"));
        assertFalse(actual.contains("reservedDiskGb"));
    }

    /** 세 디스크 필드가 제 자리로 가고, 가용량은 모델 계산식(총량 × (1 − 사용률) − 예약, 0 하한)과 같다. */
    @DisplayName("디스크 총량·예약·가용이 응답의 제 칸에 실린다")
    @Test
    void disk_fields_land_in_their_own_slots() {
        NodeResource node = sample().withDiskCapacityGb(100);   // 사용률 30%
        node.reserve(0, 0, 0, 20);

        NodeResponse r = NodeResponse.from(node);

        assertEquals(100L, r.diskCapacityGb());
        assertEquals(20L, r.reservedDiskGb());
        assertEquals(50L, r.availableDiskGb(), "100 × 0.7 − 20");
        assertEquals(30.0, r.diskUsagePercent(), "사용률 칸은 그대로");
        assertEquals(node.availableDiskGb(), r.availableDiskGb(), "모델 계산식과 동일");
    }

    /** 총량을 모르는 노드(0)는 가용도 0이고, 예약은 그래도 쌓인다 — 화면이 0/0을 "모름"으로 읽는 근거. */
    @DisplayName("디스크 총량을 모르면 가용은 0 이고 예약은 남는다")
    @Test
    void unknown_disk_capacity_reports_zero_available() {
        NodeResource node = sample();   // 총량 미설정 = 0
        node.reserve(0, 0, 0, 5);

        NodeResponse r = NodeResponse.from(node);

        assertEquals(0L, r.diskCapacityGb());
        assertEquals(0L, r.availableDiskGb());
        assertEquals(5L, r.reservedDiskGb());
    }

    /** 세 GPU 필드가 각자 제 자리로 간다 — 셋 다 double이라 인자 순서가 바뀌어도 컴파일되므로 값으로 잡는다. */
    @DisplayName("GPU 사용률·온도·전력이 응답의 제 칸에 실린다")
    @Test
    void gpu_fields_land_in_their_own_slots() {
        NodeResource node = sample().withGpuTelemetry(new com.innogrid.aims.scheduler.model.GpuTelemetry(75.5, 71, 410.25));

        NodeResponse r = NodeResponse.from(node);

        assertEquals(75.5, r.gpuUsagePercent());
        assertEquals(71.0, r.gpuTemperatureCelsius());
        assertEquals(410.25, r.gpuPowerWatts());
        assertEquals(55.0, r.temperatureCelsius(), "섀시 온도는 그대로");
        assertEquals(250.0, r.powerWatts(), "섀시 전력은 그대로");
    }

    /** 예약이 잡혀 있어도 필드 구성은 그대로여야 한다. 값만 달라진다. */
    @DisplayName("예약이 있어도 필드 구성은 같다")
    @Test
    void field_set_same_with_reservations() {
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
