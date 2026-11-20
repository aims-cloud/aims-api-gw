package com.innogrid.aims.scheduler.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.PlacementDispatch.DispatchStatus;

import static org.junit.jupiter.api.Assertions.*;

/** 디스패치 상태 전이표와 멱등키. */
class PlacementDispatchTest {

    private static Workload workload() {
        Workload w = new Workload("dp-w", 2, 1024);
        w.assignTo("mdc-01", "n1", 0.8, "ok");
        return w;
    }

    @DisplayName("새 기록은 REQUESTED 이고 수락하면 ACCEPTED 와 응답 시각이 남는다")
    @Test
    void new_dispatch_requested_then_accepted() {
        PlacementDispatch d = new PlacementDispatch(workload(), 7L, "SYSTEM");
        assertEquals(DispatchStatus.REQUESTED, d.getStatus());
        assertEquals("n1", d.getNodeId());
        assertEquals(7L, d.getScheduleRunId());
        assertNull(d.getRespondedAt());
        d.accept("ext-1", "ok");
        assertEquals(DispatchStatus.ACCEPTED, d.getStatus());
        assertEquals("ext-1", d.getExternalRef());
        assertNotNull(d.getRespondedAt());
    }

    @DisplayName("콜백 전이표 — ACCEPTED→RUNNING→COMPLETED 는 되고 역행·건너뛰기·끝 상태 이후는 안 된다")
    @Test
    void report_transition_table() {
        PlacementDispatch d = new PlacementDispatch(workload(), null, "u");
        assertFalse(d.report(DispatchStatus.RUNNING, null, null), "REQUESTED 에서는 보고를 받지 않는다");
        d.accept(null, null);
        assertFalse(d.report(DispatchStatus.COMPLETED, null, null), "RUNNING 을 건너뛸 수 없다");
        assertTrue(d.report(DispatchStatus.RUNNING, "ext", "시작"));
        assertTrue(d.report(DispatchStatus.RUNNING, null, null), "같은 상태 재보고는 멱등");
        assertEquals("ext", d.getExternalRef());
        assertTrue(d.report(DispatchStatus.COMPLETED, null, "끝"));
        assertFalse(d.report(DispatchStatus.FAILED, null, null), "끝 상태 이후는 거부");
        assertTrue(d.getStatus().terminal());
    }

    @DisplayName("보고 가능한 상태는 RUNNING·COMPLETED·FAILED 뿐이고 REJECTED 같은 내부 상태는 보고할 수 없다")
    @Test
    void only_reportable_statuses_accepted() {
        PlacementDispatch d = new PlacementDispatch(workload(), null, "u");
        d.accept(null, null);
        assertFalse(d.report(DispatchStatus.REJECTED, null, null));
        assertFalse(d.report(DispatchStatus.ACCEPTED, null, null));
        assertFalse(d.report(null, null, null));
        assertTrue(d.report(DispatchStatus.FAILED, null, "노드 준비 실패"));
        assertEquals("노드 준비 실패", d.getMessage());
    }

    @DisplayName("멱등키는 워크로드 id 와 배정 시각으로 만들어져 같은 결정은 같은 키다")
    @Test
    void idempotency_key_from_id_and_scheduled_at() {
        Workload w = workload();
        assertTrue(PlacementDispatch.keyOf(w).startsWith("null:"), "id 가 없으면 null 접두어");
        assertEquals(PlacementDispatch.keyOf(w), PlacementDispatch.keyOf(w));
        Workload pending = new Workload("p", 1, 1);
        assertTrue(PlacementDispatch.keyOf(pending).endsWith(":0"), "배정 전이면 0");
    }

    @DisplayName("실행 계층 거부는 실패로 돌리고 배정을 해제하며 재시도 횟수를 누적한다")
    @Test
    void fail_dispatch_clears_assignment() {
        Workload w = workload();
        w.failDispatch("거부");
        assertEquals(Workload.WorkloadStatus.FAILED, w.getStatus());
        assertNull(w.getAssignedNodeId());
        assertNull(w.getAssignedMdcId());
        assertNull(w.getSchedulingScore());
        assertNotNull(w.getScheduledAt(), "추적을 위해 배정 시각은 남긴다");
        assertEquals(1, w.getRetryCount());
        assertEquals("거부", w.getSchedulingReason());
    }

    @DisplayName("예약 취소는 FAILED 로 마감하고 표지를 앞에 붙이며 그 뒤 RUNNING·COMPLETED 보고는 거부, FAILED 재보고는 무변경이다")
    @Test
    void cancel_closes_as_failed_with_note() {
        PlacementDispatch d = new PlacementDispatch(workload(), null, "u");
        d.accept("ext-9", "수락");
        assertNotNull(d.getRespondedAt());

        d.cancel("예약 취소 — 운영자 요청(tester)");

        assertEquals(DispatchStatus.FAILED, d.getStatus());
        assertTrue(d.getStatus().terminal());
        assertEquals("예약 취소 — 운영자 요청(tester) / 수락", d.getMessage(), "표지가 앞, 기존 메시지가 뒤");
        assertNotNull(d.getRespondedAt());
        assertFalse(d.report(DispatchStatus.RUNNING, null, null), "끝 상태라 실행 보고 거부");
        assertFalse(d.report(DispatchStatus.COMPLETED, null, null));
        assertTrue(d.report(DispatchStatus.FAILED, null, "늦은 실패"), "같은 상태 재보고는 멱등");
        assertEquals("예약 취소 — 운영자 요청(tester) / 수락", d.getMessage(), "무변경 — 메시지도 덮이지 않는다");
    }

    @DisplayName("기존 비고가 길어도 예약 취소 표지는 앞에 남고 뒤가 잘린다")
    @Test
    void cancel_keeps_marker_when_message_is_long() {
        PlacementDispatch d = new PlacementDispatch(workload(), null, "u");
        d.accept(null, "x".repeat(PlacementDispatch.MESSAGE_MAX - 10));

        d.cancel("예약 취소 — 운영자 요청(tester)");

        assertEquals(PlacementDispatch.MESSAGE_MAX, d.getMessage().length());
        assertTrue(d.getMessage().startsWith("예약 취소 — 운영자 요청(tester) / xxx"));
    }
}
