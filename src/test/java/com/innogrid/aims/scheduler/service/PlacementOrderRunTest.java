package com.innogrid.aims.scheduler.service;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.dto.ScheduleRunDetailResponse;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 스케줄 <b>즉시 실행 경로 끝까지</b> — 배치 순서 선택지가 실행 상세의 결정 순서에 그대로 나타나는가.
 *
 * <p>테스트 환경은 메트릭 저장소가 없어 수집기를 대역으로 바꿔 배포 VM과 같은 노드 1대(2코어, CPU 10%)를 준다. 후보가 0대면
 * 실행이 "활성 노드가 없습니다"로 끝나 결정이 기록되지 않기 때문이다. 기대 결과도 VM과 같다 — 2코어는 자원 부족, 1코어 하나만 성공.</p>
 */
@TestPropertySource(properties = "aims.scheduler.retry-backoff-minutes=0")   // 순서를 보는 테스트라 재시도 간격을 끈다
@SpringBootTest
class PlacementOrderRunTest {

    @Autowired ScheduleService scheduleService;
    @Autowired ScheduleExecutionService executionService;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ScheduleRunRepository runRepository;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired SchedulingHistoryRepository historyRepository;
    @MockitoBean NodeResourceCollector collector;

    @AfterEach
    void clean() {
        historyRepository.findAll().stream().filter(h -> h.getWorkloadName() != null && h.getWorkloadName().startsWith("por-")).forEach(historyRepository::delete);
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith("por-")).forEach(workloadRepository::delete);
        scheduleRepository.findAll().stream().filter(s -> s.getScheduleId().startsWith("por-")).forEach(s -> scheduleService.delete(List.of(s.getId())));
    }

    @DisplayName("크기순 스케줄의 즉시 실행은 결정을 큰 워크로드부터 기록하고 나이순은 등록 순으로 기록한다")
    @Test
    void run_now_records_decisions_in_order() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                List.of(new NodeResource("mdc-01", "t:9100", 2, 3900, 0, 10, 60, 0, 0, 500, 0, 0, 0)), List.of()));
        save("por-w1", 1); save("por-w2", 2); save("por-w3", 1);

        long sizeId = scheduleService.create(request("por-size", "size")).id();
        ScheduleRun sizeRun = executionService.runNow(sizeId, "tester");
        ScheduleRunDetailResponse sizeDetail = scheduleService.runDetail(sizeId, sizeRun.getId());
        List<String> sizeOrder = sizeDetail.decisions().stream().map(ScheduleRunDetailResponse.Decision::workloadName).toList();

        // 첫 실행으로 w1은 SCHEDULED(대상 아님), w2·w3은 FAILED(재시도 대상). 새 워크로드 3건을 더해 나이순 실행 —
        // 대상은 FAILED 재시도 2건 + 새 3건, 전부 NORMAL이라 등록 순이어야 한다.
        save("por-x1", 1); save("por-x2", 2); save("por-x3", 1);
        long ageId = scheduleService.create(request("por-age", null)).id();
        ScheduleRun ageRun = executionService.runNow(ageId, "tester");
        List<String> ageOrder = scheduleService.runDetail(ageId, ageRun.getId()).decisions().stream()
                .map(ScheduleRunDetailResponse.Decision::workloadName).toList();

        assertEquals(List.of("por-w2", "por-w1", "por-w3"), sizeOrder, "크기순: 2코어 → 1코어(등록 순)");
        assertEquals(List.of("por-w2", "por-w3", "por-x1", "por-x2", "por-x3"), ageOrder, "나이순: 등록 순(크기 무관 — x2 의 2코어가 x1 뒤)");
        assertEquals(3, sizeDetail.decisions().size());
        List<Boolean> sizeSuccess = sizeDetail.decisions().stream().map(ScheduleRunDetailResponse.Decision::success).toList();
        assertEquals(List.of(false, true, false), sizeSuccess, "2코어는 가용 1.8 에 자원 부족, 첫 1코어 성공(예약), 다음 1코어는 가용 0.8 에 실패 — VM 과 동일");
        // 실패 결정은 후보 1대가 자원 부족으로 빠진 집계를, 성공 결정은 제외가 없어 null을 — 이력 응답과 같은 문자열 그대로
        List<String> sizeCodes = sizeDetail.decisions().stream().map(ScheduleRunDetailResponse.Decision::exclusionCodes).toList();
        // List.of는 null 원소를 받지 않아 Arrays.asList로 만든다
        assertEquals(Arrays.asList("{\"INSUFFICIENT_RESOURCE\":1}", null, "{\"INSUFFICIENT_RESOURCE\":1}"), sizeCodes,
                "실행 상세의 결정 목록이 이력 행의 제외 코드 집계를 그대로 싣는다");
    }

    private static ScheduleRequest request(String id, String order) {
        return new ScheduleRequest(id, id, Workload.ResourceType.GPU, "0 0 2 * * ?", false, true, "por", "power", null, null, null, order);
    }

    private void save(String name, int cpu) {
        Workload w = new Workload(name, cpu, 64);
        w.setResourceType(Workload.ResourceType.GPU);
        workloadRepository.saveAndFlush(w);
    }
}
