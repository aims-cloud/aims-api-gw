package com.innogrid.aims.scheduler.service;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.SchedulingPolicyRequest;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;

import tools.jackson.databind.JsonNode;

/**
 * 배치 정책 생성·수정 — 요청 검증과 저장 규칙.
 *
 * <p><b>최소 검증</b>이다. "저장하면 확실히 망가지는 값"만 거부한다 — 음수 가중치(점수 부호 반전), 가중치 전부 0,
 * 사용률 임계 0 이하나 100 초과(전 노드 제외 또는 임계 무효), 온도 임계 0 이하. 가중치 합이 1 이 아닌 것은
 * 허용한다 — 가중치는 비율로만 쓰인다(PR ② 원칙).</p>
 *
 * <p>이름 중복은 DB 유니크 제약이 잡아 주지만 그때는 500 으로 새므로 먼저 조회해 409 를 낸다.
 * {@code defaultEngine} 은 등록된 엔진이어야 한다 — 모르는 이름을 저장하면 실행 시 기본 엔진으로 조용히
 * 대체되어 정책의 의도가 사라진다.</p>
 */
@Service
public class SchedulingPolicyService {

    static final int NAME_MAX = 100;
    static final int DESCRIPTION_MAX = 300;

    private final SchedulingPolicyRepository repository;
    private final ScheduleRepository scheduleRepository;
    private final EngineRegistry engineRegistry;

    public SchedulingPolicyService(SchedulingPolicyRepository repository,
                                   ScheduleRepository scheduleRepository,
                                   EngineRegistry engineRegistry) {
        this.repository = repository;
        this.scheduleRepository = scheduleRepository;
        this.engineRegistry = engineRegistry;
    }

    /**
     * 삭제. 없으면 404, 스케줄이 참조 중이면 409.
     *
     * <p>스케줄은 정책을 번호로만 가리키고 외래키가 없다. 정책을 지우면 그 스케줄의 다음 실행이
     * "정책을 찾을 수 없습니다"로 실패한다 — 지우는 시점에 막는 것이 맞다.</p>
     */
    @Transactional
    public void delete(Long id) {
        SchedulingPolicy policy = require(id);
        if (scheduleRepository.existsByPolicyId(id)) {
            throw new BusinessException(HttpStatus.CONFLICT, String.format(
                    "스케줄이 참조하는 정책은 삭제할 수 없습니다: %s(%d). 먼저 스케줄의 정책을 바꾸거나 스케줄을 삭제하세요",
                    policy.getName(), id));
        }
        repository.delete(policy);
    }

    @Transactional
    public SchedulingPolicy create(SchedulingPolicyRequest request) {
        if (request == null) throw badRequest("요청 본문이 필요합니다");

        String name = requireText(request.name(), "정책 이름", NAME_MAX);
        if (repository.findByName(name).isPresent()) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 있는 정책 이름입니다: " + name);
        }
        // 예전 맵 방식이 설명을 빈 문자열로 기본 처리했다 — 그대로 둔다
        String description = request.description() == null ? "" : limit(request.description(), "설명", DESCRIPTION_MAX);

