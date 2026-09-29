package com.innogrid.aims.scheduler.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.common.util.SortSupport;
import com.innogrid.aims.scheduler.dispatch.DispatcherRegistry;
import com.innogrid.aims.scheduler.dispatch.PlacementDispatcher;
import com.innogrid.aims.scheduler.dto.PlacementDispatchResponse;
import com.innogrid.aims.scheduler.entity.PlacementDispatch;
import com.innogrid.aims.scheduler.entity.PlacementDispatch.DispatchStatus;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.PlacementDispatchRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import jakarta.persistence.criteria.Predicate;

/**
 * 배치 결정을 실행 계층으로 넘기고(디스패치), 실행 계층의 결과를 되돌려 받는다.
 *
 * <p><b>호출 시점.</b> {@link PlacementService#place} 의 트랜잭션이 끝난 뒤 호출자가 부른다. 결정·예약·이력은 이미 커밋된
 * 상태라 여기서 무슨 일이 나도 결정은 되돌아가지 않는다 — 디스패처 예외는 UNAVAILABLE 로 기록하고 삼킨다.</p>
 *
 * <p><b>거부·불가.</b> 실행 계층이 받지 않으면 워크로드를 FAILED 로 돌리고 배정을 해제한다({@link Workload#failDispatch}).
 * 그러면 다음 스케줄 실행이 재시도 상한 안에서 다시 대상으로 잡는다.</p>
 */
@Service
public class PlacementDispatchService {

    private static final Logger log = LoggerFactory.getLogger(PlacementDispatchService.class);

