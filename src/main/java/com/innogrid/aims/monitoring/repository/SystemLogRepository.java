package com.innogrid.aims.monitoring.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.innogrid.aims.monitoring.entity.SystemLog;

public interface SystemLogRepository extends JpaRepository<SystemLog, Long> {

    List<SystemLog> findByMdcIdOrderByTimeDesc(String mdcId);

    List<SystemLog> findByLevelOrderByTimeDesc(String level);

    List<SystemLog> findAllByOrderByTimeDesc();

    @Query("SELECT l FROM SystemLog l WHERE " +
           "(:mdcId IS NULL OR l.mdcId = :mdcId) AND " +
           "(:level IS NULL OR l.level = :level) AND " +
           "(:keyword IS NULL OR l.message LIKE %:keyword%) " +
           "ORDER BY l.time DESC")
    List<SystemLog> search(String mdcId, String level, String keyword);
}
