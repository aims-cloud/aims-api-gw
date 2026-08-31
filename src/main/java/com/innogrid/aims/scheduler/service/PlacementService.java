package com.innogrid.aims.scheduler.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.SchedulingEngine;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

/**
 * 워크로드 한 건을 배치하고 결과를 저장한다.
 *
 * <p>메트릭 조회는 이미 끝난 상태로 컨텍스트에 담겨 오므로, 이 서비스의 트랜잭션은
 * DB 저장에만 걸린다. 외부 호출이 트랜잭션 안에 들어가지 않게 하려고 수집·조율을 맡는
 * {@link SchedulingService} 와 분리했다.</p>
 */
@Service
public class PlacementService {

    private static final Logger log = LoggerFactory.getLogger(PlacementService.class);

    private final WorkloadRepository workloadRepository;
    private final SchedulingHistoryRepository historyRepository;
    private final EngineRegistry engineRegistry;

    public PlacementService(WorkloadRepository workloadRepository,
                            SchedulingHistoryRepository historyRepository,
                            EngineRegistry engineRegistry) {
        this.workloadRepository = workloadRepository;
        this.historyRepository = historyRepository;
        this.engineRegistry = engineRegistry;
    }

    /**
     * 컨텍스트를 기준으로 워크로드를 배치한다.
     * 성공하면 배치분을 컨텍스트에 예약으로 잡아 다음 워크로드가 이를 인지하게 한다.
     */
    @Transactional
    public SchedulingResult place(Workload workload, PlacementContext context, String engineId) {
        return place(workload, context, engineId, null);
    }

    /**
     * 스케줄 실행의 일부로 배치한다.
     *
     * <p>{@code scheduleRunId} 가 있으면 <b>실패한 결정도 이력으로 남긴다.</b> 실행 상세에서
     * 어떤 워크로드가 왜 배치되지 못했는지 보여야 명세 A1(제약 조건 기반 스케줄링)의 근거가
     * 된다. 단건 스케줄링 API 로 부를 때는 기존과 같이 성공한 결정만 남긴다.</p>
     */
    @Transactional
    public SchedulingResult place(Workload workload, PlacementContext context, String engineId,
                                  Long scheduleRunId) {
        SchedulingEngine engine = engineRegistry.resolve(engineId);

        log.info("스케줄링 시작: workload={}, engine={}, candidates={}",
                workload.getName(), engine.getName(), context.nodes().size());

        SchedulingResult result = engine.schedule(workload, context);

        if (result.success()) {
            workload.assignTo(result.selectedMdcId(), result.selectedNodeId(),
                    result.finalScore(), result.reason());
            context.reserve(result.selectedNodeId(), workload);
            SchedulingHistory history = toHistory(workload, context, result, engine);
            if (scheduleRunId != null) history.linkToRun(scheduleRunId, true);
            historyRepository.save(history);
        } else {
            workload.markFailed(result.reason());
            if (scheduleRunId != null) {
                SchedulingHistory history = toHistory(workload, context, result, engine);
                history.linkToRun(scheduleRunId, false);
                historyRepository.save(history);
            }
        }

        workloadRepository.save(workload);

        log.info("스케줄링 완료: workload={}, success={}, node={}",
                workload.getName(), result.success(), result.selectedNodeId());

        return result;
    }

    private SchedulingHistory toHistory(Workload workload, PlacementContext context,
                                        SchedulingResult result, SchedulingEngine engine) {
        SchedulingHistory history = new SchedulingHistory(
                workload.getId(), workload.getName(),
                result.selectedMdcId(), result.selectedNodeId(),
                result.finalScore(), result.reason(),
                engine.getId(), context.policy().getName());

        history.setCandidateCount(context.nodes().size());

        if (!result.candidateScores().isEmpty()) {
            var best = result.candidateScores().get(0);
            history.setScoreDetails(best.cpuScore(), best.memoryScore(),
                    best.powerScore(), best.temperatureScore(), best.networkScore());
        }
        context.node(result.selectedNodeId())
                .ifPresent(n -> history.setNodeStatus(
                        n.getCpuUsagePercent(), n.getMemoryUsagePercent(),
                        n.getPowerWatts(), n.getTemperatureCelsius()));

        return history;
    }
}
