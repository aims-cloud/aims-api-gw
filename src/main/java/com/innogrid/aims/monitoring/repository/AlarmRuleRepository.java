package com.innogrid.aims.monitoring.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.monitoring.entity.AlarmRule;

public interface AlarmRuleRepository extends JpaRepository<AlarmRule, Long> {

    List<AlarmRule> findByEnabledTrue();
}
