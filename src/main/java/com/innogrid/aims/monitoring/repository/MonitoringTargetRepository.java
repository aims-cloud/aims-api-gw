package com.innogrid.aims.monitoring.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.monitoring.entity.MonitoringTarget;
import com.innogrid.aims.monitoring.entity.MonitoringTarget.ExporterType;

public interface MonitoringTargetRepository extends JpaRepository<MonitoringTarget, Long> {

    List<MonitoringTarget> findByMdcId(String mdcId);

    List<MonitoringTarget> findByExporterType(ExporterType exporterType);

    List<MonitoringTarget> findByMdcIdAndExporterType(String mdcId, ExporterType exporterType);

    /** 자원 탐색기 트리 — 역할 스코프의 물리 호스트(자산) 목록에 등록된 exporter 를 한 번에 조회한다 */
    List<MonitoringTarget> findByAsset_IdIn(Collection<Long> assetIds);
}
