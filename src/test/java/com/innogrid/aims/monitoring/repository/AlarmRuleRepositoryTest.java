package com.innogrid.aims.monitoring.repository;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.monitoring.entity.AlarmRule;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class AlarmRuleRepositoryTest {

    @Autowired
    AlarmRuleRepository repository;

    @BeforeEach
    void setUp() {
        repository.save(new AlarmRule("CPU 경고", "cpu_usage", "gt", 80.0, "WARNING", "email"));
        repository.save(new AlarmRule("메모리 위험", "mem_usage", "gt", 90.0, "CRITICAL", "slack"));
        AlarmRule disabled = new AlarmRule("비활성 규칙", "disk_usage", "gt", 85.0, "WARNING", "email");
        disabled.setEnabled(false);
        repository.save(disabled);
    }

    @Test
    void 활성_규칙만_조회() {
        List<AlarmRule> rules = repository.findByEnabledTrue();
        assertEquals(2, rules.size());
    }

    @Test
    void 전체_규칙_조회() {
        assertEquals(3, repository.count());
    }

    @Test
    void 규칙_생성_및_필드_검증() {
        AlarmRule rule = repository.findByEnabledTrue().get(0);
        assertEquals("gt", rule.getCondition());
        assertNotNull(rule.getCreatedAt());
    }
}
