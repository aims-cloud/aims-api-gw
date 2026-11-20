package com.innogrid.aims.scheduler.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.dto.ScheduleResponse;
import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 배치 순서 선택지 — 기본은 현행(우선순위 → 나이), 크기 우선은 같은 우선순위 안에서만 큰 것부터. 요청은 관대 파서.
 *
 * <p>워크로드는 GPU 종류로 만들어 다른 테스트의 VM 워크로드와 섞이지 않게 한다. 나이(createdAt)는 저장 순서로 정해진다.</p>
 */
@SpringBootTest
class PlacementOrderTest {

    @Autowired ScheduleService scheduleService;
    @Autowired ScheduleExecutionService executionService;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired ObjectMapper objectMapper;

    @AfterEach
    void clean() {
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith("po-")).forEach(workloadRepository::delete);
        scheduleRepository.findAll().stream().filter(s -> s.getScheduleId().startsWith("po-")).forEach(scheduleRepository::delete);
    }

    @DisplayName("기본은 현행 비교자 그대로이고 응답에는 해석된 값이 나온다")
    @Test
    void default_order_resolved_in_response() throws Exception {
        ScheduleResponse r = scheduleService.create(request("po-default", null));

        assertEquals(Schedule.PlacementOrder.PRIORITY_THEN_AGE, r.placementOrder(), "저장 null → 해석값 AGE");
        assertNull(scheduleRepository.findById(r.id()).orElseThrow().getPlacementOrder(), "저장은 null(현행)");
        assertSame(ScheduleExecutionService.BY_PRIORITY_THEN_AGE,
                ScheduleExecutionService.orderFor(scheduleRepository.findById(r.id()).orElseThrow()), "현행 비교자 객체 그대로");

        Map<String, Object> json = objectMapper.readValue(objectMapper.writeValueAsString(r), Map.class);
        assertEquals(24, json.size(), "스케줄 응답 23 → 24 필드");
        assertEquals("PRIORITY_THEN_AGE", json.get("placementOrder"));
    }

    @DisplayName("요청은 관대 파서 size age 정식 이름을 받고 모르는 값은 400")
    @Test
    void lenient_parser_unknown_order_400() {
        assertEquals(Schedule.PlacementOrder.PRIORITY_THEN_SIZE, scheduleService.create(request("po-size", "size")).placementOrder());
        assertEquals(Schedule.PlacementOrder.PRIORITY_THEN_SIZE, scheduleService.create(request("po-size2", " priority_then_size ")).placementOrder());
        assertEquals(Schedule.PlacementOrder.PRIORITY_THEN_AGE, scheduleService.create(request("po-age", "AGE")).placementOrder());

        BusinessException e = assertThrows(BusinessException.class, () -> scheduleService.create(request("po-bad", "biggest")));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains("배치 순서"), e.getMessage());
    }

    @DisplayName("크기 우선은 같은 우선순위 안에서 CPU 메모리 나이 순이고 우선순위가 먼저다")
    @Test
    void size_order_cpu_memory_age_after_priority() {
        // 저장 순서 = 나이. 전부 GPU 종류.
        Workload small1 = save("po-small1", 1, 512, Workload.WorkloadPriority.NORMAL);
        Workload big = save("po-big", 4, 1024, Workload.WorkloadPriority.NORMAL);
        Workload mid = save("po-mid", 2, 4096, Workload.WorkloadPriority.NORMAL);
        Workload mid2 = save("po-mid2", 2, 1024, Workload.WorkloadPriority.NORMAL);
        Workload smallHigh = save("po-small-high", 1, 256, Workload.WorkloadPriority.HIGH);

        Schedule age = scheduleRepository.findById(scheduleService.create(request("po-order-age", null)).id()).orElseThrow();
        Schedule size = scheduleRepository.findById(scheduleService.create(request("po-order-size", "size")).id()).orElseThrow();

        List<String> byAge = executionService.findTargets(age).stream().map(Workload::getName).toList();
        List<String> bySize = executionService.findTargets(size).stream().map(Workload::getName).toList();

        assertEquals(List.of("po-small-high", "po-small1", "po-big", "po-mid", "po-mid2"), byAge, "현행: HIGH 먼저, 나머지는 나이순");
        assertEquals(List.of("po-small-high", "po-big", "po-mid", "po-mid2", "po-small1"), bySize,
                "크기 우선: HIGH 먼저(우선순위가 첫 기준), NORMAL 안에서 CPU 4 → 2(메모리 4096) → 2(1024) → 1");
        assertNotNull(small1); assertNotNull(big); assertNotNull(mid); assertNotNull(mid2); assertNotNull(smallHigh);
    }

    private static ScheduleRequest request(String id, String order) {
        return new ScheduleRequest(id, id, Workload.ResourceType.GPU, "0 0 2 * * ?", false, true, "po", "weighted", null, null, null, order);
    }

    private Workload save(String name, int cpu, int mem, Workload.WorkloadPriority priority) {
        Workload w = new Workload(name, cpu, mem);
        w.setResourceType(Workload.ResourceType.GPU);
        w.setPriority(priority);
        return workloadRepository.saveAndFlush(w);
    }
}
