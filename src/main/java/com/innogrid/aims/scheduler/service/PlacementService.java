package com.innogrid.aims.scheduler.service;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.SchedulingEngine;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

/**
 * 워크로드 한 건을 배치하고 결과를 저장한다.
 *
 * <p>메트릭 조회는 이미 끝난 상태로 컨텍스트에 담겨 오므로, 이 서비스의 트랜잭션은
 * DB 저장에만 걸린다. 외부 호출이 트랜잭션 안에 들어가지 않게 하려고 수집·조율을 맡는
 * {@link SchedulingService}와 분리했다.</p>
 */
@Service
public class PlacementService {

    private static final Logger log = LoggerFactory.getLogger(PlacementService.class);

    private final WorkloadRepository workloadRepository;
    private final SchedulingHistoryRepository historyRepository;
    private final EngineRegistry engineRegistry;
    /** 디스크 총량 질의의 마운트 설정 — 검사 없이 통과한 노드를 알릴 때 함께 찍는다. 없이 조립하면(테스트) 기본값. */
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

    /** 설정 없이 조립하는 형태(테스트 경로). */
    public PlacementService(WorkloadRepository workloadRepository,
                            SchedulingHistoryRepository historyRepository,
                            EngineRegistry engineRegistry) {
        this(workloadRepository, historyRepository, engineRegistry, null);
    }

    /**
     * 컨텍스트를 기준으로 워크로드를 배치한다.
     * 컨텍스트에 예약은 잡지 않는다 — 예약이 필요한 호출자(스케줄 실행)가 커밋된 결정만 잡는다(안에서 잡으면 저장·커밋 실패 때 예약이 남는다).
     */
    @Transactional
    public SchedulingResult place(Workload workload, PlacementContext context, String engineId) {
        return place(workload, context, engineId, null);
    }

