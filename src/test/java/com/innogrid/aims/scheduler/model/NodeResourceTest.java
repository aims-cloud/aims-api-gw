package com.innogrid.aims.scheduler.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NodeResourceTest {

    @Test
    void 가용량은_사용률에서_계산한다() {
        NodeResource node = node(8, 16384, 2, 25, 50);

        assertEquals(6.0, node.availableCpu(), 1e-9);
        assertEquals(8192, node.availableMemoryMb());
        assertEquals(2, node.availableGpu());
    }

    @Test
    void 예약분은_가용량에서_차감된다() {
        NodeResource node = node(8, 16384, 2, 25, 50);

        node.reserve(2, 4096, 1);

        assertEquals(4.0, node.availableCpu(), 1e-9);
        assertEquals(4096, node.availableMemoryMb());
        assertEquals(1, node.availableGpu());
    }

    @Test
    void 예약은_누적된다() {
        NodeResource node = node(8, 16384, 2, 0, 0);

        node.reserve(2, 1024, 1);
        node.reserve(3, 2048, 1);

        assertEquals(5, node.getReservedCpu());
        assertEquals(3072, node.getReservedMemoryMb());
        assertEquals(2, node.getReservedGpu());
        assertEquals(3.0, node.availableCpu(), 1e-9);
    }

    @Test
    void 예약을_반영해_수용_가능_여부를_판단한다() {
        NodeResource node = node(4, 8192, 1, 0, 0);

        assertTrue(node.canAccommodate(3, 4096, 1));

        node.reserve(3, 4096, 1);

        assertFalse(node.canAccommodate(3, 4096, 0), "CPU 가 모자란다");
        assertFalse(node.canAccommodate(1, 1024, 1), "GPU 가 모자란다");
        assertTrue(node.canAccommodate(1, 1024, 0));
    }

    @Test
    void 전력_여유율은_용량_대비로_계산한다() {
        NodeResource node = new NodeResource("mdc-01", "node-1", 8, 16384, 0,
                0, 0, 0, 400, 500, 50, 0, 0);

        assertEquals(20.0, node.powerHeadroomPercent(), 1e-9);
    }

    @Test
    void 전력_용량을_모르면_여유율을_100으로_본다() {
        NodeResource node = new NodeResource("mdc-01", "node-1", 8, 16384, 0,
                0, 0, 0, 400, 0, 50, 0, 0);

        assertEquals(100.0, node.powerHeadroomPercent(), 1e-9);
    }

    @Test
    void 예약이_없으면_실효_사용률은_실측값과_같다() {
        NodeResource node = node(8, 16384, 2, 25, 50);

        assertEquals(25.0, node.effectiveCpuUsagePercent(), 1e-9);
        assertEquals(50.0, node.effectiveMemoryUsagePercent(), 1e-9);
        assertEquals(0.0, node.reservedCpuRatio(), 1e-9);
    }

    @Test
    void 실효_사용률은_예약분을_더한다() {
        NodeResource node = node(8, 16384, 2, 20, 40);

        node.reserve(2, 1638, 0);

        // CPU 8코어 중 2코어 예약 → 25%p 가산
        assertEquals(45.0, node.effectiveCpuUsagePercent(), 1e-9);
        assertEquals(0.25, node.reservedCpuRatio(), 1e-9);
        assertEquals(49.99, node.effectiveMemoryUsagePercent(), 0.01);
    }

    @Test
    void 실효_사용률은_100을_넘지_않는다() {
        NodeResource node = node(8, 16384, 2, 90, 95);

        node.reserve(8, 16384, 0);

        assertEquals(100.0, node.effectiveCpuUsagePercent(), 1e-9);
        assertEquals(100.0, node.effectiveMemoryUsagePercent(), 1e-9);
        assertEquals(1.0, node.reservedCpuRatio(), 1e-9);
    }

    private NodeResource node(int cpu, long memMb, int gpu, double cpuUsage, double memUsage) {
        return new NodeResource("mdc-01", "node-1", cpu, memMb, gpu,
                cpuUsage, memUsage, 30, 200, 500, 50, 1000, 500);
    }
}
