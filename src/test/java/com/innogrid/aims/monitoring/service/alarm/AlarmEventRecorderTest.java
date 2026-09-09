package com.innogrid.aims.monitoring.service.alarm;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmSource;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmLevel;
import com.innogrid.aims.monitoring.entity.AlarmHistory;
import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.monitoring.entity.Event;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import com.innogrid.aims.monitoring.repository.AlarmHistoryRepository;
import com.innogrid.aims.monitoring.repository.EventRepository;
import com.innogrid.aims.monitoring.repository.PortalLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 알람 전이 이벤트 → events 원장 적재 + 포털 로그 ALARM 행 — 평가 트랜잭션 커밋 뒤에만 적재되고(롤백이면 없음),
 * 발생은 정책 레벨·해소는 INFO, 트랜잭션 밖 발행도 처리. 실제 컨텍스트에서 리스너 배선까지 확인하므로 @Transactional 없이 직접 커밋한다.
 */
@SpringBootTest
class AlarmEventRecorderTest {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate tx;
    @Autowired EventRepository eventRepository;
    @Autowired AlarmHistoryRepository historyRepository;
    @Autowired PortalLogRepository portalLogRepository;

    List<Long> before;
    List<Long> portalLogsBefore;

    @BeforeEach
    void snapshot() {
        before = eventRepository.findAll().stream().map(Event::getId).toList();
        portalLogsBefore = portalLogRepository.findAll().stream().map(PortalLogEntry::getId).toList();
    }

    @AfterEach
    void cleanUp() {
        eventRepository.findAll().stream().filter(e -> !before.contains(e.getId())).forEach(eventRepository::delete);
        portalLogRepository.findAll().stream().filter(l -> !portalLogsBefore.contains(l.getId())).forEach(portalLogRepository::delete);
        historyRepository.deleteAll();
    }

    private AlarmHistory alarm(String criterion) {
        return AlarmHistory.builder(AlarmSource.POLICY, AlarmLevel.CRITICAL, criterion)
                .policy(7L, "cpu-high").resource("node", "1", "node-01").role("ADMIN").mdc("mdc-01")
                .metric("CPU_USED_PERCENT").observed(92.4, "%").build();
    }

    private List<Event> newEvents() {
        return eventRepository.findAll().stream().filter(e -> !before.contains(e.getId())).toList();
    }

    private List<PortalLogEntry> newPortalLogs() {
        return portalLogRepository.findAll().stream().filter(l -> !portalLogsBefore.contains(l.getId())).toList();
    }

    @Test
    void 커밋_뒤_발생_이벤트가_정책_레벨로_적재되고_롤백이면_남지_않는다() {
        AlarmHistory saved = tx.execute(s -> historyRepository.save(alarm("CPU Used > 80%")));

        tx.executeWithoutResult(s -> publisher.publishEvent(AlarmStateChangedEvent.firing(saved, 92.4)));
        List<Event> events = newEvents();
        assertThat(events).hasSize(1);
        Event e = events.get(0);
        assertThat(e.getMdcId()).isEqualTo("mdc-01");
        assertThat(e.getSeverity()).isEqualTo("CRITICAL");
        assertThat(e.getSource()).isEqualTo("node-01");
        assertThat(e.getMessage()).isEqualTo("cpu-high: CPU Used > 80% (관측값 92.4%)");

        assertThat(newPortalLogs()).singleElement().satisfies(l -> {      // 포털 로그 ALARM 발생 행 (docs/08 §3.2)
            assertThat(l.getType()).isEqualTo(PortalLogType.ALARM);
            assertThat(l.getScreen()).isEqualTo("알람");
            assertThat(l.getAction()).isEqualTo("발생");
            assertThat(l.getContent()).isEqualTo("CPU Used > 80% 발생: node-01 (92.4%)");
            assertThat(l.getResult()).isEqualTo(PortalLogResult.SUCCESS);
            assertThat(l.getRefType()).isEqualTo("ALARM_HISTORY");
            assertThat(l.getRefId()).isEqualTo(String.valueOf(saved.getId()));
            assertThat(l.getUserId()).isNull();                                    // 엔진 발생이라 사용자 없음
            assertThat(l.getStartedAt()).isCloseTo(saved.getOccurredAt(), within(1, ChronoUnit.MILLIS));   // DB 는 µs 정밀도
            assertThat(l.getCompletedAt()).isCloseTo(saved.getOccurredAt(), within(1, ChronoUnit.MILLIS));   // DB 는 µs 정밀도
        });

        tx.executeWithoutResult(s -> {
            publisher.publishEvent(AlarmStateChangedEvent.firing(saved, 99.0));
            s.setRollbackOnly();
        });
        assertThat(newEvents()).hasSize(1);                      // 롤백된 평가의 이벤트·포털 로그는 없다
        assertThat(newPortalLogs()).hasSize(1);
    }

    @Test
    void 해소_이벤트는_INFO_로_적재되고_트랜잭션_밖_발행도_처리한다() {
        AlarmHistory saved = tx.execute(s -> historyRepository.save(alarm("CPU Used > 80%")));
        saved.resolve(OffsetDateTime.now());

        publisher.publishEvent(AlarmStateChangedEvent.resolved(saved, 55.0));

        List<Event> events = newEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getSeverity()).isEqualTo("INFO");
        assertThat(events.get(0).getMessage()).isEqualTo("cpu-high 해소: CPU Used > 80% (관측값 55.0%)");

        assertThat(newPortalLogs()).singleElement().satisfies(l -> {
            assertThat(l.getAction()).isEqualTo("해소");
            assertThat(l.getContent()).isEqualTo("CPU Used > 80% 해소: node-01 (55.0%)");
            assertThat(l.getRefId()).isEqualTo(String.valueOf(saved.getId()));
            assertThat(l.getStartedAt()).isCloseTo(saved.getResolvedAt(), within(1, ChronoUnit.MILLIS));   // DB 는 µs 정밀도
        });
    }

    @Test
    void Namespace_영역_알람은_mdcId_가_unknown_이다() {
        AlarmHistory vm = tx.execute(s -> historyRepository.save(
                AlarmHistory.builder(AlarmSource.POLICY, AlarmLevel.WARNING, "FS Used >= 90%")
                        .policy(8L, "fs-90").resource("vm", "TB:openstack/infra-01/vm-1", "vm-1").namespace("openstack")
                        .metric("FS_USED_PERCENT").observed(93.0, "%").build()));

        publisher.publishEvent(AlarmStateChangedEvent.firing(vm, 93.0));

        assertThat(newEvents()).singleElement().satisfies(e -> {
            assertThat(e.getMdcId()).isEqualTo("unknown");
            assertThat(e.getSeverity()).isEqualTo("WARNING");
        });
    }
}
