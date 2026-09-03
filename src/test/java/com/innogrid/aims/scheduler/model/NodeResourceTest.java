package com.innogrid.aims.scheduler.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NodeResourceTest {

    @DisplayName("가용량은 사용률에서 계산한다")
    @Test
    void available_from_usage() {
        NodeResource node = node(8, 16384, 2, 25, 50);

        assertEquals(6.0, node.availableCpu(), 1e-9);
        assertEquals(8192, node.availableMemoryMb());
        assertEquals(2, node.availableGpu());
    }

    @DisplayName("예약분은 가용량에서 차감된다")
    @Test
    void reservations_subtracted() {
        NodeResource node = node(8, 16384, 2, 25, 50);

        node.reserve(2, 4096, 1);

        assertEquals(4.0, node.availableCpu(), 1e-9);
        assertEquals(4096, node.availableMemoryMb());
        assertEquals(1, node.availableGpu());
    }

    @DisplayName("예약은 누적된다")
    @Test
    void reservations_accumulate() {
        NodeResource node = node(8, 16384, 2, 0, 0);

        node.reserve(2, 1024, 1);
        node.reserve(3, 2048, 1);

        assertEquals(5, node.getReservedCpu());
        assertEquals(3072, node.getReservedMemoryMb());
        assertEquals(2, node.getReservedGpu());
        assertEquals(3.0, node.availableCpu(), 1e-9);
    }

    @DisplayName("예약을 반영해 수용 가능 여부를 판단한다")
    @Test
    void fit_check_uses_reservations() {
        NodeResource node = node(4, 8192, 1, 0, 0);

        assertTrue(node.canAccommodate(3, 4096, 1));

        node.reserve(3, 4096, 1);

        assertFalse(node.canAccommodate(3, 4096, 0), "CPU 가 모자란다");
        assertFalse(node.canAccommodate(1, 1024, 1), "GPU 가 모자란다");
        assertTrue(node.canAccommodate(1, 1024, 0));
    }

    @DisplayName("전력 여유율은 용량 대비로 계산한다")
    @Test
    void power_headroom_vs_capacity() {
        NodeResource node = new NodeResource("mdc-01", "node-1", 8, 16384, 0,
                0, 0, 0, 400, 500, 50, 0, 0);

        assertEquals(20.0, node.powerHeadroomPercent(), 1e-9);
    }

    @DisplayName("전력 용량을 모르면 여유율을 100으로 본다")
    @Test
    void unknown_power_headroom_100() {
        NodeResource node = new NodeResource("mdc-01", "node-1", 8, 16384, 0,
                0, 0, 0, 400, 0, 50, 0, 0);

        assertEquals(100.0, node.powerHeadroomPercent(), 1e-9);
    }

    @DisplayName("예약이 없으면 실효 사용률은 실측값과 같다")
    @Test
    void no_reservation_usage_measured() {
        NodeResource node = node(8, 16384, 2, 25, 50);

        assertEquals(25.0, node.effectiveCpuUsagePercent(), 1e-9);
        assertEquals(50.0, node.effectiveMemoryUsagePercent(), 1e-9);
        assertEquals(0.0, node.reservedCpuRatio(), 1e-9);
    }

    @DisplayName("실효 사용률은 예약분을 더한다")
    @Test
    void effective_usage_adds_reservation() {
        NodeResource node = node(8, 16384, 2, 20, 40);

        node.reserve(2, 1638, 0);

        // CPU 8코어 중 2코어 예약 → 25%p 가산
        assertEquals(45.0, node.effectiveCpuUsagePercent(), 1e-9);
        assertEquals(0.25, node.reservedCpuRatio(), 1e-9);
        assertEquals(49.99, node.effectiveMemoryUsagePercent(), 0.01);
    }

    @DisplayName("실효 사용률은 100을 넘지 않는다")
    @Test
    void effective_usage_capped_100() {
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
