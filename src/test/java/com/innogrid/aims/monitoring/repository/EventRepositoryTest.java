package com.innogrid.aims.monitoring.repository;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.monitoring.entity.Event;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class EventRepositoryTest {

    @Autowired
    EventRepository repository;

    @BeforeEach
    void setUp() {
        repository.save(new Event("mdc-01", "WARNING", "node-01", "CPU 사용률 85% 초과"));
        repository.save(new Event("mdc-01", "CRITICAL", "node-03", "디스크 사용률 95% 초과"));
        repository.save(new Event("mdc-02", "INFO", "gpu-01", "GPU 온도 72도"));
    }

    @Test
    void MDC별_이벤트_조회() {
        List<Event> events = repository.findByMdcIdOrderByTimeDesc("mdc-01");
        assertEquals(2, events.size());
    }

    @Test
    void 심각도별_이벤트_조회() {
        List<Event> critical = repository.findBySeverityOrderByTimeDesc("CRITICAL");
        assertEquals(1, critical.size());
        assertEquals("node-03", critical.get(0).getSource());
    }

    @Test
    void 전체_이벤트_최신순_조회() {
        List<Event> all = repository.findAllByOrderByTimeDesc();
        assertEquals(3, all.size());
    }
}
