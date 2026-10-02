package com.innogrid.aims.monitoring.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.monitoring.entity.MonitoringTarget;
import com.innogrid.aims.monitoring.entity.MonitoringTarget.ExporterType;

public interface MonitoringTargetRepository extends JpaRepository<MonitoringTarget, Long> {

    List<MonitoringTarget> findByMdcId(String mdcId);

    List<MonitoringTarget> findByExporterType(ExporterType exporterType);

    List<MonitoringTarget> findByMdcIdAndExporterType(String mdcId, ExporterType exporterType);

    /** 자원 탐색기 트리 — 역할 스코프의 물리 호스트(자산) 목록에 등록된 exporter 를 한 번에 조회한다.
     *  호출측이 트랜잭션 밖에서 asset 을 읽으므로 함께 적재한다 */
    @EntityGraph(attributePaths = "asset")
    List<MonitoringTarget> findByAsset_IdIn(Collection<Long> assetIds);

    /** 수집 에이전트 PULL — 노드 Alloy 가 보낸 instance(address:port)로 node_exporter 타겟과 그 자산을 찾는다 */
    @EntityGraph(attributePaths = "asset")
    Optional<MonitoringTarget> findFirstByAddressAndPortAndExporterType(String address, int port, ExporterType exporterType);
}
