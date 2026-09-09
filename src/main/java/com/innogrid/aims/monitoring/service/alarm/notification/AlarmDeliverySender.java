package com.innogrid.aims.monitoring.service.alarm.notification;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.innogrid.aims.monitoring.AlarmProperties;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmDeliveryStatus;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmChannel;
import com.innogrid.aims.monitoring.entity.AlarmDelivery;
import com.innogrid.aims.monitoring.repository.AlarmDeliveryRecipientRepository;
import com.innogrid.aims.monitoring.repository.AlarmDeliveryRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 발송기 (docs/09 A.5.4) — 전송 원장의 SENDING 수신자에게 채널 발송기로 보내고 결과를 수신자 행에 기록한다.
 *
 * <ul>
 *   <li>SMTP 지연이 평가 주기(공용 스케줄러 스레드, 풀 크기 1)를 막지 않게 전용 스레드 풀에서 돈다({@link #submit}).
 *       Boot 의 {@code applicationTaskExecutor} 를 대체하지 않으려고 Spring 빈이 아닌 자체 {@link ExecutorService} 를 쓴다.</li>
 *   <li>수신자 1명당 최대 {@code max-attempts} 회, 사이 간격은 {@code retry-backoff × 시도 횟수}. 끝내 실패면 FAILED + 마지막 사유.</li>
 *   <li>결과 기록은 수신자 1행마다 짧은 트랜잭션 — 한 명의 실패가 다른 수신자 기록을 되돌리지 않는다.</li>
 * </ul>
 * {@link #send(Long)} 는 동기라 테스트가 직접 부를 수 있다.
 */
@Component
public class AlarmDeliverySender {

    private static final Logger log = LoggerFactory.getLogger(AlarmDeliverySender.class);

    private final AlarmDeliveryRepository deliveryRepository;
    private final AlarmDeliveryRecipientRepository recipientRepository;
    private final Map<AlarmChannel, NotificationSender> senders = new EnumMap<>(AlarmChannel.class);
    private final AlarmProperties properties;
    private final TransactionTemplate tx;
    private final ExecutorService executor;

    public AlarmDeliverySender(AlarmDeliveryRepository deliveryRepository, AlarmDeliveryRecipientRepository recipientRepository,
                               List<NotificationSender> senders, AlarmProperties properties, TransactionTemplate tx) {
        this.deliveryRepository = deliveryRepository;
        this.recipientRepository = recipientRepository;
        senders.forEach(s -> this.senders.put(s.channel(), s));
        this.properties = properties;
        this.tx = tx;
        AtomicInteger seq = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(Math.max(1, properties.getNotification().getThreads()), r -> {
            Thread t = new Thread(r, "alarm-notify-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** 비동기 발송 — 큐가 거부되면(종료 중) 로그만 남기고 수신자는 SENDING 으로 남는다 */
    public void submit(List<Long> deliveryIds) {
        if (deliveryIds == null || deliveryIds.isEmpty()) return;
        try {
            executor.execute(() -> deliveryIds.forEach(this::sendSafely));
        } catch (RejectedExecutionException ex) {
            log.warn("알람 발송 작업 거부(종료 중): deliveries={}", deliveryIds);
        }
    }

    private void sendSafely(Long deliveryId) {
        try {
            send(deliveryId);
        } catch (RuntimeException ex) {
            log.warn("알람 발송 처리 실패: deliveryId={}, error={}", deliveryId, ex.getMessage());
        }
    }

    /** 전송 1건의 SENDING 수신자 전부에게 발송하고 결과를 기록한다(동기) */
    public void send(Long deliveryId) {
        Snapshot snapshot = tx.execute(status -> deliveryRepository.findWithRecipientsById(deliveryId).map(Snapshot::of).orElse(null));
        if (snapshot == null || snapshot.pending().isEmpty()) return;

        NotificationSender sender = senders.get(snapshot.channel());
        int maxAttempts = Math.max(1, properties.getNotification().getMaxAttempts());
        Duration backoff = properties.getNotification().getRetryBackoff();

        for (Pending p : snapshot.pending()) {
            Result result = sender == null
                    ? Result.failed(0, "발송기 없음: " + snapshot.channel())
                    : attempt(sender, p.address(), snapshot.subject(), snapshot.body(), maxAttempts, backoff);
            OffsetDateTime now = OffsetDateTime.now();
            tx.executeWithoutResult(status -> recipientRepository.findById(p.recipientId()).ifPresent(r -> {
                if (r.getStatus() != AlarmDeliveryStatus.SENDING) return;      // 다른 스레드가 먼저 기록
                if (result.success()) r.markSuccess(result.attempts(), now);
                else r.markFailed(result.attempts(), result.reason(), now);
            }));
            if (!result.success()) {
                log.info("알람 발송 실패: deliveryId={}, channel={}, to={}, attempts={}, reason={}",
                        deliveryId, snapshot.channel(), p.address(), result.attempts(), result.reason());
            }
        }
    }

    private static Result attempt(NotificationSender sender, String address, String subject, String body,
                                  int maxAttempts, Duration backoff) {
        String lastReason = null;
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                sender.send(address, subject, body);
                return Result.ok(i);
            } catch (Exception ex) {
                lastReason = reasonOf(ex);
                if (i < maxAttempts && backoff != null && !backoff.isZero() && !backoff.isNegative()) {
                    try {
                        Thread.sleep(backoff.multipliedBy(i).toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return Result.failed(i, lastReason + " (중단됨)");
                    }
                }
            }
        }
        return Result.failed(maxAttempts, lastReason);
    }

    private static String reasonOf(Exception ex) {
        String msg = ex.getMessage();
        return msg == null || msg.isBlank() ? ex.getClass().getSimpleName() : ex.getClass().getSimpleName() + ": " + msg;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 트랜잭션 밖에서 쓰는 발송 입력 — 지연 로딩에 의존하지 않도록 값만 복사 */
    private record Snapshot(AlarmChannel channel, String subject, String body, List<Pending> pending) {
        static Snapshot of(AlarmDelivery d) {
            List<Pending> pending = d.getRecipients().stream()
                    .filter(r -> r.getStatus() == AlarmDeliveryStatus.SENDING)
                    .map(r -> new Pending(r.getId(), r.getAddress()))
                    .toList();
            return new Snapshot(d.getChannel(), d.getSubject(), d.getBody(), pending);
        }
    }

    private record Pending(Long recipientId, String address) {}

    private record Result(boolean success, int attempts, String reason) {
        static Result ok(int attempts) { return new Result(true, attempts, null); }
        static Result failed(int attempts, String reason) { return new Result(false, attempts, reason); }
    }
}
