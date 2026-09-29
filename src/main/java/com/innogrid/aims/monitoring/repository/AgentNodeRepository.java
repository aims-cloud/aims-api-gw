package com.innogrid.aims.monitoring.repository;

import java.util.List;
import com.innogrid.aims.monitoring.entity.AgentNode;
import com.innogrid.aims.monitoring.entity.AgentTransport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentNodeRepository extends JpaRepository<AgentNode, Long> {
    List<AgentNode> findByTransportNot(AgentTransport transport);
}
