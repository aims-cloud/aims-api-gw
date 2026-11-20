package com.innogrid.aims.scheduler.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.scheduler.entity.NodeExporterMapping;

/** 익스포터 대응 원장 조회. 목록은 노드 식별자 순으로 고정해 화면·응답이 결정적이게 한다. */
public interface NodeExporterMappingRepository extends JpaRepository<NodeExporterMapping, Long> {

    /** 중복 검사용 — 라벨 철자만 다른 행은 영원히 쓰이지 않으므로 등록 단계에서 막는다. */
    Optional<NodeExporterMapping> findByNodeIdIgnoreCase(String nodeId);

    List<NodeExporterMapping> findAllByOrderByNodeIdAsc();
}
