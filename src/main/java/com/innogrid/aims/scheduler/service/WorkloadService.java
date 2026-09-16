package com.innogrid.aims.scheduler.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.WorkloadRequest;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.repository.PlacementDispatchRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

/**
 * 워크로드 생성·삭제 — 요청 검증과 상태 규칙을 한곳에 둔다.
 *
 * <p>검증은 애너테이션이 아니라 여기서 {@link BusinessException} 으로 한다. 이 프로젝트는 검증 예외가
 * 기본 처리로 넘어가면 400 이 아니라 403 이 되는 경로가 있어(단계 3·10-② 기록), 서비스에서 던지는 편이
 * 400 이 확실하다. 스케줄 API 와 같은 방식이다.</p>
 */
@Service
public class WorkloadService {

    /** 엔티티 컬럼 길이와 같다. 넘기면 DB 예외로 500 이 나므로 먼저 막는다. */
    static final int NAME_MAX = 100;
    static final int DESCRIPTION_MAX = 300;
    static final int MDC_ID_MAX = 50;

    private final WorkloadRepository workloadRepository;

    /** 디스패치 기록. null 이면(직접 조립한 테스트 경로) 삭제 때 기록 정리를 건너뛴다. */
    private final PlacementDispatchRepository dispatchRepository;

    @Autowired
    public WorkloadService(WorkloadRepository workloadRepository, PlacementDispatchRepository dispatchRepository) {
        this.workloadRepository = workloadRepository;
        this.dispatchRepository = dispatchRepository;
    }

    public WorkloadService(WorkloadRepository workloadRepository) {
        this(workloadRepository, null);
    }

    @Transactional
    public Workload create(WorkloadRequest request) {
        if (request == null) throw badRequest("요청 본문이 필요합니다");

        String name = requireText(request.name(), "워크로드 이름", NAME_MAX);
        int cpu = requireAtLeast(request.requiredCpu(), 1, "요구 CPU");
        int memory = requireAtLeast(request.requiredMemoryMb(), 1, "요구 메모리(MB)");

        Workload workload = new Workload(name, cpu, memory);

        if (request.description() != null) {
            workload.setDescription(limit(request.description(), "설명", DESCRIPTION_MAX));
        }
        if (request.requiredGpu() != null) {
            workload.setRequiredGpu(requireAtLeast(request.requiredGpu(), 0, "요구 GPU"));
        }
        if (request.requiredDiskGb() != null) {
            workload.setRequiredDiskGb(requireAtLeast(request.requiredDiskGb(), 0, "요구 디스크(GB)"));
        }
        if (request.resourceType() != null) {
            workload.setResourceType(EnumSupport.parse(Workload.ResourceType.class, request.resourceType(), "리소스 종류"));
        }
        if (request.priority() != null) {
            workload.setPriority(EnumSupport.parse(Workload.WorkloadPriority.class, request.priority(), "우선순위"));
        }
        if (request.preferredMdcId() != null) {
            workload.setPreferredMdcId(optionalMdcId(request.preferredMdcId(), "선호 MDC"));
        }
        if (request.dataMdcId() != null) {
            workload.setDataMdcId(optionalMdcId(request.dataMdcId(), "데이터 MDC"));
        }
        if (request.maxPowerBudgetWatts() != null) {
            workload.setMaxPowerBudgetWatts(requirePositive(request.maxPowerBudgetWatts(), "노드 전력 상한(W)"));
        }
        if (request.maxTemperatureCelsius() != null) {
            workload.setMaxTemperatureCelsius(requirePositive(request.maxTemperatureCelsius(), "온도 상한(°C)"));
        }
        return workloadRepository.save(workload);
    }

    /**
     * 삭제. 없으면 404, 실행 중(SCHEDULED·RUNNING)이면 409.
     *
     * <p>스케줄러의 예약 장부는 별도 테이블이 아니라 <b>워크로드 행 자체</b>다(배치 노드가 적힌 SCHEDULED·RUNNING 행).
     * 실행 중인 행을 지우면 노드에서 실제로 도는 부하는 그대로인데 장부만 사라져, 그 노드가 비어 보이고
     * 다음 배치가 몰린다. 이력도 고아가 된다. 먼저 상태를 COMPLETED/FAILED 로 바꾸게 한다 —
     * 스케줄 삭제(실행 중 409)와 같은 규칙이다.</p>
     */
    @Transactional
    public void delete(Long id) {
        Workload workload = workloadRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "워크로드를 찾을 수 없습니다: " + id));

        Workload.WorkloadStatus status = workload.getStatus();
        if (status == Workload.WorkloadStatus.SCHEDULED || status == Workload.WorkloadStatus.RUNNING) {
            throw new BusinessException(HttpStatus.CONFLICT, String.format(
                    "실행 중인 워크로드는 삭제할 수 없습니다 (상태 %s, 노드 %s). 먼저 상태를 COMPLETED 또는 FAILED 로 바꾸세요",
                    status, workload.getAssignedNodeId()));
        }
        if (dispatchRepository != null) dispatchRepository.deleteByWorkloadId(id);   // FK 가 없어 여기서 함께 지운다
        workloadRepository.delete(workload);
    }

    // ── 검증 헬퍼 ───────────────────────────────────────────────

    private static String requireText(String value, String label, int max) {
        if (value == null || value.isBlank()) throw badRequest(label + "은(는) 필수입니다");
        return limit(value.trim(), label, max);
    }

    /**
     * 선택 MDC 식별자 — 앞뒤 공백을 지우고, 비면 {@code null}(지정 안 함)로 본다.
     *
     * <p>링크 원장은 MDC 이름을 다듬어 저장하고, 배치는 데이터 MDC 와 노드 MDC 를 <b>정확히 비교</b>해 같은 MDC 와
     * 미등록 원격을 가른다. 여기서 다듬지 않으면 {@code " mdc-01 "} 이 같은 MDC 로 인식되지 않아 조용히 미등록
     * 점수(기본 0.5)를 받고, 빈 문자열은 게이트를 열지 못한 채 값만 남는다.</p>
     */
    private static String optionalMdcId(String value, String label) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return limit(trimmed, label, MDC_ID_MAX);
    }

    private static String limit(String value, String label, int max) {
        if (value.length() > max) {
            throw badRequest(String.format("%s은(는) %d자 이내여야 합니다 (현재 %d자)", label, max, value.length()));
        }
        return value;
    }

    private static int requireAtLeast(Integer value, int min, String label) {
        if (value == null) throw badRequest(label + "은(는) 필수입니다");
        if (value < min) throw badRequest(String.format("%s은(는) %d 이상이어야 합니다: %d", label, min, value));
        return value;
    }

    private static double requirePositive(Double value, String label) {
        if (value <= 0) throw badRequest(String.format("%s은(는) 0 보다 커야 합니다: %s", label, value));
        return value;
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }
}
