package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * 상태라 여기서 무슨 일이 나도 결정은 되돌아가지 않는다 — 디스패처 예외는 UNAVAILABLE 로 기록하고 삼킨다.
 * 실행 컨텍스트에 잡힌 메모리 예약은 호출자인 스케줄 실행 루프가 되돌린다({@link com.innogrid.aims.scheduler.model.PlacementContext#unreserve}).</p>
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
            return SchedulingResult.failure("배치 결정은 " + dispatch.getNodeId() + " 이었으나 실행 계층이 받지 않음: " + dispatch.getMessage(),
                    result.policyName());
        }
        return result;
    }

    private static PlacementDispatcher.Request toRequest(PlacementDispatch d, Workload w, SchedulingResult r) {
        return new PlacementDispatcher.Request(d.getId(), d.getIdempotencyKey(),
                w.getId(), w.getName(), w.resolveResourceType().name(),
                w.getRequiredCpu(), w.getRequiredMemoryMb(), w.getRequiredGpu(), w.getRequiredDiskGb(),
                d.getMdcId(), d.getNodeId(),
                r != null ? r.finalScore() : w.getSchedulingScore(),
                r != null ? r.reason() : w.getSchedulingReason(),
                d.getScheduleRunId());
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
     * 옛 결정의 기록           → 200 · 기록만 전이표대로 갱신(비고 표지 "옛 결정의 기록 — 워크로드는 바꾸지 않음") · 워크로드 불변
     * </pre>
     * 워크로드 전이는 기존 전이표({@link Workload#transitionTo})를 쓴다. 디스패치는 바뀌는데 워크로드가 이미 다른 상태라
     * 전이가 안 되면(워크로드가 다른 경로로 먼저 바뀐 경우) 디스패치만 갱신하고 그 사실을 message 에 남긴다.
     * 기록이 워크로드의 <b>현재 결정</b>이 아니면({@link #isCurrentDecision} — 요청 시각이 배정 시각보다 앞서면 옛 결정)
     * 실행 계층이 말한 사실은 기록에 남기되 워크로드·재시도 회계·배정은 건드리지 않는다. 옛 결정의 열린 기록은 단건 재배치나
     * 되살려 다시 배치한 워크로드에 남는데, 전에는 그 기록에 늦게 온 콜백이 현재 결정의 워크로드를 옮기고 재시도 횟수를 올렸다.
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
            if (!isCurrentDecision(dispatch, w)) {
                dispatch.note(OLD_DECISION_NOTE);
                log.info("옛 결정의 콜백: dispatch={} workload={} {} — 워크로드({})는 바꾸지 않음",
                        dispatch.getId(), w.getId(), target, w.getStatus());
                return;
            }
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

    /**
     * 예약 취소 — 워크로드의 <b>현재 결정</b>(멱등키가 같은 행)의 전달 기록을 마감한다.
     *
     * <pre>
     * 행 없음(전달 기능 이전의 배정)        → null
     * REQUESTED(전달 중) · RUNNING(실행 중) → 409
     * ACCEPTED                             → 디스패처 취소 훅 → FAILED 로 마감(비고 "예약 취소 — …")
     * 끝 상태(FAILED·REJECTED·…)           → 그대로
     * </pre>
     * 옛 결정의 행(멱등키가 다른 행)은 건드리지 않는다 — 같은 워크로드가 여러 번 배치될 수 있어서다.
     * 호출자의 트랜잭션 안에서 부른다.
     */
    public PlacementDispatch cancelCurrent(Workload workload) {
        PlacementDispatch current = repository.findByIdempotencyKey(PlacementDispatch.keyOf(workload)).orElse(null);
        if (current == null) return null;
        DispatchStatus status = current.getStatus();
        if (status == DispatchStatus.REQUESTED || status == DispatchStatus.RUNNING) {
            throw new BusinessException(HttpStatus.CONFLICT, String.format(
                    "전달 기록 %d 가 %s 상태라 예약을 취소할 수 없습니다 — %s", current.getId(), status,
                    status == DispatchStatus.REQUESTED ? "실행 계층에 전달 중입니다" : "실행 중인 워크로드는 실행 계층 결과로 끝내십시오"));
        }
        if (status.terminal()) return current;

        String mode = "?";
        String hookNote = null;
        try {
            PlacementDispatcher dispatcher = registry.current();
            mode = dispatcher.getMode();
            dispatcher.cancel(toRequest(current, workload, null));
        } catch (RuntimeException e) {
            log.warn("디스패처 취소 훅 예외(취소는 계속한다): dispatch={}, mode={}, {}", current.getId(), mode, e.toString());
            hookNote = "취소 훅 예외: " + e.getClass().getSimpleName();
        }
        current.cancel("예약 취소 — 운영자 요청(" + actorResolver.current().email() + ")");
        if (hookNote != null) current.note(hookNote);
        log.info("예약 취소: dispatch={}, workload={}, node={} — 전달 기록 FAILED 마감", current.getId(), current.getWorkloadName(), current.getNodeId());
        return repository.save(current);
    }

    /**
     * 상태 변경 — 운영자가 워크로드를 끝 상태(COMPLETED·FAILED)로 바꿀 때 <b>최신 전달 기록</b>이 열려 있으면 같은 끝 상태로
     * 마감한다(비고 앞에 "운영자 상태 변경 — "). 전에는 워크로드만 바뀌고 기록은 ACCEPTED 로 그대로 남아, 대기로 되살려 다시
     * 배치한 뒤 그 옛 기록에 늦게 온 콜백이 새 결정의 워크로드를 옮길 수 있었다.
     *
     * <pre>
     * 행 없음(전달 기능 이전의 배정)              → null
     * 끝 상태(COMPLETED·FAILED·REJECTED·…)       → 그대로
     * REQUESTED · ACCEPTED · RUNNING(열린 상태) → 대상 끝 상태로 마감
     * </pre>
     * 현재 결정은 멱등키가 아니라 <b>요청 시각이 가장 늦은 행</b>(같으면 id 가 큰 행)으로 본다 — 배정 시각은 나노초로 들고
     * 있다가 DB 에는 마이크로초로 반올림되어 저장되므로, 저장 뒤 다시 읽은 값으로 만든 키가 드물게 1ms 어긋난다.
     * 디스패처 취소 훅은 부르지 않는다 — 상태 변경은 실행 계층에 명령하는 경로가 아니라 결과를 대신 적는 경로다
     * (기록형 디스패처는 할 일이 없고, 원격 구현체를 붙일 때 취소 알림 여부를 다시 판단한다).
     * 호출자의 트랜잭션 안에서 부른다.
     */
    public PlacementDispatch closeCurrent(Workload workload, Workload.WorkloadStatus target) {
        List<PlacementDispatch> rows = repository.findByWorkloadIdOrderByRequestedAtDescIdDesc(workload.getId());
        if (rows.isEmpty()) return null;
        PlacementDispatch current = rows.get(0);
        if (current.getStatus().terminal()) return current;
        DispatchStatus before = current.getStatus();
        DispatchStatus outcome = target == Workload.WorkloadStatus.COMPLETED ? DispatchStatus.COMPLETED : DispatchStatus.FAILED;
        current.finishByOperator(outcome, "운영자 상태 변경 — " + target + "(" + actorResolver.current().email() + ")");
        log.info("상태 변경으로 전달 기록 마감: dispatch={}, workload={}, {} → {}", current.getId(), current.getWorkloadName(), before, outcome);
        return repository.save(current);
    }

    /** 재시작 뒤 중단된 실행이 배치한 워크로드 하나를 전달 기록에 맞춰 정리한 결과. */
    public enum RestartRecovery {
        /** 실행 계층이 이미 받았다(ACCEPTED·RUNNING) — 워크로드를 그대로 둔다. */
        KEPT,
        /** 전달 중(REQUESTED)이었다 — 기록을 UNAVAILABLE 로 닫고 워크로드를 실패로 돌린다. */
        CLOSED_AND_FAILED,
        /** 현재 결정의 기록이 없거나 이미 끝났다 — 워크로드를 실패로 돌린다. */
        FAILED,
        /** 다시 읽었을 때 배치 상태가 아니었다 — 손대지 않는다. */
        SKIPPED
    }

    /** 재시작 때 전달 중이던 기록을 닫으며 비고 앞에 두는 표지. */
    public static final String RESTART_NOTE = "재시작 — 전달 결과 모름";

    /** 옛 결정의 기록에 온 콜백을 기록에만 반영했음을 비고 뒤에 남기는 표지. */
    public static final String OLD_DECISION_NOTE = "옛 결정의 기록 — 워크로드는 바꾸지 않음";

    /**
     * 기록이 워크로드의 <b>현재 결정</b>인지 — 기록의 요청 시각이 워크로드의 배정 시각보다 앞서지 않으면 현재 결정이다.
     * 기록은 배치가 커밋된 뒤에 만들어지므로 현재 결정이면 항상 성립하고, 옛 결정의 기록(단건 재배치·되살려 다시 배치한 뒤
     * 남은 것)은 배정 시각보다 앞선다. DB 의 마이크로초 반올림은 두 시각의 순서를 바꾸지 않는다. 멱등키(밀리초)는 같은
     * 반올림으로 1ms 어긋날 수 있어 쓰지 않는다. 배정 시각이 없는 워크로드는 코드상 기록이 생기지 않으므로 현재 결정이
     * 아닌 것으로 본다. 결과 콜백·재시작 정리·시작 시각 조회({@link #runningSince})가 함께 쓴다.
     */
    static boolean isCurrentDecision(PlacementDispatch dispatch, Workload workload) {
        return workload.getScheduledAt() != null && !dispatch.getRequestedAt().isBefore(workload.getScheduledAt());
    }

    /**
     * 실행 중 워크로드마다 <b>시작 시각</b> — 현재 결정({@link #isCurrentDecision})의 기록이 RUNNING 이 된 시각, 곧 실행
     * 계층의 RUNNING 콜백을 받은 서버 시각(응답 시각 칸)이다. 예약 반영이 실행 중 워크로드의 CPU·메모리·디스크 예약을
     * 언제까지 남길지 정하는 기준이다. 조회는 한 번이다.
     *
     * <p>결과에 없는 워크로드: 운영자가 상태 변경 API 로 RUNNING 으로 바꾸고 RUNNING 콜백은 아직 오지 않은 것(기록은 수락
     * 상태로 남는다) · 옛 결정의 RUNNING 기록만 있는 것 · 응답 시각이 없는 것. 호출자는 이들을 배치 시각으로 대신 본다.</p>
     */
    @Transactional(readOnly = true)
    public Map<Long, OffsetDateTime> runningSince(Collection<Workload> running) {
        Map<Long, Workload> byId = new HashMap<>();
        for (Workload w : running) {
            if (w.getId() != null) byId.put(w.getId(), w);
        }
        Map<Long, OffsetDateTime> since = new HashMap<>();
        if (byId.isEmpty()) return since;
        for (PlacementDispatch d : repository.findByWorkloadIdInAndStatus(byId.keySet(), DispatchStatus.RUNNING)) {
            Workload w = byId.get(d.getWorkloadId());
            if (w == null || d.getRespondedAt() == null || !isCurrentDecision(d, w)) continue;
            since.merge(w.getId(), d.getRespondedAt(), (a, b) -> a.isAfter(b) ? a : b);
        }
        return since;
    }

    /**
     * 재시작 뒤 중단된 실행이 배치한 워크로드 하나를 <b>전달 기록에 맞춰</b> 정리한다 — 워크로드 1건이 트랜잭션 1개다.
     *
     * <pre>
     * 현재 결정의 기록이 ACCEPTED·RUNNING  → 워크로드 유지(배정·재시도 그대로). 실행 계층이 이미 받았으니 실패로 돌리면 두 번 배치된다
     * 현재 결정의 기록이 REQUESTED         → 기록 UNAVAILABLE(비고 "재시작 — 전달 결과 모름") + 워크로드 실패·배정 해제
     * 기록 없음 · 옛 결정의 기록 · 끝난 기록 → 워크로드 실패·배정 해제(전과 같음)
     * </pre>
     * 현재 결정은 <b>요청 시각이 가장 늦은 기록</b>(같으면 id 가 큰 기록)이 워크로드의 배정 시각보다 앞서지 않는 것으로 본다 — 기록은 배치가
     * 커밋된 뒤에 만들어지므로 현재 결정이면 항상 성립하고, 옛 결정의 기록(단건 재배치·이전 실패 처리로 남은 것)은
     * 배정 시각보다 앞선다. DB 의 마이크로초 반올림은 두 시각의 순서를 바꾸지 않는다.
     * 디스패처 취소 훅은 부르지 않는다 — 기동 정리는 실행 계층에 명령하는 경로가 아니다.
     *
     * @param reason 실패로 돌릴 때 워크로드에 남길 사유
     */
    public RestartRecovery recoverAfterRestart(Long workloadId, String reason) {
        return tx.execute(status -> {
            Workload workload = workloadRepository.findById(workloadId).orElse(null);
            if (workload == null || workload.getStatus() != Workload.WorkloadStatus.SCHEDULED) return RestartRecovery.SKIPPED;
            List<PlacementDispatch> rows = repository.findByWorkloadIdOrderByRequestedAtDescIdDesc(workloadId);
            PlacementDispatch latest = rows.isEmpty() ? null : rows.get(0);
            boolean current = latest != null && isCurrentDecision(latest, workload);
            if (current && !latest.getStatus().terminal()) {
                if (latest.getStatus() != DispatchStatus.REQUESTED) return RestartRecovery.KEPT;
                latest.close(DispatchStatus.UNAVAILABLE, RESTART_NOTE);
                repository.save(latest);
                workload.failDispatch(reason);
                workloadRepository.save(workload);
                return RestartRecovery.CLOSED_AND_FAILED;
            }
            workload.failDispatch(reason);
            workloadRepository.save(workload);
            return RestartRecovery.FAILED;
        });
    }

    // ── 조회 ────────────────────────────────────────────────────

    public PlacementDispatch require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "디스패치를 찾을 수 없습니다: " + id));
    }

    @Transactional(readOnly = true)
    public List<PlacementDispatch> byWorkload(Long workloadId) {
        return repository.findByWorkloadIdOrderByRequestedAtDescIdDesc(workloadId);
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
