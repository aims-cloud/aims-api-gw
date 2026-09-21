package com.innogrid.aims.scheduler.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
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
    /** 디스크 총량 질의의 마운트 설정 — 검사 없이 통과한 노드를 알릴 때 함께 찍는다. 없이 조립하면(시험) 기본값. */
    private final String diskMountPoint;

    @org.springframework.beans.factory.annotation.Autowired
    public PlacementService(WorkloadRepository workloadRepository,
                            SchedulingHistoryRepository historyRepository,
                            EngineRegistry engineRegistry,
                            com.innogrid.aims.scheduler.config.SchedulerProperties properties) {
        this.workloadRepository = workloadRepository;
        this.historyRepository = historyRepository;
        this.engineRegistry = engineRegistry;
        this.diskMountPoint = properties == null ? "/" : properties.getDiskMountPoint();
    }

    /** 설정 없이 조립하는 형태(기존 시험 경로). */
    public PlacementService(WorkloadRepository workloadRepository,
                            SchedulingHistoryRepository historyRepository,
                            EngineRegistry engineRegistry) {
        this(workloadRepository, historyRepository, engineRegistry, null);
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
        ConstraintEvaluator.warnUncheckedDisk(log, workload, result, context, diskMountPoint);

        if (result.success()) {
            workload.assignTo(result.selectedMdcId(), result.selectedNodeId(),
                    result.finalScore(), result.reason());
            context.reserve(result.selectedNodeId(), workload);
            SchedulingHistory history = toHistory(workload, context, result, engine);
            // 성공 여부는 실행 연결과 무관하게 남긴다 — 예전엔 단건 배치 행이 NULL 로 남아 성공 필터에서 빠졌다.
            // runId 가 없으면 연결만 비고 success 는 기록된다.
            history.linkToRun(scheduleRunId, true);
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

    /**
     * 결정 한 건을 이력 행으로 옮긴다.
     *
     * <p><b>차원별 점수는 선택된 노드 것이어야 한다.</b> 엔진이 돌려주는 후보 목록은 제외된 노드까지 포함해
     * 총점순으로 정렬돼 있고(엔진 3종 공통), 승자는 제외되지 않은 노드 중에서 고른다. 그래서 점수를 깎지 않는
     * 제약(GPU·디스크·링크 DOWN·MDC 예산)에 걸린 노드가 1위이면 목록의 첫 항목이 승자가 아니다. 예전엔 그 첫
     * 항목을 저장해, 한 행 안에서 총점·사유·노드 상태는 승자 것인데 차원별 점수만 다른 노드 것이었다.</p>
     */
    private SchedulingHistory toHistory(Workload workload, PlacementContext context,
                                        SchedulingResult result, SchedulingEngine engine) {
        SchedulingHistory history = new SchedulingHistory(
                workload.getId(), workload.getName(),
                result.selectedMdcId(), result.selectedNodeId(),
                result.finalScore(), result.reason(),
                engine.getId(), context.policy().getName());

        history.setCandidateCount(context.nodes().size());

        // 승자를 nodeId 로 찍어 쓴다 — 아래 context.node(...) 와 같은 키여야 점수와 사용률이 같은 노드에서 온다.
        // 못 찾으면(배치 실패) 0 으로 둔다. 그 행은 총점·노드 상태도 0 이라 행 전체가 일관된다.
        result.candidateScores().stream()
                .filter(score -> score.nodeId().equals(result.selectedNodeId()))
                .findFirst()
                .ifPresent(selected -> history.setScoreDetails(
                        selected.cpuScore(), selected.memoryScore(),
                        selected.powerScore(), selected.temperatureScore(), selected.networkScore()));
        context.node(result.selectedNodeId())
                .ifPresent(n -> history.setNodeStatus(
                        n.getCpuUsagePercent(), n.getMemoryUsagePercent(),
                        n.getPowerWatts(), n.getTemperatureCelsius()));

        return history;
    }
}
