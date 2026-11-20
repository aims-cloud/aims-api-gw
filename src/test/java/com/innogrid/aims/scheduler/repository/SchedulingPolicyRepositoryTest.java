package com.innogrid.aims.scheduler.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload.ResourceType;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class SchedulingPolicyRepositoryTest {

    @Autowired
    private SchedulingPolicyRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @DisplayName("공통 활성 정책을 찾는다")
    @Test
    void finds_common_active_policy() {
        repository.save(activePolicy("common", null));

        assertTrue(repository.findFirstByActiveTrueAndResourceTypeIsNull().isPresent());
        assertEquals("common", repository.findFirstByActiveTrueAndResourceTypeIsNull().get().getName());
    }

    @DisplayName("종류별 활성 정책을 찾는다")
    @Test
    void finds_active_policy_per_type() {
        repository.save(activePolicy("gpu", ResourceType.GPU));

        assertEquals("gpu", repository.findFirstByActiveTrueAndResourceType(ResourceType.GPU).get().getName());
        assertTrue(repository.findFirstByActiveTrueAndResourceType(ResourceType.VM).isEmpty());
    }

    @DisplayName("공통과 종류별 활성 정책이 함께 있어도 각각 조회된다")
    @Test
    void common_and_type_policies_both_found() {
        repository.save(activePolicy("common", null));
        repository.save(activePolicy("gpu", ResourceType.GPU));

        assertEquals("common", repository.findFirstByActiveTrueAndResourceTypeIsNull().get().getName());
        assertEquals("gpu", repository.findFirstByActiveTrueAndResourceType(ResourceType.GPU).get().getName());
        assertEquals(2, repository.count());
    }

    @DisplayName("비활성 정책은 조회되지 않는다")
    @Test
    void inactive_policy_not_found() {
        SchedulingPolicy inactive = activePolicy("gpu-old", ResourceType.GPU);
        inactive.setActive(false);
        repository.save(inactive);

        assertTrue(repository.findFirstByActiveTrueAndResourceType(ResourceType.GPU).isEmpty());
    }

    @DisplayName("신규 필드가 저장되고 읽힌다")
    @Test
    void new_fields_saved_and_read_back() {
        SchedulingPolicy policy = activePolicy("gpu", ResourceType.GPU);
        policy.setDefaultEngine("power");
        policy.setWarningTemperatureCelsius(70.0);
        repository.save(policy);
        repository.flush();

        SchedulingPolicy loaded = repository.findFirstByActiveTrueAndResourceType(ResourceType.GPU).orElseThrow();
        assertEquals(ResourceType.GPU, loaded.getResourceType());
        assertEquals("power", loaded.getDefaultEngine());
        assertEquals(70.0, loaded.getWarningTemperatureCelsius());
    }

    @DisplayName("신규 필드는 지정하지 않으면 null 이다")
    @Test
    void new_fields_null_when_omitted() {
        repository.save(activePolicy("common", null));
        repository.flush();

        SchedulingPolicy loaded = repository.findFirstByActiveTrueAndResourceTypeIsNull().orElseThrow();
        assertNull(loaded.getResourceType());
        assertNull(loaded.getDefaultEngine());
        assertNull(loaded.getWarningTemperatureCelsius());
    }

    private SchedulingPolicy activePolicy(String name, ResourceType type) {
        SchedulingPolicy policy = new SchedulingPolicy(name, name + " 정책");
        policy.setResourceType(type);
        policy.setActive(true);
        return policy;
    }
}
