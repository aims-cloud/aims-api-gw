package com.innogrid.aims.monitoring.service;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.monitoring.entity.MonitoringTarget;
import com.innogrid.aims.monitoring.entity.MonitoringTarget.ExporterType;
import com.innogrid.aims.monitoring.entity.MonitoringTarget.TargetStatus;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class ResourceCollectorServiceTest {

    @Autowired
    ResourceCollectorService service;

    @Test
    void 수집대상_등록_및_조회() {
        MonitoringTarget target = service.registerTarget(
                "mdc-01", "node-1", "192.168.1.10", 9100, ExporterType.NODE_EXPORTER);

        assertNotNull(target.getId());
        assertEquals("mdc-01", target.getMdcId());
        assertEquals("192.168.1.10:9100", target.getScrapeEndpoint());
        assertEquals(TargetStatus.PENDING, target.getStatus());
    }

    @Test
    void MDC별_수집대상_목록_조회() {
        service.registerTarget("mdc-01", "node-1", "192.168.1.10", 9100, ExporterType.NODE_EXPORTER);
        service.registerTarget("mdc-01", "gpu-1", "192.168.1.11", 9400, ExporterType.DCGM_EXPORTER);
        service.registerTarget("mdc-02", "node-1", "192.168.2.10", 9100, ExporterType.NODE_EXPORTER);

        List<MonitoringTarget> mdc01 = service.listTargets("mdc-01");
        assertEquals(2, mdc01.size());

        List<MonitoringTarget> all = service.listTargets(null);
        assertEquals(3, all.size());
    }

    @Test
    void 수집대상_삭제() {
        MonitoringTarget target = service.registerTarget(
                "mdc-01", "node-1", "192.168.1.10", 9100, ExporterType.NODE_EXPORTER);

        service.removeTarget(target.getId());

        assertNull(service.getTarget(target.getId()));
    }
}
