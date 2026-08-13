package com.innogrid.aims.monitoring.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.monitoring.entity.Event;

public interface EventRepository extends JpaRepository<Event, Long> {

    List<Event> findByMdcIdOrderByTimeDesc(String mdcId);

    List<Event> findBySeverityOrderByTimeDesc(String severity);

    List<Event> findAllByOrderByTimeDesc();
}
