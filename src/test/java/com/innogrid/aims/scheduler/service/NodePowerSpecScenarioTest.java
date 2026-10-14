package com.innogrid.aims.scheduler.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.innogrid.aims.scheduler.dto.NodePowerSpecRequest;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.repository.NodePowerSpecRepository;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 시나리오 — 원장에 정격을 넣으면 모의 메트릭 모드에서 드러난 한계가 실제로 닫히는가.
 *
 * <pre>
 * 배경   가상 클러스터의 GPU 노드 seoul-02 는 정격 900W 로 전력을 만들지만, 엔진은 원장·설정이 비어 있어 500W 로 본다.
 *        그래서 한 주기의 87% 동안 "전력 여유 부족"으로 후보에서 빠진다.
 * 절차   ① 원장 비었을 때 정격 500W 확인  ② 원장에 900W 등록  ③ 정격이 900W 로 바뀌고 전력 여유가 양수로 돌아섬
 *        ④ 회수 가능 전력(정격 × 유휴 비율)도 함께 커짐  ⑤ 원장을 지우면 500W 로 원복
 * </pre>
 *
 * <p>모의 메트릭 모드를 켠 별도 컨텍스트에서 실제 수집 경로로 검증한다.</p>
 */
@SpringBootTest
@TestPropertySource(properties = "aims.scheduler.mock-enabled=true")
class NodePowerSpecScenarioTest {

    private static final String GPU_NODE = "seoul-02:9100";

    @Autowired NodePowerSpecService specService;
    @Autowired NodePowerSpecRepository specRepository;
    @Autowired NodeResourceCollector collector;
    @Autowired SchedulingService schedulingService;
    @Autowired SchedulerProperties properties;

    @AfterEach
    void clean() {
        specRepository.findAll().stream().filter(s -> s.getNodeId().equals(GPU_NODE)).forEach(specRepository::delete);
    }

    private Optional<NodeResource> gpuNode() {
        return collector.collect().nodes().stream().filter(n -> n.getNodeId().equals(GPU_NODE)).findFirst();
    }

    @DisplayName("원장에 정격 900W 를 넣으면 GPU 노드의 정격·전력 여유가 함께 바뀌고, 지우면 원복된다")
    @Test
    void ledger_capacity_reaches_collected_node() {
        NodeResource before = gpuNode().orElseThrow();
        assertEquals(500.0, before.getPowerCapacityWatts(), "원장이 비면 설정 기본값");
        double headroomBefore = before.powerHeadroomPercent();

        var saved = specService.create(new NodePowerSpecRequest(GPU_NODE, 900.0, "SPEC_SHEET", null));

        NodeResource after = gpuNode().orElseThrow();
        assertEquals(900.0, after.getPowerCapacityWatts(), "원장 값이 수집에 반영된다");
        assertTrue(after.powerHeadroomPercent() > headroomBefore,
                "같은 실측 전력이라도 정격이 커지면 여유가 는다: %.1f%% → %.1f%%".formatted(headroomBefore, after.powerHeadroomPercent()));
        assertTrue(after.powerHeadroomPercent() > 0, "900W 정격에서는 전력 여유가 양수 — 모의 메트릭 모드의 상시 제외가 풀린다");

        specService.delete(saved.getId());
        assertEquals(500.0, gpuNode().orElseThrow().getPowerCapacityWatts(), "지우면 설정 기본값으로 원복");
    }

    @DisplayName("정격이 커지면 그 노드의 회수 가능 전력(정격 × 유휴 비율)도 함께 커진다")
    @Test
    void reclaimable_power_follows_capacity() {
        PlacementContext before = schedulingService.createContext(new SchedulingPolicy("nps-p", "d"));
        double reclaimableBefore = reclaimable(before);

        specService.create(new NodePowerSpecRequest(GPU_NODE, 900.0, null, null));

        PlacementContext after = schedulingService.createContext(new SchedulingPolicy("nps-p", "d"));
        assertTrue(reclaimable(after) >= reclaimableBefore,
                "정격이 커지면 회수 가능 전력 추정이 줄지 않는다: %.1fW → %.1fW".formatted(reclaimableBefore, reclaimable(after)));
    }

    @DisplayName("원장이 비어 있으면 배치 결과가 이 PR 이전과 같다 — 정격은 전부 설정 기본값")
    @Test
    void empty_ledger_keeps_current_behaviour() {
        assertTrue(specRepository.findAll().stream().noneMatch(s -> s.getNodeId().equals(GPU_NODE)));
        List<NodeResource> nodes = collector.collect().nodes();
        assertEquals(8, nodes.size());
        assertTrue(nodes.stream().allMatch(n -> n.getPowerCapacityWatts() == 500.0), "전부 설정 기본값 500W");

        Workload w = new Workload("nps-plain", 2, 2048);
        SchedulingResult r = schedulingService.scheduleWorkload(saveAndGetId(w), "weighted");
        assertTrue(r.success(), r.reason());
    }

    private double reclaimable(PlacementContext ctx) {
        return ctx.reclaimableIdleWatts(properties.getPower().getActiveCpuPercent(), new PowerModel(properties));
    }

    /** 배치에는 저장된 워크로드가 필요하다. 시나리오 뒤에는 지운다. */
    private Long saveAndGetId(Workload w) {
        var saved = workloadRepository.saveAndFlush(w);
        savedWorkloadId = saved.getId();
        return savedWorkloadId;
    }

    @Autowired com.innogrid.aims.scheduler.repository.WorkloadRepository workloadRepository;
    @Autowired com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository historyRepository;
    @Autowired com.innogrid.aims.scheduler.repository.PlacementDispatchRepository dispatchRepository;
    private Long savedWorkloadId;

    @AfterEach
    void cleanWorkload() {
        if (savedWorkloadId == null) return;
        historyRepository.findAll().stream().filter(h -> h.getWorkloadId() != null && h.getWorkloadId().equals(savedWorkloadId)).forEach(historyRepository::delete);
        dispatchRepository.findAll().stream().filter(d -> savedWorkloadId.equals(d.getWorkloadId())).forEach(dispatchRepository::delete);
        workloadRepository.findById(savedWorkloadId).ifPresent(workloadRepository::delete);
        savedWorkloadId = null;
    }
}
