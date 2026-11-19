package com.innogrid.aims.scheduler.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.scheduler.entity.NodePlacementHold;

/** 노드 배치 중지 원장 조회. 목록 API 는 노드 식별자 순으로 돌려준다(배치 입력의 순서는 {@code NodePlacementHolds}가 정한다). */
public interface NodePlacementHoldRepository extends JpaRepository<NodePlacementHold, Long> {

    Optional<NodePlacementHold> findByNodeId(String nodeId);

    List<NodePlacementHold> findAllByOrderByNodeIdAsc();
}
