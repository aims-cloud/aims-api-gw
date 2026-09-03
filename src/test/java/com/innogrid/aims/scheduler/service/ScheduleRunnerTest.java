package com.innogrid.aims.scheduler.service;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 러너는 {@code SmartLifecycle} 이라 <b>다른 담당자의 테스트를 포함해 모든 스프링 컨텍스트</b>
 * 에서 함께 기동한다. 여기서 문제가 생기면 스케줄러와 무관한 테스트까지 무너지므로,
 * 기동 자체가 안전한지를 먼저 고정해 둔다.
 *
 * <p>러너를 멈추면 같은 컨텍스트를 공유하는 다른 테스트에 영향이 가므로
 * {@code stop()} 은 호출하지 않는다.</p>
 */
@SpringBootTest
class ScheduleRunnerTest {

    @Autowired private ScheduleRunner runner;
    @Autowired private SchedulerProperties properties;
    @Autowired private ScheduleRunRepository runRepository;

    @DisplayName("컨텍스트와 함께 기동한다")
    @Test
    void starts_with_context() {
        assertTrue(runner.isRunning(), "SmartLifecycle 로 자동 기동되어야 한다");
    }

    @DisplayName("폴링 스레드는 데몬이다")
    @Test
    void polling_thread_is_daemon() {
        // 데몬이 아니면 JVM 이 종료되지 않아 테스트 실행이 끝나지 않는다
        Optional<Thread> poller = findThread("aims-schedule-poller");

        assertTrue(poller.isPresent(), "폴링 스레드가 있어야 한다");
        assertTrue(poller.get().isDaemon(), "데몬 스레드여야 한다");
    }

    @DisplayName("최초 지연 동안에는 아무것도 하지 않는다")
    @Test
    void nothing_runs_during_initial_delay() {
        // 전체 테스트가 최초 지연보다 짧게 끝나므로 테스트 중에는 폴링이 일어나지 않는다.
        // 이 여유가 없으면 컨텍스트 기동 직후 DB 준비 전에 폴링이 돌 수 있다.
        assertTrue(properties.getInitialDelaySeconds() >= 30,
                "최초 지연이 충분해야 한다: " + properties.getInitialDelaySeconds());
        assertTrue(runRepository.findAll().stream()
                        .noneMatch(run -> run.getTriggerType() == ScheduleRun.TriggerType.AUTO),
                "테스트 중 자동 실행이 일어나면 안 된다");
    }

    @DisplayName("다시 시작해도 스레드가 늘지 않는다")
    @Test
    void restart_does_not_add_threads() {
        runner.start();   // 이미 기동된 상태에서 중복 호출

        assertTrue(runner.isRunning());
        assertEquals(1, countThreads("aims-schedule-poller"), "폴링 스레드는 하나여야 한다");
    }

    private Optional<Thread> findThread(String name) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> name.equals(t.getName()))
                .findFirst();
    }

    private long countThreads(String name) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> name.equals(t.getName()))
                .count();
    }
}
