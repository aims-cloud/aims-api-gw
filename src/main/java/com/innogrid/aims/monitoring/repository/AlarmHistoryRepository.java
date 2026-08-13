package com.innogrid.aims.monitoring.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.monitoring.entity.AlarmHistory;

public interface AlarmHistoryRepository extends JpaRepository<AlarmHistory, Long> {

    List<AlarmHistory> findByMdcIdOrderByTriggeredAtDesc(String mdcId);

    List<AlarmHistory> findByStatusOrderByTriggeredAtDesc(AlarmHistory.AlarmStatus status);

    List<AlarmHistory> findAllByOrderByTriggeredAtDesc();
}
