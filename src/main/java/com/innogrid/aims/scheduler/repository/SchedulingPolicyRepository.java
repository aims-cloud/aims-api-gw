package com.innogrid.aims.scheduler.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;

/**
 * 배치 정책 조회.
 *
 * <p>활성 정책은 <b>종류(공통 포함)당 하나</b>가 규칙이다. 그래도 API 밖에서 들어온 데이터로 둘 이상이
 * 있을 수 있으므로 목록 조회를 두고, 호출자가 결정적으로 고른다. 예전의 {@code findByActiveTrue()}(Optional) 는
 * 활성이 둘이면 예외를 던지는 지뢰였고 사용처가 없어 지웠다.</p>
 */
public interface SchedulingPolicyRepository extends JpaRepository<SchedulingPolicy, Long> {

    Optional<SchedulingPolicy> findByName(String name);

    /** 특정 리소스 종류를 위한 활성 정책 하나. 둘 이상이면 어느 것인지 정해지지 않는다 — 결정이 필요하면 목록을 쓴다. */
    Optional<SchedulingPolicy> findFirstByActiveTrueAndResourceType(Workload.ResourceType resourceType);

    /** 종류를 가리지 않는 공통 활성 정책 하나. */
    Optional<SchedulingPolicy> findFirstByActiveTrueAndResourceTypeIsNull();

    /** 특정 리소스 종류의 활성 정책 전부(이름순). 유일성 검사와 결정적 선택에 쓴다. */
    List<SchedulingPolicy> findByActiveTrueAndResourceTypeOrderByNameAsc(Workload.ResourceType resourceType);

    /** 공통 활성 정책 전부(이름순). */
    List<SchedulingPolicy> findByActiveTrueAndResourceTypeIsNullOrderByNameAsc();
}
