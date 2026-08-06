package com.innogrid.aims.metering.repository;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.metering.entity.MeteringHourly;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class MeteringHourlyRepositoryTest {

    @Autowired
    MeteringHourlyRepository repository;

    @BeforeEach
    void setUp() {
        OffsetDateTime now = OffsetDateTime.now();
        repository.save(new MeteringHourly(now.minusHours(1), "mdc-01", "node-1", "cpu_usage", 72.5, 95.0, 30.0, 240));
        repository.save(new MeteringHourly(now.minusHours(1), "mdc-01", "node-1", "mem_usage", 65.3, 80.0, 50.0, 240));
        repository.save(new MeteringHourly(now.minusHours(2), "mdc-01", "node-2", "cpu_usage", 45.2, 60.0, 20.0, 240));
        repository.save(new MeteringHourly(now.minusHours(1), "mdc-02", "node-3", "cpu_usage", 88.1, 99.0, 70.0, 240));
    }

    @Test
    void MDC별_메트릭별_조회() {
        OffsetDateTime from = OffsetDateTime.now().minusHours(3);
        OffsetDateTime to = OffsetDateTime.now();

        List<MeteringHourly> results = repository
                .findByMdcIdAndMetricNameAndTimeBetweenOrderByTimeDesc("mdc-01", "cpu_usage", from, to);

        assertEquals(2, results.size());
        assertEquals("cpu_usage", results.get(0).getMetricName());
        assertEquals("mdc-01", results.get(0).getMdcId());
    }

    @Test
    void MDC별_전체_메트릭_조회() {
        OffsetDateTime from = OffsetDateTime.now().minusHours(3);
        OffsetDateTime to = OffsetDateTime.now();

        List<MeteringHourly> results = repository
                .findByMdcIdAndTimeBetweenOrderByTimeDesc("mdc-01", from, to);

        assertEquals(3, results.size());
    }

    @Test
    void 전체_기간_조회() {
        OffsetDateTime from = OffsetDateTime.now().minusHours(3);
        OffsetDateTime to = OffsetDateTime.now();

        List<MeteringHourly> results = repository.findByTimeBetweenOrderByTimeDesc(from, to);

        assertEquals(4, results.size());
    }

    @Test
    void 오래된_데이터_삭제() {
        // 13개월 전 데이터 추가
        OffsetDateTime oldTime = OffsetDateTime.now().minusMonths(14);
        repository.save(new MeteringHourly(oldTime, "mdc-01", "node-1", "cpu_usage", 50.0, 60.0, 40.0, 240));

        OffsetDateTime cutoff = OffsetDateTime.now().minusMonths(13);
        int deleted = repository.deleteOlderThan(cutoff);

        assertEquals(1, deleted);
        assertEquals(4, repository.count());
    }

    @Test
    void 저장된_데이터_값_검증() {
        OffsetDateTime from = OffsetDateTime.now().minusHours(3);
        OffsetDateTime to = OffsetDateTime.now();

        List<MeteringHourly> results = repository
                .findByMdcIdAndMetricNameAndTimeBetweenOrderByTimeDesc("mdc-02", "cpu_usage", from, to);

        assertEquals(1, results.size());
        MeteringHourly row = results.get(0);
        assertEquals(88.1, row.getAvgValue(), 0.01);
        assertEquals(99.0, row.getMaxValue(), 0.01);
        assertEquals(70.0, row.getMinValue(), 0.01);
        assertEquals(240, row.getSampleCount());
    }
}
