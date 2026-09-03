package com.innogrid.aims.scheduler.service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.model.ExcludedNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 보호 장치 — 상한 안이면 그대로, 넘기면 기다리기를 멈추고 "응답 없음", 연결 실패도 같은 예외, 순서 보존, 설정 기본값.
 */
class MetricsQueryGuardTest {

    private final MetricsQueryGuard guard = new MetricsQueryGuard(2);

    @AfterEach
    void down() {
        guard.shutdown();
    }

    @DisplayName("상한 안에 끝나면 결과를 그대로 돌려준다")
    @Test
    void within_timeout_returns_result() {
        assertEquals("ok", guard.call(() -> "ok", 1000));
    }

    @DisplayName("상한을 넘기면 기다리기를 멈추고 응답 없음 예외를 던진다")
    @Test
    void timeout_throws_unresponsive() {
        CountDownLatch release = new CountDownLatch(1);
        long started = System.nanoTime();
        try {
            MetricsQueryGuard.MetricsUnavailableException e = assertThrows(MetricsQueryGuard.MetricsUnavailableException.class,
                    () -> guard.call(() -> block(release), 100));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMs < 2000, "상한(100ms) 근처에서 돌아와야 한다: " + elapsedMs + "ms");
            assertTrue(e.getMessage().contains("100ms"), e.getMessage());
            assertEquals(100, e.elapsedMs());
        } finally {
            release.countDown();
        }
    }

    @DisplayName("저장소 연결 실패도 응답 없음 예외로 바꾼다 다른 예외는 그대로")
    @Test
    void connection_failure_becomes_unresponsive() {
        MetricsQueryGuard.MetricsUnavailableException e = assertThrows(MetricsQueryGuard.MetricsUnavailableException.class,
                () -> guard.call(() -> { throw new ResourceAccessException("connection refused"); }, 1000));
        assertTrue(e.getMessage().contains("연결하지 못했습니다"), e.getMessage());

        IllegalArgumentException other = assertThrows(IllegalArgumentException.class,
                () -> guard.call(() -> { throw new IllegalArgumentException("bad promql"); }, 1000));
        assertEquals("bad promql", other.getMessage());
    }

    @DisplayName("여러 작업은 제출 순서대로 받고 넘긴 것만 비운다")
    @Test
    void tasks_return_in_submit_order() {
        CountDownLatch release = new CountDownLatch(1);
        try {
            List<Future<String>> futures = List.of(
                    guard.submit(() -> "first"),
                    guard.submit(() -> block(release)),
                    guard.submit(() -> "third"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

            List<Optional<String>> out = guard.awaitAll(futures, 200, deadline);

            assertEquals(Optional.of("first"), out.get(0));
            assertTrue(out.get(1).isEmpty(), "느린 작업만 비어 있다");
            assertEquals(Optional.of("third"), out.get(2), "순서가 제출 순서와 같다");
        } finally {
            release.countDown();
        }
    }

    @DisplayName("전체 기한이 먼저 오면 남은 작업은 기다리지 않는다")
    @Test
    void overall_deadline_skips_remaining_tasks() {
        CountDownLatch release = new CountDownLatch(1);
        try {
            List<Future<String>> futures = List.of(
                    guard.submit(() -> block(release)),
                    guard.submit(() -> block(release)));
            long started = System.nanoTime();
            long deadline = started + TimeUnit.MILLISECONDS.toNanos(150);

            List<Optional<String>> out = guard.awaitAll(futures, 10_000, deadline);

            assertTrue(out.stream().allMatch(Optional::isEmpty));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000, "전체 기한 150ms 근처에서 끝난다");
        } finally {
            release.countDown();
        }
    }

    @DisplayName("설정 기본값과 제외 사유 문구")
    @Test
    void default_settings_and_reason_text() {
        SchedulerProperties.Metrics m = new SchedulerProperties().getMetrics();
        assertEquals(10_000, m.getNodeTimeoutMs());
        assertEquals(30_000, m.getCollectTimeoutMs());
        assertEquals(5_000, m.getQueryTimeoutMs());
        assertEquals(4, m.getParallelism());
        assertEquals(4, new MetricsQueryGuard(new SchedulerProperties()).parallelism());

        ExcludedNode t = ExcludedNode.timedOut("mdc-01", "n1", 10_000);
        assertTrue(t.isTimeout());
        assertTrue(t.reason().contains("응답 없음") && t.reason().contains("10000ms"), t.reason());
        ExcludedNode m2 = new ExcludedNode("mdc-01", "n2", List.of("cpu사용률"));
        assertFalse(m2.isTimeout());
        assertTrue(m2.reason().startsWith("핵심 메트릭 판단 불가: cpu사용률"), "기존 사유 문구 불변");
    }

    private static String block(CountDownLatch release) {
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "late";
    }
}
