package com.innogrid.aims.resource.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.resource.entity.VmGroupMember;

public interface VmGroupMemberRepository extends JpaRepository<VmGroupMember, Long> {

    List<VmGroupMember> findByVmGroupIdOrderByCreatedAtAsc(Long vmGroupId);

    Optional<VmGroupMember> findByVmGroupIdAndServerId(Long vmGroupId, String serverId);

    boolean existsByVmGroupIdAndServerId(Long vmGroupId, String serverId);

    void deleteByVmGroupId(Long vmGroupId);

    long countByVmGroupId(Long vmGroupId);
}
