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
        Schedule schedule = require(id);
        ScheduleRun run = runRepository.findByIdAndScheduleId(runId, schedule.getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "실행 이력을 찾을 수 없습니다: " + runId));

        List<ScheduleRunDetailResponse.Decision> decisions =
                historyRepository.findByScheduleRunIdOrderByCreatedAtAscIdAsc(run.getId()).stream()
                        .map(ScheduleRunDetailResponse.Decision::from)
                        .toList();
        return new ScheduleRunDetailResponse(ScheduleRunResponse.from(run),
                ScheduleRunDetailResponse.ClusterSnapshot.from(run), decisions);
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
            schedule.setName(requireText(request.name(), "스케줄명", Schedule.NAME_MAX));
        }
        if (request.cronExpression() != null) {
            String cron = requireText(request.cronExpression(), "실행 주기", Schedule.CRON_MAX);
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
