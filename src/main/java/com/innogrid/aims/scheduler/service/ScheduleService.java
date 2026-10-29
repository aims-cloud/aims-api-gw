package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.common.util.SortSupport;
import com.innogrid.aims.scheduler.dto.CronPreviewResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.dto.ScheduleUpdateRequest;
import com.innogrid.aims.scheduler.dto.ScheduleResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRunDetailResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRunEngineReplayResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRunReplayResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRunResponse;
import com.innogrid.aims.scheduler.entity.PlacementDispatch;
import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.ScheduleRunInput;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.PlacementDispatchRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunInputRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;

import jakarta.persistence.criteria.Predicate;

/**
 * 스케줄 CRUD 와 조회.
 *
 * <p>실행 흐름은 {@link ScheduleExecutionService} 가 맡는다. 실행은 외부 메트릭 조회를
 * 포함해 오래 걸리므로 트랜잭션 경계를 분리해 두었다.</p>
 *
 * <p>요청 검증은 여기서 한다. 저장소 최신 DTO 관례가 검증 애너테이션을 쓰지 않기도 하고,
 * 서비스에서 {@code BusinessException} 을 던지는 편이 400 이 확실하게 나간다.</p>
 */
@Service
public class ScheduleService {

    /** 목록 정렬을 허용할 필드. 여기 없는 이름은 400 으로 막는다. */
    private static final Set<String> SORTABLE = Set.of(
            "scheduleId", "name", "resourceType", "cronExpression", "description",
            "autoRun", "enabled", "lastRunAt", "lastRunFinishedAt", "lastRunStatus",
            "nextRunAt", "createdAt", "updatedAt");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");
    private static final Set<String> RUN_SORTABLE = Set.of(
            "startedAt", "finishedAt", "status", "triggerType", "targetCount",
            "successCount", "failedCount");
    private static final Sort DEFAULT_RUN_SORT = Sort.by(Sort.Direction.DESC, "startedAt");

    private static final Pattern SCHEDULE_ID = Pattern.compile(Schedule.ID_PATTERN);
    private static final DateTimeFormatter LOCAL_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ScheduleRepository scheduleRepository;
    private final ScheduleRunRepository runRepository;
    private final SchedulingHistoryRepository historyRepository;
    private final ScheduleRunInputRepository inputRepository;
    private final PlacementDispatchRepository dispatchRepository;
    private final CronSupport cronSupport;
    private final EngineRegistry engineRegistry;
    private final ScheduleActorResolver actorResolver;

    public ScheduleService(ScheduleRepository scheduleRepository,
                           ScheduleRunRepository runRepository,
                           SchedulingHistoryRepository historyRepository,
                           ScheduleRunInputRepository inputRepository,
                           PlacementDispatchRepository dispatchRepository,
                           CronSupport cronSupport,
                           EngineRegistry engineRegistry,
                           ScheduleActorResolver actorResolver) {
        this.scheduleRepository = scheduleRepository;
        this.runRepository = runRepository;
        this.historyRepository = historyRepository;
        this.inputRepository = inputRepository;
        this.dispatchRepository = dispatchRepository;
        this.cronSupport = cronSupport;
        this.engineRegistry = engineRegistry;
        this.actorResolver = actorResolver;
    }

    // ── 조회 ───────────────────────────────────────────────────

    /** 목록. page 는 1부터다. */
    @Transactional(readOnly = true)
    public PageResponse<ScheduleResponse> list(String keyword, Workload.ResourceType resourceType,
                                               Boolean enabled, int page, int size, String sort) {
        Sort order = StableSort.withIdTiebreaker(SortSupport.parse(sort, SORTABLE, DEFAULT_SORT));
        Page<Schedule> found = scheduleRepository.findAll(
                filter(keyword, resourceType, enabled), PageRequest.of(page - 1, size, order));
        return toPageResponse(found.map(ScheduleResponse::from), page, size);
    }

    @Transactional(readOnly = true)
    public ScheduleResponse get(Long id) {
        return ScheduleResponse.from(require(id));
    }

