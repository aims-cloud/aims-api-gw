package com.innogrid.aims.metering.repository;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.innogrid.aims.metering.entity.MeteringHourly;

public interface MeteringHourlyRepository extends JpaRepository<MeteringHourly, Long> {

    List<MeteringHourly> findByMdcIdAndMetricNameAndTimeBetweenOrderByTimeDesc(
            String mdcId, String metricName, OffsetDateTime from, OffsetDateTime to);

    List<MeteringHourly> findByMdcIdAndTimeBetweenOrderByTimeDesc(
            String mdcId, OffsetDateTime from, OffsetDateTime to);

    List<MeteringHourly> findByTimeBetweenOrderByTimeDesc(
            OffsetDateTime from, OffsetDateTime to);

    @Modifying
    @Query("DELETE FROM MeteringHourly m WHERE m.time < :cutoff")
    int deleteOlderThan(OffsetDateTime cutoff);
}
