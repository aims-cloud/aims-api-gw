package com.innogrid.aims.scheduler.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.scheduler.entity.NodeExporterMapping;

/** 익스포터 대응 원장 조회. 목록은 노드 식별자 순으로 고정해 화면·응답이 결정적이게 한다. */
public interface NodeExporterMappingRepository extends JpaRepository<NodeExporterMapping, Long> {

    Optional<NodeExporterMapping> findByNodeId(String nodeId);

    List<NodeExporterMapping> findAllByOrderByNodeIdAsc();
}
