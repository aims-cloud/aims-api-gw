package com.innogrid.aims.billing.repository;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.billing.entity.PricingPolicy;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class PricingPolicyRepositoryTest {

    @Autowired
    PricingPolicyRepository repository;

    @BeforeEach
    void setUp() {
        repository.save(new PricingPolicy("cpu_usage", "hour", 500, "CPU 시간당 단가"));
        repository.save(new PricingPolicy("mem_usage", "hour", 300, "메모리 시간당 단가"));
        PricingPolicy inactive = new PricingPolicy("old_metric", "hour", 100, "비활성");
        inactive.setActive(false);
        repository.save(inactive);
    }

    @Test
    void 활성_정책만_조회() {
        List<PricingPolicy> policies = repository.findByActiveTrueOrderByMetricName();
        assertEquals(2, policies.size());
    }

    @Test
    void 메트릭별_단가_조회() {
        Optional<PricingPolicy> policy = repository.findByMetricNameAndActiveTrue("cpu_usage");
        assertTrue(policy.isPresent());
        assertEquals(500, policy.get().getUnitPrice());
    }

    @Test
    void 비활성_메트릭_조회_안됨() {
        Optional<PricingPolicy> policy = repository.findByMetricNameAndActiveTrue("old_metric");
        assertTrue(policy.isEmpty());
    }
}
