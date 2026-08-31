package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.common.util.SortSupport;
import com.innogrid.aims.scheduler.dto.CronPreviewResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.dto.ScheduleResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRunDetailResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRunResponse;
import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;

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
    private final CronSupport cronSupport;
    private final EngineRegistry engineRegistry;
    private final ScheduleActorResolver actorResolver;

    public ScheduleService(ScheduleRepository scheduleRepository,
                           ScheduleRunRepository runRepository,
                           SchedulingHistoryRepository historyRepository,
                           CronSupport cronSupport,
                           EngineRegistry engineRegistry,
                           ScheduleActorResolver actorResolver) {
        this.scheduleRepository = scheduleRepository;
        this.runRepository = runRepository;
        this.historyRepository = historyRepository;
        this.cronSupport = cronSupport;
        this.engineRegistry = engineRegistry;
        this.actorResolver = actorResolver;
    }

    // ── 조회 ───────────────────────────────────────────────────

    /** 목록. page 는 1부터다. */
    @Transactional(readOnly = true)
    public PageResponse<ScheduleResponse> list(String keyword, Workload.ResourceType resourceType,
                                               Boolean enabled, int page, int size, String sort) {
        Sort order = SortSupport.parse(sort, SORTABLE, DEFAULT_SORT);
        Page<Schedule> found = scheduleRepository.findAll(
                filter(keyword, resourceType, enabled), PageRequest.of(page - 1, size, order));
        return toPageResponse(found.map(ScheduleResponse::from), page, size);
    }

    @Transactional(readOnly = true)
    public ScheduleResponse get(Long id) {
        return ScheduleResponse.from(require(id));
    }

    /** 실행 이력. 자동 실행이 대상 0건으로 끝난 경우는 이력이 남지 않는다. */
    @Transactional(readOnly = true)
    public PageResponse<ScheduleRunResponse> runs(Long id, int page, int size, String sort) {
        Schedule schedule = require(id);
        Sort order = SortSupport.parse(sort, RUN_SORTABLE, DEFAULT_RUN_SORT);
        Page<ScheduleRun> found = runRepository.findByScheduleId(
                schedule.getId(), PageRequest.of(page - 1, size, order));
        return toPageResponse(found.map(ScheduleRunResponse::from), page, size);
    }

    /** 실행 1건의 상세 — 워크로드별 배치 결정을 함께 돌려준다. */
    @Transactional(readOnly = true)
    public ScheduleRunDetailResponse runDetail(Long id, Long runId) {
        Schedule schedule = require(id);
        ScheduleRun run = runRepository.findByIdAndScheduleId(runId, schedule.getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "실행 이력을 찾을 수 없습니다: " + runId));

        List<ScheduleRunDetailResponse.Decision> decisions =
                historyRepository.findByScheduleRunIdOrderByCreatedAtAsc(run.getId()).stream()
                        .map(ScheduleRunDetailResponse.Decision::from)
                        .toList();
        return new ScheduleRunDetailResponse(ScheduleRunResponse.from(run), decisions);
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

        String name = requireText(request.name(), "스케줄명");
        String cron = requireText(request.cronExpression(), "실행 주기");
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
     * 편집. 스케줄 ID 는 업무 키라 바꿀 수 없다.
     * cron 이나 사용 여부가 바뀌면 다음 실행 시각을 반드시 다시 계산한다 —
     * 그러지 않으면 예전 주기로 한 번 더 실행된다.
     */
    @Transactional
    public ScheduleResponse update(Long id, ScheduleRequest request) {
        Schedule schedule = require(id);

        if (request.scheduleId() != null && !request.scheduleId().equals(schedule.getScheduleId())) {
            throw badRequest("스케줄 ID 는 변경할 수 없습니다");
        }
        if (request.name() != null) {
            schedule.setName(requireText(request.name(), "스케줄명"));
        }
        if (request.cronExpression() != null) {
            String cron = requireText(request.cronExpression(), "실행 주기");
            cronSupport.validate(cron);
            schedule.setCronExpression(cron);
        }
        validateEngine(request.engine());
        apply(schedule, request);

        ScheduleActorResolver.Actor actor = actorResolver.current();
        schedule.setUpdatedBy(actor.email(), actor.name());

        refreshNextRunAt(schedule);
        return ScheduleResponse.from(scheduleRepository.save(schedule));
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
        if (!schedule.isAutoRunnable()) {
            schedule.setNextRunAt(null);
            return;
        }
        schedule.setNextRunAt(cronSupport.nextRunAt(schedule.getCronExpression(), OffsetDateTime.now()));
    }

    private void apply(Schedule schedule, ScheduleRequest request) {
        if (request.resourceType() != null) schedule.setResourceType(request.resourceType());
        if (request.description() != null) schedule.setDescription(request.description());
        if (request.engine() != null) schedule.setEngine(request.engine());
        if (request.policyId() != null) schedule.setPolicyId(request.policyId());
        if (request.maxPowerBudgetWatts() != null) {
            schedule.setMaxPowerBudgetWatts(request.maxPowerBudgetWatts());
        }
        if (request.maxTemperatureCelsius() != null) {
            schedule.setMaxTemperatureCelsius(request.maxTemperatureCelsius());
        }
        if (request.autoRun() != null) schedule.setAutoRun(request.resolveAutoRun());
        if (request.enabled() != null) schedule.setEnabled(request.resolveEnabled());
    }

    private void validateEngine(String engine) {
        if (engine != null && !engine.isBlank() && !engineRegistry.contains(engine)) {
            throw badRequest("알 수 없는 스케줄링 엔진입니다: " + engine);
        }
    }

    private String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw badRequest(label + "은(는) 필수입니다");
        }
        return value.trim();
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
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("scheduleId")), like),
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("description"), "")), like)));
            }
            if (resourceType != null) {
                predicates.add(cb.equal(root.get("resourceType"), resourceType));
            }
            if (enabled != null) {
                predicates.add(cb.equal(root.get("enabled"), enabled));
            }
            return predicates.isEmpty() ? cb.conjunction()
                    : cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }
}
