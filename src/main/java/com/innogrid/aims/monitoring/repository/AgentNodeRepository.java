package com.innogrid.aims.monitoring.repository;

import java.util.List;
import java.util.Optional;
import com.innogrid.aims.monitoring.entity.AgentNode;
import com.innogrid.aims.monitoring.entity.AgentTransport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentNodeRepository extends JpaRepository<AgentNode, Long> {
    List<AgentNode> findByTransportNot(AgentTransport transport);

    /** 물리 노드 에이전트 — 자산당 1행 */
    Optional<AgentNode> findByAsset_Id(Long assetId);

    /** VM 에이전트 — Tumblebug 키당 1행 */
    Optional<AgentNode> findByTbNsAndTbInfraAndTbNode(String tbNs, String tbInfra, String tbNode);

    /** Tumblebug Infra 안 VM 에이전트 전체 — 설치 상태 목록 */
    List<AgentNode> findByTbNsAndTbInfra(String tbNs, String tbInfra);
}