    /**
     * 스케줄 실행의 일부로 배치한다. {@code scheduleRunId}가 있으면 <b>실패한 결정도 이력으로 남긴다.</b> 실행 상세에서
     * 어떤 워크로드가 왜 배치되지 못했는지 보여야 제약 조건 기반 스케줄링의 근거가 된다. 단건 스케줄링 API로 부를 때는
     * 성공한 결정만 남긴다.
     *
     * <p>컨텍스트에 예약은 잡지 않는다. 스케줄 실행의 배치 루프가 이 메서드가 정상 반환한 뒤(=결정이 커밋된 뒤)
     * 잡는다. 여기서 잡으면 이력·워크로드 저장이나 커밋이 실패해도 예약이 남아, 그 예외로 루프가 끝난 뒤 남기는
     * 실행 후 스냅샷에 저장되지 않은 배치가 반영된다.</p>
     *
     * <p>{@code scheduleRunId}가 있고 저장된 워크로드면 저장 직전에 상태가 아직 대기·실패일 때만 결정한 상태로
     * 옮긴다. 그 사이 겹친 다른 실행·단건 배치가 배치했거나 지웠으면 워크로드를 쓰지 않고 {@link #recordSkip}으로
     * 건너뜀 한 건만 남긴다. 옮겼으면 그 행을 다시 읽어 재시도 회계(횟수, 마지막 실패 시각)를 이어받은 뒤 결정을
     * 얹어, 그 사이 겹친 실패 결정이나 되살리기가 바꾼 회계를 덮지 않는다.</p>
     */
    @Transactional
    public SchedulingResult place(Workload workload, PlacementContext context, String engineId,
                                  Long scheduleRunId) {
        SchedulingEngine engine = engineRegistry.resolve(engineId);

        log.info("스케줄링 시작: workload={}, engine={}, candidates={}",
                workload.getName(), engine.getName(), context.nodes().size());

        SchedulingResult result = engine.schedule(workload, context);
        ConstraintEvaluator.warnUncheckedDisk(log, workload, result, context, diskMountPoint);

        if (scheduleRunId != null && workload.getId() != null) {
            // 실행은 배치 직전에 다시 읽지만 그 읽기와 이 저장은 다른 트랜잭션이다. 그 사이 겹친 다른 실행·단건 배치가 이 워크로드를
            // 배치했거나 지웠으면 덮지 않는다 — 아직 대기·실패일 때만 결정한 상태로 옮기고, 아니면 워크로드를 쓰지 않고 건너뛴다.
            Workload.WorkloadStatus decided = result.success()
                    ? Workload.WorkloadStatus.SCHEDULED : Workload.WorkloadStatus.FAILED;
            if (workloadRepository.updateStatusIfIn(workload.getId(), ScheduleExecutionService.TARGET_STATUSES, decided) == 0) {
                String reason = workloadRepository.findStatusById(workload.getId())
                        .map(status -> "배치 건너뜀 — 배치하는 사이 상태가 " + status + " 로 바뀜")
                        .orElse("배치 건너뜀 — 배치하는 사이 삭제됨");
                return recordSkip(workload, context, engineId, scheduleRunId, reason);
            }
            // 잡은 행의 재시도 회계를 이어받는다 — 이 트랜잭션은 앞서 이 워크로드를 읽지 않았으므로(받은 것은 분리된 사본 · 실행
            // 클래스에 트랜잭션이 없고 open-in-view는 꺼져 있다) 여기서 읽는 행은 잡은 뒤의 최신 값이다.
            workloadRepository.findById(workload.getId()).ifPresent(workload::continueRetryAccountingFrom);
        }

        if (result.success()) {
            workload.assignTo(result.selectedMdcId(), result.selectedNodeId(),
                    result.finalScore(), result.reason());
            SchedulingHistory history = toHistory(workload, context, result, engine);
            // 성공 여부는 실행 연결과 무관하게 남긴다 — NULL이면 단건 배치 행이 성공 필터에서 빠진다.
            // runId가 없으면 연결만 비고 success는 기록된다.
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
     * 스케줄 실행이 건너뛴 워크로드(배치 직전에 다시 읽었거나 저장 직전 조건부 갱신이 0 행)를 실패 결정 한 건으로 남긴다.
     *
     * <p>워크로드는 쓰지 않는다 — 지워졌거나 다른 경로로 상태가 바뀐 것이라 재시도 횟수를 올리거나 배정을 바꾸면
     * 안 된다. 실행 결과가 센 대상마다 실행 상세의 결정 목록에 한 행이 있도록 사유와 함께 이력 한 행(후보 0)만 남긴다.
     * 이력의 워크로드 id·이름은 넘겨받은 사본의 값이다 — 다시 읽기에서 건너뛰면 대상 조회 때, 저장 직전 확인에서 건너뛰면 다시 읽은 때의 값(지워졌으면 없는 id).</p>
     */
    @Transactional
    public SchedulingResult recordSkip(Workload polled, PlacementContext context, String engineId,
                                       Long scheduleRunId, String reason) {
        SchedulingEngine engine = engineRegistry.resolve(engineId);
        SchedulingHistory history = new SchedulingHistory(
                polled.getId(), polled.getName(), null, null, 0, reason,
                engine.getId(), context.policy().getName());
        history.setCandidateCount(0);
        history.linkToRun(scheduleRunId, false);
        historyRepository.save(history);
        log.info("배치 건너뜀: workload={}({}), {}", polled.getName(), polled.getId(), reason);
        return SchedulingResult.failure(reason, context.policy().getName());
    }

    /**
     * 결정 한 건을 이력 행으로 옮긴다.
     *
     * <p><b>차원별 점수는 선택된 노드 것이어야 한다.</b> 엔진이 돌려주는 후보 목록은 제외된 노드까지 포함해
     * 총점순으로 정렬돼 있고(엔진 3종 공통), 승자는 제외되지 않은 노드 중에서 고른다. 그래서 점수를 깎지 않는
     * 제약(GPU·디스크·링크 DOWN·MDC 예산)에 걸린 노드가 1위이면 목록의 첫 항목이 승자가 아니다. 그 첫 항목을
     * 저장하면 한 행 안에서 총점·사유·노드 상태는 승자 것인데 차원별 점수만 다른 노드 것이 된다.</p>
     */
    private SchedulingHistory toHistory(Workload workload, PlacementContext context,
                                        SchedulingResult result, SchedulingEngine engine) {
        SchedulingHistory history = new SchedulingHistory(
                workload.getId(), workload.getName(),
                result.selectedMdcId(), result.selectedNodeId(),
                result.finalScore(), result.reason(),
                engine.getId(), context.policy().getName());

        history.setCandidateCount(context.nodes().size());
        history.setExclusionCodes(exclusionCodesOf(result));

        // 승자를 nodeId로 찍어 쓴다 — 아래 context.node(...)와 같은 키여야 점수와 사용률이 같은 노드에서 온다.
        // 못 찾으면(배치 실패) 0으로 둔다. 그 행은 총점·노드 상태도 0이라 행 전체가 일관된다.
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

    /**
     * 후보 노드의 제외 사유 코드를 코드별로 센다 — {@code {"COOLING_DOWN":1,"INSUFFICIENT_RESOURCE":3}}.
     *
     * <p>엔진은 성공·실패 양쪽 모두 제외된 노드까지 포함한 후보 목록을 돌려준다. 이력이 선택된 노드 한 건만 쓰고
     * 나머지를 버리면 사유는 문장 하나({@code reason})로만 남아 "어느 제약이 가장 먼저 걸려 빠진 후보가 몇이었나"를
     * 셀 수 없다. 적격 후보(코드 없음)는 세지 않고, 제외가 하나도 없으면 {@code null}. 키는 코드 이름 오름차순이라
     * 같은 결정은 같은 문자열이 되고, 키가 열거형 이름·값이 정수라 이스케이프가 필요 없어 직접 조립한다. 키 토큰은
     * {@link SchedulingHistory#exclusionCodeKey} — 배치 이력 페이징의 {@code exclusionCode} 필터가 같은 토큰으로 찾는다. 값의 뜻은
     * {@link SchedulingHistory}의 열 설명에 있다.</p>
     */
    static String exclusionCodesOf(SchedulingResult result) {
        if (result == null) return null;
        Map<String, Long> counts = result.candidateScores().stream()
                .map(SchedulingResult.NodeScore::exclusionCode)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Enum::name, TreeMap::new, Collectors.counting()));
        if (counts.isEmpty()) return null;
        return counts.entrySet().stream()
                .map(e -> SchedulingHistory.exclusionCodeKey(e.getKey()) + e.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }
}
