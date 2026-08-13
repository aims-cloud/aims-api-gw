package com.innogrid.aims.billing.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.billing.entity.PricingPolicy;

public interface PricingPolicyRepository extends JpaRepository<PricingPolicy, Long> {

    List<PricingPolicy> findByActiveTrueOrderByMetricName();

    Optional<PricingPolicy> findByMetricNameAndActiveTrue(String metricName);
}
