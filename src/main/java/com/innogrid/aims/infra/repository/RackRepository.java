package com.innogrid.aims.infra.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.infra.entity.Rack;

public interface RackRepository extends JpaRepository<Rack, Long> {

    List<Rack> findByMdcId(String mdcId);
}
