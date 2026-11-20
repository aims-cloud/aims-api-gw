package com.innogrid.aims.resource.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.resource.entity.VmGroup;

public interface VmGroupRepository extends JpaRepository<VmGroup, Long> {

    Optional<VmGroup> findByName(String name);

    boolean existsByName(String name);
}
