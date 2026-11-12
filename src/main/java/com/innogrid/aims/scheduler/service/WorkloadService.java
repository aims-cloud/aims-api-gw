package com.innogrid.aims.scheduler.service;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.WorkloadRequest;
import com.innogrid.aims.scheduler.dto.WorkloadUpdateRequest;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.repository.PlacementDispatchRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import tools.jackson.databind.JsonNode;

/**
 * 워크로드 생성·편집·삭제 — 요청 검증과 상태 규칙을 한곳에 둔다.
 *
 * <p>검증은 애너테이션이 아니라 여기서 {@link BusinessException} 으로 한다. 이 프로젝트는 검증 예외가
 * 기본 처리로 넘어가면 400 이 아니라 403 이 되는 경로가 있어, 서비스에서 던지는 편이
 * 400 이 확실하다. 스케줄 API 와 같은 방식이다.</p>
 */
@Service
public class WorkloadService {

    /** 엔티티 컬럼 길이와 같다. 넘기면 DB 예외로 500 이 나므로 먼저 막는다. */
    static final int NAME_MAX = 100;
    static final int DESCRIPTION_MAX = 300;
    static final int MDC_ID_MAX = 50;

    /** 편집할 수 있는 상태({@link Workload.WorkloadStatus#isEditable}) — 편집 저장 직전에 이 상태일 때만 행을 잡는다. */
    private static final Set<Workload.WorkloadStatus> EDITABLE = EnumSet.copyOf(Arrays.stream(Workload.WorkloadStatus.values())
            .filter(Workload.WorkloadStatus::isEditable).toList());

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
        return workloadRepository.save(draft(request));
    }

    /**
     * 저장하지 않는 초안 — 생성과 같은 검증·사유 문구로 엔티티만 만든다. 어떤 행도 쓰지 않고 DB 를 읽지도 않는다
     * (저장 전 모의 배치가 쓴다). 생성은 이 초안을 저장하는 것뿐이라 두 경로의 400 규칙이 갈릴 수 없다.
     */
    public Workload draft(WorkloadRequest request) {
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
        if (request.requiredBandwidthMbps() != null) {
            workload.setRequiredBandwidthMbps(requirePositive(request.requiredBandwidthMbps(), "요구 대역폭(Mbps)"));
        }
        return workload;
    }

    /**
     * 편집(부분 갱신). 보낸 키만 바꾸고 없는 키는 유지한다.
     *
     * <pre>
     * 없음                         → 404
     * SCHEDULED                    → 409 (먼저 예약 취소)
     * RUNNING · COMPLETED          → 409
     * 본문 없음 · 칸 검증 실패      → 400 (생성과 같은 규칙과 사유 문구 · 비울 수 있는 여섯 칸은 JSON 타입도 검사)
     * PENDING · FAILED             → 200
     * </pre>
     *
     * <p><b>상태를 본문보다 먼저 본다</b> — 편집할 수 없는 워크로드라면 본문이 맞아도 소용없으므로 진짜 막힌 이유를 먼저
     * 알린다(삭제·예약 취소와 같은 순서). <b>모든 칸을 먼저 검증한 뒤 한꺼번에 반영한다</b> — 한 칸이 400 이면 엔티티는 전혀
     * 바뀌지 않는다(트랜잭션 롤백에만 기대지 않는다). 깨진 JSON 처럼 본문을 읽지 못한 400 은 이 메서드에 오기 전에
     * 프레임워크가 내므로 404·409 보다 앞선다.</p>
     *
     * <p>재시도 회계(횟수·마지막 실패 시각)와 배정·사유·시각 칸은 건드리지 않는다. 회계를 지우는 길은 명시적 되살리기
     * (FAILED→PENDING · 일괄 재개)와 완료뿐이다 — 편집이 지우면 재시도 상한을 우회하는 숨은 되살리기가 된다.</p>
     *
     * <p><b>저장 직전에 상태를 다시 확인하고 행을 잡는다</b>({@link #lockForEdit}). 읽은 뒤 그 사이 단건 배치·스케줄 실행이
     * 배치를 커밋했거나 워크로드가 지워졌으면 읽은 사본을 저장하지 않고 409·404 로 끝낸다 — 엔티티에 동적 갱신·행 버전이
     * 없어 읽은 사본을 그대로 저장하면 모든 열이 읽을 때의 값으로 덮여 막 끝난 배치가 대기로 돌아간다. 그 사이의 실패 결정·
     * 되살리기처럼 편집할 수 있는 상태 안의 변화는 다시 읽은 값 위에 보낸 키만 얹어 보존한다. 이 확인은 본문 검증 뒤라
     * 겹칠 때는 400 이 409·404 보다 먼저일 수 있다.</p>
     */
    @Transactional
    public Workload update(Long id, WorkloadUpdateRequest request) {
        Workload current = workloadRepository.findById(id).orElseThrow(() -> notFound(id));
        if (!current.getStatus().isEditable()) throw notEditable(current);
        if (request == null) throw badRequest("요청 본문이 필요합니다");

        // 먼저 전부 검증해 지역 값에 담는다
        String name = request.name() == null ? null : requireText(request.name(), "워크로드 이름", NAME_MAX);
        Integer cpu = request.requiredCpu() == null ? null : requireAtLeast(request.requiredCpu(), 1, "요구 CPU");
        Integer memory = request.requiredMemoryMb() == null ? null
                : requireAtLeast(request.requiredMemoryMb(), 1, "요구 메모리(MB)");
        Integer gpu = request.requiredGpu() == null ? null : requireAtLeast(request.requiredGpu(), 0, "요구 GPU");
        Integer disk = request.requiredDiskGb() == null ? null
                : requireAtLeast(request.requiredDiskGb(), 0, "요구 디스크(GB)");
        Workload.ResourceType type = request.resourceType() == null ? null
                : EnumSupport.parse(Workload.ResourceType.class, request.resourceType(), "리소스 종류");
        Workload.WorkloadPriority priority = request.priority() == null ? null
                : EnumSupport.parse(Workload.WorkloadPriority.class, request.priority(), "우선순위");
        Clearable<String> description = clearableText(request.description(), "설명", DESCRIPTION_MAX);
        Clearable<String> dataMdc = clearableMdcId(request.dataMdcId(), "데이터 MDC");
        Clearable<String> preferredMdc = clearableMdcId(request.preferredMdcId(), "선호 MDC");
        Clearable<Double> power = clearablePositive(request.maxPowerBudgetWatts(), "노드 전력 상한(W)");
        Clearable<Double> temperature = clearablePositive(request.maxTemperatureCelsius(), "온도 상한(°C)");
        Clearable<Double> bandwidth = clearablePositive(request.requiredBandwidthMbps(), "요구 대역폭(Mbps)");

        // 저장 직전에 행을 잡고 다시 읽는다 — 값은 반드시 그 뒤에 얹는다(앞에서 바꾸면 읽은 사본이 먼저 저장된다)
        Workload workload = lockForEdit(id);

        // 그다음 한꺼번에 반영한다
        if (name != null) workload.setName(name);
        if (cpu != null) workload.setRequiredCpu(cpu);
        if (memory != null) workload.setRequiredMemoryMb(memory);
        if (gpu != null) workload.setRequiredGpu(gpu);
        if (disk != null) workload.setRequiredDiskGb(disk);
        if (type != null) workload.setResourceType(type);
        if (priority != null) workload.setPriority(priority);
        if (description.sent()) workload.setDescription(description.value());
        if (dataMdc.sent()) workload.setDataMdcId(dataMdc.value());
        if (preferredMdc.sent()) workload.setPreferredMdcId(preferredMdc.value());
        if (power.sent()) workload.setMaxPowerBudgetWatts(power.value());
        if (temperature.sent()) workload.setMaxTemperatureCelsius(temperature.value());
        if (bandwidth.sent()) workload.setRequiredBandwidthMbps(bandwidth.value());
        return workloadRepository.save(workload);
    }

    /**
     * 편집 저장 직전 — 아직 편집할 수 있는 상태일 때만 행을 잡고, 최신 값을 다시 읽어 돌려준다. 잡은 행은 이 트랜잭션이 끝날
     * 때까지 다른 쪽이 바꾸지 못한다.
     *
     * <p>잡지 못했으면(0행) 다시 읽어 사유를 정한다 — 지워졌으면 404, 배치·실행 중·완료로 바뀌었으면 처음 확인과 같은 409.
     * 다시 읽었더니 편집할 수 있는 상태라면 그 사이 상태가 두 번 바뀐 것이라 409 로 다시 조회를 요청한다.</p>
     */
    private Workload lockForEdit(Long id) {
        if (workloadRepository.lockIfStatusIn(id, EDITABLE) == 0) {
            Workload now = workloadRepository.findById(id).orElse(null);
            if (now == null) throw notFound(id);
            if (!now.getStatus().isEditable()) throw notEditable(now);
            throw new BusinessException(HttpStatus.CONFLICT, "편집하는 사이 상태가 바뀌었습니다 — 다시 조회한 뒤 편집하세요");
        }
        return workloadRepository.findById(id).orElseThrow(() -> notFound(id));
    }

    private static BusinessException notFound(Long id) {
        return new BusinessException(HttpStatus.NOT_FOUND, "워크로드를 찾을 수 없습니다: " + id);
    }

    /** 편집할 수 없는 상태의 409 — 처음 확인과 저장 직전 확인이 같은 문구를 쓴다. */
    private static BusinessException notEditable(Workload workload) {
        return new BusinessException(HttpStatus.CONFLICT, switch (workload.getStatus()) {
            case SCHEDULED -> String.format("배치된 워크로드는 편집할 수 없습니다 (상태 SCHEDULED, 노드 %s). "
                    + "먼저 예약 취소(POST /workloads/{id}/release)로 배정을 푸세요", workload.getAssignedNodeId());
            case RUNNING -> String.format("실행 중인 워크로드는 편집할 수 없습니다 (상태 RUNNING, 노드 %s)",
                    workload.getAssignedNodeId());
            default -> "완료된 워크로드는 실행 기록이라 편집할 수 없습니다 — 다시 돌리려면 새로 생성하세요";
        });
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
                    "실행 중인 워크로드는 삭제할 수 없습니다 (상태 %s, 노드 %s). %s",
                    status, workload.getAssignedNodeId(),
                    status == Workload.WorkloadStatus.SCHEDULED
                            ? "먼저 예약 취소(POST /workloads/{id}/release)로 배정을 푸세요"
                            : "먼저 상태를 COMPLETED 또는 FAILED 로 바꾸세요"));
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

    /**
     * 유한한 양수만 받는다 — 무한대·NaN 을 통과시키면 점수 식에서 NaN 이 나고, NaN 총점은 반올림에서
     * 0.0 으로 삼켜져 오류가 아니라 최하위 점수로 보인다.
     */
    private static double requirePositive(Double value, String label) {
        if (!(value > 0) || !Double.isFinite(value)) {
            throw badRequest(String.format("%s은(는) 유한한 0 보다 큰 수여야 합니다: %s", label, value));
        }
        return value;
    }

    /** 비울 수 있는 칸의 검증 결과 — 보냈는지와 그 값(명시적 null 이면 {@code null}). */
    private record Clearable<T>(boolean sent, T value) {
        static <T> Clearable<T> keep() { return new Clearable<>(false, null); }
        static <T> Clearable<T> set(T value) { return new Clearable<>(true, value); }
    }

    /** 키 없음 → 유지, 명시적 null → 비움, 문자열 → 길이 검증(생성과 마찬가지로 앞뒤 공백을 지우지 않음), 그 외 → 400. */
    private static Clearable<String> clearableText(JsonNode node, String label, int max) {
        if (node == null) return Clearable.keep();
        if (node.isNull()) return Clearable.set(null);
        if (!node.isString()) throw badRequest(label + "은(는) 문자열이어야 합니다");
        return Clearable.set(limit(node.asString(), label, max));
    }

    /** 키 없음 → 유지, 명시적 null → 비움, 문자열 → 생성과 같은 MDC 규칙(앞뒤 공백 제거 · 빈 값은 비움), 그 외 → 400. */
    private static Clearable<String> clearableMdcId(JsonNode node, String label) {
        if (node == null) return Clearable.keep();
        if (node.isNull()) return Clearable.set(null);
        if (!node.isString()) throw badRequest(label + "은(는) 문자열이어야 합니다");
        return Clearable.set(optionalMdcId(node.asString(), label));
    }

    /**
     * 키 없음 → 유지, 명시적 null → 비움, 숫자 → 생성과 같은 양수 규칙, 그 외(숫자 문자열 포함) → 400.
     * 생성 요청은 {@code "100"} 같은 숫자 문자열을 받아 주지만 여기서는 받지 않는다 — MDC 링크 원장 수정과 같은 규칙이다.
     *
     * <p>값은 {@code numberValue().doubleValue()} 로 꺼낸다. {@code asDouble()} 은 double 범위를 넘는 큰 정수에서
     * {@code JsonNodeException} 을 던지는데 처리기가 없어 400 이 되지 못한다. 이쪽은 ±무한대가 되어 양수 검사가 400 으로 막는다.</p>
     */
    private static Clearable<Double> clearablePositive(JsonNode node, String label) {
        if (node == null) return Clearable.keep();
        if (node.isNull()) return Clearable.set(null);
        if (!node.isNumber()) throw badRequest(label + "은(는) 숫자여야 합니다");
        return Clearable.set(requirePositive(node.numberValue().doubleValue(), label));
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }
}
