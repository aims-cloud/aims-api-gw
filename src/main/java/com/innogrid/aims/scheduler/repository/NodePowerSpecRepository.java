package com.innogrid.aims.scheduler.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.scheduler.entity.NodePowerSpec;

/** 노드 전력 사양 원장 조회. 목록은 노드 식별자 순으로 고정해 화면·응답이 결정적이게 한다. */
public interface NodePowerSpecRepository extends JpaRepository<NodePowerSpec, Long> {

    Optional<NodePowerSpec> findByNodeId(String nodeId);

    List<NodePowerSpec> findAllByOrderByNodeIdAsc();
}
