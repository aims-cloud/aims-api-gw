package com.innogrid.aims.resource.repository;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.resource.entity.CatalogItem;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class CatalogItemRepositoryTest {

    @Autowired
    CatalogItemRepository repository;

    @BeforeEach
    void setUp() {
        CatalogItem small = new CatalogItem("Small VM", "2vCPU, 4GB", "COMPUTE");
        small.setCpu(2);
        small.setMemoryGb(4);
        small.setMonthlyPrice(50000);
        repository.save(small);

        CatalogItem gpu = new CatalogItem("GPU VM", "8vCPU, A100", "GPU");
        gpu.setCpu(8);
        gpu.setGpu("A100");
        gpu.setMonthlyPrice(500000);
        repository.save(gpu);

        CatalogItem inactive = new CatalogItem("Old VM", "deprecated", "COMPUTE");
        inactive.setActive(false);
        repository.save(inactive);
    }

    @Test
    void 활성_카탈로그만_조회() {
        List<CatalogItem> items = repository.findByActiveTrueOrderByCreatedAtDesc();
        assertEquals(2, items.size());
    }

    @Test
    void 전체_카탈로그_조회() {
        assertEquals(3, repository.count());
    }

    @Test
    void 카탈로그_필드_검증() {
        CatalogItem item = repository.findByActiveTrueOrderByCreatedAtDesc().stream()
                .filter(i -> i.getName().equals("GPU VM"))
                .findFirst().orElseThrow();
        assertEquals("A100", item.getGpu());
        assertEquals(500000, item.getMonthlyPrice());
    }
}
