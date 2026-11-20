package com.innogrid.aims.infra.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.innogrid.aims.infra.entity.Rack;

public interface RackRepository extends JpaRepository<Rack, Long>, JpaSpecificationExecutor<Rack> {

    List<Rack> findByMdcId(String mdcId);

    Optional<Rack> findFirstByName(String name);
}
