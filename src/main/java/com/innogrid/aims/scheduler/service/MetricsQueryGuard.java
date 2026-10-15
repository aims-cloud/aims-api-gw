package com.innogrid.aims.scheduler.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

import com.innogrid.aims.scheduler.config.SchedulerProperties;

import jakarta.annotation.PreDestroy;

/**
 * 메트릭 질의 시간 상한 — 저장소가 응답을 안 줘도 스케줄러가 멈추지 않게 하는 보호 장치.
 *
 * <p>저장소 클라이언트에는 연결·읽기 타임아웃이 없다(타 담당자 파일). 저장소가 연결은 받고 응답을 주지
 * 않으면 요청 스레드가 무한 대기하고, 배치·스케줄 러너·노드 조회·전력 효율·통합 진단이 모두 같은 클라이언트를
 * 쓰므로 전부 멈춘다. 이 클래스는 질의를 <b>작은 내부 풀</b>에서 돌리고 상한이 지나면 기다리기를 멈춘다.</p>
 *
 * <p><b>한계를 정확히 알아야 한다.</b> 기다리기를 멈춰도 HTTP 호출 자체는 저장소가 응답할 때까지 풀 스레드를
 * 붙잡는다. 풀은 고정 크기(기본 4)라 붙잡힌 스레드가 늘어도 상한 4에서 멈추지만, 4개가 다 붙잡히면 이후 질의는
 * 대기열에서 상한을 넘겨 전부 "응답 없음"이 된다 — 요청 스레드는 멈추지 않으나 후보가 비는 상태다. 저장소가
 * 살아나면 자연히 회복되고, 붙잡힌 스레드를 실제로 회수하는 것은 클라이언트 타임아웃만 할 수 있다.</p>
 *
 * <p>전역 설정 빈을 만들지 않는다 — 스케줄러 패키지 안의 컴포넌트이고 풀은 데몬 스레드라 JVM 종료를 막지 않으며
 * {@link #shutdown()} 에서 정리한다. 러너의 전용 executor 와 같은 원칙이다.</p>
 */
@Component
public class MetricsQueryGuard {

    private static final Logger log = LoggerFactory.getLogger(MetricsQueryGuard.class);

    /** 포화 WARN 은 1분에 한 번만 — 저장소가 멈춘 동안 매 질의마다 찍히면 로그가 넘친다. */
    private static final long SATURATION_WARN_INTERVAL_MS = 60_000;

    private final ThreadPoolExecutor pool;
    private final AtomicLong lastSaturationWarnAt = new AtomicLong(0);

    @Autowired
    public MetricsQueryGuard(SchedulerProperties properties) {
        this(Math.max(1, properties.getMetrics().getParallelism()));
    }

    /** 풀 크기를 직접 주는 형태 — 단위 테스트와 스프링 없이 조립하는 수집기가 쓴다. */
    public MetricsQueryGuard(int parallelism) {
        AtomicInteger seq = new AtomicInteger();
        this.pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(Math.max(1, parallelism), r -> {
            Thread t = new Thread(r, "scheduler-metrics-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 질의 하나를 상한 안에서 실행한다.
     *
     * @throws MetricsUnavailableException 상한을 넘겼거나 저장소에 연결하지 못했을 때. 질의 자체의 다른 예외는 그대로 던진다
     */
    public <T> T call(Supplier<T> query, long timeoutMs) {
        Future<T> future = submit(query);
        return await(future, timeoutMs, "질의");
    }

    /** 작업을 풀에 넣는다. 결과는 {@link #await} 로 상한 안에 받는다. */
    public <T> Future<T> submit(Supplier<T> task) {
        warnIfSaturated();
        return pool.submit(task::get);
    }

    /**
     * 제출한 작업의 결과를 상한 안에 받는다. 상한을 넘기면 기다리기를 멈추고 작업은 취소 표시만 한다
     * (HTTP 호출은 저장소가 응답할 때까지 스레드를 붙잡는다 — 클래스 주석 참고).
     */
    public <T> T await(Future<T> future, long timeoutMs, String what) {
        long started = System.nanoTime();
        try {
            return future.get(Math.max(0, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new MetricsUnavailableException(String.format("%s가 상한 %dms 안에 끝나지 않았습니다", what, timeoutMs), timeoutMs);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof ResourceAccessException) {
                throw new MetricsUnavailableException("메트릭 저장소에 연결하지 못했습니다: " + cause.getMessage(),
                        Duration.ofNanos(System.nanoTime() - started).toMillis());
            }
            if (cause instanceof RuntimeException re) throw re;
            throw new IllegalStateException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new MetricsUnavailableException("질의 대기가 중단되었습니다", 0);
        }
    }

    /**
     * 여러 작업을 제출 순서대로 받는다. 전체 기한과 작업별 상한 중 먼저 오는 쪽까지만 기다리고,
     * 넘긴 작업은 {@code Optional.empty()} 로 표시한다 — 호출자가 "응답 없음"으로 처리한다.
     * 순서를 지키므로 병렬로 돌려도 결과 목록의 순서가 제출 순서와 같다.
     */
    public <T> List<Optional<T>> awaitAll(List<Future<T>> futures, long perTaskTimeoutMs, long deadlineNanos) {
        List<Optional<T>> out = new ArrayList<>(futures.size());
        for (Future<T> f : futures) {
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            long wait = Math.max(0, Math.min(perTaskTimeoutMs, remainingMs));
            try {
                out.add(Optional.ofNullable(f.get(wait, TimeUnit.MILLISECONDS)));
            } catch (TimeoutException e) {
                f.cancel(true);
                out.add(Optional.empty());
            } catch (ExecutionException e) {
                out.add(Optional.empty());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                f.cancel(true);
                out.add(Optional.empty());
            }
        }
        return out;
    }

    public int parallelism() {
        return pool.getMaximumPoolSize();
    }

    private void warnIfSaturated() {
        if (pool.getActiveCount() < pool.getMaximumPoolSize()) return;
        long now = System.currentTimeMillis();
        long last = lastSaturationWarnAt.get();
        if (now - last >= SATURATION_WARN_INTERVAL_MS && lastSaturationWarnAt.compareAndSet(last, now)) {
            log.warn("메트릭 질의 풀 {}개가 모두 응답을 기다리는 중입니다 — 저장소가 응답하지 않는 것으로 보입니다. "
                    + "이후 질의는 상한에서 '응답 없음'으로 처리됩니다. 붙잡힌 스레드는 저장소가 응답해야 풀립니다(클라이언트 타임아웃 필요)",
                    pool.getMaximumPoolSize());
        }
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    /** 상한 초과 또는 저장소 연결 실패. 호출자가 "응답 없음"으로 바꿔 처리한다. */
    public static class MetricsUnavailableException extends RuntimeException {
        private final long elapsedMs;

        public MetricsUnavailableException(String message, long elapsedMs) {
            super(message);
            this.elapsedMs = elapsedMs;
        }

        public long elapsedMs() {
            return elapsedMs;
        }
    }
}
