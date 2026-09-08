package com.innogrid.aims.infra.ipam.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.infra.ipam.entity.NetworkAuditHistory;
import com.innogrid.aims.infra.ipam.entity.NetworkAuditHistory.Target;

public interface NetworkAuditHistoryRepository extends JpaRepository<NetworkAuditHistory, Long> {

    List<NetworkAuditHistory> findByTargetAndTargetIdOrderByStartedAtDesc(Target target, Long targetId);
}
