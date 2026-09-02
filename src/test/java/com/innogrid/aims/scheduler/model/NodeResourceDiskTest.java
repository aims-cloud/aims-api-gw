package com.innogrid.aims.scheduler.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 디스크 총량·예약·가용량 — 생성자를 바꾸지 않고 세터와 오버로드로 더한 필드의 계산.
 */
class NodeResourceDiskTest {

    @Test
    void 총량과_사용률과_예약으로_가용_디스크를_계산한다() {
        NodeResource node = node(40).withDiskCapacityGb(500);    // 사용률 40% → 300 GB 남음

        assertEquals(500, node.diskCapacityGb());
        assertEquals(300, node.availableDiskGb());

        node.reserve(1, 512, 0, 100);
        assertEquals(100, node.reservedDiskGb());
        assertEquals(200, node.availableDiskGb());
    }

    @Test
    void 총량을_모르면_가용_디스크는_0_이다() {
        NodeResource node = node(40);                             // withDiskCapacityGb 를 부르지 않음

        assertEquals(0, node.diskCapacityGb(), "0 = 모름");
        assertEquals(0, node.availableDiskGb());
        node.reserve(1, 512, 0, 100);
        assertEquals(0, node.availableDiskGb(), "모르는 총량에서 예약을 빼 음수를 만들지 않는다");
    }

    @Test
    void 기존_3인자_reserve_는_디스크를_예약하지_않는다() {
        NodeResource node = node(0).withDiskCapacityGb(500);

        node.reserve(2, 4096, 1);

        assertEquals(0, node.reservedDiskGb());
        assertEquals(500, node.availableDiskGb());
        assertEquals(2, node.getReservedCpu(), "CPU·메모리·GPU 예약은 예전과 같다");
    }

    @Test
    void 예약이_총량을_넘어도_가용은_0_아래로_가지_않는다() {
        NodeResource node = node(0).withDiskCapacityGb(100);
        node.reserve(0, 0, 0, 150);

        assertEquals(0, node.availableDiskGb());
    }

    @Test
    void snapshot_은_디스크_총량과_예약을_복사한다() {
        NodeResource node = node(20).withDiskCapacityGb(500);
        node.reserve(1, 512, 0, 50);

        NodeResource copy = node.snapshot();

        assertEquals(500, copy.diskCapacityGb());
        assertEquals(50, copy.reservedDiskGb());
        assertEquals(node.availableDiskGb(), copy.availableDiskGb());
    }

    private static NodeResource node(double diskUsagePercent) {
        return new NodeResource("mdc-01", "n1", 8, 16384, 0, 10, 20, diskUsagePercent, 200, 500, 45, 1000, 500);
    }
}
