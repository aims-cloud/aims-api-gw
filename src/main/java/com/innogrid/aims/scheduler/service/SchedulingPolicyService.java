package com.innogrid.aims.scheduler.service;

import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.ActivationResponse;
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
 * 사용률 임계 0 이하나 100 초과(전 노드 제외 또는 임계 무효), 온도 임계 0 이하. 가중치 합이 1이 아닌 것은
 * 허용한다 — 가중치는 비율로만 쓰인다.</p>
 *
 * <p>이름 중복은 DB 유니크 제약이 잡아 주지만 그때는 500으로 새므로 먼저 조회해 409를 낸다.
 * {@code defaultEngine}은 등록된 엔진이어야 한다 — 모르는 이름을 저장하면 실행 시 기본 엔진으로 조용히
 * 대체되어 정책의 의도가 사라진다.</p>
 *
 * <p><b>활성 정책은 종류(공통 포함)당 하나</b>. 생성 시 {@code active}를 생략하면 그 종류에 활성이 없을 때만
 * 켜지고, 명시적 {@code active:true}나 {@code PUT active:true}는 충돌 시 409, 전환은 {@link #activate}로 한다.
 * 다른 정책의 상태를 조용히 바꾸는 경로는 없다.</p>
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
        if (request.active() == null) {
            // 조건부 기본 활성 — 그 종류(반영 후)에 활성 정책이 없을 때만 켠다. 빈 상태의 첫 정책은 지금처럼 바로 쓰이고,
            // 이미 활성이 있으면 비활성으로 만들어져 응답에 드러난다. 다른 정책의 상태는 바꾸지 않는다.
            policy.setActive(otherActives(policy).isEmpty());
        }
        ensureActiveUnique(policy);
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
        ensureActiveUnique(policy);
        return repository.save(policy);
    }

    /**
     * 전환 — 같은 리소스 종류의 다른 활성 정책을 끄고 이 정책을 켠다. 한 트랜잭션이다.
     *
     * <p>{@code PUT active:true} 는 충돌 시 409 로 거부한다(다른 정책을 조용히 바꾸지 않기 위해). 전환은 "다른 것을
     * 끄고 이것을 켜라"가 요청의 명시된 의미이므로 끈 정책을 응답에 담아 돌려준다. 이미 활성이면 끄는 것 없이 200.</p>
     */
    @Transactional
    public ActivationResponse activate(Long id) {
        SchedulingPolicy target = require(id);
        List<SchedulingPolicy> others = otherActives(target);
        for (SchedulingPolicy other : others) other.setActive(false);
        target.setActive(true);
        repository.saveAll(others);
        repository.save(target);
        return ActivationResponse.of(target, others);
    }

    /** 없으면 404. */
    public SchedulingPolicy require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "정책을 찾을 수 없습니다: " + id));
    }

    // ── 반영 ───────────────────────────────────────────────────

    private void apply(SchedulingPolicy policy, SchedulingPolicyRequest r) {
        // "비울 수 있는" 세 필드 — 키 없음(null)은 유지, 명시적 null(isNull)은 비움
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

    // ── 활성 유일성 — 종류(공통 포함)당 활성 정책은 하나 ─────────────

    /** 같은 리소스 종류의 다른 활성 정책. 자기 자신(id 일치)은 뺀다. */
    private List<SchedulingPolicy> otherActives(SchedulingPolicy policy) {
        List<SchedulingPolicy> actives = policy.getResourceType() == null
                ? repository.findByActiveTrueAndResourceTypeIsNullOrderByNameAsc()
                : repository.findByActiveTrueAndResourceTypeOrderByNameAsc(policy.getResourceType());
        return actives.stream()
                .filter(p -> policy.getId() == null || !p.getId().equals(policy.getId()))
                .toList();
    }

    /**
     * 반영이 끝난 정책이 활성이면 같은 종류에 다른 활성이 없어야 한다. 있으면 409.
     * 요청이 아니라 반영 후 정책을 보므로 종류를 바꾸는 부분 수정도 걸린다.
     */
    private void ensureActiveUnique(SchedulingPolicy policy) {
        if (!policy.isActive()) return;
        List<SchedulingPolicy> others = otherActives(policy);
        if (others.isEmpty()) return;
        SchedulingPolicy other = others.get(0);
        throw new BusinessException(HttpStatus.CONFLICT, String.format(
                "같은 리소스 종류(%s)에 이미 활성 정책이 있습니다: %s(%d). 먼저 그 정책을 비활성화하거나 POST /policies/%d/activate 로 전환하세요",
                policy.getResourceType() == null ? "공통" : policy.getResourceType().name(),
                other.getName(), other.getId(), policy.getId() == null ? 0 : policy.getId()).replace("/policies/0/activate", "/policies/{id}/activate"));
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