    /**
     * 실행 이력. 자동 실행이 대상 0건으로 끝난 경우는 이력이 남지 않는다.
     *
     * <p>필터는 전부 선택이고 AND 로 겹친다. {@code from}·{@code to} 는 시작 시각({@code startedAt})의
     * 반개구간 {@code [from, to)} 이며 기본 창이 없다 — 배치 이력 페이징과 같은 규칙이다.
     * 시작이 끝보다 뒤면 400.</p>
     */
    @Transactional(readOnly = true)
    public PageResponse<ScheduleRunResponse> runs(Long id, ScheduleRun.RunStatus status, ScheduleRun.TriggerType triggerType,
                                                  OffsetDateTime from, OffsetDateTime to, int page, int size, String sort) {
        Schedule schedule = require(id);
        requireOrderedWindow(from, to);
        Sort order = StableSort.withIdTiebreaker(SortSupport.parse(sort, RUN_SORTABLE, DEFAULT_RUN_SORT));
        Page<ScheduleRun> found = runRepository.findAll(
                runFilter(schedule.getId(), status, triggerType, from, to), PageRequest.of(page - 1, size, order));
        return toPageResponse(found.map(ScheduleRunResponse::from), page, size);
    }

    private static void requireOrderedWindow(OffsetDateTime from, OffsetDateTime to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "기간의 시작이 끝보다 뒤입니다");
        }
    }

    /** 스케줄은 필수, 나머지는 null 이면 조건 없음. */
    private static Specification<ScheduleRun> runFilter(Long scheduleId, ScheduleRun.RunStatus status,
                                                        ScheduleRun.TriggerType triggerType,
                                                        OffsetDateTime from, OffsetDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("scheduleId"), scheduleId));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (triggerType != null) predicates.add(cb.equal(root.get("triggerType"), triggerType));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("startedAt"), from));
            if (to != null) predicates.add(cb.lessThan(root.get("startedAt"), to));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** 실행 1건의 상세 — 워크로드별 배치 결정을 함께 돌려준다. */
    @Transactional(readOnly = true)
    public ScheduleRunDetailResponse runDetail(Long id, Long runId) {
        ScheduleRun run = requireRun(id, runId);

        List<ScheduleRunDetailResponse.Decision> decisions =
                historyRepository.findByScheduleRunIdOrderByCreatedAtAscIdAsc(run.getId()).stream()
                        .map(ScheduleRunDetailResponse.Decision::from)
                        .toList();
        return new ScheduleRunDetailResponse(ScheduleRunResponse.from(run),
                ScheduleRunDetailResponse.ClusterSnapshot.from(run), decisions);
    }

    /**
     * 실행 1건을 저장한 입력으로 다시 계산해 배치 이력과 대조한다. DB 에 저장하지 않는다 — 엔진·모델은 요청마다 새로 만들고
     * 운영 냉각 대기도 건드리지 않는다.
     *
     * <p>404: 실행이 없거나 다른 스케줄의 실행이거나 저장한 입력이 없다. 409: 아직 실행 중이거나, 형식 판이 지금 읽을 수
     * 있는 판이 아니거나, 원문이 저장한 지문과 다르거나, 지금 코드로 읽을 수 없거나, 다시 만들 수 없는 엔진이다. 500: 다시
     * 계산하던 엔진이 예외를 냈다(워크로드 id 를 사유에 싣는다).</p>
     */
    @Transactional(readOnly = true)
    public ScheduleRunReplayResponse replay(Long id, Long runId) {
        StoredRun stored = loadStoredRun(id, runId);
        List<RunInputReplay.Step> steps;
        try {
            steps = RunInputReplay.replay(stored.inputs(), stored.history(), stored.rolledBack());
        } catch (RuntimeException e) {
            // 처리하지 않은 예외는 인증 없는 오류 경로로 넘어가 본문 없는 403 이 된다 — 사유를 실어 500 으로 돌려준다
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "실행 재생에 실패했습니다: " + e.getMessage());
        }
        RunInputReplay.Report report = RunInputReplay.compare(steps, stored.history(), stored.missingDispatch());
        return toReplayResponse(stored.run(), stored.input(), stored.inputs().engineId(), report);
    }

    /**
     * 실행 1건을 저장한 입력으로 운영 엔진 세 개마다 다시 계산해 워크로드별 선택 노드·성공 여부를 나란히 돌려준다. DB 에 저장하지
     * 않는다. 엔진마다 설정·모델·컨텍스트를 새로 만들고, 실제로 쓴 엔진은 한 번만 계산해 그 결과로 재생 판정도 낸다.
     *
     * <p>오류는 실행 재생과 같다(같은 검사를 거친다). 500 사유에는 엔진 id 를 함께 싣는다.</p>
     */
    @Transactional(readOnly = true)
    public ScheduleRunEngineReplayResponse replayEngines(Long id, Long runId) {
        StoredRun stored = loadStoredRun(id, runId);
        Map<String, List<RunInputReplay.Step>> byEngine = new LinkedHashMap<>();
        for (String engine : RunInputReplay.ENGINE_ORDER) {
            try {
                byEngine.put(engine, RunInputReplay.replay(stored.inputs(), engine, stored.history(), stored.rolledBack()));
            } catch (RuntimeException e) {
                throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "엔진별 재계산에 실패했습니다: 엔진 " + engine + " · " + e.getMessage());
            }
        }
        String recordedEngine = stored.inputs().engineId();
        RunInputReplay.Report report = RunInputReplay.compare(byEngine.get(recordedEngine), stored.history(),
                stored.missingDispatch());
        return toEngineReplayResponse(stored, report.verdict(), byEngine);
    }

    /** 다시 계산할 실행과 그 입력·배치 이력·전달 기록 — 실행 재생과 엔진별 재계산이 같은 검사를 거쳐 얻는다. */
    private record StoredRun(ScheduleRun run, ScheduleRunInput input, RunInputCapture.RunInputs inputs,
                             List<SchedulingHistory> history, Set<Long> rolledBack, Set<Long> missingDispatch) {}

    private StoredRun loadStoredRun(Long id, Long runId) {
        ScheduleRun run = requireRun(id, runId);
        if (run.getStatus() == ScheduleRun.RunStatus.RUNNING) {
            throw new BusinessException(HttpStatus.CONFLICT, "아직 실행 중인 실행은 재생할 수 없습니다: " + runId);
        }
        ScheduleRunInput input = inputRepository.findByScheduleRunId(run.getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "저장한 입력이 없는 실행입니다(입력 저장 전 실행·배치 전에 끝난 실행·저장 실패): " + runId));
        if (input.getFormatVersion() != RunInputCapture.FORMAT_VERSION) {
            throw new BusinessException(HttpStatus.CONFLICT, "재생할 수 없는 입력 형식 판입니다: " + input.getFormatVersion());
        }
        if (!RunInputCapture.digest(input.getPayload()).equals(input.getInputDigest())) {
            throw new BusinessException(HttpStatus.CONFLICT, "저장한 입력이 저장 때의 지문과 다릅니다: " + runId);
        }
        RunInputCapture.RunInputs inputs;
        try {
            inputs = RunInputCapture.read(input.getPayload());
        } catch (JacksonException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "저장한 입력을 지금 코드로 읽을 수 없습니다: " + runId);
        }
        if (!RunInputReplay.ENGINES.contains(inputs.engineId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "다시 만들 수 없는 엔진입니다: " + inputs.engineId());
        }

        List<SchedulingHistory> history = historyRepository.findByScheduleRunIdOrderByCreatedAtAscIdAsc(run.getId());
        List<PlacementDispatch> dispatches = dispatchRepository.findByScheduleRunIdOrderByRequestedAtAsc(run.getId());
        return new StoredRun(run, input, inputs, history, RunInputReplay.rolledBackOf(dispatches),
                RunInputReplay.missingDispatch(history, dispatches));
    }

    private static ScheduleRunEngineReplayResponse toEngineReplayResponse(StoredRun stored,
                                                                          ScheduleRunReplayResponse.Verdict verdict,
                                                                          Map<String, List<RunInputReplay.Step>> byEngine) {
        String recordedEngine = stored.inputs().engineId();
        List<ScheduleRunEngineReplayResponse.Engine> engines = new ArrayList<>();
        byEngine.forEach((engine, steps) -> engines.add(new ScheduleRunEngineReplayResponse.Engine(engine,
                engine.equals(recordedEngine),
                (int) steps.stream().filter(s -> !s.skipped() && s.result().success()).count(),
                (int) steps.stream().filter(s -> !s.skipped() && !s.result().success()).count(),
                RunInputReplay.sameAsRecorded(steps, stored.history()))));

        Map<Long, SchedulingHistory> recorded = new HashMap<>();
        stored.history().forEach(h -> recorded.put(h.getWorkloadId(), h));
        List<RunInputReplay.Step> recordedSteps = byEngine.get(recordedEngine);
        List<ScheduleRunEngineReplayResponse.Step> rows = new ArrayList<>();
        for (int i = 0; i < recordedSteps.size(); i++) {
            RunInputReplay.Step step = recordedSteps.get(i);
            SchedulingHistory h = recorded.get(step.workloadId());
            List<ScheduleRunEngineReplayResponse.Choice> choices = new ArrayList<>();
            if (!step.skipped()) {
                for (Map.Entry<String, List<RunInputReplay.Step>> e : byEngine.entrySet()) {
                    SchedulingResult r = e.getValue().get(i).result();
                    choices.add(new ScheduleRunEngineReplayResponse.Choice(e.getKey(), r.selectedNodeId(), r.success()));
                }
            }
            rows.add(new ScheduleRunEngineReplayResponse.Step(step.workloadId(), step.skipped(),
                    h == null ? null : h.getSelectedNodeId(), h == null ? null : h.getSuccess(), choices));
        }
        int skipped = (int) recordedSteps.stream().filter(RunInputReplay.Step::skipped).count();
        int rolledBack = (int) stored.inputs().workloads().stream().filter(w -> stored.rolledBack().contains(w.id())).count();
        ScheduleRunInput input = stored.input();
        return new ScheduleRunEngineReplayResponse(stored.run().getId(), input.getFormatVersion(), input.getCapturedAt(),
                recordedEngine, input.getInputDigest(), input.getKnobsDigest(), verdict, recordedSteps.size(), skipped,
                rolledBack, engines, rows);
    }

    private static ScheduleRunReplayResponse toReplayResponse(ScheduleRun run, ScheduleRunInput input, String engineId,
                                                              RunInputReplay.Report report) {
        List<ScheduleRunReplayResponse.Step> steps = report.steps().stream().map(c -> {
            SchedulingHistory h = c.recorded();
            SchedulingResult r = c.step().result();
            return new ScheduleRunReplayResponse.Step(c.step().workloadId(), c.outcome(),
                    h == null ? null : h.getSelectedNodeId(),
                    h == null ? null : h.getFinalScore(),
                    h == null ? null : h.getSuccess(),
                    r == null ? null : r.selectedNodeId(),
                    r == null ? null : r.finalScore(),
                    r == null ? null : r.success());
        }).toList();
        return new ScheduleRunReplayResponse(run.getId(), input.getFormatVersion(), input.getCapturedAt(), engineId,
                input.getInputDigest(), input.getKnobsDigest(), report.verdict(), steps.size(),
                count(steps, ScheduleRunReplayResponse.Outcome.MATCH),
                count(steps, ScheduleRunReplayResponse.Outcome.MISMATCH),
                count(steps, ScheduleRunReplayResponse.Outcome.SKIPPED),
                count(steps, ScheduleRunReplayResponse.Outcome.NOT_RECORDED),
                report.missingDispatchRecords(), steps);
    }

    private static int count(List<ScheduleRunReplayResponse.Step> steps, ScheduleRunReplayResponse.Outcome outcome) {
        return (int) steps.stream().filter(s -> s.outcome() == outcome).count();
    }

    /** 실행이 없거나 다른 스케줄의 실행이면 404. */
    private ScheduleRun requireRun(Long id, Long runId) {
        Schedule schedule = require(id);
        return runRepository.findByIdAndScheduleId(runId, schedule.getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "실행 이력을 찾을 수 없습니다: " + runId));
    }

    // ── 생성·수정·삭제 ──────────────────────────────────────────

    @Transactional
    public ScheduleResponse create(ScheduleRequest request) {
        String scheduleId = requireText(request.scheduleId(), "스케줄 ID");
        if (!SCHEDULE_ID.matcher(scheduleId).matches()) {
            throw badRequest("스케줄 ID 는 영문·숫자로 시작하고 영문·숫자·_.- 로 이루어진 50자 이내여야 합니다: "
                    + scheduleId);
        }
        if (scheduleRepository.existsByScheduleId(scheduleId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 있는 스케줄 ID 입니다: " + scheduleId);
        }

        String name = requireText(request.name(), "스케줄명", Schedule.NAME_MAX);
        String cron = requireText(request.cronExpression(), "실행 주기", Schedule.CRON_MAX);
        cronSupport.validate(cron);
        validateEngine(request.engine());

        Schedule schedule = new Schedule(scheduleId, name, cron);
        apply(schedule, request);

        ScheduleActorResolver.Actor actor = actorResolver.current();
        schedule.setCreatedBy(actor.email(), actor.name());
        schedule.setUpdatedBy(actor.email(), actor.name());

        refreshNextRunAt(schedule);
        return ScheduleResponse.from(scheduleRepository.save(schedule));
    }

    /**
     * 편집(부분 갱신). 보낸 키만 바꾸고 없는 키는 유지한다. 스케줄 ID 는 업무 키라 바꿀 수 없다.
     * cron 이나 사용 여부가 바뀌면 다음 실행 시각을 반드시 다시 계산한다 —
     * 그러지 않으면 예전 주기로 한 번 더 실행된다.
     *
     * <pre>
     * 없음                       → 404
     * 본문 없음 · 스케줄 ID 불일치 → 400
     * 칸 검증 실패                → 400 (생성과 같은 규칙 · 비울 수 있는 다섯 칸은 JSON 타입도 검사)
     * </pre>
     *
     * <p>지정 정책·엔진·전력 상한·온도 상한·설명은 명시적 null 로 비운다(정책·온도는 상속으로, 전력은 제약 없음으로 복귀).
     * <b>모든 칸을 먼저 검증한 뒤 한꺼번에 반영한다</b> — 한 칸이 400 이면 엔티티는 전혀 바뀌지 않는다.
     * 깨진 JSON 이나 리소스 종류·불리언 타입 오류처럼 본문을 읽지 못한 400 은 이 메서드에 오기 전에 프레임워크가 낸다.</p>
     */
    @Transactional
    public ScheduleResponse update(Long id, ScheduleUpdateRequest request) {
        Schedule schedule = require(id);
        if (request == null) throw badRequest("요청 본문이 필요합니다");
        if (request.scheduleId() != null && !request.scheduleId().equals(schedule.getScheduleId())) {
            throw badRequest("스케줄 ID 는 변경할 수 없습니다");
        }

        // 먼저 전부 검증해 지역 값에 담는다
        String name = request.name() == null ? null : requireText(request.name(), "스케줄명", Schedule.NAME_MAX);
        String cron = null;
        if (request.cronExpression() != null) {
            cron = requireText(request.cronExpression(), "실행 주기", Schedule.CRON_MAX);
            cronSupport.validate(cron);
        }
        Schedule.PlacementOrder order = request.placementOrder() == null ? null : parsePlacementOrder(request.placementOrder());
        Clearable<String> description = clearableText(request.description(), "설명", Schedule.DESCRIPTION_MAX);
        Clearable<String> engine = clearableEngine(request.engine());
        Clearable<Long> policyId = clearableLong(request.policyId(), "배치 정책 id");
        Clearable<Double> power = clearableNumber(request.maxPowerBudgetWatts(), "노드 전력 상한(W)");
        Clearable<Double> temperature = clearableNumber(request.maxTemperatureCelsius(), "온도 상한(℃)");

        // 그다음 한꺼번에 반영한다
        if (name != null) schedule.setName(name);
        if (cron != null) schedule.setCronExpression(cron);
        if (request.resourceType() != null) schedule.setResourceType(request.resourceType());
        if (order != null) schedule.setPlacementOrder(order);
        if (request.autoRun() != null) schedule.setAutoRun(request.autoRun());
        if (request.enabled() != null) schedule.setEnabled(request.enabled());
        if (description.sent()) schedule.setDescription(description.value());
        if (engine.sent()) schedule.setEngine(engine.value());
        if (policyId.sent()) schedule.setPolicyId(policyId.value());
        if (power.sent()) schedule.setMaxPowerBudgetWatts(power.value());
        if (temperature.sent()) schedule.setMaxTemperatureCelsius(temperature.value());

        ScheduleActorResolver.Actor actor = actorResolver.current();
        schedule.setUpdatedBy(actor.email(), actor.name());

        refreshNextRunAt(schedule);
        return ScheduleResponse.from(scheduleRepository.save(schedule));
    }

    /** 비울 수 있는 칸의 검증 결과 — 보냈는지와 그 값(명시적 null 이면 {@code null}). */
    private record Clearable<T>(boolean sent, T value) {
        static <T> Clearable<T> keep() { return new Clearable<>(false, null); }
        static <T> Clearable<T> set(T value) { return new Clearable<>(true, value); }
    }

    /** 키 없음 → 유지, 명시적 null → 비움, 문자열 → 생성과 같은 규칙(앞뒤 공백 제거 · 빈 값은 비움 · 길이), 그 외 → 400. */
    private Clearable<String> clearableText(JsonNode node, String label, int max) {
        if (node == null) return Clearable.keep();
        if (node.isNull()) return Clearable.set(null);
        if (!node.isString()) throw badRequest(label + "은(는) 문자열이어야 합니다");
        return Clearable.set(optionalText(node.asString(), label, max));
    }

    /** 키 없음 → 유지, 명시적 null·공백 → 비움(정책 기본 엔진 → 서버 기본), 등록된 엔진 → 값, 그 외 → 400. */
    private Clearable<String> clearableEngine(JsonNode node) {
        if (node == null) return Clearable.keep();
        if (node.isNull()) return Clearable.set(null);
        if (!node.isString()) throw badRequest("엔진은(는) 문자열이어야 합니다");
        validateEngine(node.asString());
        return Clearable.set(optionalText(node.asString(), "엔진", Schedule.ENGINE_MAX));
    }

    /** 키 없음 → 유지, 명시적 null → 비움, long 범위 정수 → 값, 그 외(숫자 문자열 · 소수 · 범위 초과) → 400. */
    private Clearable<Long> clearableLong(JsonNode node, String label) {
        if (node == null) return Clearable.keep();
        if (node.isNull()) return Clearable.set(null);
        if (!node.isIntegralNumber() || !node.canConvertToLong()) throw badRequest(label + "은(는) 정수여야 합니다");
        return Clearable.set(node.asLong());
    }

    /**
     * 키 없음 → 유지, 명시적 null → 비움, JSON 숫자 → 값, 그 외(숫자 문자열 포함) → 400.
     * 값은 {@code numberValue().doubleValue()} 로 꺼낸다 — {@code asDouble()} 은 double 범위를 넘는 큰 정수에서 예외를 던진다.
     * 값의 범위는 생성과 같이 검사하지 않는다.
     */
    private Clearable<Double> clearableNumber(JsonNode node, String label) {
        if (node == null) return Clearable.keep();
        if (node.isNull()) return Clearable.set(null);
        if (!node.isNumber()) throw badRequest(label + "은(는) 숫자여야 합니다");
        return Clearable.set(node.numberValue().doubleValue());
    }

    /**
     * 다중 삭제 — 목록 체크박스 기준.
     *
     * <p>실행 중인 스케줄이 하나라도 섞이면 전체를 거부한다. 일부만 지워지면 화면과
     * 실제 상태가 어긋나는데, 응답 형식상 어느 것이 실패했는지 알릴 방법이 없다.
     * 없는 ID 는 무시한다(같은 요청을 두 번 보내도 결과가 같다).</p>
     */
    @Transactional
    public void delete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw badRequest("삭제할 스케줄 ID 가 필요합니다");
        }
        List<Schedule> targets = scheduleRepository.findAllById(ids);
        if (targets.isEmpty()) return;

        List<String> running = targets.stream()
                .filter(s -> s.getLastRunStatus() == ScheduleRun.RunStatus.RUNNING)
                .map(Schedule::getScheduleId)
                .toList();
        if (!running.isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "실행 중인 스케줄은 삭제할 수 없습니다: " + String.join(", ", running));
        }

        for (Schedule schedule : targets) {
            List<ScheduleRun> runs = runRepository.findByScheduleId(schedule.getId());
            unlinkHistories(runs);
            if (!runs.isEmpty()) {
                inputRepository.deleteByScheduleRunIds(runs.stream().map(ScheduleRun::getId).toList());
            }
            runRepository.deleteAll(runs);
        }
        scheduleRepository.deleteAll(targets);
    }

    /**
     * 실행 이력을 지우기 전에 배치 결정과의 연결만 끊는다.
     * 외래키가 없어 DB 가 정리해 주지 않으며, 결정 이력 자체는 연구 근거라 남긴다.
     */
    private void unlinkHistories(List<ScheduleRun> runs) {
        if (runs.isEmpty()) return;
        List<Long> runIds = runs.stream().map(ScheduleRun::getId).toList();
        List<SchedulingHistory> histories = historyRepository.findByScheduleRunIdIn(runIds);
        histories.forEach(SchedulingHistory::unlinkRun);
        historyRepository.saveAll(histories);
    }

    // ── cron 검증 ──────────────────────────────────────────────

    /**
     * 실행 주기를 검증하고 다음 실행 시각을 미리 보여준다.
     *
     * <p>요일을 이름으로 함께 돌려준다. 도움말 모달은 Quartz 규약을 안내하는데 런타임은
     * Spring 이라 숫자 요일 해석이 하루씩 다르다. 백엔드가 변환하지만 사용자가 의도한
     * 요일에 실제로 실행되는지 눈으로 확인할 수 있어야 한다.</p>
     */
    public CronPreviewResponse previewCron(String cronExpression) {
        cronSupport.validate(cronExpression);
        ZoneId zone = cronSupport.zone();

        List<CronPreviewResponse.NextRun> runs = new ArrayList<>();
        for (OffsetDateTime at : cronSupport.nextRuns(cronExpression, CronSupport.PREVIEW_COUNT)) {
            var local = at.atZoneSameInstant(zone);
            runs.add(new CronPreviewResponse.NextRun(
                    at,
                    local.format(LOCAL_TIME),
                    local.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.KOREAN)));
        }
        return new CronPreviewResponse(cronExpression.trim(), zone.getId(), runs);
    }

    /**
     * 저장하지 않는 초안 — 폼 값을 실행이 보는 형태의 {@link Schedule} 로 바꾼다(저장 전 미리보기용).
     *
     * <p>스케줄명·실행 주기는 보지 않는다 — 폼을 채우는 중에도 부를 수 있어야 하고, 실행 주기는
     * {@code /cron/validate} 가 따로 맡는다. 검증은 생성과 같은 규칙으로 엔진 등록 여부·배치 순서·설명 길이만 본다
     * (설명은 결과에 쓰이지 않지만 길이 상한은 {@code apply} 가 생성과 같이 건다). 편집과 달리 "키 없음" 은 기존 값이
     * 아니라 기본값(종류 VM · 사용 중 · 정책 상속)이다. 어떤 행도 쓰지 않는다.</p>
     *
     * <p>스케줄명·실행 주기가 비어 있어 저장하면 안 된다 — 같은 패키지의 미리보기만 쓰도록 접근을 좁힌다.</p>
     */
    Schedule draft(ScheduleRequest request) {
        if (request == null) throw badRequest("요청 본문이 필요합니다");
        validateEngine(request.engine());
        String scheduleId = request.scheduleId() == null || request.scheduleId().isBlank()
                ? null : request.scheduleId().trim();
        Schedule schedule = new Schedule(scheduleId, null, null);
        apply(schedule, request);
        return schedule;
    }

    // ── 내부 ────────────────────────────────────────────────────

    /** 없으면 404. */
    public Schedule require(Long id) {
        if (id == null) {
            throw badRequest("스케줄 ID 가 필요합니다");
        }
        return scheduleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "스케줄을 찾을 수 없습니다: " + id));
    }

    /**
     * 다음 자동 실행 시각을 현재 기준으로 다시 계산한다.
     *
     * <p>사용 여부를 껐다 켜거나 cron 을 바꾼 뒤 예전 값을 그대로 두면 그 값이 이미
     * 과거라서 켜자마자 실행된다. 자동 실행이 꺼져 있으면 예정 시각을 비운다.</p>
     */
    public void refreshNextRunAt(Schedule schedule) {
        schedule.setNextRunAt(nextRunAt(schedule.getCronExpression(), schedule.isAutoRunnable()));
    }

    /**
     * 다음 실행 시각을 값만으로 계산한다. 자동 실행 대상이 아니면 {@code null}.
     *
     * <p>실행이 끝날 때는 엔티티가 <b>실행 시작 시점의 사본</b>이라 그 사이 바뀐 cron·자동 실행 여부를 모른다.
     * 그래서 호출자가 DB 의 최신 값을 읽어 넘길 수 있게 열어 둔다.</p>
     */
    public OffsetDateTime nextRunAt(String cronExpression, boolean autoRunnable) {
        return autoRunnable ? cronSupport.nextRunAt(cronExpression, OffsetDateTime.now()) : null;
    }

    private void apply(Schedule schedule, ScheduleRequest request) {
        if (request.resourceType() != null) schedule.setResourceType(request.resourceType());
        if (request.description() != null) {
            schedule.setDescription(optionalText(request.description(), "설명", Schedule.DESCRIPTION_MAX));
        }
        if (request.engine() != null) {
            schedule.setEngine(optionalText(request.engine(), "엔진", Schedule.ENGINE_MAX));
        }
        if (request.policyId() != null) schedule.setPolicyId(request.policyId());
        if (request.maxPowerBudgetWatts() != null) {
            schedule.setMaxPowerBudgetWatts(request.maxPowerBudgetWatts());
        }
        if (request.maxTemperatureCelsius() != null) {
            schedule.setMaxTemperatureCelsius(request.maxTemperatureCelsius());
        }
        if (request.placementOrder() != null) schedule.setPlacementOrder(parsePlacementOrder(request.placementOrder()));
        if (request.autoRun() != null) schedule.setAutoRun(request.resolveAutoRun());
        if (request.enabled() != null) schedule.setEnabled(request.resolveEnabled());
    }

    /** 배치 순서 — 정식 이름 외에 "size"·"age" 도 받는다(화면에서 짧게 보내기 쉽게). 모르는 값은 400. */
    static Schedule.PlacementOrder parsePlacementOrder(String raw) {
        String v = raw.trim().toUpperCase();
        if (v.equals("SIZE") || v.equals("PRIORITY_THEN_SIZE")) return Schedule.PlacementOrder.PRIORITY_THEN_SIZE;
        if (v.equals("AGE") || v.equals("PRIORITY_THEN_AGE")) return Schedule.PlacementOrder.PRIORITY_THEN_AGE;
        return EnumSupport.parse(Schedule.PlacementOrder.class, raw, "배치 순서");
    }

    private void validateEngine(String engine) {
        // 저장 직전에 trim 하므로 검증도 trim 한 값으로 — 그러지 않으면 "weighted " 가 알 수 없는 엔진으로 거부된다.
        String trimmed = engine == null ? null : engine.trim();
        if (trimmed != null && !trimmed.isEmpty() && !engineRegistry.contains(trimmed)) {
            throw badRequest("알 수 없는 스케줄링 엔진입니다: " + trimmed);
        }
    }

    /**
     * 길이 상한이 <b>다른 곳에서 이미 강제되는</b> 값 전용 — 지금은 스케줄 ID 하나뿐이다.
     *
     * <p>{@link Schedule#ID_PATTERN} 이 50자를 강제하므로 여기서 또 재면 중복이고, 먼저 걸리면
     * "영문·숫자로 시작하고…" 라는 기존 사유가 길이 사유로 바뀐다. 그 외의 값에는 길이를 받는 쪽을 쓴다.</p>
     */
    private String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw badRequest(label + "은(는) 필수입니다");
        }
        return value.trim();
    }

    private String requireText(String value, String label, int max) {
        if (value == null || value.isBlank()) {
            throw badRequest(label + "은(는) 필수입니다");
        }
        return limit(value.trim(), label, max);
    }

    /**
     * 컬럼 길이를 넘는 값을 400 으로 막는다.
     *
     * <p>없으면 INSERT 가 컬럼 길이에서 실패하는데, 그 예외를 잡는 곳이 없어 스프링 기본 처리가 {@code /error} 를
     * 다시 태우고 그 재요청이 인증에 막혀 <b>클라이언트는 400 이 아니라 403(본문 없음)</b> 을 받는다.
     * 같은 패키지의 다른 원장 서비스 다섯은 이미 이 형태를 쓴다.</p>
     *
     * <p><b>스케줄 ID 에는 쓰지 않는다</b> — {@link Schedule#ID_PATTERN} 이 이미 50자를 강제하고 있어
     * 중복 검증이 되고, 여기서 먼저 걸리면 기존 사유 문구가 바뀐다.</p>
     */
    private String limit(String value, String label, int max) {
        if (value.length() > max) {
            throw badRequest(String.format("%s은(는) %d자 이내여야 합니다 (현재 %d자)", label, max, value.length()));
        }
        return value;
    }

    /**
     * 선택 입력의 앞뒤 공백을 떼고, 비면 미지정(null)으로 본다.
     *
     * <p>공백만 있는 엔진 이름은 {@code validateEngine} 의 {@code isBlank()} 를 그대로 통과해 저장됐다.
     * PostgreSQL 이 {@code varchar} 의 뒤쪽 공백은 잘라 받으므로 오류도 나지 않고 값만 이상하게 남는다(실측).
     * 공백을 null 로 정규화하면 {@code ScheduleExecutionService} 가 이미 쓰는 "공백 = 미지정" 규약과 결과가 같아진다.</p>
     */
    private String optionalText(String value, String label, int max) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : limit(trimmed, label, max);
    }

    /**
     * 스프링 {@link Page} 를 저장소 표준 응답으로 옮긴다.
     * {@code PageResponse.of} 는 전체 목록을 메모리에 올린 뒤 자르는 형태라, DB 에서
     * 이미 잘라 온 결과에는 표준 생성자를 직접 쓴다.
     */
    private <T> PageResponse<T> toPageResponse(Page<T> page, int requestedPage, int size) {
        return new PageResponse<>(page.getContent(), requestedPage, size,
                page.getTotalElements(), page.getTotalPages());
    }

    private Specification<Schedule> filter(String keyword, Workload.ResourceType resourceType,
                                           Boolean enabled) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            if (keyword != null && !keyword.isBlank()) {
                // %·_ 는 문자 그대로 — 워크로드 페이징과 같은 이스케이프 규칙(escape 인자를 명시해 DB 기본값에 기대지 않는다)
                String like = LikePatterns.contains(keyword.trim().toLowerCase(Locale.ROOT));
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("scheduleId")), like, LikePatterns.ESCAPE),
                        cb.like(cb.lower(root.get("name")), like, LikePatterns.ESCAPE),
                        cb.like(cb.lower(cb.coalesce(root.get("description"), "")), like, LikePatterns.ESCAPE)));
            }
            if (resourceType != null) {
                Predicate same = cb.equal(root.get("resourceType"), resourceType);
                // 종류를 비워 둔 스케줄은 실행이 VM 으로 해석한다(Schedule.resolveResourceType) — 워크로드 페이징과 같은 규칙
                predicates.add(resourceType == Workload.ResourceType.VM
                        ? cb.or(same, cb.isNull(root.get("resourceType"))) : same);
            }
            if (enabled != null) {
                predicates.add(cb.equal(root.get("enabled"), enabled));
            }
            return predicates.isEmpty() ? cb.conjunction()
                    : cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }
}