    static final Set<String> SORTABLE = Set.of("id", "requestedAt", "respondedAt", "status", "workloadId");
    static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "requestedAt");

    private final PlacementDispatchRepository repository;
    private final WorkloadRepository workloadRepository;
    private final DispatcherRegistry registry;
    private final ScheduleActorResolver actorResolver;

    /**
     * 결과 반영(디스패치 행 + 워크로드)을 한 트랜잭션으로 묶는다. 같은 빈 안에서 {@code this.settle()} 을 부르면 프록시를
     * 거치지 않아 {@code @Transactional} 이 무시되므로(리뷰에서 확인) 템플릿으로 명시한다.
     */
    private final TransactionTemplate tx;

    public PlacementDispatchService(PlacementDispatchRepository repository,
                                   WorkloadRepository workloadRepository,
                                   DispatcherRegistry registry,
                                   ScheduleActorResolver actorResolver,
                                   PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.workloadRepository = workloadRepository;
        this.registry = registry;
        this.actorResolver = actorResolver;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ── 결정 → 실행 계층 ──────────────────────────────────────────

    /**
     * 성공한 배치 결정을 실행 계층으로 넘긴다. 실패한 결정은 아무것도 하지 않고 null.
     * 같은 결정(멱등키)이 이미 있으면 그 행을 돌려주고 다시 넘기지 않는다.
     */
    public PlacementDispatch afterPlacement(Workload workload, SchedulingResult result, Long scheduleRunId) {
        if (result == null || !result.success() || workload.getId() == null) return null;

        String key = PlacementDispatch.keyOf(workload);
        PlacementDispatch existing = repository.findByIdempotencyKey(key).orElse(null);
        if (existing != null) return existing;

        String actor = scheduleRunId != null ? ScheduleActorResolver.SYSTEM : actorResolver.current().email();
        PlacementDispatch dispatch;
        try {
            dispatch = repository.save(new PlacementDispatch(workload, scheduleRunId, actor));
        } catch (DataIntegrityViolationException race) {
            // 같은 결정이 동시에 두 번 들어온 경우 — 유니크 제약이 잡았으니 먼저 만든 행을 쓴다
            return repository.findByIdempotencyKey(key).orElseThrow(() -> race);
        }

        String mode = "?";
        PlacementDispatcher.Outcome outcome;
        try {
            PlacementDispatcher dispatcher = registry.current();
            mode = dispatcher.getMode();
            outcome = dispatcher.dispatch(toRequest(dispatch, workload, result));
            if (outcome == null) outcome = PlacementDispatcher.Outcome.unavailable("디스패처가 결과를 돌려주지 않음");
        } catch (RuntimeException e) {
            log.warn("디스패처 예외: dispatch={}, mode={}, {}", dispatch.getId(), mode, e.toString());
            outcome = PlacementDispatcher.Outcome.unavailable("디스패처 예외: " + e.getClass().getSimpleName());
        }
        final PlacementDispatcher.Outcome decided = outcome;
        return tx.execute(status -> settle(dispatch, workload.getId(), decided));
    }

    /** 반드시 {@link #tx} 안에서 부른다. 워크로드가 사이에 지워졌으면 워크로드 쪽은 건너뛴다(부활 금지). */
    private PlacementDispatch settle(PlacementDispatch dispatch, Long workloadId, PlacementDispatcher.Outcome outcome) {
        if (outcome.accepted()) {
            dispatch.accept(outcome.externalRef(), outcome.message());
        } else {
            DispatchStatus closed = outcome.kind() == PlacementDispatcher.Outcome.Kind.REJECTED
                    ? DispatchStatus.REJECTED : DispatchStatus.UNAVAILABLE;
            dispatch.close(closed, outcome.message());
            workloadRepository.findById(workloadId).ifPresent(fresh -> {
                fresh.failDispatch("실행 계층 " + (closed == DispatchStatus.REJECTED ? "거부" : "불가") + ": " + outcome.message());
                workloadRepository.save(fresh);
            });
            log.info("실행 요청 무산: dispatch={}, workload={}, {} — 워크로드 FAILED, 배정 해제",
                    dispatch.getId(), dispatch.getWorkloadName(), closed);
        }
        return repository.save(dispatch);
    }

    /** 디스패치가 실행 계층에 수락되지 않았으면 배치 결과를 실패로 낮춘다 — 호출자가 성공으로 세지 않게. */
    public static SchedulingResult downgrade(SchedulingResult result, PlacementDispatch dispatch) {
        if (dispatch == null || result == null || !result.success()) return result;
        if (dispatch.getStatus() == DispatchStatus.REJECTED || dispatch.getStatus() == DispatchStatus.UNAVAILABLE) {
            return SchedulingResult.failure("배치 결정은 " + dispatch.getNodeId() + " 이었으나 실행 계층이 받지 않음: " + dispatch.getMessage());
        }
        return result;
    }

    private static PlacementDispatcher.Request toRequest(PlacementDispatch d, Workload w, SchedulingResult r) {
        return new PlacementDispatcher.Request(d.getId(), d.getIdempotencyKey(),
                w.getId(), w.getName(), w.resolveResourceType().name(),
                w.getRequiredCpu(), w.getRequiredMemoryMb(), w.getRequiredGpu(), w.getRequiredDiskGb(),
                d.getMdcId(), d.getNodeId(), r.finalScore(), r.reason(), d.getScheduleRunId());
    }

    // ── 실행 계층 → 결과 ──────────────────────────────────────────

    /**
     * 실행 계층의 결과를 반영한다. 디스패치와 워크로드를 함께 갱신한다.
     *
     * <pre>
     * 없는 id                 → 404
     * status 없음·모르는 값    → 400 (RUNNING·COMPLETED·FAILED 만)
     * 같은 상태 재보고         → 200 무변경 (멱등)
     * 전이표 밖               → 409
     * </pre>
     * 워크로드 전이는 기존 전이표({@link Workload#transitionTo})를 쓴다. 디스패치는 바뀌는데 워크로드가 이미 다른 상태라
     * 전이가 안 되면(운영자가 상태 변경 API 로 먼저 바꾼 경우) 디스패치만 갱신하고 그 사실을 message 에 남긴다.
     */
    @Transactional
    public PlacementDispatch applyResult(Long dispatchId, String rawStatus, String externalRef, String message) {
        PlacementDispatch dispatch = require(dispatchId);
        if (rawStatus == null || rawStatus.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "status 는 필수입니다 (RUNNING | COMPLETED | FAILED)");
        }
        DispatchStatus target = EnumSupport.parse(DispatchStatus.class, rawStatus, "결과 상태");
        if (!target.reportable()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "실행 계층이 보고할 수 있는 상태는 RUNNING, COMPLETED, FAILED 입니다: " + target);
        }
        if (externalRef != null && externalRef.length() > PlacementDispatch.EXTERNAL_REF_MAX) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "externalRef 는 " + PlacementDispatch.EXTERNAL_REF_MAX + "자 이하여야 합니다");
        }
        if (message != null && message.length() > PlacementDispatch.MESSAGE_MAX) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "message 는 " + PlacementDispatch.MESSAGE_MAX + "자 이하여야 합니다");
        }
        if (target == dispatch.getStatus()) return dispatch;   // 멱등
        if (!dispatch.report(target, externalRef, message)) {
            throw new BusinessException(HttpStatus.CONFLICT, String.format(
                    "디스패치 %d 는 %s 상태라 %s 로 바꿀 수 없습니다", dispatchId, dispatch.getStatus(), target));
        }

        Workload.WorkloadStatus workloadTarget = switch (target) {
            case RUNNING -> Workload.WorkloadStatus.RUNNING;
            case COMPLETED -> Workload.WorkloadStatus.COMPLETED;
            default -> Workload.WorkloadStatus.FAILED;
        };
        workloadRepository.findById(dispatch.getWorkloadId()).ifPresent(w -> {
            if (w.getStatus() == workloadTarget) return;
            if (w.transitionTo(workloadTarget)) {
                if (workloadTarget == Workload.WorkloadStatus.FAILED) {
                    // message 는 실행 계층 계약에서 선택값이다. 사유가 없어도 실패 회계와 백오프 시각은 반드시 남긴다.
                    // 실행 실패가 배치 재시도 상한을 함께 쓰는 것은 의도다(반복 실패 워크로드가 영영 재배치되지 않게).
                    String reason = message == null || message.isBlank() ? "실행 실패" : "실행 실패: " + message;
                    w.markFailed(reason.length() > PlacementDispatch.MESSAGE_MAX ? reason.substring(0, PlacementDispatch.MESSAGE_MAX) : reason);
                }
                workloadRepository.save(w);
            } else {
                dispatch.note("워크로드는 " + w.getStatus() + " 상태라 " + workloadTarget + " 로 전이하지 않음");
            }
        });
        return repository.save(dispatch);
    }

    // ── 조회 ────────────────────────────────────────────────────

    public PlacementDispatch require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "디스패치를 찾을 수 없습니다: " + id));
    }

    @Transactional(readOnly = true)
    public List<PlacementDispatch> byWorkload(Long workloadId) {
        return repository.findByWorkloadIdOrderByRequestedAtDesc(workloadId);
    }

    @Transactional(readOnly = true)
    public PageResponse<PlacementDispatchResponse> page(String status, Long workloadId, Long scheduleRunId,
                                                        int page, int size, String sort) {
        DispatchStatus parsed = (status == null || status.isBlank()) ? null
                : EnumSupport.parse(DispatchStatus.class, status, "디스패치 상태");
        Sort order = StableSort.withIdTiebreaker(SortSupport.parse(sort, SORTABLE, DEFAULT_SORT));
        Page<PlacementDispatch> found = repository.findAll(filter(parsed, workloadId, scheduleRunId),
                PageRequest.of(page - 1, size, order));
        Page<PlacementDispatchResponse> mapped = found.map(PlacementDispatchResponse::from);
        return new PageResponse<>(mapped.getContent(), page, size, mapped.getTotalElements(), mapped.getTotalPages());
    }

    @Transactional
    public long deleteByWorkload(Long workloadId) {
        return repository.deleteByWorkloadId(workloadId);
    }

    private static Specification<PlacementDispatch> filter(DispatchStatus status, Long workloadId, Long runId) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (status != null) ps.add(cb.equal(root.get("status"), status));
            if (workloadId != null) ps.add(cb.equal(root.get("workloadId"), workloadId));
            if (runId != null) ps.add(cb.equal(root.get("scheduleRunId"), runId));
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }
}
