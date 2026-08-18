package com.innogrid.aims.scheduler.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.scheduler.engine.PowerAwareScheduler;
import com.innogrid.aims.scheduler.engine.SchedulingEngine;
import com.innogrid.aims.scheduler.engine.TemperatureAwareScheduler;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

@Service
public class SchedulingService {

    private static final Logger log = LoggerFactory.getLogger(SchedulingService.class);

    private final WorkloadRepository workloadRepository;
    private final SchedulingPolicyRepository policyRepository;
    private final SchedulingHistoryRepository historyRepository;
    private final NodeResourceCollector nodeCollector;
    private final Map<String, SchedulingEngine> engines;

    public SchedulingService(WorkloadRepository workloadRepository,
                             SchedulingPolicyRepository policyRepository,
                             SchedulingHistoryRepository historyRepository,
                             NodeResourceCollector nodeCollector,
                             WeightedScoreScheduler weightedScheduler,
                             PowerAwareScheduler powerScheduler,
                             TemperatureAwareScheduler tempScheduler) {
        this.workloadRepository = workloadRepository;
        this.policyRepository = policyRepository;
        this.historyRepository = historyRepository;
        this.nodeCollector = nodeCollector;
        this.engines = Map.of(
                "weighted", weightedScheduler,
                "power", powerScheduler,
                "temperature", tempScheduler
        );
    }

    /**
     * 워크로드를 스케줄링한다.
     * @param workloadId 스케줄링할 워크로드 ID
     * @param engineName 사용할 엔진 (weighted, power, temperature)
     */
    @Transactional
    public SchedulingResult scheduleWorkload(Long workloadId, String engineName) {
        Workload workload = workloadRepository.findById(workloadId)
                .orElseThrow(() -> new IllegalArgumentException("워크로드를 찾을 수 없습니다: " + workloadId));

        SchedulingEngine engine = engines.getOrDefault(engineName, engines.get("weighted"));

        SchedulingPolicy policy = policyRepository.findByActiveTrue()
                .orElseGet(() -> createDefaultPolicy());

        // 실시간 노드 자원 수집
        List<NodeResource> candidates = nodeCollector.collectAll();
        if (candidates.isEmpty()) {
            return SchedulingResult.failure("활성 노드가 없습니다");
        }

        log.info("스케줄링 시작: workload={}, engine={}, candidates={}",
                workload.getName(), engine.getName(), candidates.size());

        // 스케줄링 실행
        SchedulingResult result = engine.schedule(workload, candidates, policy);

        // 결과 저장
        if (result.success()) {
            workload.assignTo(result.selectedMdcId(), result.selectedNodeId(),
                    result.finalScore(), result.reason());

            // 이력 저장
            SchedulingHistory history = new SchedulingHistory(
                    workload.getId(), workload.getName(),
                    result.selectedMdcId(), result.selectedNodeId(),
                    result.finalScore(), result.reason(), engineName, policy.getName());
            history.setCandidateCount(candidates.size());

            // 선택된 노드의 상세 정보 기록
            if (!result.candidateScores().isEmpty()) {
                var best = result.candidateScores().get(0);
                history.setScoreDetails(best.cpuScore(), best.memoryScore(),
                        best.powerScore(), best.temperatureScore(), best.networkScore());
            }
            candidates.stream()
                    .filter(n -> n.getNodeId().equals(result.selectedNodeId()))
                    .findFirst()
                    .ifPresent(n -> history.setNodeStatus(
                            n.getCpuUsagePercent(), n.getMemoryUsagePercent(),
                            n.getPowerWatts(), n.getTemperatureCelsius()));

            historyRepository.save(history);
        } else {
            workload.markFailed(result.reason());
        }

        workloadRepository.save(workload);

        log.info("스케줄링 완료: workload={}, success={}, node={}",
                workload.getName(), result.success(), result.selectedNodeId());

        return result;
    }

    /**
     * 현재 노드 자원 상태를 조회한다 (스케줄링 전 미리보기).
     */
    public List<NodeResource> getNodeResources() {
        return nodeCollector.collectAll();
    }

    /**
     * 사용 가능한 스케줄링 엔진 목록.
     */
    public List<Map<String, String>> getAvailableEngines() {
        return engines.entrySet().stream()
                .map(e -> Map.of("id", e.getKey(), "name", e.getValue().getName()))
                .toList();
    }

    private SchedulingPolicy createDefaultPolicy() {
        SchedulingPolicy policy = new SchedulingPolicy("default", "기본 스케줄링 정책");
        return policyRepository.save(policy);
    }
}
