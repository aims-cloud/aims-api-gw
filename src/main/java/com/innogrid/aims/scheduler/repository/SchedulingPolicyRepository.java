package com.innogrid.aims.scheduler.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;

public interface SchedulingPolicyRepository extends JpaRepository<SchedulingPolicy, Long> {

    Optional<SchedulingPolicy> findByName(String name);

    Optional<SchedulingPolicy> findByActiveTrue();
}
