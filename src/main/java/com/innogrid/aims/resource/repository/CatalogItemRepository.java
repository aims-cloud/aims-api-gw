package com.innogrid.aims.resource.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.resource.entity.CatalogItem;

public interface CatalogItemRepository extends JpaRepository<CatalogItem, Long> {

    List<CatalogItem> findByActiveTrueOrderByCreatedAtDesc();
}
