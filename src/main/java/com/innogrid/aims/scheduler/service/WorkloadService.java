package com.innogrid.aims.scheduler.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.WorkloadRequest;
import com.innogrid.aims.scheduler.entity.Workload;
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

    public WorkloadService(WorkloadRepository workloadRepository) {
        this.workloadRepository = workloadRepository;
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
            workload.setPreferredMdcId(limit(request.preferredMdcId(), "선호 MDC", MDC_ID_MAX));
        }
        if (request.dataMdcId() != null) {
            workload.setDataMdcId(limit(request.dataMdcId(), "데이터 MDC", MDC_ID_MAX));
        }
        if (request.maxPowerBudgetWatts() != null) {
            workload.setMaxPowerBudgetWatts(requirePositive(request.maxPowerBudgetWatts(), "노드 전력 상한(W)"));
        }
        if (request.maxTemperatureCelsius() != null) {
            workload.setMaxTemperatureCelsius(requirePositive(request.maxTemperatureCelsius(), "온도 상한(°C)"));
        }
        return workloadRepository.save(workload);
    }

    // ── 검증 헬퍼 ───────────────────────────────────────────────

    private static String requireText(String value, String label, int max) {
        if (value == null || value.isBlank()) throw badRequest(label + "은(는) 필수입니다");
        return limit(value.trim(), label, max);
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
