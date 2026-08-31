package com.innogrid.aims.scheduler.repository;

import org.junit.jupiter.api.BeforeEach;
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

    @Test
    void 공통_활성_정책을_찾는다() {
        repository.save(activePolicy("common", null));

        assertTrue(repository.findFirstByActiveTrueAndResourceTypeIsNull().isPresent());
        assertEquals("common", repository.findFirstByActiveTrueAndResourceTypeIsNull().get().getName());
    }

    @Test
    void 종류별_활성_정책을_찾는다() {
        repository.save(activePolicy("gpu", ResourceType.GPU));

        assertEquals("gpu", repository.findFirstByActiveTrueAndResourceType(ResourceType.GPU).get().getName());
        assertTrue(repository.findFirstByActiveTrueAndResourceType(ResourceType.VM).isEmpty());
    }

    @Test
    void 공통과_종류별_활성_정책이_함께_있어도_각각_조회된다() {
        repository.save(activePolicy("common", null));
        repository.save(activePolicy("gpu", ResourceType.GPU));

        assertEquals("common", repository.findFirstByActiveTrueAndResourceTypeIsNull().get().getName());
        assertEquals("gpu", repository.findFirstByActiveTrueAndResourceType(ResourceType.GPU).get().getName());
        assertEquals(2, repository.count());
    }

    @Test
    void 비활성_정책은_조회되지_않는다() {
        SchedulingPolicy inactive = activePolicy("gpu-old", ResourceType.GPU);
        inactive.setActive(false);
        repository.save(inactive);

        assertTrue(repository.findFirstByActiveTrueAndResourceType(ResourceType.GPU).isEmpty());
    }

    @Test
    void 신규_필드가_저장되고_읽힌다() {
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

    @Test
    void 신규_필드는_지정하지_않으면_null_이다() {
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
