package com.innogrid.aims.scheduler.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;

public interface SchedulingPolicyRepository extends JpaRepository<SchedulingPolicy, Long> {

    Optional<SchedulingPolicy> findByName(String name);

    /**
     * 활성 정책 하나.
     *
     * @deprecated 리소스 종류별 정책이 생기면서 활성 정책이 둘 이상 존재할 수 있다.
     *             이 메서드는 그때 {@code NonUniqueResultException} 을 던지므로
     *             {@link #findFirstByActiveTrueAndResourceType} /
     *             {@link #findFirstByActiveTrueAndResourceTypeIsNull} 을 쓴다.
     *             기존 호출부 호환을 위해 남겨 둔다.
     */
    @Deprecated
    Optional<SchedulingPolicy> findByActiveTrue();

    /** 특정 리소스 종류를 위한 활성 정책. */
    Optional<SchedulingPolicy> findFirstByActiveTrueAndResourceType(Workload.ResourceType resourceType);

    /** 종류를 가리지 않는 공통 활성 정책. */
    Optional<SchedulingPolicy> findFirstByActiveTrueAndResourceTypeIsNull();
}
