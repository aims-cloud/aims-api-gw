package com.innogrid.aims.scheduler.service;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.PolicyChoice;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;

import static com.innogrid.aims.scheduler.service.SchedulingService.DEFAULT_POLICY_NAME;

/**
 * 스케줄링 서비스에서 떼어 낸 <b>배치 정책 선택</b> — 종류별 활성 → 공통 활성 → 기본 정책 순으로 고르고, 실행·배치 경로에서는
 * 기본 정책 행이 없을 때 만들어 저장한다.
 *
 * <p>스프링 빈이 아니다. 서비스가 생성자에서 한 번 만들고, 생성자는 참조만 저장한다. 로그는 서비스 이름으로 남긴다 — 옮기기 전과
 * 같은 로거 이름이라야 운영 로그 검색이 그대로 맞는다.</p>
 */
final class PolicyResolver {

    private static final Logger log = LoggerFactory.getLogger(SchedulingService.class);

    private final SchedulingPolicyRepository policyRepository;

    PolicyResolver(SchedulingPolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }

    /** {@link #choose}와 같은 선택에서 {@code default} 행이 없으면 만들어 저장한다(실행·배치 경로 — 이력이 정책 이름을 가리켜야 한다). */
    PolicyChoice resolve(Workload.ResourceType resourceType) {
        PolicyChoice choice = choose(resourceType);
        if (choice.persisted()) return choice;
        return new PolicyChoice(policyRepository.save(choice.policy()), choice.source(), true);
    }

    /** 정책을 고르되 아무것도 저장하지 않는다. 규칙은 {@link SchedulingService#choosePolicy}의 설명과 같다. */
    PolicyChoice choose(Workload.ResourceType resourceType) {
        if (resourceType != null) {
            Optional<SchedulingPolicy> byType =
                    pickOne(policyRepository.findByActiveTrueAndResourceTypeOrderByNameAsc(resourceType), resourceType.name())
                            .or(() -> policyRepository.findFirstByActiveTrueAndResourceType(resourceType));
            if (byType.isPresent()) return new PolicyChoice(byType.get(), PolicyChoice.Source.RESOURCE_TYPE, true);
        }
        // 목록이 비면 단건 조회로 한 번 더 본다. 실제 DB에서는 같은 답이라 활성이 없을 때만 한 번 더 도는 가벼운 질의이고,
        // 단건 조회만 준비한 호출자(기존 단위 테스트의 대역)와의 호환을 유지한다.
        Optional<SchedulingPolicy> common = pickOne(policyRepository.findByActiveTrueAndResourceTypeIsNullOrderByNameAsc(), "공통")
                .or(policyRepository::findFirstByActiveTrueAndResourceTypeIsNull);
        if (common.isPresent()) return new PolicyChoice(common.get(), PolicyChoice.Source.COMMON, true);

        Optional<SchedulingPolicy> existing = policyRepository.findByName(DEFAULT_POLICY_NAME);
        if (existing.isPresent()) {
            log.warn("활성 배치 정책이 없어 비활성 상태의 '{}' 정책 값으로 배치합니다 (저장·상태 변경 없음)", DEFAULT_POLICY_NAME);
            return new PolicyChoice(existing.get(), PolicyChoice.Source.DEFAULT, true);
        }
        return new PolicyChoice(new SchedulingPolicy(DEFAULT_POLICY_NAME, "기본 스케줄링 정책"), PolicyChoice.Source.DEFAULT, false);
    }

    /** 활성 정책 목록에서 하나를 결정적으로 고른다(이름순 첫 번째). 둘 이상이면 WARN. */
    private static Optional<SchedulingPolicy> pickOne(List<SchedulingPolicy> actives, String scope) {
        if (actives.isEmpty()) return Optional.empty();
        if (actives.size() > 1) {
            log.warn("{} 활성 배치 정책이 {}개입니다 — 이름순 첫 번째 '{}' 를 씁니다: {}", scope, actives.size(),
                    actives.get(0).getName(), actives.stream().map(SchedulingPolicy::getName).toList());
        }
        return Optional.of(actives.get(0));
    }
}
