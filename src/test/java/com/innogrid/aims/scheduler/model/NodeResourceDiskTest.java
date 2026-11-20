package com.innogrid.aims.scheduler.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 디스크 총량·예약·가용량 — 생성자를 바꾸지 않고 세터와 오버로드로 더한 필드의 계산.
 */
class NodeResourceDiskTest {

    @DisplayName("총량과 사용률과 예약으로 가용 디스크를 계산한다")
    @Test
    void available_disk_from_usage_and_reserve() {
        NodeResource node = node(40).withDiskCapacityGb(500);    // 사용률 40% → 300GB 남음

        assertEquals(500, node.diskCapacityGb());
        assertEquals(300, node.availableDiskGb());

        node.reserve(1, 512, 0, 100);
        assertEquals(100, node.reservedDiskGb());
        assertEquals(200, node.availableDiskGb());
    }

    @DisplayName("총량을 모르면 가용 디스크는 0 이다")
    @Test
    void unknown_capacity_disk_0() {
        NodeResource node = node(40);                             // withDiskCapacityGb를 부르지 않음

        assertEquals(0, node.diskCapacityGb(), "0 = 모름");
        assertEquals(0, node.availableDiskGb());
        node.reserve(1, 512, 0, 100);
        assertEquals(0, node.availableDiskGb(), "모르는 총량에서 예약을 빼 음수를 만들지 않는다");
    }

    @DisplayName("기존 3인자 reserve 는 디스크를 예약하지 않는다")
    @Test
    void reserve_3_arg_skips_disk() {
        NodeResource node = node(0).withDiskCapacityGb(500);

        node.reserve(2, 4096, 1);

        assertEquals(0, node.reservedDiskGb());
        assertEquals(500, node.availableDiskGb());
        assertEquals(2, node.getReservedCpu(), "CPU·메모리·GPU 예약은 예전과 같다");
    }

    @DisplayName("예약이 총량을 넘어도 가용은 0 아래로 가지 않는다")
    @Test
    void over_reservation_floors_at_0() {
        NodeResource node = node(0).withDiskCapacityGb(100);
        node.reserve(0, 0, 0, 150);

        assertEquals(0, node.availableDiskGb());
    }

    @DisplayName("snapshot 은 디스크 총량과 예약을 복사한다")
    @Test
    void snapshot_copies_disk_fields() {
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
