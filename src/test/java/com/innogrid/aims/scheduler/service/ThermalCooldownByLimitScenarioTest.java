package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.ThermalCooldownResponse;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.ExclusionReason;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 스케줄이 낮춘 온도 상한으로 걸린 냉각 대기와 원래 정책의 배치 — 공유 평가기를 쓰는 실제 경로.
 *
 * <p>스케줄의 온도 상한은 id 없는 정책 사본에 실린다({@code applyOverrides}). 그 사본으로 평가해 뜨거운 노드에 냉각 대기를
 * 건 뒤, 원래 정책으로 같은 노드를 평가한다. 공유 냉각 상태가 다른 클래스로 새지 않게 앞뒤에서 이 노드들의 대기를 지운다.</p>
 */
@SpringBootTest
class ThermalCooldownByLimitScenarioTest {

    private static final String HOT = "tcl-hot:9100";
    private static final String COOL = "tcl-cool:9100";

    @Autowired SchedulingService schedulingService;
    @Autowired ScheduleExecutionService executionService;
    @Autowired ThermalModel thermalModel;
    @Autowired SchedulerProperties properties;
    @MockitoBean NodeResourceCollector collector;

    @BeforeEach
    @AfterEach
    void clearCooldowns() {
        thermalModel.clearCritical(HOT);
        thermalModel.clearCritical(COOL);
    }

    @DisplayName("스케줄이 낮춘 온도 상한으로 걸린 냉각 대기는 원래 정책의 배치를 막지 않는다")
    @Test
    void schedule_lowered_limit_cooldown_does_not_block_policy_placement() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(List.of(node(HOT, 70), node(COOL, 40)), List.of()));
        SchedulingPolicy policy = new SchedulingPolicy("tcl-p", "d");                       // 기본 상한 75℃
        Schedule schedule = new Schedule("tcl-s", "tcl-s", "0 0 * * * *");
        schedule.setMaxTemperatureCelsius(60.0);
        SchedulingPolicy lowered = executionService.applyOverrides(policy, schedule);
        Workload draft = new Workload("tcl-draft", 1, 1024);

        SchedulingResult.NodeScore byLowered = hot(schedulingService.evaluateIn(draft, "weighted", schedulingService.createContext(lowered)));
        assertEquals(ExclusionReason.TEMPERATURE_EXCEEDED, byLowered.exclusionCode(), byLowered.exclusionReason());

        SchedulingResult.NodeScore byPolicy = hot(schedulingService.evaluateIn(draft, "weighted", schedulingService.createContext(policy)));
        assertTrue(byPolicy.eligible(), "70℃ 는 75℃ 상한을 넘지 않았다 — " + byPolicy.exclusionReason());

        List<ThermalCooldownResponse> cooldowns = schedulingService.getCoolingDownNodes();
        assertEquals(1, cooldowns.size(), cooldowns.toString());
        assertEquals(HOT, cooldowns.get(0).nodeId());
        assertEquals(properties.getThermal().getCooldownMinutes(), cooldowns.get(0).remainingCooldownMinutes());
    }

    private static SchedulingResult.NodeScore hot(SchedulingResult result) {
        return result.candidateScores().stream().filter(s -> HOT.equals(s.nodeId())).findFirst().orElseThrow();
    }

    private static NodeResource node(String id, double temperature) {
        return new NodeResource("tcl-mdc", id, 8, 16384, 0, 30, 40, 30, 200, 500, temperature, 1000, 500);
    }
}
