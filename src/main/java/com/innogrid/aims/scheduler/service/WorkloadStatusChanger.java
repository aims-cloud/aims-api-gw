package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.entity.PlacementDispatch;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;
import com.innogrid.aims.scheduler.service.SchedulingService.Release;

/**
 * 스케줄링 서비스에서 떼어 낸 <b>워크로드 상태 변경</b> — 재시도 상한에 걸린 워크로드 일괄 재개, 상태 전이, 배치 예약 취소.
 *
 * <p>스프링 빈이 아니다. 서비스가 생성자에서 한 번 만들고, 생성자는 참조만 저장한다. 트랜잭션은 서비스의 공개 메서드가 연다 —
 * 전달 기록 마감({@link PlacementDispatchService#closeCurrent} · {@link PlacementDispatchService#cancelCurrent})이 그 트랜잭션
 * 안에서 불려야 워크로드 저장과 한 단위가 된다. 설정값(재시도 상한 · 한 실행 처리 상한)은 부를 때마다 읽는다.</p>
 */
final class WorkloadStatusChanger {

    private final WorkloadRepository workloadRepository;

    /** 실행 계층 연동. null 이면(직접 조립한 테스트 경로) 전달 기록을 마감하지 않는다. */
    private final PlacementDispatchService dispatchService;

    private final SchedulerProperties properties;

    WorkloadStatusChanger(WorkloadRepository workloadRepository, PlacementDispatchService dispatchService,
                          SchedulerProperties properties) {
        this.workloadRepository = workloadRepository;
        this.dispatchService = dispatchService;
        this.properties = properties;
    }

    /** 재시도 상한에 걸린 워크로드를 여러 건 한 번에 되살린다. 규칙은 {@link SchedulingService#resumeExhausted} 의 설명과 같다. */
    List<Workload> resumeExhausted(Workload.ResourceType resourceType, OffsetDateTime from, OffsetDateTime to) {
        if (resourceType == null && from == null && to == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "리소스 종류나 기간 중 하나는 지정해야 합니다 — 조건 없는 전체 재개는 받지 않습니다");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "기간의 시작이 끝보다 뒤입니다");
        }

        // 널 파라미터는 PostgreSQL 이 타입을 추론하지 못한다 — 경계값과 깃발로 바꿔 넘긴다.
        boolean anyType = resourceType == null;
        boolean matchNullType = resourceType == Workload.ResourceType.VM;
        boolean anyPeriod = from == null && to == null;
        List<Workload> exhausted = workloadRepository.findRetryExhausted(
                Workload.WorkloadStatus.FAILED, properties.getMaxRetryCount(),
                anyType, anyType ? Workload.ResourceType.VM : resourceType,
                matchNullType,
                anyPeriod,
                from != null ? from : OffsetDateTime.parse("1970-01-01T00:00:00Z"),
                to != null ? to : OffsetDateTime.now().plusYears(100));
        int limit = properties.getMaxWorkloadsPerRun();
        if (exhausted.size() > limit) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, String.format(
                    "조건에 걸린 워크로드가 %d건으로 한 번에 되살릴 수 있는 %d건을 넘습니다 — 기간이나 리소스 종류로 좁히세요",
                    exhausted.size(), limit));
        }

        List<Workload> resumed = new ArrayList<>();
        for (Workload workload : exhausted) {
            if (workload.transitionTo(Workload.WorkloadStatus.PENDING)) {
                resumed.add(workloadRepository.save(workload));
            }
        }
        return resumed;
    }

    /** 워크로드의 실행 상태를 전이한다. 규칙은 {@link SchedulingService#changeStatus} 의 설명과 같다. */
    Workload changeStatus(Long workloadId, Workload.WorkloadStatus target) {
        Workload workload = workloadRepository.findById(workloadId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "워크로드를 찾을 수 없습니다: " + workloadId));

        if (!workload.transitionTo(target)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, String.format(
                    "허용되지 않는 상태 전이입니다: %s → %s", workload.getStatus(), target));
        }
        if (dispatchService != null
                && (target == Workload.WorkloadStatus.COMPLETED || target == Workload.WorkloadStatus.FAILED)) {
            dispatchService.closeCurrent(workload, target);
        }
        return workloadRepository.save(workload);
    }

    /** 배치 예약을 취소한다. 규칙은 {@link SchedulingService#release} 의 설명과 같다. */
    Release release(Long workloadId) {
        Workload workload = workloadRepository.findById(workloadId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "워크로드를 찾을 수 없습니다: " + workloadId));
        if (workload.getStatus() != Workload.WorkloadStatus.SCHEDULED) {
            throw new BusinessException(HttpStatus.CONFLICT, workload.getStatus() == Workload.WorkloadStatus.RUNNING
                    ? "실행 중인 워크로드는 예약을 취소할 수 없습니다 — 실행 계층 결과로 끝내십시오"
                    : String.format("취소할 예약이 없습니다 — 워크로드가 %s 상태입니다(SCHEDULED 만 가능)", workload.getStatus()));
        }
        String mdcId = workload.getAssignedMdcId();
        String nodeId = workload.getAssignedNodeId();
        PlacementDispatch dispatch = dispatchService == null ? null : dispatchService.cancelCurrent(workload);
        workload.release("예약 취소 — 이전 배정 " + mdcId + "/" + nodeId);
        return new Release(workloadRepository.save(workload), mdcId, nodeId, dispatch);
    }
}
