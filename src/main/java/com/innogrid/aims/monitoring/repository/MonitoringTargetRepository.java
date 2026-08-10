package com.innogrid.aims.monitoring.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.monitoring.entity.MonitoringTarget;
import com.innogrid.aims.monitoring.entity.MonitoringTarget.ExporterType;

public interface MonitoringTargetRepository extends JpaRepository<MonitoringTarget, Long> {

    List<MonitoringTarget> findByMdcId(String mdcId);

    List<MonitoringTarget> findByExporterType(ExporterType exporterType);

    List<MonitoringTarget> findByMdcIdAndExporterType(String mdcId, ExporterType exporterType);
}