        SchedulingPolicy policy = new SchedulingPolicy(name, description);
        apply(policy, request);
        validate(policy);
        return repository.save(policy);
    }

    /** 부분 수정. 요청에 있는 키만 반영한다. */
    @Transactional
    public SchedulingPolicy update(Long id, SchedulingPolicyRequest request) {
        SchedulingPolicy policy = require(id);
        if (request == null) throw badRequest("요청 본문이 필요합니다");

        if (request.name() != null) {
            String name = requireText(request.name(), "정책 이름", NAME_MAX);
            Optional<SchedulingPolicy> other = repository.findByName(name);
            if (other.isPresent() && !other.get().getId().equals(policy.getId())) {
                throw new BusinessException(HttpStatus.CONFLICT, "이미 있는 정책 이름입니다: " + name);
            }
            policy.setName(name);
        }
        if (request.description() != null) {
            policy.setDescription(limit(request.description(), "설명", DESCRIPTION_MAX));
        }
        apply(policy, request);
        validate(policy);
        return repository.save(policy);
    }

    /** 없으면 404. */
    public SchedulingPolicy require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "정책을 찾을 수 없습니다: " + id));
    }

    // ── 반영 ───────────────────────────────────────────────────

    private void apply(SchedulingPolicy policy, SchedulingPolicyRequest r) {
        // "비울 수 있는" 세 필드 — 키 없음(null)은 유지, 명시적 null(isNull) 은 비움
        if (r.resourceType() != null) {
            policy.setResourceType(r.resourceType().isNull() ? null
                    : EnumSupport.parse(Workload.ResourceType.class, text(r.resourceType(), "리소스 종류"), "리소스 종류"));
        }
        if (r.defaultEngine() != null) {
            if (r.defaultEngine().isNull()) {
                policy.setDefaultEngine(null);
            } else {
                String engine = text(r.defaultEngine(), "기본 엔진").trim();
                if (!engineRegistry.contains(engine)) {
                    throw badRequest(String.format("알 수 없는 스케줄링 엔진입니다: %s (가능한 값: %s)",
                            engine, engineRegistry.describe().stream().map(m -> m.get("id")).toList()));
                }
                policy.setDefaultEngine(engine);
            }
        }
        if (r.warningTemperatureCelsius() != null) {
            if (r.warningTemperatureCelsius().isNull()) {
                policy.setWarningTemperatureCelsius(null);
            } else {
                if (!r.warningTemperatureCelsius().isNumber()) throw badRequest("경고 온도는 숫자여야 합니다");
                double v = r.warningTemperatureCelsius().asDouble();
                if (v <= 0) throw badRequest("경고 온도는 0 보다 커야 합니다: " + v);
                policy.setWarningTemperatureCelsius(v);
            }
        }

        if (r.cpuWeight() != null) policy.setCpuWeight(r.cpuWeight());
        if (r.memoryWeight() != null) policy.setMemoryWeight(r.memoryWeight());
        if (r.powerWeight() != null) policy.setPowerWeight(r.powerWeight());
        if (r.temperatureWeight() != null) policy.setTemperatureWeight(r.temperatureWeight());
        if (r.networkWeight() != null) policy.setNetworkWeight(r.networkWeight());

        if (r.maxCpuUsagePercent() != null) policy.setMaxCpuUsagePercent(r.maxCpuUsagePercent());
        if (r.maxMemoryUsagePercent() != null) policy.setMaxMemoryUsagePercent(r.maxMemoryUsagePercent());
        if (r.maxTemperatureCelsius() != null) policy.setMaxTemperatureCelsius(r.maxTemperatureCelsius());
        if (r.maxPowerUsagePercent() != null) policy.setMaxPowerUsagePercent(r.maxPowerUsagePercent());

        if (r.powerAwareEnabled() != null) policy.setPowerAwareEnabled(r.powerAwareEnabled());
        if (r.temperatureAwareEnabled() != null) policy.setTemperatureAwareEnabled(r.temperatureAwareEnabled());
        if (r.affinityEnabled() != null) policy.setAffinityEnabled(r.affinityEnabled());
        if (r.active() != null) policy.setActive(r.active());
    }

    // ── 검증 — 반영이 끝난 정책 전체를 본다 (부분 수정이라 요청만 봐서는 합계를 알 수 없다) ──

    private void validate(SchedulingPolicy p) {
        nonNegative(p.getCpuWeight(), "cpuWeight");
        nonNegative(p.getMemoryWeight(), "memoryWeight");
        nonNegative(p.getPowerWeight(), "powerWeight");
        nonNegative(p.getTemperatureWeight(), "temperatureWeight");
        nonNegative(p.getNetworkWeight(), "networkWeight");
        double sum = p.getCpuWeight() + p.getMemoryWeight() + p.getPowerWeight()
                + p.getTemperatureWeight() + p.getNetworkWeight();
        if (sum <= 0) throw badRequest("가중치 합은 0 보다 커야 합니다 (현재 " + sum + ")");

        percent(p.getMaxCpuUsagePercent(), "maxCpuUsagePercent");
        percent(p.getMaxMemoryUsagePercent(), "maxMemoryUsagePercent");
        percent(p.getMaxPowerUsagePercent(), "maxPowerUsagePercent");
        if (p.getMaxTemperatureCelsius() <= 0) {
            throw badRequest("온도 임계는 0 보다 커야 합니다: " + p.getMaxTemperatureCelsius());
        }
    }

    private static void nonNegative(double v, String field) {
        if (v < 0) throw badRequest(String.format("가중치는 0 이상이어야 합니다: %s=%s", field, v));
    }

    private static void percent(double v, String field) {
        if (v <= 0 || v > 100) {
            throw badRequest(String.format("사용률 임계는 0 초과 100 이하여야 합니다: %s=%s", field, v));
        }
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private static String text(JsonNode node, String label) {
        if (!node.isString()) throw badRequest(label + "은(는) 문자열이어야 합니다");
        return node.asString();
    }

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

    private static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }
}
