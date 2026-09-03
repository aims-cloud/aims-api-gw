package com.innogrid.aims.scheduler.dto;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.innogrid.aims.scheduler.dto.ResponseJsonGoldenTest.Samples;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 응답 DTO 4종이 엔티티·모델을 그대로 직렬화한 것과 <b>키·값이 완전히 같은 JSON</b> 을 내는지.
 *
 * <p>이것이 컨트롤러를 DTO 로 바꿔도 프런트가 아무 차이를 못 느낀다는 증명이다. 값이 채워진 표본과
 * 비어 있는 표본을 둘 다 본다 — null 이 0 으로, 0 이 null 로 바뀌는 것도 계약 위반이다.</p>
 */
@SpringBootTest
class ResponseDtoEquivalenceTest {

    @Autowired
    ObjectMapper objectMapper;

    @DisplayName("노드 응답은 모델 직렬화와 같다")
    @Test
    void node_response_matches_model() {
        NodeResource node = new NodeResource("mdc-01", "node-1", 8, 16384, 2, 40, 50, 30, 250, 500, 55, 1000, 500);
        node.setThermalState(NodeResource.ThermalState.WARNING);
        node.setPowerSource(NodeResource.PowerSource.IPMI);
        node.reserve(2, 4096, 1);

        assertEquals(toMap(node), toMap(NodeResponse.from(node)));
    }

    @DisplayName("노드 응답은 예약이 없어도 같다")
    @Test
    void node_response_without_reservation() {
        NodeResource node = new NodeResource("mdc-01", "node-2", 2, 3916, 0, 12.67, 68.7, 40, 0, 500, 0, 0, 0);

        assertEquals(toMap(node), toMap(NodeResponse.from(node)));
    }

    @DisplayName("워크로드 응답은 엔티티 직렬화와 같다")
    @Test
    void workload_response_matches_entity() {
        Workload full = Samples.workload();
        Workload bare = new Workload("bare", 1, 512);

        assertEquals(toMap(full), toMap(WorkloadResponse.from(full)));
        assertEquals(toMap(bare), toMap(WorkloadResponse.from(bare)), "null 인 retryCount·resourceType 이 그대로 null 이어야 한다");
    }

    @DisplayName("배치 이력 응답은 엔티티 직렬화와 같다")
    @Test
    void history_response_matches_entity() {
        SchedulingHistory linked = Samples.history();
        SchedulingHistory standalone = new SchedulingHistory(1L, "w", "mdc-01", "n1", 0.5, "r", "weighted", "default");

        assertEquals(toMap(linked), toMap(SchedulingHistoryResponse.from(linked)));
        assertEquals(toMap(standalone), toMap(SchedulingHistoryResponse.from(standalone)), "success·scheduleRunId 가 null 로 유지돼야 한다");
    }

    @DisplayName("배치 정책 응답은 엔티티 직렬화와 같다")
    @Test
    void policy_response_matches_entity() {
        SchedulingPolicy custom = Samples.policy();
        SchedulingPolicy defaults = new SchedulingPolicy("default", "기본");

        assertEquals(toMap(custom), toMap(SchedulingPolicyResponse.from(custom)));
        assertEquals(toMap(defaults), toMap(SchedulingPolicyResponse.from(defaults)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        return objectMapper.convertValue(value, Map.class);
    }
}
